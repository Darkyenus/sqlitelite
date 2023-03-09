package com.darkyen.sqlite;

import android.database.sqlite.SQLiteException;
import org.jetbrains.annotations.Nullable;

import static com.darkyen.sqlite.SQLiteNative.nativeBindBlob;
import static com.darkyen.sqlite.SQLiteNative.nativeBindDouble;
import static com.darkyen.sqlite.SQLiteNative.nativeBindLong;
import static com.darkyen.sqlite.SQLiteNative.nativeBindNull;
import static com.darkyen.sqlite.SQLiteNative.nativeBindString;

public final class SQLiteStatement implements AutoCloseable {
    private final SQLiteConnection connection;
    int managementIndex = -1;
    private long statementPtr;

    /** Not evaluating through a cursor,
     * ready to start cursor row or direct execution. */
    private static final int STATE_NORMAL = 0;
    /** Just returned a cursor row. */
    private static final int STATE_CURSOR_ROW = 1;
    /** Just reached cursor end. Only reset is now possible. */
    private static final int STATE_CURSOR_END = 2;
    /** Cursor errored out while iterating. Only reset is now possible. */
    private static final int STATE_CURSOR_ERROR = 3;
    private int state = STATE_NORMAL;

    SQLiteStatement(SQLiteConnection connection, long statementPtr) {
        this.connection = connection;
        this.statementPtr = statementPtr;
    }

    private void assertNormalState() {
        if (state != STATE_NORMAL) throw new IllegalStateException("This operation can be performed only when not in cursor mode");
    }
    private void assertCursorRowState() {
        if (state != STATE_CURSOR_ROW) throw new IllegalStateException("Cursor is not at any row");
    }

    private long statementPtr() {
        final long ptr = statementPtr;
        if (ptr == 0) throw new IllegalStateException("Statement already closed");
        return ptr;
    }

    /** Bind NULL to the parameter at given index. Note that indices start at 1. */
    public void bindNull(int index) {
        assertNormalState();
        nativeBindNull(connection.connectionPtr(), statementPtr(), index);
    }
    /** Bind 1 or 0 to the parameter at given index. Note that indices start at 1. */
    public void bind(int index, boolean value) {
        assertNormalState();
        nativeBindLong(connection.connectionPtr(), statementPtr(), index, value ? 1L : 0L);
    }
    /** Bind long to the parameter at given index. Note that indices start at 1. */
    public void bind(int index, long value) {
        assertNormalState();
        nativeBindLong(connection.connectionPtr(), statementPtr(), index, value);
    }
    /** Bind double to the parameter at given index. Note that indices start at 1. */
    public void bind(int index, double value) {
        assertNormalState();
        nativeBindDouble(connection.connectionPtr(), statementPtr(), index, value);
    }
    /** Bind String or null to the parameter at given index. Note that indices start at 1. */
    public void bind(int index, String value) {
        assertNormalState();
        if (value == null) {
            nativeBindNull(connection.connectionPtr(), statementPtr(), index);
        } else {
            nativeBindString(connection.connectionPtr(), statementPtr(), index, value);
        }
    }
    /** Bind byte[] or null to the parameter at given index. Note that indices start at 1. */
    public void bind(int index, byte[] value) {
        assertNormalState();
        if (statementPtr == 0) return;
        if (value == null) {
            nativeBindNull(connection.connectionPtr(), statementPtr(), index);
        } else {
            nativeBindBlob(connection.connectionPtr(), statementPtr(), index, value);
        }
    }

    /** Remove all existing bindings. */
    public void clearBindings() {
        assertNormalState();
        SQLiteNative.nativeClearBindings(connection.connectionPtr(), statementPtr());
    }


    /**
     * Fully execute statement and ignore what it returns.
     * (Useful for PRAGMAs etc.)
     * Keeps any bindings.
     * @throws SQLiteException on any error
     */
    public void execute() {
        assertNormalState();
        SQLiteNative.nativeExecuteIgnoreAndReset(connection.connectionPtr(), statementPtr());
    }

    /**
     * Fully execute statement that is expected to return no rows
     * (such as CREATE, DROP, etc.).
     * Keeps any bindings.
     * @throws SQLiteException on any error
     */
    public void executeForNothing() {
        assertNormalState();
        SQLiteNative.nativeExecuteAndReset(connection.connectionPtr(), statementPtr());
    }

    /**
     * Fully execute statement that is expected to return no rows
     * (such as CREATE, DROP, some PRAGMA etc.).
     * Keeps any bindings.
     * @throws SQLiteException on any error
     */
    public long executeForLong(long defaultValue) {
        assertNormalState();
        return SQLiteNative.nativeExecuteForLongAndReset(connection.connectionPtr(), statementPtr(), defaultValue);
    }

