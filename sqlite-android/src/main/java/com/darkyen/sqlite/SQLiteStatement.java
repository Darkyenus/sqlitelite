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

    SQLiteStatement(SQLiteConnection connection, long statementPtr) {
        this.connection = connection;
        this.statementPtr = statementPtr;
    }

    private long statementPtr() {
        final long ptr = statementPtr;
        if (ptr == 0) throw new IllegalStateException("Statement already closed");
        return ptr;
    }

    /** Bind NULL to the parameter at given index. Note that indices start at 1. */
    public void bindNull(int index) {
        nativeBindNull(connection.connectionPtr(), statementPtr(), index);
    }
    /** Bind 1 or 0 to the parameter at given index. Note that indices start at 1. */
    public void bind(int index, boolean value) {
        nativeBindLong(connection.connectionPtr(), statementPtr(), index, value ? 1L : 0L);
    }
    /** Bind long to the parameter at given index. Note that indices start at 1. */
    public void bind(int index, long value) {
        nativeBindLong(connection.connectionPtr(), statementPtr(), index, value);
    }
    /** Bind double to the parameter at given index. Note that indices start at 1. */
    public void bind(int index, double value) {
        nativeBindDouble(connection.connectionPtr(), statementPtr(), index, value);
    }
    /** Bind String or null to the parameter at given index. Note that indices start at 1. */
    public void bind(int index, String value) {
        if (value == null) {
            nativeBindNull(connection.connectionPtr(), statementPtr(), index);
        } else {
            nativeBindString(connection.connectionPtr(), statementPtr(), index, value);
        }
    }
    /** Bind byte[] or null to the parameter at given index. Note that indices start at 1. */
    public void bind(int index, byte[] value) {
        if (statementPtr == 0) return;
        if (value == null) {
            nativeBindNull(connection.connectionPtr(), statementPtr(), index);
        } else {
            nativeBindBlob(connection.connectionPtr(), statementPtr(), index, value);
        }
    }

    /**
     * Fully execute statement that is expected to return no rows
     * (such as CREATE, DROP, some PRAGMA etc.).
     * Keeps any bindings.
     */
    public void executeForVoid() {
        SQLiteNative.nativeExecuteAndReset(connection.connectionPtr(), statementPtr());
    }

    /**
     * Fully execute statement that is expected to return no rows
     * (such as CREATE, DROP, some PRAGMA etc.).
     * Keeps any bindings.
     */
    public long executeForLong(long defaultValue) {
        return SQLiteNative.nativeExecuteForLongAndReset(connection.connectionPtr(), statementPtr(), defaultValue);
    }

    /**
     * Fully execute statement that is expected to return a single row with a single double cell.
     * Keeps any bindings.
     */
    public double executeForDouble(double defaultValue) {
        return SQLiteNative.nativeExecuteForDoubleAndReset(connection.connectionPtr(), statementPtr(), defaultValue);
    }

    /**
     * Fully execute statement that is expected to return a single row with a single String cell or no rows,
     * in which case returns null.
     * Keeps any bindings.
     */
    @Nullable
    public String executeForString() {
        return SQLiteNative.nativeExecuteForStringOrNullAndReset(connection.connectionPtr(), statementPtr());
    }

    /**
     * Fully execute statement that is expected to return a single row with a single BLOB cell or no rows,
     * in which case returns null.
     * Keeps any bindings.
     */
    @Nullable
    public byte[] executeForBlob() {
        return SQLiteNative.nativeExecuteForBlobOrNullAndReset(connection.connectionPtr(), statementPtr());
    }

    /**
     * Fully execute insert statement and return the ROWID of the inserted row.
     * Returns -1 if no ID was inserted.
     * Keeps any bindings.
     */
    public long executeForRowID() {
        return SQLiteNative.nativeExecuteForLastInsertedRowIDAndReset(connection.connectionPtr(), statementPtr());
    }

    /**
     * Fully execute insert statement and return the ROWID of the inserted row.
     * Keeps any bindings.
     */
    public long executeForChangedRowCount() {
        return SQLiteNative.nativeExecuteForChangedRowsAndReset(connection.connectionPtr(), statementPtr());
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
