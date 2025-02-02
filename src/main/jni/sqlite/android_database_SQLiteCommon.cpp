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

#include "android_database_SQLiteCommon.h"

namespace android {

/* throw a SQLiteException with a message appropriate for the error in handle */
void throw_sqlite3_exception(JNIEnv* env, sqlite3* handle) {
    throw_sqlite3_exception(env, handle, NULL);
}

/* throw a SQLiteException with the given message */
void throw_sqlite3_exception(JNIEnv* env, const char* message) {
    throw_sqlite3_exception(env, NULL, message);
}

/* throw a SQLiteException with a message appropriate for the error in handle
   concatenated with the given message
 */
void throw_sqlite3_exception(JNIEnv* env, sqlite3* handle, const char* message) {
    if (handle) {
        // get the error code and message from the SQLite connection
        // the error message may contain more information than the error code
        // because it is based on the extended error code rather than the simplified
        // error code that SQLite normally returns.
        throw_sqlite3_exception(env, sqlite3_extended_errcode(handle),
                sqlite3_errmsg(handle), message);
    } else {
        // we use SQLITE_OK so that a generic SQLiteException is thrown;
        // any code not specified in the switch statement below would do.
        throw_sqlite3_exception(env, SQLITE_OK, "unknown error", message);
    }
}

/* throw a SQLiteException for a given error code
 * should only be used when the database connection is not available because the
 * error information will not be quite as rich */
void throw_sqlite3_exception_errcode(JNIEnv* env, int errcode, const char* message) {
    throw_sqlite3_exception(env, errcode, "unknown error", message);
}

/* throw a SQLiteException for a given error code, sqlite3message, and
   user message
 */
void throw_sqlite3_exception(JNIEnv* env, int errcode,
                             const char* sqlite3Message, const char* message) {
    const char* exceptionClass;
    const char* additionalInfo = "";
    switch (errcode & 0xff) { /* mask off extended error code */
        case SQLITE_ERROR:
            exceptionClass = "android/database/sqlite/SQLiteException";
            switch (errcode) {
                case SQLITE_ERROR_MISSING_COLLSEQ: additionalInfo = "MISSING_COLLSEQ"; break;
                case SQLITE_ERROR_RETRY: additionalInfo = "RETRY"; break;
                case SQLITE_ERROR_SNAPSHOT: additionalInfo = "SNAPSHOT"; break;
                default: break;
            }
            break;
        case SQLITE_IOERR:
            exceptionClass = "android/database/sqlite/SQLiteDiskIOException";
            switch (errcode) {
                case SQLITE_IOERR_READ: additionalInfo = "READ"; break;
                case SQLITE_IOERR_SHORT_READ: additionalInfo = "SHORT_READ"; break;
                case SQLITE_IOERR_WRITE: additionalInfo = "WRITE"; break;
                case SQLITE_IOERR_FSYNC: additionalInfo = "FSYNC"; break;
                case SQLITE_IOERR_DIR_FSYNC: additionalInfo = "DIR_FSYNC"; break;
                case SQLITE_IOERR_TRUNCATE: additionalInfo = "TRUNCATE"; break;
                case SQLITE_IOERR_FSTAT: additionalInfo = "FSTAT"; break;
                case SQLITE_IOERR_UNLOCK: additionalInfo = "UNLOCK"; break;
                case SQLITE_IOERR_RDLOCK: additionalInfo = "RDLOCK"; break;
                case SQLITE_IOERR_DELETE: additionalInfo = "DELETE"; break;
                case SQLITE_IOERR_BLOCKED: additionalInfo = "BLOCKED"; break;
                case SQLITE_IOERR_NOMEM: additionalInfo = "NOMEM"; break;
                case SQLITE_IOERR_ACCESS: additionalInfo = "ACCESS"; break;
                case SQLITE_IOERR_CHECKRESERVEDLOCK: additionalInfo = "CHECKRESERVEDLOCK"; break;
                case SQLITE_IOERR_LOCK: additionalInfo = "LOCK"; break;
                case SQLITE_IOERR_CLOSE: additionalInfo = "CLOSE"; break;
                case SQLITE_IOERR_DIR_CLOSE: additionalInfo = "DIR_CLOSE"; break;
                case SQLITE_IOERR_SHMOPEN: additionalInfo = "SHMOPEN"; break;
                case SQLITE_IOERR_SHMSIZE: additionalInfo = "SHMSIZE"; break;
                case SQLITE_IOERR_SHMLOCK: additionalInfo = "SHMLOCK"; break;
                case SQLITE_IOERR_SHMMAP: additionalInfo = "SHMMAP"; break;
                case SQLITE_IOERR_SEEK: additionalInfo = "SEEK"; break;
                case SQLITE_IOERR_DELETE_NOENT: additionalInfo = "DELETE_NOENT"; break;
                case SQLITE_IOERR_MMAP: additionalInfo = "MMAP"; break;
                case SQLITE_IOERR_GETTEMPPATH: additionalInfo = "GETTEMPPATH"; break;
                case SQLITE_IOERR_CONVPATH: additionalInfo = "CONVPATH"; break;
                case SQLITE_IOERR_VNODE: additionalInfo = "VNODE"; break;
                case SQLITE_IOERR_AUTH: additionalInfo = "AUTH"; break;
                case SQLITE_IOERR_BEGIN_ATOMIC: additionalInfo = "BEGIN_ATOMIC"; break;
                case SQLITE_IOERR_COMMIT_ATOMIC: additionalInfo = "COMMIT_ATOMIC"; break;
                case SQLITE_IOERR_ROLLBACK_ATOMIC: additionalInfo = "ROLLBACK_ATOMIC"; break;
                case SQLITE_IOERR_DATA: additionalInfo = "DATA"; break;
                case SQLITE_IOERR_CORRUPTFS: additionalInfo = "CORRUPTFS"; break;
                case SQLITE_IOERR_IN_PAGE: additionalInfo = "IN_PAGE"; break;
                default: break;
            }
            break;
        case SQLITE_CORRUPT:
            // treat "unsupported file format" error as corruption also
            exceptionClass = "android/database/sqlite/SQLiteDatabaseCorruptException";
            switch (errcode) {
                case SQLITE_CORRUPT_VTAB: additionalInfo = "CORRUPT_VTAB";
                case SQLITE_CORRUPT_SEQUENCE: additionalInfo = "CORRUPT_SEQUENCE";
                case SQLITE_CORRUPT_INDEX: additionalInfo = "CORRUPT_INDEX";
                default: break;
            }
        case SQLITE_NOTADB:
            exceptionClass = "android/database/sqlite/SQLiteDatabaseCorruptException";
            additionalInfo = "NOTADB";
            break;
        case SQLITE_CONSTRAINT:
            exceptionClass = "android/database/sqlite/SQLiteConstraintException";
            switch (errcode) {
                case SQLITE_CONSTRAINT_CHECK: additionalInfo = "CHECK"; break;
                case SQLITE_CONSTRAINT_COMMITHOOK: additionalInfo = "COMMITHOOK"; break;
                case SQLITE_CONSTRAINT_FOREIGNKEY: additionalInfo = "FOREIGNKEY"; break;
                case SQLITE_CONSTRAINT_FUNCTION: additionalInfo = "FUNCTION"; break;
                case SQLITE_CONSTRAINT_NOTNULL: additionalInfo = "NOTNULL"; break;
                case SQLITE_CONSTRAINT_PRIMARYKEY: additionalInfo = "PRIMARYKEY"; break;
                case SQLITE_CONSTRAINT_TRIGGER: additionalInfo = "TRIGGER"; break;
                case SQLITE_CONSTRAINT_UNIQUE: additionalInfo = "UNIQUE"; break;
                case SQLITE_CONSTRAINT_VTAB: additionalInfo = "VTAB"; break;
                case SQLITE_CONSTRAINT_ROWID: additionalInfo = "ROWID"; break;
                case SQLITE_CONSTRAINT_PINNED: additionalInfo = "PINNED"; break;
                case SQLITE_CONSTRAINT_DATATYPE: additionalInfo = "DATATYPE"; break;
                default: break;
            }
            break;
        case SQLITE_ABORT:
            exceptionClass = "android/database/sqlite/SQLiteAbortException";
            switch (errcode) {
                case SQLITE_ABORT_ROLLBACK: additionalInfo = "ROLLBACK"; break;
                default: break;
            }
            break;
        case SQLITE_DONE:
            exceptionClass = "android/database/sqlite/SQLiteDoneException";
            sqlite3Message = nullptr; // SQLite error message is irrelevant in this case
            break;
        case SQLITE_FULL:
            exceptionClass = "android/database/sqlite/SQLiteFullException";
            break;
        case SQLITE_MISUSE:
            exceptionClass = "android/database/sqlite/SQLiteMisuseException";
            break;
        case SQLITE_PERM:
            exceptionClass = "android/database/sqlite/SQLiteAccessPermException";
            break;
        case SQLITE_BUSY:
            exceptionClass = "android/database/sqlite/SQLiteDatabaseLockedException";
            switch (errcode) {
                case SQLITE_BUSY_RECOVERY: additionalInfo = "RECOVERY"; break;
                case SQLITE_BUSY_SNAPSHOT: additionalInfo = "SNAPSHOT"; break;
                case SQLITE_BUSY_TIMEOUT: additionalInfo = "TIMEOUT"; break;
                default: break;
            }
            break;
        case SQLITE_LOCKED:
            exceptionClass = "android/database/sqlite/SQLiteTableLockedException";
            switch (errcode) {
                case SQLITE_LOCKED_SHAREDCACHE: additionalInfo = "SHAREDCACHE"; break;
                case SQLITE_LOCKED_VTAB: additionalInfo = "VTAB"; break;
                default: break;
            }
            break;
        case SQLITE_READONLY:
            exceptionClass = "android/database/sqlite/SQLiteReadOnlyDatabaseException";
            switch (errcode) {
                case SQLITE_READONLY_RECOVERY: additionalInfo = "RECOVERY"; break;
                case SQLITE_READONLY_CANTLOCK: additionalInfo = "CANTLOCK"; break;
                case SQLITE_READONLY_ROLLBACK: additionalInfo = "ROLLBACK"; break;
                case SQLITE_READONLY_DBMOVED: additionalInfo = "DBMOVED"; break;
                case SQLITE_READONLY_CANTINIT: additionalInfo = "CANTINIT"; break;
                case SQLITE_READONLY_DIRECTORY: additionalInfo = "DIRECTORY"; break;
                default: break;
            }
            break;
        case SQLITE_CANTOPEN:
            exceptionClass = "android/database/sqlite/SQLiteCantOpenDatabaseException";
            switch (errcode) {
                case SQLITE_CANTOPEN_NOTEMPDIR: additionalInfo = "NOTEMPDIR";
                case SQLITE_CANTOPEN_ISDIR: additionalInfo = "ISDIR";
                case SQLITE_CANTOPEN_FULLPATH: additionalInfo = "FULLPATH";
                case SQLITE_CANTOPEN_CONVPATH: additionalInfo = "CONVPATH";
                case SQLITE_CANTOPEN_DIRTYWAL: additionalInfo = "DIRTYWAL";
                case SQLITE_CANTOPEN_SYMLINK: additionalInfo = "SYMLINK";
                default: break;
            }
            break;
        case SQLITE_TOOBIG:
            exceptionClass = "android/database/sqlite/SQLiteBlobTooBigException";
            break;
        case SQLITE_RANGE:
            exceptionClass = "android/database/sqlite/SQLiteBindOrColumnIndexOutOfRangeException";
            break;
        case SQLITE_NOMEM:
            exceptionClass = "android/database/sqlite/SQLiteOutOfMemoryException";
            break;
        case SQLITE_MISMATCH:
            exceptionClass = "android/database/sqlite/SQLiteDatatypeMismatchException";
            break;
        case SQLITE_INTERRUPT:
            exceptionClass = "com/darkyen/sqlitelite/SQLiteInterruptedException";
            break;
        default:
            exceptionClass = "android/database/sqlite/SQLiteException";
            break;
    }

    // check this exception class exists otherwise just default to SQLiteException
    if (env->FindClass(exceptionClass) == nullptr) {
        exceptionClass = "android/database/sqlite/SQLiteException";
    }

    if (sqlite3Message) {
        char *zFullmsg = sqlite3_mprintf(
            "%s (%s%scode %d)%s%s", sqlite3Message,
            additionalInfo,
            additionalInfo[0] != 0 ? ", " : "",
            errcode,
            (message ? ": " : ""), (message ? message : "")
        );
        jniThrowException(env, exceptionClass, zFullmsg);
        sqlite3_free(zFullmsg);
    } else {
        jniThrowException(env, exceptionClass, message);
    }
}


} // namespace android
