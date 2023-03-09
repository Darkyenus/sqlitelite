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

#include "sqlite3.h"
#include "JNIHelp.h"
#include "ALog-priv.h"
#include "android_database_SQLiteCommon.h"
#include "CursorWindow.h"

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

static struct {
    jclass clazz;
} gStringClassInfo;

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
    sqlite3* dbConnection;
    int err = sqlite3_open_v2(pathChars, &dbConnection, openFlags | SQLITE_OPEN_EXRESCODE, NULL);
    env->ReleaseStringUTFChars(pathStr, pathChars);
    if (err != SQLITE_OK) {
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
    sqlite3_finalize(statement);
}

static jint nativeGetParameterCount(JNIEnv* env, jclass clazz, jlong connectionPtr,
        jlong statementPtr) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    return sqlite3_bind_parameter_count(statement);
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

static int executeNonQuery(JNIEnv* env, sqlite3* dbConnection, sqlite3_stmt* statement) {
    int err = sqlite3_step(statement);
    if (err == SQLITE_ROW) {
        throw_sqlite3_exception(env,
                "Queries can be performed using SQLiteDatabase query or rawQuery methods only.");
    } else if (err != SQLITE_DONE) {
        throw_sqlite3_exception(env, dbConnection);
    }
    return err;
}

static void nativeExecute(JNIEnv* env, jclass clazz, jlong connectionPtr, jlong statementPtr) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    executeNonQuery(env, dbConnection, statement);
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
            const jchar* text = static_cast<const jchar*>(sqlite3_column_text16(statement, c));
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

static jint nativeExecuteForChangedRowCount(JNIEnv* env, jclass clazz,
        jlong connectionPtr, jlong statementPtr) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = executeNonQuery(env, dbConnection, statement);
    return err == SQLITE_DONE ? sqlite3_changes(dbConnection) : -1;
}

static jlong nativeExecuteForLastInsertedRowId(JNIEnv* env, jclass clazz,
        jlong connectionPtr, jlong statementPtr) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = executeNonQuery(env, dbConnection, statement);
    return err == SQLITE_DONE && sqlite3_changes(dbConnection) > 0
            ? sqlite3_last_insert_rowid(dbConnection) : -1;
}

static int executeOneRowQuery(JNIEnv* env, sqlite3* dbConnection, sqlite3_stmt* statement) {
    int err = sqlite3_step(statement);
    if (err != SQLITE_ROW) {
        throw_sqlite3_exception(env, dbConnection);
    }
    return err;
}

static jlong nativeExecuteForLong(JNIEnv* env, jclass clazz,
        jlong connectionPtr, jlong statementPtr) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = executeOneRowQuery(env, dbConnection, statement);
    if (err == SQLITE_ROW && sqlite3_column_count(statement) >= 1) {
        return sqlite3_column_int64(statement, 0);
    }
    return -1;
}

static jstring nativeExecuteForString(JNIEnv* env, jclass clazz,
        jlong connectionPtr, jlong statementPtr) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);

    int err = executeOneRowQuery(env, dbConnection, statement);
    if (err == SQLITE_ROW && sqlite3_column_count(statement) >= 1) {
        const jchar* text = static_cast<const jchar*>(sqlite3_column_text16(statement, 0));
        if (text) {
            size_t length = sqlite3_column_bytes16(statement, 0) / sizeof(jchar);
            return env->NewString(text, length);
        }
    }
    return NULL;
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

enum CopyRowResult {
    CPR_OK,
    CPR_FULL,
    CPR_ERROR,
};

