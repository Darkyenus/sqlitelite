package com.darkyen.sqlite;

import android.database.sqlite.SQLiteException;
import android.util.Log;
import io.requery.android.database.sqlite.SQLiteDatabase;
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration;

public class SQLiteDatabaseConnection {
    private long connectionPointer;


    private SQLiteDatabaseConnection(long connectionPointer) {
        this.connectionPointer = connectionPointer;
    }

    public static SQLiteDatabaseConnection open(SQLiteDelegate delegate) {
        return null;
    }
}
