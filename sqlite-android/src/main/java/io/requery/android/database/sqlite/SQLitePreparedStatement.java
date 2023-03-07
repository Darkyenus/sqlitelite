/*
 * Copyright (C) 2006 The Android Open Source Project
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

package io.requery.android.database.sqlite;

import android.database.SQLException;
import android.database.sqlite.SQLiteBindOrColumnIndexOutOfRangeException;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteException;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.util.Log;
import com.darkyen.sqlite.SQLiteNative;
import io.requery.android.database.CursorWindow;

import java.util.Arrays;

import static com.darkyen.sqlite.SQLiteNative.nativeBindBlob;
import static com.darkyen.sqlite.SQLiteNative.nativeBindDouble;
import static com.darkyen.sqlite.SQLiteNative.nativeBindLong;
import static com.darkyen.sqlite.SQLiteNative.nativeBindNull;
import static com.darkyen.sqlite.SQLiteNative.nativeBindString;
import static com.darkyen.sqlite.SQLiteNative.nativeExecuteForCursorWindow;
import static com.darkyen.sqlite.SQLiteNative.nativeFinalizeStatement;
import static com.darkyen.sqlite.SQLiteNative.nativeGetParameterCount;
import static com.darkyen.sqlite.SQLiteNative.nativePrepareStatement;

/**
 * A base class for compiled SQLite programs.
 * <p>
 * This class is not thread-safe.
 * </p>
 */
@SuppressWarnings("unused")
public final class SQLitePreparedStatement implements AutoCloseable {
    private static final String TAG = "SQLitePreparedStatement";
    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private final SQLiteDatabase mDatabase;
    private final String mSql;
    private final int mNumParameters;
    private final Object[] mBindArgs;

    private final CancellationSignal mCancellationSignal;

    long statementPtr;

