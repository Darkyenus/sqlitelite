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

import android.annotation.SuppressLint;
import android.database.Cursor;
import android.database.sqlite.SQLiteBindOrColumnIndexOutOfRangeException;
import android.database.sqlite.SQLiteDatabaseLockedException;
import android.database.sqlite.SQLiteException;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.LruCache;
import io.requery.android.database.CursorWindow;

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
 * Connection objects are not thread-safe.  They are acquired as needed to
 * perform a database operation and are then returned to the pool.  At any
 * given time, a connection is either owned and used by a {@link SQLiteSession}
 * object or the {@link SQLiteConnectionPool}.  Those classes are
 * responsible for serializing operations to guard against concurrent
 * use of a connection.
 * </p><p>
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
public final class SQLiteConnection implements CancellationSignal.OnCancelListener {
    private static final String TAG = "SQLiteConnection";
    private static final boolean DEBUG = false;

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private final CloseGuard mCloseGuard = CloseGuard.get();

    private final SQLiteConnectionPool mPool;
    private final SQLiteDatabaseConfiguration mConfiguration;
    private final int mConnectionId;
    private final boolean mIsPrimaryConnection;
    private final boolean mIsReadOnlyConnection;
    private final PreparedStatementCache mPreparedStatementCache;
    private PreparedStatement mPreparedStatementPool;


    // The native SQLiteConnection pointer.  (FOR INTERNAL USE ONLY)
    private long mConnectionPtr;

    private boolean mOnlyAllowReadOnlyOperations;

    // The number of times attachCancellationSignal has been called.
    // Because SQLite statement execution can be reentrant, we keep track of how many
    // times we have attempted to attach a cancellation signal to the connection so that
    // we can ensure that we detach the signal at the right time.
    @Deprecated//("No longer used")
    private int mCancellationSignalAttachCount;

    private static native long nativeOpen(String path, int openFlags, String label);
    private static native void nativeClose(long connectionPtr);
    private static native long nativePrepareStatement(long connectionPtr, String sql);
    private static native void nativeFinalizeStatement(long connectionPtr, long statementPtr);
    private static native int nativeGetParameterCount(long connectionPtr, long statementPtr);
    private static native boolean nativeIsReadOnly(long connectionPtr, long statementPtr);
    private static native int nativeGetColumnCount(long connectionPtr, long statementPtr);
    private static native String nativeGetColumnName(long connectionPtr, long statementPtr,
            int index);
    private static native void nativeBindNull(long connectionPtr, long statementPtr,
            int index);
    private static native void nativeBindLong(long connectionPtr, long statementPtr,
            int index, long value);
    private static native void nativeBindDouble(long connectionPtr, long statementPtr,
            int index, double value);
    private static native void nativeBindString(long connectionPtr, long statementPtr,
            int index, String value);
    private static native void nativeBindBlob(long connectionPtr, long statementPtr,
            int index, byte[] value);
    private static native void nativeResetStatementAndClearBindings(
            long connectionPtr, long statementPtr);
    private static native void nativeExecute(long connectionPtr, long statementPtr);
    private static native long nativeExecuteForLong(long connectionPtr, long statementPtr);
    private static native String nativeExecuteForString(long connectionPtr, long statementPtr);
    private static native int nativeExecuteForChangedRowCount(long connectionPtr, long statementPtr);
    private static native long nativeExecuteForLastInsertedRowId(
            long connectionPtr, long statementPtr);
    private static native long nativeExecuteForCursorWindow(
            long connectionPtr, long statementPtr, long winPtr,
            int startPos, int requiredPos, boolean countAllRows);
    private static native void nativeInterrupt(long connectionPtr);


    private SQLiteConnection(SQLiteConnectionPool pool,
            SQLiteDatabaseConfiguration configuration,
            int connectionId, boolean primaryConnection) {
        mPool = pool;
        mConfiguration = new SQLiteDatabaseConfiguration(configuration);
        mConnectionId = connectionId;
        mIsPrimaryConnection = primaryConnection;
        mIsReadOnlyConnection = (configuration.openFlags & SQLiteDatabase.OPEN_READONLY) != 0;
        mPreparedStatementCache = new PreparedStatementCache(
                mConfiguration.maxSqlCacheSize);
        mCloseGuard.open("close");
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mPool != null && mConnectionPtr != 0) {
                mPool.onConnectionLeaked();
            }

