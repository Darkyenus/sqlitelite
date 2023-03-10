/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// modified from original source see README at the top level of this project

#define LOG_TAG "SQLiteConnection"

#include <jni.h>
#include <sys/mman.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <assert.h>

#include "sqlite3ex.h"
#include "JNIHelp.h"
#include "ALog-priv.h"
#include "android_database_SQLiteCommon.h"

// Set to 1 to use UTF16 storage for localized indexes.
#define UTF16_STORAGE 0

namespace android {

/* Busy timeout in milliseconds.
 * If another connection (possibly in another process) has the database locked for
 * longer than this amount of time then SQLite will generate a SQLITE_BUSY error.
 * The SQLITE_BUSY error is then raised as a SQLiteDatabaseLockedException.
 *
 * In ordinary usage, busy timeouts are quite rare.  Most databases only ever
 * have a single open connection at a time unless they are using WAL.  When using
 * WAL, a timeout could occur if one connection is busy performing an auto-checkpoint
 * operation.  The busy timeout needs to be long enough to tolerate slow I/O write
 * operations but not so long as to cause the application to hang indefinitely if
 * there is a problem acquiring a database lock.
 */
static const int BUSY_TIMEOUT_MS = 2500;

// Limit heap to 8MB for now.  This is 4 times the maximum cursor window
// size, as has been used by the original code in SQLiteDatabase for
// a long time.
static const int SOFT_HEAP_LIMIT = 8 * 1024 * 1024;

static JavaVM *gpJavaVM = 0;

// Called each time a message is logged.
static void sqliteLogCallback(void* data, int iErrCode, const char* zMsg) {
    bool verboseLog = !!data;
    if (iErrCode == 0 || iErrCode == SQLITE_CONSTRAINT || iErrCode == SQLITE_SCHEMA) {
        if (verboseLog) {
            ALOG(LOG_VERBOSE, SQLITE_LOG_TAG, "(%d) %s\n", iErrCode, zMsg);
        }
    } else {
        ALOG(LOG_ERROR, SQLITE_LOG_TAG, "(%d) %s\n", iErrCode, zMsg);
    }
}

// Sets the global SQLite configuration.
// This must be called before any other SQLite functions are called.
static void sqliteInitialize() {
    // Enable multi-threaded mode.  In this mode, SQLite is safe to use by multiple
    // threads as long as no two threads use the same database connection at the same
    // time (which we guarantee in the SQLite database wrappers).
    sqlite3_config(SQLITE_CONFIG_MULTITHREAD);

    // Redirect SQLite log messages to the Android log.
#if 0
    bool verboseLog = android_util_Log_isVerboseLogEnabled(SQLITE_LOG_TAG);
#endif
    bool verboseLog = false;
    sqlite3_config(SQLITE_CONFIG_LOG, &sqliteLogCallback, verboseLog ? (void*)1 : NULL);

    // The soft heap limit prevents the page cache allocations from growing
    // beyond the given limit, no matter what the max page cache sizes are
    // set to. The limit does not, as of 3.5.0, affect any other allocations.
    sqlite3_soft_heap_limit(SOFT_HEAP_LIMIT);

    // Initialize SQLite.
    sqlite3_initialize();
}

static jint nativeReleaseMemory(JNIEnv* env, jclass clazz) {
    return sqlite3_release_memory(SOFT_HEAP_LIMIT);
}

static jlong nativeOpen(JNIEnv* env, jclass clazz, jstring pathStr, jint openFlags) {
    const char* pathChars = env->GetStringUTFChars(pathStr, NULL);
    sqlite3* dbConnection = NULL;
    int err = sqlite3_open_v2(pathChars, &dbConnection, openFlags | SQLITE_OPEN_EXRESCODE, NULL);
    env->ReleaseStringUTFChars(pathStr, pathChars);
    if (err != SQLITE_OK) {
        sqlite3_close(dbConnection);
        throw_sqlite3_exception_errcode(env, err, "Could not open database");
        return 0;
    }

    // Check that the database is really read/write when that is what we asked for.
    if ((openFlags & SQLITE_OPEN_READWRITE) && sqlite3_db_readonly(dbConnection, NULL)) {
        throw_sqlite3_exception(env, dbConnection, "Could not open the database in read/write mode.");
        sqlite3_close(dbConnection);
        return 0;
    }

    // Set the default busy handler to retry automatically before returning SQLITE_BUSY.
    err = sqlite3_busy_timeout(dbConnection, BUSY_TIMEOUT_MS);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, dbConnection, "Could not set busy timeout");
        sqlite3_close(dbConnection);
        return 0;
    }

    return reinterpret_cast<jlong>(dbConnection);
}