    SQLitePreparedStatement(SQLiteDatabase db, String sql, Object[] bindArgs, CancellationSignal cancellationSignal) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }

        mDatabase = db;
        mSql = sql.trim();
        mCancellationSignal = cancellationSignal;

        final long connectionPtr = db.mConnection.mConnectionPtr;
        statementPtr = nativePrepareStatement(connectionPtr, sql);

        boolean ok = false;
        try {
            mNumParameters = nativeGetParameterCount(connectionPtr, statementPtr);
            ok = true;
        } finally {
            if (!ok) {
                nativeFinalizeStatement(connectionPtr, statementPtr);
            }
        }

        if (bindArgs != null && bindArgs.length > mNumParameters) {
            throw new IllegalArgumentException("Too many bind arguments.  "
                    + bindArgs.length + " arguments were provided but the statement needs "
                    + mNumParameters + " arguments.");
        }

        if (mNumParameters != 0) {
            mBindArgs = new Object[mNumParameters];
            if (bindArgs != null) {
                System.arraycopy(bindArgs, 0, mBindArgs, 0, bindArgs.length);
            }
        } else {
            mBindArgs = null;
        }
    }

    void bindArguments(Object[] bindArgs) {
        final long connectionPtr = this.mDatabase.mConnection.mConnectionPtr;
        final int count = bindArgs != null ? bindArgs.length : 0;
        if (count != mNumParameters) {
            String message = "Expected " + mNumParameters + " bind arguments but "
                    + count + " were provided.";
            throw new SQLiteBindOrColumnIndexOutOfRangeException(message);
        }
        if (count == 0) {
            return;
        }

        final long statementPtr = this.statementPtr;
        for (int i = 0; i < count; i++) {
            final Object arg = bindArgs[i];

            if (arg == null) {
                nativeBindNull(connectionPtr, statementPtr, i + 1);
            } else if (arg instanceof byte[]) {
                nativeBindBlob(connectionPtr, statementPtr, i + 1, (byte[])arg);
            } else if (arg instanceof Float || arg instanceof Double) {
                nativeBindDouble(connectionPtr, statementPtr, i + 1, ((Number)arg).doubleValue());
            } else if (arg instanceof Long || arg instanceof Integer
                    || arg instanceof Short || arg instanceof Byte) {
                nativeBindLong(connectionPtr, statementPtr, i + 1, ((Number)arg).longValue());
            } else if (arg instanceof Boolean) {
                // Provide compatibility with legacy applications which may pass
                // Boolean values in bind args.
                nativeBindLong(connectionPtr, statementPtr, i + 1, (Boolean) arg ? 1 : 0);
            } else {
                nativeBindString(connectionPtr, statementPtr, i + 1, arg.toString());
            }
        }
    }

    SQLiteDatabase getDatabase() {
        return mDatabase;
    }

    String getSql() {
        return mSql;
    }

    Object[] getBindArgs() {
        return mBindArgs;
    }

    /**
     * Bind a NULL value to this statement. The value remains bound until
     * {@link #clearBindings} is called.
     *
     * @param index The 1-based index to the parameter to bind null to
     */
    public void bindNull(int index) {
        bind(index, null);
    }

    /**
     * Bind a long value to this statement. The value remains bound until
     * {@link #clearBindings} is called.
     *addToBindArgs
     * @param index The 1-based index to the parameter to bind
     * @param value The value to bind
     */
    public void bindLong(int index, long value) {
        bind(index, value);
    }

    /**
     * Bind a double value to this statement. The value remains bound until
     * {@link #clearBindings} is called.
     *
     * @param index The 1-based index to the parameter to bind
     * @param value The value to bind
     */
    public void bindDouble(int index, double value) {
        bind(index, value);
    }

    /**
     * Bind a String value to this statement. The value remains bound until
     * {@link #clearBindings} is called.
     *
     * @param index The 1-based index to the parameter to bind
     * @param value The value to bind, must not be null
     */
    public void bindString(int index, String value) {
        if (value == null) {
            throw new IllegalArgumentException("the bind value at index " + index + " is null");
        }
        bind(index, value);
    }

    /**
     * Bind a byte array value to this statement. The value remains bound until
     * {@link #clearBindings} is called.
     *
     * @param index The 1-based index to the parameter to bind
     * @param value The value to bind, must not be null
     */
    public void bindBlob(int index, byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("the bind value at index " + index + " is null");
        }
        bind(index, value);
    }

    /**
     * Binds the given Object to the given SQLitePreparedStatement using the proper
     * typing. For example, bind numbers as longs/doubles, and everything else
     * as a string by call toString() on it.
     *
     * @param index the 1-based index to bind at
     * @param value the value to bind
     */
    public void bindObject(int index, Object value) {
        if (value == null) {
            bindNull(index);
        } else if (value instanceof Double || value instanceof Float) {
            bindDouble(index, ((Number)value).doubleValue());
        } else if (value instanceof Number) {
            bindLong(index, ((Number)value).longValue());
        } else if (value instanceof Boolean) {
            Boolean bool = (Boolean)value;
            if (bool) {
                bindLong(index, 1);
            } else {
                bindLong(index, 0);
            }
        } else if (value instanceof byte[]){
            bindBlob(index, (byte[]) value);
        } else {
            bindString(index, value.toString());
        }
    }

    /**
     * Clears all existing bindings. Unset bindings are treated as NULL.
     */
    public void clearBindings() {
        if (mBindArgs != null) {
            Arrays.fill(mBindArgs, null);
        }
    }

    /**
     * Given an array of String bindArgs, this method binds all of them in one single call.
     *
     * @param bindArgs the String array of bind args, none of which must be null.
     */
    public void bindAllArgsAsStrings(String[] bindArgs) {
        if (bindArgs != null) {
            for (int i = bindArgs.length; i != 0; i--) {
                bindString(i, bindArgs[i - 1]);
            }
        }
    }

    @Override
    public void close() {
        final long ptr = this.statementPtr;
        if (ptr != 0) {
            statementPtr = 0;
            final long connectionPtr = mDatabase.mConnection.mConnectionPtr;
            nativeFinalizeStatement(connectionPtr, ptr);
        }
        if (mBindArgs != null) {
            Arrays.fill(mBindArgs, null);
        }
    }

    private void bind(int index, Object value) {
        if (index < 1 || index > mNumParameters) {
            throw new IllegalArgumentException("Cannot bind argument at index "
                    + index + " because the index is out of range.  "
                    + "The statement has " + mNumParameters + " parameters.");
        }
        mBindArgs[index - 1] = value;
    }

    //region SQLiteStatement
    /**
     * Execute this SQL statement, if it is not a SELECT / INSERT / DELETE / UPDATE, for example
     * CREATE / DROP table, view, trigger, index etc.
     *
     * @throws SQLException If the SQL string is invalid for some reason
     */
    public void execute() {
        try {
            mDatabase.mConnection.execute(getSql(), getBindArgs(), null); // might throw
        } catch (SQLiteDatabaseCorruptException ex) {
            mDatabase.onCorruption();
            throw ex;
        }
    }

    /**
     * Execute this SQL statement, if the number of rows affected by execution of this SQL
     * statement is of any importance to the caller - for example, UPDATE / DELETE SQL statements.
     *
     * @return the number of rows affected by this SQL statement execution.
     * @throws SQLException If the SQL string is invalid for some reason
     */
    public int executeUpdateDelete() {
        try {
            return mDatabase.mConnection.executeForChangedRowCount(getSql(), getBindArgs(), null); // might throw
        } catch (SQLiteDatabaseCorruptException ex) {
            mDatabase.onCorruption();
            throw ex;
        }
    }

    /**
     * Execute this SQL statement and return the ID of the row inserted due to this call.
     * The SQL statement should be an INSERT for this to be a useful call.
     *
     * @return the row ID of the last row inserted, if this insert is successful. -1 otherwise.
     *
     * @throws SQLException If the SQL string is invalid for some reason
     */
    public long executeInsert() {
        try {
            return mDatabase.mConnection.executeForLastInsertedRowId(getSql(), getBindArgs(), null); // might throw
        } catch (SQLiteDatabaseCorruptException ex) {
            mDatabase.onCorruption();
            throw ex;
        }
    }

    /**
     * Execute a statement that returns a 1 by 1 table with a numeric value.
     * For example, SELECT COUNT(*) FROM table;
     *
     * @return The result of the query.
     *
     * @throws SQLiteDoneException if the query returns zero rows
     */
    public long simpleQueryForLong() {
        try {
            return mDatabase.mConnection.executeForLong(getSql(), getBindArgs(), null); // might throw
        } catch (SQLiteDatabaseCorruptException ex) {
            mDatabase.onCorruption();
            throw ex;
        }
    }

    /**
     * Execute a statement that returns a 1 by 1 table with a text value.
     * For example, SELECT COUNT(*) FROM table;
     *
     * @return The result of the query.
     *
     * @throws SQLiteDoneException if the query returns zero rows
     */
    public String simpleQueryForString() {
        try {
            return mDatabase.mConnection.executeForString(getSql(), getBindArgs(), null); // might throw
        } catch (SQLiteDatabaseCorruptException ex) {
            mDatabase.onCorruption();
            throw ex;
        }
    }
    //endregion

    //region SQLiteQuery

    /**
     * Reads rows into a buffer.
     *
     * @param window The window to fill into
     * @param startPos The start position for filling the window.
     * @param requiredPos The position of a row that MUST be in the window.
     * If it won't fit, then the query should discard part of what it filled.
     * @param countAllRows True to count all rows that the query would
     * return regardless of whether they fit in the window.
     * @return Number of rows that were enumerated.  Might not be all rows
     * unless countAllRows is true.
     *
     * @throws SQLiteException if an error occurs.
     * @throws OperationCanceledException if the operation was canceled.
     */
    int fillWindow(CursorWindow window, int startPos, int requiredPos, boolean countAllRows) {
        final long mConnectionPtr = mDatabase.mConnection.mConnectionPtr;
        window.acquireReference();
        try {
            SQLiteNative.nativeResetStatementAndClearBindings(mConnectionPtr, statementPtr);
            bindArguments(mBindArgs);

                mDatabase.mConnection.attachCancellationSignal(mCancellationSignal);
                try {
                    final long result = nativeExecuteForCursorWindow(
                            mDatabase.mConnection.mConnectionPtr, statementPtr, window.mWindowPtr,
                            startPos, requiredPos, countAllRows);
                    int actualPos = (int)(result >> 32);
                    int countedRows = (int)result;
                    window.setStartPosition(actualPos);
                    return countedRows;
                } finally {
                    mDatabase.mConnection.detachCancellationSignal(mCancellationSignal);
                }
        } catch (SQLiteDatabaseCorruptException ex) {
            mDatabase.onCorruption();
            throw ex;
        } catch (SQLiteException ex) {
            Log.e(TAG, "exception: " + ex.getMessage() + "; query: " + getSql());
            throw ex;
        } finally {
            window.releaseReference();
        }
    }
    //endregion
}
