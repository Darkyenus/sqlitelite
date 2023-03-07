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
/*
** Modified to support SQLite extensions by the SQLite developers: 
** sqlite-dev@sqlite.org.
*/

package io.requery.android.database.sqlite;

import android.database.sqlite.SQLiteException;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.util.Log;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;

import static com.darkyen.sqlite.SQLiteNative.nativeClose;
import static com.darkyen.sqlite.SQLiteNative.nativeExecute;
import static com.darkyen.sqlite.SQLiteNative.nativeExecuteForChangedRowCount;
import static com.darkyen.sqlite.SQLiteNative.nativeExecuteForLastInsertedRowId;
import static com.darkyen.sqlite.SQLiteNative.nativeExecuteForLong;
import static com.darkyen.sqlite.SQLiteNative.nativeExecuteForString;
import static com.darkyen.sqlite.SQLiteNative.nativeExecutePragma;
import static com.darkyen.sqlite.SQLiteNative.nativeInterrupt;
import static com.darkyen.sqlite.SQLiteNative.nativeOpen;

/**
 * Represents a SQLite database connection.
 * Each connection wraps an instance of a native <code>sqlite3</code> object.
 * <p>
 * When database connection pooling is enabled, there can be multiple active
 * connections to the same database.  Otherwise there is typically only one
 * connection per database.
 * </p><p>
 * When the SQLite WAL feature is enabled, multiple readers and one writer
 * can concurrently access the database.  Without WAL, readers and writers
 * are mutually exclusive.
 * </p>
 *
 * <h2>Ownership and concurrency guarantees</h2>
 * <p>
 * The guarantee of having a single owner allows this class to be implemented
 * without locks and greatly simplifies resource management.
 * </p>
 *
 * <h2>Encapsulation guarantees</h2>
 * <p>
 * The connection object object owns *all* of the SQLite related native
 * objects that are associated with the connection.  What's more, there are
 * no other objects in the system that are capable of obtaining handles to
 * those native objects.  Consequently, when the connection is closed, we do
 * not have to worry about what other components might have references to
 * its associated SQLite state -- there are none.
 * </p><p>
 * Encapsulation is what ensures that the connection object's
 * lifecycle does not become a tortured mess of finalizers and reference
 * queues.
 * </p>
 *
 * <h2>Reentrance</h2>
 * <p>
 * This class must tolerate reentrant execution of SQLite operations because
 * triggers may call custom SQLite functions that perform additional queries.
 * </p>
 */
public final class SQLiteConnection implements CancellationSignal.OnCancelListener, Closeable {
    private static final String TAG = "SQLiteConnection";

    private final SQLiteDatabase db;

    // The native SQLiteConnection pointer.  (FOR INTERNAL USE ONLY)
    long mConnectionPtr;

    // The number of times attachCancellationSignal has been called.
    // Because SQLite statement execution can be reentrant, we keep track of how many
    // times we have attempted to attach a cancellation signal to the connection so that
    // we can ensure that we detach the signal at the right time.
    @Deprecated//("No longer used")
    private int mCancellationSignalAttachCount;

    SQLiteConnection(SQLiteDatabase db, SQLiteDatabaseConfiguration configuration) {
        this.db = db;
        mConnectionPtr = nativeOpen(configuration.path,
                configuration.openFlags,
                configuration.label);
        boolean ok = false;
        try {
            boolean readOnly = (configuration.openFlags & SQLiteDatabase.OPEN_READONLY) != 0;
            if (!readOnly) {
                executePragma("foreign_keys", configuration.foreignKeyConstraintsEnabled ? "1" : "0");
                if (!configuration.isInMemoryDb()) {
                    executePragma("journal_mode", "WAL");
                }
            }
            ok = true;
        } finally {
            if (!ok) {
                nativeClose(mConnectionPtr);
            }
        }
    }

    @Override
    public void close() {
        final long ptr = mConnectionPtr;
        mConnectionPtr = 0;
        if (ptr != 0) {
            nativeClose(ptr);
        }
    }

    /**
     * Executes a statement that does not return a result.
     *
     * @param sql The SQL statement to execute.
     * @param bindArgs The arguments to bind, or null if none.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error
     * or invalid number of bind arguments.
     * @throws OperationCanceledException if the operation was canceled.
     */
    public void execute(String sql, Object[] bindArgs, CancellationSignal cancellationSignal) {
        try (SQLitePreparedStatement statement = new SQLitePreparedStatement(db, sql, bindArgs, cancellationSignal)) {
            attachCancellationSignal(cancellationSignal);
            try {
                statement.bindArguments(bindArgs);
                nativeExecute(mConnectionPtr, statement.statementPtr);
            } finally {
                detachCancellationSignal(cancellationSignal);
            }
        }
    }