static void nativeClose(JNIEnv* env, jclass clazz, jlong connectionPtr) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);

    if (dbConnection) {
        ALOGV("Closing connection %p", dbConnection);
        int err = sqlite3_close(dbConnection);
        if (err != SQLITE_OK) {
            // This can happen if sub-objects aren't closed first.  Make sure the caller knows.
            ALOGE("sqlite3_close(%p) failed: %d", dbConnection, err);
            throw_sqlite3_exception(env, dbConnection, "Count not close db.");
            return;
        }
    }
}

static sqlite3_stmt* prepareStatement(JNIEnv* env, sqlite3* dbConnection, jstring sqlString) {
    jsize sqlLength = env->GetStringLength(sqlString);
    const jchar* sql = env->GetStringCritical(sqlString, NULL);
    sqlite3_stmt* statement;
    int err = sqlite3_prepare16_v2(dbConnection,
            sql, sqlLength * sizeof(jchar), &statement, NULL);
    env->ReleaseStringCritical(sqlString, sql);

    if (err == SQLITE_OK) {
        return statement;
    }

    // Error messages like 'near ")": syntax error' are not
    // always helpful enough, so construct an error string that
    // includes the query itself.
    const char *query = env->GetStringUTFChars(sqlString, NULL);
    char *message = (char*) malloc(strlen(query) + 50);
    if (message) {
        strcpy(message, ", while compiling: "); // less than 50 chars
        strcat(message, query);
    }
    env->ReleaseStringUTFChars(sqlString, query);
    throw_sqlite3_exception(env, dbConnection, message);
    free(message);
    return NULL;
}

static jlong nativePrepareStatement(JNIEnv* env, jclass clazz, jlong connectionPtr, jstring sqlString) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = prepareStatement(env, dbConnection, sqlString);

    if (statement != 0) {
        ALOGV("Prepared statement %p on connection %p", statement, dbConnection);
    }
    return reinterpret_cast<jlong>(statement);
}

static void nativeFinalizeStatement(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jlong statementPtr) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    // We ignore the result of sqlite3_finalize because it is really telling us about
    // whether any errors occurred while executing the statement.  The statement itself
    // is always finalized regardless.
    ALOGV("Finalized statement %p on connection %p", statement, dbConnection);
    int err = sqlite3_finalize(statement);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, dbConnection, "Failed to finalize statement");
    }
}

static void nativeBindNull(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jlong statementPtr, jint index) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = sqlite3_bind_null(statement, index);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, dbConnection, NULL);
    }
}

static void nativeBindLong(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jlong statementPtr, jint index, jlong value) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = sqlite3_bind_int64(statement, index, value);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, dbConnection, NULL);
    }
}

static void nativeBindDouble(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jlong statementPtr, jint index, jdouble value) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = sqlite3_bind_double(statement, index, value);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, dbConnection, NULL);
    }
}

static void nativeBindString(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jlong statementPtr, jint index, jstring valueString) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    jsize valueLength = env->GetStringLength(valueString);
    const jchar* value = env->GetStringCritical(valueString, NULL);
    int err = sqlite3_bind_text16(statement, index, value, valueLength * sizeof(jchar),
            SQLITE_TRANSIENT);
    env->ReleaseStringCritical(valueString, value);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, dbConnection, NULL);
    }
}

static void nativeBindBlob(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jlong statementPtr, jint index, jbyteArray valueArray) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    jsize valueLength = env->GetArrayLength(valueArray);
    jbyte* value = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(valueArray, NULL));
    int err = sqlite3_bind_blob(statement, index, value, valueLength, SQLITE_TRANSIENT);
    env->ReleasePrimitiveArrayCritical(valueArray, value, JNI_ABORT);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, dbConnection, NULL);
    }
}

static void nativeResetStatementAndClearBindings(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jlong statementPtr) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = sqlite3_reset(statement);
    if (err == SQLITE_OK) {
        err = sqlite3_clear_bindings(statement);
    }
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, dbConnection, NULL);
    }
}