static CopyRowResult copyRow(JNIEnv* env, CursorWindow* window,
        sqlite3_stmt* statement, int numColumns, int startPos, int addedRows) {
    // Allocate a new field directory for the row.
    status_t status = window->allocRow();
    if (status) {
        LOG_WINDOW("Failed allocating fieldDir at startPos %d row %d, error=%d",
                startPos, addedRows, status);
        return CPR_FULL;
    }

    // Pack the row into the window.
    CopyRowResult result = CPR_OK;
    for (int i = 0; i < numColumns; i++) {
        int type = sqlite3_column_type(statement, i);
        if (type == SQLITE_TEXT) {
            // TEXT data
            const char* text = reinterpret_cast<const char*>(
                    sqlite3_column_text(statement, i));
            // SQLite does not include the NULL terminator in size, but does
            // ensure all strings are NULL terminated, so increase size by
            // one to make sure we store the terminator.
            size_t sizeIncludingNull = sqlite3_column_bytes(statement, i) + 1;
            status = window->putString(addedRows, i, text, sizeIncludingNull);
            if (status) {
                LOG_WINDOW("Failed allocating %u bytes for text at %d,%d, error=%d",
                        sizeIncludingNull, startPos + addedRows, i, status);
                result = CPR_FULL;
                break;
            }
            LOG_WINDOW("%d,%d is TEXT with %u bytes",
                    startPos + addedRows, i, sizeIncludingNull);
        } else if (type == SQLITE_INTEGER) {
            // INTEGER data
            int64_t value = sqlite3_column_int64(statement, i);
            status = window->putLong(addedRows, i, value);
            if (status) {
                LOG_WINDOW("Failed allocating space for a long in column %d, error=%d",
                        i, status);
                result = CPR_FULL;
                break;
            }
            LOG_WINDOW("%d,%d is INTEGER 0x%016llx", startPos + addedRows, i, value);
        } else if (type == SQLITE_FLOAT) {
            // FLOAT data
            double value = sqlite3_column_double(statement, i);
            status = window->putDouble(addedRows, i, value);
            if (status) {
                LOG_WINDOW("Failed allocating space for a double in column %d, error=%d",
                        i, status);
                result = CPR_FULL;
                break;
            }
            LOG_WINDOW("%d,%d is FLOAT %lf", startPos + addedRows, i, value);
        } else if (type == SQLITE_BLOB) {
            // BLOB data
            const void* blob = sqlite3_column_blob(statement, i);
            size_t size = sqlite3_column_bytes(statement, i);
            status = window->putBlob(addedRows, i, blob, size);
            if (status) {
                LOG_WINDOW("Failed allocating %u bytes for blob at %d,%d, error=%d",
                        size, startPos + addedRows, i, status);
                result = CPR_FULL;
                break;
            }
            LOG_WINDOW("%d,%d is Blob with %u bytes",
                    startPos + addedRows, i, size);
        } else if (type == SQLITE_NULL) {
            // NULL field
            status = window->putNull(addedRows, i);
            if (status) {
                LOG_WINDOW("Failed allocating space for a null in column %d, error=%d",
                        i, status);
                result = CPR_FULL;
                break;
            }

            LOG_WINDOW("%d,%d is NULL", startPos + addedRows, i);
        } else {
            // Unknown data
            ALOGE("Unknown column type when filling database window");
            throw_sqlite3_exception(env, "Unknown column type when filling window");
            result = CPR_ERROR;
            break;
        }
    }

    // Free the last row if if was not successfully copied.
    if (result != CPR_OK) {
        window->freeLastRow();
    }
    return result;
}