    /**
     * Executes a statement that returns a single <code>long</code> result.
     *
     * @param sql The SQL statement to execute.
     * @param bindArgs The arguments to bind, or null if none.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * @return The value of the first column in the first row of the result set
     * as a <code>long</code>, or zero if none.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error
     * or invalid number of bind arguments.
     * @throws OperationCanceledException if the operation was canceled.
     */
    public long executeForLong(String sql, Object[] bindArgs,
            CancellationSignal cancellationSignal) {
        try (SQLitePreparedStatement statement = new SQLitePreparedStatement(db, sql, bindArgs, cancellationSignal)) {
            attachCancellationSignal(cancellationSignal);
            try {
                statement.bindArguments(bindArgs);
                return nativeExecuteForLong(mConnectionPtr, statement.statementPtr);
            } finally {
                detachCancellationSignal(cancellationSignal);
            }
        }
    }

    /**
     * Executes a statement that returns a single {@link String} result.
     *
     * @param sql The SQL statement to execute.
     * @param bindArgs The arguments to bind, or null if none.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * @return The value of the first column in the first row of the result set
     * as a <code>String</code>, or null if none.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error
     * or invalid number of bind arguments.
     * @throws OperationCanceledException if the operation was canceled.
     */
    public String executeForString(String sql, Object[] bindArgs,
            CancellationSignal cancellationSignal) {
        try (SQLitePreparedStatement statement = new SQLitePreparedStatement(db, sql, bindArgs, cancellationSignal)) {
            attachCancellationSignal(cancellationSignal);
            try {
                statement.bindArguments(bindArgs);
                return nativeExecuteForString(mConnectionPtr, statement.statementPtr);
            } finally {
                detachCancellationSignal(cancellationSignal);
            }
        }
    }

    public String executePragma(@NotNull String pragmaName, @NotNull String value) {
        final String sql = "PRAGMA " + pragmaName + "=" + value;
        try {
            String result = nativeExecutePragma(mConnectionPtr, sql);
            Log.i(TAG, sql+" -> "+result);
            return result;
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to execute "+sql, e);
            return null;
        }
    }

    /**
     * Executes a statement that returns a count of the number of rows
     * that were changed.  Use for UPDATE or DELETE SQL statements.
     *
     * @param sql The SQL statement to execute.
     * @param bindArgs The arguments to bind, or null if none.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * @return The number of rows that were changed.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error
     * or invalid number of bind arguments.
     * @throws OperationCanceledException if the operation was canceled.
     */
    public int executeForChangedRowCount(String sql, Object[] bindArgs,
            CancellationSignal cancellationSignal) {
        try (SQLitePreparedStatement statement = new SQLitePreparedStatement(db, sql, bindArgs, cancellationSignal)) {
            attachCancellationSignal(cancellationSignal);
            try {
                statement.bindArguments(bindArgs);
                return nativeExecuteForChangedRowCount(mConnectionPtr, statement.statementPtr);
            } finally {
                detachCancellationSignal(cancellationSignal);
            }
        }
    }

    /**
     * Executes a statement that returns the row id of the last row inserted
     * by the statement.  Use for INSERT SQL statements.
     *
     * @param sql The SQL statement to execute.
     * @param bindArgs The arguments to bind, or null if none.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * @return The row id of the last row that was inserted, or 0 if none.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error
     * or invalid number of bind arguments.
     * @throws OperationCanceledException if the operation was canceled.
     */
    public long executeForLastInsertedRowId(String sql, Object[] bindArgs,
            CancellationSignal cancellationSignal) {
        try (SQLitePreparedStatement statement = new SQLitePreparedStatement(db, sql, bindArgs, cancellationSignal)) {
            attachCancellationSignal(cancellationSignal);
            try {
                statement.bindArguments(bindArgs);
                return nativeExecuteForLastInsertedRowId(mConnectionPtr, statement.statementPtr);
            } finally {
                detachCancellationSignal(cancellationSignal);
            }
        }
    }

    void attachCancellationSignal(CancellationSignal cancellationSignal) {
        if (cancellationSignal != null) {
            cancellationSignal.throwIfCanceled();

            mCancellationSignalAttachCount += 1;
            if (mCancellationSignalAttachCount == 1) {
                // After this point, onCancel() may be called concurrently.
                cancellationSignal.setOnCancelListener(this);
            }
        }
    }

    void detachCancellationSignal(CancellationSignal cancellationSignal) {
        if (cancellationSignal != null) {
            assert mCancellationSignalAttachCount > 0;

            mCancellationSignalAttachCount -= 1;
            if (mCancellationSignalAttachCount == 0) {
                // After this point, onCancel() cannot be called concurrently.
                cancellationSignal.setOnCancelListener(null);
            }
        }
    }

    // CancellationSignal.OnCancelListener callback.
    // This method may be called on a different thread than the executing statement.
    // However, it will only be called between calls to attachCancellationSignal and
    // detachCancellationSignal, while a statement is executing.  We can safely assume
    // that the SQLite connection is still alive.
    public void onCancel() {
        nativeInterrupt(mConnectionPtr);
    }
}