static jstring nativeExecutePragma(JNIEnv* env, jclass clazz, jlong connectionPtr, jstring sqlString) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = prepareStatement(env, dbConnection, sqlString);
    if (statement == 0) return NULL;

    int err = sqlite3_step(statement);
    jstring result = NULL;
    if (err == SQLITE_ROW) {
        int columns = sqlite3_column_count(statement);
        size_t totalLength = 0;
        for (int c = 0; c < columns; c++) {
            // Force conversion
            sqlite3_column_text16(statement, c);
            totalLength += sqlite3_column_bytes16(statement, c) / sizeof(jchar);
        }
        if (totalLength == 0) {
            result = env->NewString(NULL, 0);
        } else {
            jchar* buffer = (jchar*) malloc(totalLength * sizeof(jchar));
            size_t offset = 0;
            for (int c = 0; c < columns; c++) {
                const jchar* text = static_cast<const jchar*>(sqlite3_column_text16(statement, 0));
                size_t sizeBytes = sqlite3_column_bytes16(statement, c);
                memcpy(buffer + offset, text, sizeBytes);
                offset += sizeBytes / sizeof(jchar);
            }
            result = env->NewString(buffer, totalLength);
            free(buffer);
        }
    } else if (err != SQLITE_DONE) {
        throw_sqlite3_exception(env, dbConnection);
    }

    sqlite3_finalize(statement);
    return result;
}

static void nativeExecuteAndReset(JNIEnv* env, jclass clazz, jlong connectionPtr, jlong statementPtr) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);
    int err = sqlite3_step(statement);
    if (err != SQLITE_DONE) {
        throw_sqlite3_exception(env, dbConnection, "Expected 0 rows");
    }
    sqlite3_reset(statement);
}
static void nativeExecuteIgnoreAndReset(JNIEnv* env, jclass clazz, jlong connectionPtr, jlong statementPtr) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);
    int err = sqlite3_step(statement);
    if (err != SQLITE_DONE && err != SQLITE_ROW) {
        throw_sqlite3_exception(env, dbConnection, "Unexpected error");
    }
    sqlite3_reset(statement);
}
static jlong nativeExecuteForLongAndReset(JNIEnv* env, jclass clazz, jlong connectionPtr, jlong statementPtr, jlong defaultValue) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);
    jlong result = 0;
    int err = sqlite3_step(statement);
    if (err == SQLITE_DONE) {
        result = defaultValue;
    } else if (err == SQLITE_ROW) {
        if (sqlite3_column_count(statement) != 1) {
            throw_sqlite3_exception(env, dbConnection, "Expected exactly one column");
        } else {
            result = (jlong) sqlite3_column_int64(statement, 0);
            if (sqlite3_step(statement) != SQLITE_DONE) {
                throw_sqlite3_exception(env, dbConnection, "Got more than one row");
            }
        }
    } else {
        throw_sqlite3_exception(env, dbConnection, "Error evaluating");
    }
    sqlite3_reset(statement);
    return result;
}
static jdouble nativeExecuteForDoubleAndReset(JNIEnv* env, jclass clazz, jlong connectionPtr, jlong statementPtr, jdouble defaultValue) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);
    jdouble result = 0;
    int err = sqlite3_step(statement);
    if (err == SQLITE_DONE) {
        result = defaultValue;
    } else if (err == SQLITE_ROW) {
        if (sqlite3_column_count(statement) != 1) {
            throw_sqlite3_exception(env, dbConnection, "Expected exactly one column");
        } else {
            result = (jdouble) sqlite3_column_double(statement, 0);
            if (sqlite3_step(statement) != SQLITE_DONE) {
                throw_sqlite3_exception(env, dbConnection, "Got more than one row");
            }
        }
    } else {
        throw_sqlite3_exception(env, dbConnection, "Error evaluating");
    }
    sqlite3_reset(statement);
    return result;
}
static jstring nativeExecuteForStringOrNullAndReset(JNIEnv* env, jclass clazz, jlong connectionPtr, jlong statementPtr) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);
    jstring result = NULL;
    int err = sqlite3_step(statement);
    if (err == SQLITE_DONE) {
        result = NULL;
    } else if (err == SQLITE_ROW) {
        if (sqlite3_column_count(statement) != 1) {
            throw_sqlite3_exception(env, dbConnection, "Expected exactly one column");
        } else {
            const jchar* text = static_cast<const jchar*>(sqlite3_column_text16(statement, 0));
            size_t length = sqlite3_column_bytes16(statement, 0) / sizeof(jchar);
            result = env->NewString(text, length);

            if (sqlite3_step(statement) != SQLITE_DONE) {
                throw_sqlite3_exception(env, dbConnection, "Got more than one row");
            }
        }
    } else {
        throw_sqlite3_exception(env, dbConnection, "Error evaluating");
    }
    sqlite3_reset(statement);
    return result;
}
static jbyteArray nativeExecuteForBlobOrNullAndReset(JNIEnv* env, jclass clazz, jlong connectionPtr, jlong statementPtr) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);
    jbyteArray result = NULL;
    int err = sqlite3_step(statement);
    if (err == SQLITE_DONE) {
        result = NULL;
    } else if (err == SQLITE_ROW) {
        if (sqlite3_column_count(statement) != 1) {
            throw_sqlite3_exception(env, dbConnection, "Expected exactly one column");
        } else {
            const void* blob = sqlite3_column_blob(statement, 0);
            size_t length = sqlite3_column_bytes(statement, 0);
            result = env->NewByteArray(length);
            if (length > 0) {
                env->SetByteArrayRegion(result, 0, (jsize) length, static_cast<const jbyte*>(blob));
            }
            if (sqlite3_step(statement) != SQLITE_DONE) {
                throw_sqlite3_exception(env, dbConnection, "Got more than one row");
            }
        }
    } else {
        throw_sqlite3_exception(env, dbConnection, "Error evaluating");
    }
    sqlite3_reset(statement);
    return result;
}
static jlong nativeExecuteForLastInsertedRowIDAndReset(JNIEnv* env, jclass clazz, jlong connectionPtr, jlong statementPtr) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    sqlite3_set_last_insert_rowid(dbConnection, -1);// To make sure we return -1 when the statement is not insert
    int err = sqlite3_step(statement);
    jlong result = -1;
    if (err != SQLITE_DONE) {
        throw_sqlite3_exception(env, dbConnection, "Expected 0 rows");
    } else {
        result = (jlong) sqlite3_last_insert_rowid(dbConnection);
    }
    sqlite3_reset(statement);
    return result;
}
static jlong nativeExecuteForChangedRowsAndReset(JNIEnv* env, jclass clazz, jlong connectionPtr, jlong statementPtr) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = sqlite3_step(statement);
    jlong result = 0;
    if (err != SQLITE_DONE) {
        throw_sqlite3_exception(env, dbConnection, "Expected 0 rows");
    } else {
        result = (jlong) sqlite3_changes64(dbConnection);
    }
    sqlite3_reset(statement);
    return result;
}