static jlong nativeExecuteForCursorWindow(JNIEnv* env, jclass clazz,
        jlong connectionPtr, jlong statementPtr, jlong windowPtr,
        jint startPos, jint requiredPos, jboolean countAllRows) {
    sqlite3* dbConnection = reinterpret_cast<sqlite3*>(connectionPtr);
    sqlite3_stmt* statement = reinterpret_cast<sqlite3_stmt*>(statementPtr);
    CursorWindow* window = reinterpret_cast<CursorWindow*>(windowPtr);

    status_t status = window->clear();
    if (status) {
        throw_sqlite3_exception(env, dbConnection, "Failed to clear the cursor window");
        return 0;
    }

    int numColumns = sqlite3_column_count(statement);
    status = window->setNumColumns(numColumns);
    if (status) {
        throw_sqlite3_exception(env, dbConnection, "Failed to set the cursor window column count");
        return 0;
    }

    int retryCount = 0;
    int totalRows = 0;
    int addedRows = 0;
    bool windowFull = false;
    bool gotException = false;
    while (!gotException && (!windowFull || countAllRows)) {
        int err = sqlite3_step(statement);
        if (err == SQLITE_ROW) {
            LOG_WINDOW("Stepped statement %p to row %d", statement, totalRows);
            retryCount = 0;
            totalRows += 1;

            // Skip the row if the window is full or we haven't reached the start position yet.
            if (startPos >= totalRows || windowFull) {
                continue;
            }

            CopyRowResult cpr = copyRow(env, window, statement, numColumns, startPos, addedRows);
            if (cpr == CPR_FULL && addedRows && startPos + addedRows <= requiredPos) {
                // We filled the window before we got to the one row that we really wanted.
                // Clear the window and start filling it again from here.
                // TODO: Would be nicer if we could progressively replace earlier rows.
                window->clear();
                window->setNumColumns(numColumns);
                startPos += addedRows;
                addedRows = 0;
                cpr = copyRow(env, window, statement, numColumns, startPos, addedRows);
            }

            if (cpr == CPR_OK) {
                addedRows += 1;
            } else if (cpr == CPR_FULL) {
                windowFull = true;
            } else {
                gotException = true;
            }
        } else if (err == SQLITE_DONE) {
            // All rows processed, bail
            LOG_WINDOW("Processed all rows");
            break;
        } else if (err == SQLITE_LOCKED || err == SQLITE_BUSY) {
            // The table is locked, retry
            LOG_WINDOW("Database locked, retrying");
            if (retryCount > 50) {
                ALOGE("Bailing on database busy retry");
                throw_sqlite3_exception(env, dbConnection, "retrycount exceeded");
                gotException = true;
            } else {
                // Sleep to give the thread holding the lock a chance to finish
                usleep(1000);
                retryCount++;
            }
        } else {
            throw_sqlite3_exception(env, dbConnection);
            gotException = true;
        }
    }

    LOG_WINDOW("Resetting statement %p after fetching %d rows and adding %d rows"
            "to the window in %d bytes",
            statement, totalRows, addedRows, window->size() - window->freeSpace());
    sqlite3_reset(statement);

    // Report the total number of rows on request.
    if (startPos > totalRows) {
        ALOGE("startPos %d > actual rows %d", startPos, totalRows);
    }
    jlong result = jlong(startPos) << 32 | jlong(totalRows);
    return result;
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
    { "nativeGetParameterCount", "(JJ)I",
            (void*)nativeGetParameterCount },
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
    { "nativeExecute", "(JJ)V",
            (void*)nativeExecute },
    { "nativeExecutePragma", "(JLjava/lang/String;)Ljava/lang/String;",
            (void*)nativeExecutePragma },
    { "nativeExecuteForLong", "(JJ)J",
            (void*)nativeExecuteForLong },
    { "nativeExecuteForString", "(JJ)Ljava/lang/String;",
            (void*)nativeExecuteForString },
    { "nativeExecuteForChangedRowCount", "(JJ)I",
            (void*)nativeExecuteForChangedRowCount },
    { "nativeExecuteForLastInsertedRowId", "(JJ)J",
            (void*)nativeExecuteForLastInsertedRowId },

    { "nativeExecuteAndReset", "(JJ)V", (void*) nativeExecuteAndReset },
    { "nativeExecuteForLongAndReset", "(JJJ)J", (void*) nativeExecuteForLongAndReset },
    { "nativeExecuteForDoubleAndReset", "(JJD)D", (void*) nativeExecuteForDoubleAndReset },
    { "nativeExecuteForStringOrNullAndReset", "(JJ)Ljava/lang/String;", (void*) nativeExecuteForStringOrNullAndReset },
    { "nativeExecuteForBlobOrNullAndReset", "(JJ)[B", (void*) nativeExecuteForBlobOrNullAndReset },
    { "nativeExecuteForLastInsertedRowIDAndReset", "(JJ)J", (void*) nativeExecuteForLastInsertedRowIDAndReset },
    { "nativeExecuteForChangedRowsAndReset", "(JJ)J", (void*) nativeExecuteForChangedRowsAndReset },

    { "nativeExecuteForCursorWindow", "(JJJIIZ)J",
            (void*)nativeExecuteForCursorWindow },
    { "nativeInterrupt", "(J)V",
            (void*)nativeInterrupt },
    { "nativeReleaseMemory", "()I",
            (void*)nativeReleaseMemory },
};

extern int register_android_database_CursorWindow(JNIEnv *env);

} // namespace android

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
  JNIEnv *env = 0;

  android::gpJavaVM = vm;
  vm->GetEnv((void**)&env, JNI_VERSION_1_4);

  jclass clazz;
  FIND_CLASS(clazz, "java/lang/String");
  android::gStringClassInfo.clazz = jclass(env->NewGlobalRef(clazz));

  jniRegisterNativeMethods(env,
      "com/darkyen/sqlite/SQLiteNative",
      android::sMethods, NELEM(android::sMethods)
  );

  android::sqliteInitialize();

  android::register_android_database_CursorWindow(env);

  return JNI_VERSION_1_4;
}