    /**
     * Fully execute statement that is expected to return a single row with a single double cell.
     * Keeps any bindings.
     * @throws SQLiteException on any error
     */
    public double executeForDouble(double defaultValue) {
        assertNormalState();
        return SQLiteNative.nativeExecuteForDoubleAndReset(connection.connectionPtr(), statementPtr(), defaultValue);
    }

    /**
     * Fully execute statement that is expected to return a single row with a single String cell or no rows,
     * in which case returns null.
     * Keeps any bindings.
     * @throws SQLiteException on any error
     */
    @Nullable
    public String executeForString() {
        assertNormalState();
        return SQLiteNative.nativeExecuteForStringOrNullAndReset(connection.connectionPtr(), statementPtr());
    }

    /**
     * Fully execute statement that is expected to return a single row with a single BLOB cell or no rows,
     * in which case returns null.
     * Keeps any bindings.
     * @throws SQLiteException on any error
     */
    @Nullable
    public byte[] executeForBlob() {
        assertNormalState();
        return SQLiteNative.nativeExecuteForBlobOrNullAndReset(connection.connectionPtr(), statementPtr());
    }

    /**
     * Fully execute insert statement and return the ROWID of the inserted row.
     * Returns -1 if no ID was inserted.
     * Keeps any bindings.
     * @throws SQLiteException on any error
     */
    public long executeForRowID() {
        assertNormalState();
        return SQLiteNative.nativeExecuteForLastInsertedRowIDAndReset(connection.connectionPtr(), statementPtr());
    }

    /**
     * Fully execute insert statement and return the ROWID of the inserted row.
     * Keeps any bindings.
     * @throws SQLiteException on any error
     */
    public long executeForChangedRowCount() {
        assertNormalState();
        return SQLiteNative.nativeExecuteForChangedRowsAndReset(connection.connectionPtr(), statementPtr());
    }

    /**
     * Execute this statement to get the next row of values.
     * The row is valid until called again or until {@link #cursorReset(boolean)} is called.
     * Do not mix with {@link #executeForNothing()} and related methods.
     * @return true if there is another row, false if at the end
     * @throws SQLiteException on any error
     */
    public boolean cursorNextRow() {
        switch (state) {
            case STATE_NORMAL:
            case STATE_CURSOR_ROW:
                state = STATE_CURSOR_ERROR;// Preemptively set error, will be changed later
                break;
            case STATE_CURSOR_END:
                return false;// SQLite does not like step calls when it returned DONE
            case STATE_CURSOR_ERROR:
            default:
                throw new IllegalStateException("Cursor needs to be reset after error");
        }
        final boolean result = SQLiteNative.nativeCursorStep(connection.connectionPtr(), statementPtr());
        state = result ? STATE_CURSOR_ROW : STATE_CURSOR_END;
        return result;
    }

    /**
     * Reset the cursor execution to be ready for another invocation.
     * See {@link #cursorNextRow()} for more info.
     * @param clearBindings true to clear bindings, false to keep them
     */
    public void cursorReset(boolean clearBindings) {
        if (state == STATE_NORMAL) throw new IllegalStateException("Not in cursor mode, nothing to reset");
        state = STATE_NORMAL;
        if (clearBindings) {
            SQLiteNative.nativeResetStatementAndClearBindings(connection.connectionPtr(), statementPtr());
        } else {
            SQLiteNative.nativeResetStatement(connection.connectionPtr(), statementPtr());
        }
    }

    public boolean cursorGetBoolean(int index) {
        assertCursorRowState();
        return SQLiteNative.nativeCursorGetLong(connection.connectionPtr(), statementPtr(), index) != 0L;
    }
    public long cursorGetLong(int index) {
        assertCursorRowState();
        return SQLiteNative.nativeCursorGetLong(connection.connectionPtr(), statementPtr(), index);
    }
    public double cursorGetDouble(int index) {
        assertCursorRowState();
        return SQLiteNative.nativeCursorGetDouble(connection.connectionPtr(), statementPtr(), index);
    }
    public @Nullable String cursorGetString(int index) {
        assertCursorRowState();
        return SQLiteNative.nativeCursorGetString(connection.connectionPtr(), statementPtr(), index);
    }
    public @Nullable byte[] cursorGetBlob(int index) {
        assertCursorRowState();
        return SQLiteNative.nativeCursorGetBlob(connection.connectionPtr(), statementPtr(), index);
    }

    @Override
    public void close() throws SQLiteException {
        final long ptr = statementPtr;
        if (ptr == 0) return;// Already deleted
        SQLiteNative.nativeFinalizeStatement(connection.connectionPtr(), ptr);
        statementPtr = 0;

        // It is managed, delete it from management tracking list
        if (managementIndex >= 0) {
            connection.close(this);
        }
    }
}
