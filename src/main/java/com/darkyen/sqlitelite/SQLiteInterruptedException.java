package com.darkyen.sqlitelite;

import android.database.sqlite.SQLiteException;

/**
 * Thrown
 */
public class SQLiteInterruptedException extends SQLiteException {
    public SQLiteInterruptedException() {}

    public SQLiteInterruptedException(String error) {
        super(error);
    }

    public SQLiteInterruptedException(String error, Throwable cause) {
        super(error, cause);
    }
}