static jboolean nativeCursorStep(JNIEnv* env, jclass clazz, jlong connectionPtr, jlong statementPtr) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);
    int err = sqlite3_step(statement);
    if (err == SQLITE_ROW) {
        return JNI_TRUE;
    } else if (err == SQLITE_DONE) {
        return JNI_FALSE;
    } else {
        throw_sqlite3_exception(env, dbConnection, NULL);
        return JNI_FALSE;
    }
}
static void maybe_throw_after_column_get(JNIEnv* env, sqlite3* dbConnection) {
    int err = sqlite3_extended_errcode(dbConnection);
    if (err == SQLITE_OK) return;
    throw_sqlite3_exception(env, err, sqlite3_errmsg(dbConnection), "Column get failed");
}
static jlong nativeCursorGetLong(JNIEnv* env, jclass clazz, jlong connectionPtr, jlong statementPtr, jint index) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);
    sqlite3ex_clear_errcode(dbConnection);
    int type = sqlite3_column_type(statement, index);
    jlong result = type == SQLITE_NULL ? 0 : (jlong) sqlite3_column_int64(statement, index);
    maybe_throw_after_column_get(env, dbConnection);
    return result;
}
static jdouble nativeCursorGetDouble(JNIEnv* env, jclass clazz, jlong connectionPtr, jlong statementPtr, jint index) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);
    sqlite3ex_clear_errcode(dbConnection);
    int type = sqlite3_column_type(statement, index);
    jdouble result = type == SQLITE_NULL ? 0.0 : (jdouble) sqlite3_column_double(statement, index);
    maybe_throw_after_column_get(env, dbConnection);
    return result;
}
static jstring nativeCursorGetString(JNIEnv* env, jclass clazz, jlong connectionPtr, jlong statementPtr, jint index) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);
    sqlite3ex_clear_errcode(dbConnection);

    int type = sqlite3_column_type(statement, index);
    jstring result = NULL;
    if (type != SQLITE_NULL) {
        const jchar* text = static_cast<const jchar*>(sqlite3_column_text16(statement, index));
        size_t length = sqlite3_column_bytes16(statement, index) / sizeof(jchar);
        result = env->NewString(text, length);
    }

    maybe_throw_after_column_get(env, dbConnection);
    return result;
}
static jbyteArray nativeCursorGetBlob(JNIEnv* env, jclass clazz, jlong connectionPtr, jlong statementPtr, jint index) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);
    sqlite3ex_clear_errcode(dbConnection);

    int type = sqlite3_column_type(statement, index);
    jbyteArray result = NULL;
    if (type != SQLITE_NULL) {
        const void* blob = sqlite3_column_blob(statement, index);
        size_t length = sqlite3_column_bytes(statement, index);
        result = env->NewByteArray(length);
        if (length > 0) {
            env->SetByteArrayRegion(result, 0, (jsize) length, static_cast<const jbyte*>(blob));
        }
    }

    maybe_throw_after_column_get(env, dbConnection);
    return result;
}