            dispose(true);
        } finally {
            super.finalize();
        }
    }

    // Called by SQLiteConnectionPool only.
    static SQLiteConnection open(SQLiteConnectionPool pool,
            SQLiteDatabaseConfiguration configuration,
            int connectionId, boolean primaryConnection) {
        SQLiteConnection connection = new SQLiteConnection(pool, configuration,
                connectionId, primaryConnection);
        try {
            connection.open();
            return connection;
        } catch (SQLiteException ex) {
            connection.dispose(false);
            throw ex;
        }
    }

    // Called by SQLiteConnectionPool only.
    // Closes the database closes and releases all of its associated resources.
    // Do not call methods on the connection after it is closed.  It will probably crash.
    void close() {
        dispose(false);
    }

    private void open() {
        mConnectionPtr = nativeOpen(mConfiguration.path,
                mConfiguration.openFlags,
                mConfiguration.label);

        setForeignKeyModeFromConfiguration();

        // Enable WAL
        if (!mConfiguration.isInMemoryDb() && !mIsReadOnlyConnection) {
            String actualValue = "?";
            try {
                actualValue = executeForString("PRAGMA journal_mode=" + "WAL", null, null);
                if (actualValue.equalsIgnoreCase("WAL")) {
                    return;
                }
                // PRAGMA journal_mode silently fails and returns the original journal
                // mode in some cases if the journal mode could not be changed.
            } catch (SQLiteException ex) {
                // This error (SQLITE_BUSY) occurs if one connection has the database
                // open in WAL mode and another tries to change it to non-WAL.
                if (!(ex instanceof SQLiteDatabaseLockedException)) {
                    throw ex;
                }
            }

            // Because we always disable WAL mode when a database is first opened
            // (even if we intend to re-enable it), we can encounter problems if
            // there is another open connection to the database somewhere.
            // This can happen for a variety of reasons such as an application opening
            // the same database in multiple processes at the same time or if there is a
            // crashing content provider service that the ActivityManager has
            // removed from its registry but whose process hasn't quite died yet
            // by the time it is restarted in a new process.
            //
            // If we don't change the journal mode, nothing really bad happens.
            // In the worst case, an application that enables WAL might not actually
            // get it, although it can still use connection pooling.
            Log.w(TAG, "Could not change the database journal mode of '"
                    + mConfiguration.label + "' from '" + actualValue + "' to '" + "WAL"
                    + "' because the database is locked.  This usually means that "
                    + "there are other open connections to the database which prevents "
                    + "the database from enabling or disabling write-ahead logging mode.  "
                    + "Proceeding without changing the journal mode.");
        }
    }

    private void dispose(boolean finalized) {
        if (mCloseGuard != null) {
            if (finalized) {
                mCloseGuard.warnIfOpen();
            }
            mCloseGuard.close();
        }

        if (mConnectionPtr != 0) {
            mPreparedStatementCache.evictAll();
            nativeClose(mConnectionPtr);
            mConnectionPtr = 0;
        }
    }

    private void setForeignKeyModeFromConfiguration() {
        if (!mIsReadOnlyConnection) {
            final long newValue = mConfiguration.foreignKeyConstraintsEnabled ? 1 : 0;
            long value = executeForLong("PRAGMA foreign_keys", null, null);
            if (value != newValue) {
                execute("PRAGMA foreign_keys=" + newValue, null, null);
            }
        }
    }

    // Called by SQLiteConnectionPool only.
    void reconfigure(SQLiteDatabaseConfiguration configuration) {
        mOnlyAllowReadOnlyOperations = false;

        // Remember what changed.
        boolean foreignKeyModeChanged = configuration.foreignKeyConstraintsEnabled
                != mConfiguration.foreignKeyConstraintsEnabled;

        // Update configuration parameters.
        mConfiguration.updateParametersFrom(configuration);

        // Update prepared statement cache size.
        /* mPreparedStatementCache.resize(configuration.maxSqlCacheSize); */

        // Update foreign key mode.
        if (foreignKeyModeChanged) {
            setForeignKeyModeFromConfiguration();
        }
    }

    // Called by SQLiteConnectionPool only.
    // When set to true, executing write operations will throw SQLiteException.
    // Preparing statements that might write is ok, just don't execute them.
    void setOnlyAllowReadOnlyOperations(boolean readOnly) {
        mOnlyAllowReadOnlyOperations = readOnly;
    }

    // Called by SQLiteConnectionPool only.
    // Returns true if the prepared statement cache contains the specified SQL.
    boolean isPreparedStatementInCache(String sql) {
        return mPreparedStatementCache.get(sql) != null;
    }

    /**
     * Returns true if this is the primary database connection.
     * @return True if this is the primary database connection.
     */
    public boolean isPrimaryConnection() {
        return mIsPrimaryConnection;
    }

    /**
     * Prepares a statement for execution but does not bind its parameters or execute it.
     * <p>
     * This method can be used to check for syntax errors during compilation
     * prior to execution of the statement.  If the {@code outStatementInfo} argument
     * is not null, the provided {@link SQLiteStatementInfo} object is populated
     * with information about the statement.
     * </p><p>
     * A prepared statement makes no reference to the arguments that may eventually
     * be bound to it, consequently it it possible to cache certain prepared statements
     * such as SELECT or INSERT/UPDATE statements.  If the statement is cacheable,
     * then it will be stored in the cache for later.
     * </p><p>
     * To take advantage of this behavior as an optimization, the connection pool
     * provides a method to acquire a connection that already has a given SQL statement
     * in its prepared statement cache so that it is ready for execution.
     * </p>
     *
     * @param sql The SQL statement to prepare.
     * @param outStatementInfo The {@link SQLiteStatementInfo} object to populate
     * with information about the statement, or null if none.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error.
     */
    public void prepare(String sql, SQLiteStatementInfo outStatementInfo) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }

        final PreparedStatement statement = acquirePreparedStatement(sql);
        try {
            if (outStatementInfo != null) {
                outStatementInfo.numParameters = statement.mNumParameters;
                outStatementInfo.readOnly = statement.mReadOnly;

                final int columnCount = nativeGetColumnCount(
                        mConnectionPtr, statement.mStatementPtr);
                if (columnCount == 0) {
                    outStatementInfo.columnNames = EMPTY_STRING_ARRAY;
                } else {
                    outStatementInfo.columnNames = new String[columnCount];
                    for (int i = 0; i < columnCount; i++) {
                        outStatementInfo.columnNames[i] = nativeGetColumnName(
                                mConnectionPtr, statement.mStatementPtr, i);
                    }
                }
            }
        } finally {
            releasePreparedStatement(statement);
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
    public void execute(String sql, Object[] bindArgs,
            CancellationSignal cancellationSignal) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }

        final PreparedStatement statement = acquirePreparedStatement(sql);
        try {
            throwIfStatementForbidden(statement);
            bindArguments(statement, bindArgs);
            attachCancellationSignal(cancellationSignal);
            try {
                nativeExecute(mConnectionPtr, statement.mStatementPtr);
            } finally {
                detachCancellationSignal(cancellationSignal);
            }
        } finally {
            releasePreparedStatement(statement);
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
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }

        final PreparedStatement statement = acquirePreparedStatement(sql);
        try {
            throwIfStatementForbidden(statement);
            bindArguments(statement, bindArgs);
            attachCancellationSignal(cancellationSignal);
            try {
                return nativeExecuteForLong(mConnectionPtr, statement.mStatementPtr);
            } finally {
                detachCancellationSignal(cancellationSignal);
            }
        } finally {
            releasePreparedStatement(statement);
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
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }

        final PreparedStatement statement = acquirePreparedStatement(sql);
        try {
            throwIfStatementForbidden(statement);
            bindArguments(statement, bindArgs);
            attachCancellationSignal(cancellationSignal);
            try {
                return nativeExecuteForString(mConnectionPtr, statement.mStatementPtr);
            } finally {
                detachCancellationSignal(cancellationSignal);
            }
        } finally {
            releasePreparedStatement(statement);
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
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }

        int changedRows;
        final PreparedStatement statement = acquirePreparedStatement(sql);
        try {
            throwIfStatementForbidden(statement);
            bindArguments(statement, bindArgs);
            attachCancellationSignal(cancellationSignal);
            try {
                changedRows = nativeExecuteForChangedRowCount(
                        mConnectionPtr, statement.mStatementPtr);
                return changedRows;
            } finally {
                detachCancellationSignal(cancellationSignal);
            }
        } finally {
            releasePreparedStatement(statement);
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
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }

        final PreparedStatement statement = acquirePreparedStatement(sql);
        try {
            throwIfStatementForbidden(statement);
            bindArguments(statement, bindArgs);
            attachCancellationSignal(cancellationSignal);
            try {
                return nativeExecuteForLastInsertedRowId(
                        mConnectionPtr, statement.mStatementPtr);
            } finally {
                detachCancellationSignal(cancellationSignal);
            }
        } finally {
            releasePreparedStatement(statement);
        }
    }

    /**
     * Executes a statement and populates the specified {@link CursorWindow}
     * with a range of results.  Returns the number of rows that were counted
     * during query execution.
     *
     * @param sql The SQL statement to execute.
     * @param bindArgs The arguments to bind, or null if none.
     * @param window The cursor window to clear and fill.
     * @param startPos The start position for filling the window.
     * @param requiredPos The position of a row that MUST be in the window.
     * If it won't fit, then the query should discard part of what it filled
     * so that it does.  Must be greater than or equal to <code>startPos</code>.
     * @param countAllRows True to count all rows that the query would return
     * regagless of whether they fit in the window.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * @return The number of rows that were counted during query execution.  Might
     * not be all rows in the result set unless <code>countAllRows</code> is true.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error
     * or invalid number of bind arguments.
     * @throws OperationCanceledException if the operation was canceled.
     */
    public int executeForCursorWindow(String sql,
                                      Object[] bindArgs,
                                      CursorWindow window,
                                      int startPos,
                                      int requiredPos,
                                      boolean countAllRows,
                                      CancellationSignal cancellationSignal) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }
        if (window == null) {
            throw new IllegalArgumentException("window must not be null.");
        }

        window.acquireReference();
        try {
            int actualPos;
            int countedRows;
            final PreparedStatement statement = acquirePreparedStatement(sql);
            try {
                throwIfStatementForbidden(statement);
                bindArguments(statement, bindArgs);
                attachCancellationSignal(cancellationSignal);
                try {
                    final long result = nativeExecuteForCursorWindow(
                            mConnectionPtr, statement.mStatementPtr, window.mWindowPtr,
                            startPos, requiredPos, countAllRows);
                    actualPos = (int)(result >> 32);
                    countedRows = (int)result;
                    window.setStartPosition(actualPos);
                    return countedRows;
                } finally {
                    detachCancellationSignal(cancellationSignal);
                }
            } finally {
                releasePreparedStatement(statement);
            }
        } finally {
            window.releaseReference();
        }
    }

    private PreparedStatement acquirePreparedStatement(String sql) {
        PreparedStatement statement = mPreparedStatementCache.get(sql);
        boolean skipCache = false;
        if (statement != null) {
            if (!statement.mInUse) {
                return statement;
            }
            // The statement is already in the cache but is in use (this statement appears
            // to be not only re-entrant but recursive!).  So prepare a new copy of the
            // statement but do not cache it.
            skipCache = true;
        }

        final long statementPtr = nativePrepareStatement(mConnectionPtr, sql);
        try {
            final int numParameters = nativeGetParameterCount(mConnectionPtr, statementPtr);
            final int type = SQLiteStatementType.getSqlStatementType(sql);
            final boolean readOnly = nativeIsReadOnly(mConnectionPtr, statementPtr);
            statement = obtainPreparedStatement(sql, statementPtr, numParameters, type, readOnly);
            if (!skipCache && isCacheable(type)) {
                mPreparedStatementCache.put(sql, statement);
                statement.mInCache = true;
            }
        } catch (RuntimeException ex) {
            // Finalize the statement if an exception occurred and we did not add
            // it to the cache.  If it is already in the cache, then leave it there.
            if (statement == null || !statement.mInCache) {
                nativeFinalizeStatement(mConnectionPtr, statementPtr);
            }
            throw ex;
        }
        statement.mInUse = true;
        return statement;
    }

    private void releasePreparedStatement(PreparedStatement statement) {
        statement.mInUse = false;
        if (statement.mInCache) {
            try {
                nativeResetStatementAndClearBindings(mConnectionPtr, statement.mStatementPtr);
            } catch (SQLiteException ex) {
                // The statement could not be reset due to an error.  Remove it from the cache.
                // When remove() is called, the cache will invoke its entryRemoved() callback,
                // which will in turn call finalizePreparedStatement() to finalize and
                // recycle the statement.
                if (DEBUG) {
                    Log.d(TAG, "Could not reset prepared statement due to an exception.  "
                            + "Removing it from the cache.  SQL: "
                            + statement.mSql, ex);
                }

                mPreparedStatementCache.remove(statement.mSql);
            }
        } else {
            finalizePreparedStatement(statement);
        }
    }

    private void finalizePreparedStatement(PreparedStatement statement) {
        nativeFinalizeStatement(mConnectionPtr, statement.mStatementPtr);
        recyclePreparedStatement(statement);
    }

    private void attachCancellationSignal(CancellationSignal cancellationSignal) {
        if (cancellationSignal != null) {
            cancellationSignal.throwIfCanceled();

            mCancellationSignalAttachCount += 1;
            if (mCancellationSignalAttachCount == 1) {
                // After this point, onCancel() may be called concurrently.
                cancellationSignal.setOnCancelListener(this);
            }
        }
    }

    @SuppressLint("Assert")
    private void detachCancellationSignal(CancellationSignal cancellationSignal) {
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

    private void bindArguments(PreparedStatement statement, Object[] bindArgs) {
        final int count = bindArgs != null ? bindArgs.length : 0;
        if (count != statement.mNumParameters) {
            String message = "Expected " + statement.mNumParameters + " bind arguments but "
                + count + " were provided.";
            throw new SQLiteBindOrColumnIndexOutOfRangeException(message);
        }
        if (count == 0) {
            return;
        }

        final long statementPtr = statement.mStatementPtr;
        for (int i = 0; i < count; i++) {
            final Object arg = bindArgs[i];
            switch (getTypeOfObject(arg)) {
                case Cursor.FIELD_TYPE_NULL:
                    nativeBindNull(mConnectionPtr, statementPtr, i + 1);
                    break;
                case Cursor.FIELD_TYPE_INTEGER:
                    nativeBindLong(mConnectionPtr, statementPtr, i + 1,
                            ((Number)arg).longValue());
                    break;
                case Cursor.FIELD_TYPE_FLOAT:
                    nativeBindDouble(mConnectionPtr, statementPtr, i + 1,
                            ((Number)arg).doubleValue());
                    break;
                case Cursor.FIELD_TYPE_BLOB:
                    nativeBindBlob(mConnectionPtr, statementPtr, i + 1, (byte[])arg);
                    break;
                case Cursor.FIELD_TYPE_STRING:
                default:
                    if (arg instanceof Boolean) {
                        // Provide compatibility with legacy applications which may pass
                        // Boolean values in bind args.
                        nativeBindLong(mConnectionPtr, statementPtr, i + 1, (Boolean) arg ? 1 : 0);
                    } else {
                        nativeBindString(mConnectionPtr, statementPtr, i + 1, arg.toString());
                    }
                    break;
            }
        }
    }

    /**
     * Returns data type of the given object's value.
     *<p>
     * Returned values are
     * <ul>
     *   <li>{@link Cursor#FIELD_TYPE_NULL}</li>
     *   <li>{@link Cursor#FIELD_TYPE_INTEGER}</li>
     *   <li>{@link Cursor#FIELD_TYPE_FLOAT}</li>
     *   <li>{@link Cursor#FIELD_TYPE_STRING}</li>
     *   <li>{@link Cursor#FIELD_TYPE_BLOB}</li>
     *</ul>
     *</p>
     *
     * @param obj the object whose value type is to be returned
     * @return object value type
     */
    private static int getTypeOfObject(Object obj) {
        if (obj == null) {
            return Cursor.FIELD_TYPE_NULL;
        } else if (obj instanceof byte[]) {
            return Cursor.FIELD_TYPE_BLOB;
        } else if (obj instanceof Float || obj instanceof Double) {
            return Cursor.FIELD_TYPE_FLOAT;
        } else if (obj instanceof Long || obj instanceof Integer
            || obj instanceof Short || obj instanceof Byte) {
            return Cursor.FIELD_TYPE_INTEGER;
        } else {
            return Cursor.FIELD_TYPE_STRING;
        }
    }

    private void throwIfStatementForbidden(PreparedStatement statement) {
        if (mOnlyAllowReadOnlyOperations && !statement.mReadOnly) {
            throw new SQLiteException("Cannot execute this statement because it "
                    + "might modify the database but the connection is read-only.");
        }
    }

    private static boolean isCacheable(int statementType) {
        return statementType == SQLiteStatementType.STATEMENT_UPDATE
            || statementType == SQLiteStatementType.STATEMENT_SELECT;
    }


    @Override
    public String toString() {
        return "SQLiteConnection: " + mConfiguration.path + " (" + mConnectionId + ")";
    }

    private PreparedStatement obtainPreparedStatement(String sql, long statementPtr,
            int numParameters, int type, boolean readOnly) {
        PreparedStatement statement = mPreparedStatementPool;
        if (statement != null) {
            mPreparedStatementPool = statement.mPoolNext;
            statement.mPoolNext = null;
            statement.mInCache = false;
        } else {
            statement = new PreparedStatement();
        }
        statement.mSql = sql;
        statement.mStatementPtr = statementPtr;
        statement.mNumParameters = numParameters;
        statement.mType = type;
        statement.mReadOnly = readOnly;
        return statement;
    }

    private void recyclePreparedStatement(PreparedStatement statement) {
        statement.mSql = null;
        statement.mPoolNext = mPreparedStatementPool;
        mPreparedStatementPool = statement;
    }

    /**
     * Holder type for a prepared statement.
     *
     * Although this object holds a pointer to a native statement object, it
     * does not have a finalizer.  This is deliberate.  The {@link SQLiteConnection}
     * owns the statement object and will take care of freeing it when needed.
     * In particular, closing the connection requires a guarantee of deterministic
     * resource disposal because all native statement objects must be freed before
     * the native database object can be closed.  So no finalizers here.
     */
    private static final class PreparedStatement {
        // Next item in pool.
        public PreparedStatement mPoolNext;

        // The SQL from which the statement was prepared.
        public String mSql;

        // The native sqlite3_stmt object pointer.
        // Lifetime is managed explicitly by the connection.
        public long mStatementPtr;

        // The number of parameters that the prepared statement has.
        public int mNumParameters;

        // The statement type.
        public int mType;

        // True if the statement is read-only.
        public boolean mReadOnly;

        // True if the statement is in the cache.
        public boolean mInCache;

        // True if the statement is in use (currently executing).
        // We need this flag because due to the use of custom functions in triggers, it's
        // possible for SQLite calls to be re-entrant.  Consequently we need to prevent
        // in use statements from being finalized until they are no longer in use.
        public boolean mInUse;
    }

    private final class PreparedStatementCache
            extends LruCache<String, PreparedStatement> {
        public PreparedStatementCache(int size) {
            super(size);
        }

        protected void entryRemoved(boolean evicted, String key,
                PreparedStatement oldValue, PreparedStatement newValue) {
            oldValue.mInCache = false;
            if (!oldValue.mInUse) {
                finalizePreparedStatement(oldValue);
            }
        }
    }
}
