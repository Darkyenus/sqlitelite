package com.darkyen.sqlite;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Log;
import org.jetbrains.annotations.Nullable;

import java.io.File;

import static com.darkyen.sqlite.SQLiteConnection.SQLITE_OPEN_CREATE;
import static com.darkyen.sqlite.SQLiteConnection.SQLITE_OPEN_NOFOLLOW;
import static com.darkyen.sqlite.SQLiteConnection.SQLITE_OPEN_READWRITE;

public abstract class SQLiteDelegate {
    private static final String TAG = "SQLiteDelegate";

    public final @Nullable File file;
    /**
     * The version used when opening a connection.
     * If the value is 0 or less, it means "don't care". Otherwise, the version is enforced.
     */
    protected int version = 0;
    protected int openFlags = SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_NOFOLLOW;
    /** True if foreign key constraints are enabled. Default is false. */
    protected boolean foreignKeyConstraintsEnabled = false;

    /**
     * Create a new delegate. Does not create the database, just this object.
     * @param file of the database or null for in-memory database
     */
    protected SQLiteDelegate(@Nullable File file) {
        this.file = file;
    }

    /**
     * Called when the database connection is being configured, to enable features
     * such as write-ahead logging or foreign key support.
     * <p>
     * This method is called before {@link #onCreate}, {@link #onUpgrade},
     * {@link #onDowngrade}, or {@link #onOpen} are called.  It should not modify
     * the database except to configure the database connection as required.
     * </p><p>
     * This method should only call methods that configure the parameters of the
     * database connection, such as executing PRAGMA statements.
     * </p>
     *
     * @param db The database.
     */
    public void onConfigure(SQLiteConnection db) {}

    /**
     * Called when the database is created for the first time. This is where the
     * creation of tables and the initial population of the tables should happen.
     *
     * @param db The database.
     */
    public abstract void onCreate(SQLiteConnection db);

    /**
     * Called when the database needs to be upgraded. The implementation
     * should use this method to drop tables, add tables, or do anything else it
     * needs to upgrade to the new schema version.
     *
     * <p>
     * The SQLite ALTER TABLE documentation can be found
     * <a href="http://sqlite.org/lang_altertable.html">here</a>. If you add new columns
     * you can use ALTER TABLE to insert them into a live table. If you rename or remove columns
     * you can use ALTER TABLE to rename the old table, then create the new table and then
     * populate the new table with the contents of the old table.
     * </p><p>
     * This method executes within a transaction.  If an exception is thrown, all changes
     * will automatically be rolled back.
     * </p>
     *
     * @param db The database.
     * @param oldVersion The old database version.
     * @param newVersion The new database version.
     */
    public void onUpgrade(SQLiteConnection db, int oldVersion, int newVersion) {}

    /**
     * Called when the database needs to be downgraded. This is strictly similar to
     * {@link #onUpgrade} method, but is called whenever current version is newer than requested one.
     * However, this method is not abstract, so it is not mandatory for a customer to
     * implement it. If not overridden, default implementation will reject downgrade and
     * throws SQLiteException
     *
     * <p>
     * This method executes within a transaction.  If an exception is thrown, all changes
     * will automatically be rolled back.
     * </p>
     *
     * @param db The database.
     * @param oldVersion The old database version.
     * @param newVersion The new database version.
     */
    public void onDowngrade(SQLiteConnection db, int oldVersion, int newVersion) {
        throw new SQLiteException("Can't downgrade database from version " +
                oldVersion + " to " + newVersion);
    }

    /**
     * Called when the database has been opened.  The implementation
     * should check {@link SQLiteDatabase#isReadOnly} before updating the
     * database.
     * <p>
     * This method is called after the database connection has been configured
     * and after the database schema has been created, upgraded or downgraded as necessary.
     * If the database connection must be configured in some way before the schema
     * is created, upgraded, or downgraded, do it in {@link #onConfigure} instead.
     * </p>
     *
     * @param db The database.
     */
    public void onOpen(SQLiteConnection db) {}

    /**
     * The method invoked when database corruption is detected.
     * @param dbObj the {@link SQLiteConnection} object representing the database on which corruption
     * is detected.
     */
    public void onCorruption(SQLiteConnection dbObj) {
        final File file = this.file;
        Log.e(TAG, "Corruption reported by sqlite on database: " + file);
        //TODO If the corruption is recoverable, recover
        dbObj.close();
        if (file != null) {
            SQLiteDatabase.deleteDatabase(file);
            Log.e(TAG, "Deleted files of a corrupted database: "+file);
        }
    }
}