static void nativeResetStatement(JNIEnv* env, jclass clazz, jlong connectionPtr, jlong statementPtr) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = sqlite3_reset(statement);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, dbConnection, NULL);
    }
}
static void nativeClearBindings(JNIEnv* env, jclass clazz, jlong connectionPtr, jlong statementPtr) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = sqlite3_clear_bindings(statement);
    if (err != SQLITE_OK) {
        throw_sqlite3_exception(env, dbConnection, NULL);
    }
}

static void nativeInterrupt(JNIEnv* env, jobject clazz, jlong connectionPtr) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_interrupt(dbConnection);
}

static JNINativeMethod sMethods[] =
{
    /* name, signature, funcPtr */
    { "nativeOpen", "(Ljava/lang/String;I)J",
            (void*)nativeOpen },
    { "nativeClose", "(J)V",
            (void*)nativeClose },
    { "nativePrepareStatement", "(JLjava/lang/String;)J",
            (void*)nativePrepareStatement },
    { "nativeFinalizeStatement", "(JJ)V",
            (void*)nativeFinalizeStatement },
    { "nativeBindNull", "(JJI)V",
            (void*)nativeBindNull },
    { "nativeBindLong", "(JJIJ)V",
            (void*)nativeBindLong },
    { "nativeBindDouble", "(JJID)V",
            (void*)nativeBindDouble },
    { "nativeBindString", "(JJILjava/lang/String;)V",
            (void*)nativeBindString },
    { "nativeBindBlob", "(JJI[B)V",
            (void*)nativeBindBlob },
    { "nativeResetStatementAndClearBindings", "(JJ)V",
            (void*)nativeResetStatementAndClearBindings },
    { "nativeExecutePragma", "(JLjava/lang/String;)Ljava/lang/String;",
            (void*)nativeExecutePragma },

    { "nativeExecuteAndReset", "(JJ)V", (void*) nativeExecuteAndReset },
    { "nativeExecuteIgnoreAndReset", "(JJ)V", (void*) nativeExecuteIgnoreAndReset },
    { "nativeExecuteForLongAndReset", "(JJJ)J", (void*) nativeExecuteForLongAndReset },
    { "nativeExecuteForDoubleAndReset", "(JJD)D", (void*) nativeExecuteForDoubleAndReset },
    { "nativeExecuteForStringOrNullAndReset", "(JJ)Ljava/lang/String;", (void*) nativeExecuteForStringOrNullAndReset },
    { "nativeExecuteForBlobOrNullAndReset", "(JJ)[B", (void*) nativeExecuteForBlobOrNullAndReset },
    { "nativeExecuteForLastInsertedRowIDAndReset", "(JJ)J", (void*) nativeExecuteForLastInsertedRowIDAndReset },
    { "nativeExecuteForChangedRowsAndReset", "(JJ)J", (void*) nativeExecuteForChangedRowsAndReset },

    { "nativeCursorStep", "(JJ)Z", (void*) nativeCursorStep },
    { "nativeCursorGetLong", "(JJI)J", (void*) nativeCursorGetLong },
    { "nativeCursorGetDouble", "(JJI)D", (void*) nativeCursorGetDouble },
    { "nativeCursorGetString", "(JJI)Ljava/lang/String;", (void*) nativeCursorGetString },
    { "nativeCursorGetBlob", "(JJI)[B", (void*) nativeCursorGetBlob },
    { "nativeResetStatement", "(JJ)V", (void*) nativeResetStatement },
    { "nativeClearBindings", "(JJ)V", (void*) nativeClearBindings },

    { "nativeInterrupt", "(J)V",
            (void*)nativeInterrupt },
    { "nativeReleaseMemory", "()I",
            (void*)nativeReleaseMemory },
};

} // namespace android

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
  JNIEnv *env = 0;

  android::gpJavaVM = vm;
  vm->GetEnv((void**)&env, JNI_VERSION_1_4);

  jniRegisterNativeMethods(env,
      "com/darkyen/sqlite/SQLiteNative",
      android::sMethods, NELEM(android::sMethods)
  );

  android::sqliteInitialize();

  return JNI_VERSION_1_4;
}



