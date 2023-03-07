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
/*
** Modified to support SQLite extensions by the SQLite developers:
** sqlite-dev@sqlite.org.
*/

package io.requery.android.database.sqlite;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.database.sqlite.SQLiteException;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import androidx.annotation.IntDef;
import io.requery.android.database.DatabaseErrorHandler;
import io.requery.android.database.DefaultDatabaseErrorHandler;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Exposes methods to manage a SQLite database.
 *
 * <p>
 * SQLiteDatabase has methods to create, delete, execute SQL commands, and
 * perform other common database management tasks.
 * </p><p>
 * See the Notepad sample application in the SDK for an example of creating
 * and managing a database.
 * </p><p>
 * Database names must be unique within an application, not across all applications.
 * </p>
 *
 * <h3>Localized Collation - ORDER BY</h3>
 * <p>
 * In addition to SQLite's default <code>BINARY</code> collator, Android supplies
 * two more, <code>LOCALIZED</code>, which changes with the system's current locale,
 * and <code>UNICODE</code>, which is the Unicode Collation Algorithm and not tailored
 * to the current locale.
 * </p>
 */
@SuppressWarnings({"unused", "JavaDoc", "TryFinallyCanBeTryWithResources"})
@SuppressLint("ShiftFlags") // suppressed for readability with native code
public final class SQLiteDatabase extends SQLiteClosable {

    /**
     * Name of the compiled native library.
     */
    public static final String LIBRARY_NAME = "sqlite3x";
    static {
        System.loadLibrary(LIBRARY_NAME);
    }

    private static final String TAG = "SQLiteDatabase";

    private static final int EVENT_DB_CORRUPT = 75004;

    // Stores reference to all databases opened in the current process.
    // (The referent Object is not used at this time.)
    // INVARIANT: Guarded by sActiveDatabases.
    private static final WeakHashMap<SQLiteDatabase, Object> sActiveDatabases = new WeakHashMap<>();

    public SQLiteSession mSession = null;

    // Error handler to be used when SQLite returns corruption errors.
    // INVARIANT: Immutable.
    private final DatabaseErrorHandler mErrorHandler;

    // Shared database state lock.
    // This lock guards all of the shared state of the database, such as its
    // configuration, whether it is open or closed, and so on.  This lock should
    // be held for as little time as possible.
    //
    // The lock MUST NOT be held while attempting to acquire database connections or
    // while executing SQL statements on behalf of the client as it can lead to deadlock.
    //
    // It is ok to hold the lock while reconfiguring the connection pool or dumping
    // statistics because those operations are non-reentrant and do not try to acquire
    // connections that might be held by other threads.
    //
    // Basic rule: grab the lock, access or modify global state, release the lock, then
    // do the required SQL work.
    private final Object mLock = new Object();

    // Warns if the database is finalized without being closed properly.
    // INVARIANT: Guarded by mLock.
    private final CloseGuard mCloseGuardLocked = CloseGuard.get();

    // The database configuration.
    // INVARIANT: Guarded by mLock.
    public final SQLiteDatabaseConfiguration mConfigurationLocked;

    /**
     * When a constraint violation occurs, an immediate ROLLBACK occurs,
     * thus ending the current transaction, and the command aborts with a
     * return code of SQLITE_CONSTRAINT. If no transaction is active
     * (other than the implied transaction that is created on every command)
     * then this algorithm works the same as ABORT.
     */
    public static final int CONFLICT_ROLLBACK = 1;

    /**
     * When a constraint violation occurs,no ROLLBACK is executed
     * so changes from prior commands within the same transaction
     * are preserved. This is the default behavior.
     */
    public static final int CONFLICT_ABORT = 2;

    /**
     * When a constraint violation occurs, the command aborts with a return
     * code SQLITE_CONSTRAINT. But any changes to the database that
     * the command made prior to encountering the constraint violation
     * are preserved and are not backed out.
     */
    public static final int CONFLICT_FAIL = 3;

    /**
     * When a constraint violation occurs, the one row that contains
     * the constraint violation is not inserted or changed.
     * But the command continues executing normally. Other rows before and
     * after the row that contained the constraint violation continue to be
     * inserted or updated normally. No error is returned.
     */
    public static final int CONFLICT_IGNORE = 4;

    /**
     * When a UNIQUE constraint violation occurs, the pre-existing rows that
     * are causing the constraint violation are removed prior to inserting
     * or updating the current row. Thus the insert or update always occurs.
     * The command continues executing normally. No error is returned.
     * If a NOT NULL constraint violation occurs, the NULL value is replaced
     * by the default value for that column. If the column has no default
     * value, then the ABORT algorithm is used. If a CHECK constraint
     * violation occurs then the IGNORE algorithm is used. When this conflict
     * resolution strategy deletes rows in order to satisfy a constraint,
     * it does not invoke delete triggers on those rows.
     * This behavior might change in a future release.
     */
    public static final int CONFLICT_REPLACE = 5;

    /**
     * Use the following when no conflict action is specified.
     */
    public static final int CONFLICT_NONE = 0;

    /** Conflict options integer enumeration definition */
    @IntDef({
        CONFLICT_ABORT,
        CONFLICT_FAIL,
        CONFLICT_IGNORE,
        CONFLICT_NONE,
        CONFLICT_REPLACE,
        CONFLICT_ROLLBACK})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ConflictAlgorithm {
    }

    private static final String[] CONFLICT_VALUES = new String[]
            {"", " OR ROLLBACK ", " OR ABORT ", " OR FAIL ", " OR IGNORE ", " OR REPLACE "};

    /** Open flag to open in the database in read only mode */
    public static final int OPEN_READONLY         = 0x00000001;

    /** Open flag to open in the database in read/write mode */
    public static final int OPEN_READWRITE        = 0x00000002;

    /** Open flag to create the database if it does not exist */
    public static final int OPEN_CREATE           = 0x00000004;

    /** Open flag to support URI filenames */
    public static final int OPEN_URI              = 0x00000040;

    /** Open flag opens the database in multi-thread threading mode */
    public static final int OPEN_NOMUTEX          = 0x00008000;

    /** Open flag opens the database in serialized threading mode */
    public static final int OPEN_FULLMUTEX        = 0x00010000;

    /** Open flag equivalent to {@link #OPEN_READWRITE} | {@link #OPEN_CREATE} */
    public static final int CREATE_IF_NECESSARY = OPEN_READWRITE | OPEN_CREATE;


    /** Integer flag definition for the database open options */
    @SuppressLint("UniqueConstants") // duplicate values provided for compatibility
    @IntDef(flag = true, value = {
        OPEN_READONLY,
        OPEN_READWRITE,
        OPEN_CREATE,
        OPEN_URI,
        OPEN_NOMUTEX,
        OPEN_FULLMUTEX,
        CREATE_IF_NECESSARY
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface OpenFlags {
    }

    private SQLiteDatabase(SQLiteDatabaseConfiguration configuration,
                           DatabaseErrorHandler errorHandler) {
        mErrorHandler = errorHandler != null ? errorHandler : new DefaultDatabaseErrorHandler();
        mConfigurationLocked = configuration;
    }

    @SuppressWarnings("ThrowFromFinallyBlock")
    @Override
    protected void finalize() throws Throwable {
        try {
            dispose(true);
        } finally {
            super.finalize();
        }
    }

    @Override
    protected void onAllReferencesReleased() {
        dispose(false);
    }

    private void dispose(boolean finalized) {
        final SQLiteSession session;
        synchronized (mLock) {
            if (mCloseGuardLocked != null) {
                if (finalized) {
                    mCloseGuardLocked.warnIfOpen();
                }
                mCloseGuardLocked.close();
            }

            session = mSession;
            mSession = null;
        }

        if (!finalized) {
            synchronized (sActiveDatabases) {
                sActiveDatabases.remove(this);
            }

            if (session != null) {
                session.close();
            }
        }
    }

    /**
     * Attempts to release memory that SQLite holds but does not require to
     * operate properly. Typically this memory will come from the page cache.
     *
     * @return the number of bytes actually released
     */
    public static int releaseMemory() {
        return SQLiteGlobal.releaseMemory();
    }

    /**
     * Gets a label to use when describing the database in log messages.
     * @return The label.
     */
    String getLabel() {
        synchronized (mLock) {
            return mConfigurationLocked.label;
        }
    }

    /**
     * Sends a corruption message to the database error handler.
     */
    void onCorruption() {
        EventLog.writeEvent(EVENT_DB_CORRUPT, getLabel());
        mErrorHandler.onCorruption(this);
    }

    private static boolean isMainThread() {
        // FIXME: There should be a better way to do this.
        // Would also be nice to have something that would work across Binder calls.
        Looper looper = Looper.myLooper();
        return looper != null && looper == Looper.getMainLooper();
    }

    /**
     * Begins a transaction in EXCLUSIVE mode.
     * <p>
     * Transactions can be nested.
     * When the outer transaction is ended all of
     * the work done in that transaction and all of the nested transactions will be committed or
     * rolled back. The changes will be rolled back if any transaction is ended without being
     * marked as clean (by calling setTransactionSuccessful). Otherwise they will be committed.
     * </p>
     * <p>Here is the standard idiom for transactions:
     *
     * <pre>
     *   db.beginTransaction();
     *   try {
     *     ...
     *     db.setTransactionSuccessful();
     *   } finally {
     *     db.endTransaction();
     *   }
     * </pre>
     */
    public void beginTransactionExclusive() {
        beginTransaction(SQLiteSession.TRANSACTION_MODE_EXCLUSIVE);
    }

    /**
     * Begins a transaction in IMMEDIATE mode. Transactions can be nested. When
     * the outer transaction is ended all of the work done in that transaction
     * and all of the nested transactions will be committed or rolled back. The
     * changes will be rolled back if any transaction is ended without being
     * marked as clean (by calling setTransactionSuccessful). Otherwise they
     * will be committed.
     * <p>
     * Here is the standard idiom for transactions:
     *
     * <pre>
     *   db.beginTransactionNonExclusive();
     *   try {
     *     ...
     *     db.setTransactionSuccessful();
     *   } finally {
     *     db.endTransaction();
     *   }
     * </pre>
     */
    public void beginTransactionImmediate() {
        beginTransaction(SQLiteSession.TRANSACTION_MODE_IMMEDIATE);
    }

    /**
     * Begins a transaction in DEFERRED mode.
     */
    public void beginTransactionDeferred() {
        beginTransaction(SQLiteSession.TRANSACTION_MODE_DEFERRED);
    }

    private void beginTransaction(int mode) {
        acquireReference();
        try {
            mSession.beginTransaction(mode, null);
        } finally {
            releaseReference();
        }
    }

    /**
     * End a transaction. See beginTransaction for notes about how to use this and when transactions
     * are committed and rolled back.
     */
    public void endTransaction() {
        acquireReference();
        try {
            mSession.endTransaction();
        } finally {
            releaseReference();
        }
    }

    public void commitTransaction() {
        acquireReference();
        try {
            mSession.setTransactionSuccessful();
            mSession.endTransaction();
        } finally {
            releaseReference();
        }
    }

    /**
     * Marks the current transaction as successful. Do not do any more database work between
     * calling this and calling endTransaction. Do as little non-database work as possible in that
     * situation too. If any errors are encountered between this and endTransaction the transaction
     * will still be committed.
     *
     * @throws IllegalStateException if the current thread is not in a transaction or the
     * transaction is already marked as successful.
     */
    public void setTransactionSuccessful() {
        acquireReference();
        try {
            mSession.setTransactionSuccessful();
        } finally {
            releaseReference();
        }
    }

    /**
     * Returns true if the current thread has a transaction pending.
     *
     * @return True if the current thread is in a transaction.
     */
    public boolean inTransaction() {
        acquireReference();
        try {
            return mSession.hasTransaction();
        } finally {
            releaseReference();
        }
    }

    /**
     * Open the database according to the flags {@link OpenFlags}
     *
     * @param path to database file to open and/or create
     * @param flags to control database access mode
     * @return the newly opened database
     * @throws SQLiteException if the database cannot be opened
     */
    public static SQLiteDatabase openDatabase(String path,
                                              @OpenFlags int flags) {
        return openDatabase(path, flags, null);
    }

    /**
     * Open the database according to the flags {@link OpenFlags}
     *
     * <p>Accepts input param: a concrete instance of {@link DatabaseErrorHandler} to be
     * used to handle corruption when sqlite reports database corruption.</p>
     *
     * @param path to database file to open and/or create
     * @param flags to control database access mode
     * @param errorHandler the {@link DatabaseErrorHandler} obj to be used to handle corruption
     * when sqlite reports database corruption
     * @return the newly opened database
     * @throws SQLiteException if the database cannot be opened
     */
    public static SQLiteDatabase openDatabase(String path,
                                              @OpenFlags int flags,
                                              DatabaseErrorHandler errorHandler) {
        SQLiteDatabaseConfiguration configuration = new SQLiteDatabaseConfiguration(path, flags);
        SQLiteDatabase db = new SQLiteDatabase(configuration, errorHandler);
        db.open();
        return db;
    }

    /**
     * Open the database according to the given configuration.
     *
     * <p>Accepts input param: a concrete instance of {@link DatabaseErrorHandler} to be
     * used to handle corruption when sqlite reports database corruption.</p>
     *
     * @param configuration to database configuration to use
     * @param errorHandler the {@link DatabaseErrorHandler} obj to be used to handle corruption
     * when sqlite reports database corruption
     * @return the newly opened database
     * @throws SQLiteException if the database cannot be opened
     */
    public static SQLiteDatabase openDatabase(SQLiteDatabaseConfiguration configuration,
                                              DatabaseErrorHandler errorHandler) {
        SQLiteDatabase db = new SQLiteDatabase(configuration, errorHandler);
        db.open();
        return db;
    }

    /**
     * Equivalent to openDatabase(file.getPath(), factory, CREATE_IF_NECESSARY).
     */
    public static SQLiteDatabase openOrCreateDatabase(File file) {
        return openOrCreateDatabase(file.getPath());
    }

    /**
     * Equivalent to openDatabase(path, factory, CREATE_IF_NECESSARY).
     */
    public static SQLiteDatabase openOrCreateDatabase(String path) {
        return openDatabase(path, CREATE_IF_NECESSARY, null);
    }

    /**
     * Equivalent to openDatabase(path, factory, CREATE_IF_NECESSARY, errorHandler).
     */
    public static SQLiteDatabase openOrCreateDatabase(String path,
            DatabaseErrorHandler errorHandler) {
        return openDatabase(path, CREATE_IF_NECESSARY, errorHandler);
    }

    /**
     * Deletes a database including its journal file and other auxiliary files
     * that may have been created by the database engine.
     *
     * @param file The database file path.
     * @return True if the database was successfully deleted.
     */
    public static boolean deleteDatabase(File file) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }

        boolean deleted;
        deleted = file.delete();
        deleted |= new File(file.getPath() + "-journal").delete();
        deleted |= new File(file.getPath() + "-shm").delete();
        deleted |= new File(file.getPath() + "-wal").delete();

        File dir = file.getParentFile();
        if (dir != null) {
            final String prefix = file.getName() + "-mj";
            final FileFilter filter = candidate -> candidate.getName().startsWith(prefix);
            final File[] files = dir.listFiles(filter);
            for (File masterJournal : files) {
                deleted |= masterJournal.delete();
            }
        }
        return deleted;
    }

    private void open() {
        try {
            if (!mConfigurationLocked.isInMemoryDb()
                    && (mConfigurationLocked.openFlags & OPEN_CREATE) != 0) {
                ensureFile(mConfigurationLocked.path);
            }
            try {
                mSession = new SQLiteSession(this);
            } catch (SQLiteDatabaseCorruptException ex) {
                onCorruption();
                mSession = new SQLiteSession(this);
            }
        } catch (SQLiteException ex) {
            Log.e(TAG, "Failed to open database '" + getLabel() + "'.", ex);
            close();
            throw ex;
        }
    }

    private static void ensureFile(String path) {
        File file = new File(path);
        if (!file.exists()) {
            try {
                if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                    // Fixes #103: Check parent directory's existence before
                    // attempting to create.
                    Log.e(TAG, "Couldn't mkdirs " + file);
                }
                if (!file.createNewFile()) {
                    Log.e(TAG, "Couldn't create " + file);
                }
            } catch (IOException e) {
                Log.e(TAG, "Couldn't ensure file " + file, e);
            }
        }
    }

    /**
     * Create a memory backed SQLite database.  Its contents will be destroyed
     * when the database is closed.
     *
     * @return a SQLiteDatabase object, or null if the database can't be created
     */
    public static SQLiteDatabase create() {
        // This is a magic string with special meaning for SQLite.
        return openDatabase(SQLiteDatabaseConfiguration.MEMORY_DB_PATH, CREATE_IF_NECESSARY);
    }

    /**
     * Gets the database version.
     *
     * @return the database version
     */
    public int getVersion() {
        return ((Long) longForQuery("PRAGMA user_version;", null)).intValue();
    }

    /**
     * Sets the database version.
     *
     * @param version the new database version
     */
    public void setVersion(int version) {
        execSQL("PRAGMA user_version = " + version);
    }

    /**
     * Returns the maximum size the database may grow to.
     *
     * @return the new maximum database size
     */
    public long getMaximumSize() {
        long pageCount = longForQuery("PRAGMA max_page_count;", null);
        return pageCount * getPageSize();
    }

    /**
     * Sets the maximum size the database will grow to. The maximum size cannot
     * be set below the current size.
     *
     * @param numBytes the maximum database size, in bytes
     * @return the new maximum database size
     */
    public long setMaximumSize(long numBytes) {
        long pageSize = getPageSize();
        long numPages = numBytes / pageSize;
        // If numBytes isn't a multiple of pageSize, bump up a page
        if ((numBytes % pageSize) != 0) {
            numPages++;
        }
        long newPageCount = longForQuery("PRAGMA max_page_count = " + numPages, null);
        return newPageCount * pageSize;
    }

    /**
     * Returns the current database page size, in bytes.
     *
     * @return the database page size, in bytes
     */
    public long getPageSize() {
        return longForQuery("PRAGMA page_size;", null);
    }

    /**
     * Sets the database page size. The page size must be a power of two. This
     * method does not work if any data has been written to the database file,
     * and must be called right after the database has been created.
     *
     * @param numBytes the database page size, in bytes
     */
    public void setPageSize(long numBytes) {
        execSQL("PRAGMA page_size = " + numBytes);
    }

    /**
     * Finds the name of the first table, which is editable.
     *
     * @param tables a list of tables
     * @return the first table listed
     */
    public static String findEditTable(String tables) {
        if (!TextUtils.isEmpty(tables)) {
            // find the first word terminated by either a space or a comma
            int spacepos = tables.indexOf(' ');
            int commapos = tables.indexOf(',');

            if (spacepos > 0 && (spacepos < commapos || commapos < 0)) {
                return tables.substring(0, spacepos);
            } else if (commapos > 0 && (commapos < spacepos || spacepos < 0) ) {
                return tables.substring(0, commapos);
            }
            return tables;
        } else {
            throw new IllegalStateException("Invalid tables");
        }
    }

    /**
     * Compiles an SQL statement into a reusable pre-compiled statement object.
     * The parameters are identical to {@link #execSQL(String)}. You may put ?s in the
     * statement and fill in those values with {@link SQLitePreparedStatement#bindString}
     * and {@link SQLitePreparedStatement#bindLong} each time you want to run the
     * statement. Statements may not return result sets larger than 1x1.
     *<p>
     * No two threads should be using the same {@link SQLitePreparedStatement} at the same time.
     *
     * @param sql The raw SQL statement, may contain ? for unknown values to be
     *            bound later.
     * @return A pre-compiled {@link SQLitePreparedStatement} object. Note that
     * {@link SQLitePreparedStatement}s are not synchronized, see the documentation for more details.
     */
    public SQLitePreparedStatement compileStatement(String sql) throws SQLException {
        acquireReference();
        try {
            return new SQLitePreparedStatement(this, sql, null, null);
        } finally {
            releaseReference();
        }
    }


    /**
     * Runs the provided SQL and returns a {@link SQLiteCursor} over the result set.
     *
     * @param query the SQL query. The SQL string must not be ; terminated
     * @return A {@link SQLiteCursor} object, which is positioned before the first entry. Note that
     * {@link SQLiteCursor}s are not synchronized, see the documentation for more details.
     */
    public SQLiteCursor query(String query) {
        return rawQueryWithFactory(query, null, null, null);
    }

    /**
     * Runs the provided SQL and returns a {@link SQLiteCursor} over the result set.
     *
     * @param query the SQL query. The SQL string must not be ; terminated
     * @param selectionArgs You may include ?s in where clause in the query,
     *     which will be replaced by the values from selectionArgs.
     * @return A {@link SQLiteCursor} object, which is positioned before the first entry. Note that
     * {@link SQLiteCursor}s are not synchronized, see the documentation for more details.
     */
    public SQLiteCursor query(String query, Object[] selectionArgs) {
        return rawQueryWithFactory(query, selectionArgs, null, null);
    }


    /**
     * Runs the provided SQL and returns a {@link SQLiteCursor} over the result set.
     *
     * @param sql the SQL query. The SQL string must not be ; terminated
     * @param selectionArgs You may include ?s in where clause in the query,
     *     which will be replaced by the values from selectionArgs.
     * @return A {@link SQLiteCursor} object, which is positioned before the first entry. Note that
     * {@link SQLiteCursor}s are not synchronized, see the documentation for more details.
     */
    public SQLiteCursor rawQuery(String sql, Object[] selectionArgs) {
        return rawQueryWithFactory(sql, selectionArgs, null, null);
    }

    /**
     * Runs the provided SQL and returns a {@link SQLiteCursor} over the result set.
     *
     * @param sql the SQL query. The SQL string must not be ; terminated
     * @param selectionArgs You may include ?s in where clause in the query,
     *     which will be replaced by the values from selectionArgs.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * If the operation is canceled, then {@link OperationCanceledException} will be thrown
     * when the query is executed.
     * @return A {@link SQLiteCursor} object, which is positioned before the first entry. Note that
     * {@link SQLiteCursor}s are not synchronized, see the documentation for more details.
     */
    public SQLiteCursor rawQuery(String sql, Object[] selectionArgs,
            CancellationSignal cancellationSignal) {
        return rawQueryWithFactory(sql, selectionArgs, null, cancellationSignal);
    }

    /**
     * Runs the provided SQL and returns a cursor over the result set.
     *
     * @param sql the SQL query. The SQL string must not be ; terminated
     * @param selectionArgs You may include ?s in where clause in the query,
     *     which will be replaced by the values from selectionArgs.
     * @param editTable the name of the first table, which is editable
     * @return A {@link SQLiteCursor} object, which is positioned before the first entry. Note that
     * {@link SQLiteCursor}s are not synchronized, see the documentation for more details.
     */
    public SQLiteCursor rawQueryWithFactory(String sql, Object[] selectionArgs, String editTable) {
        return rawQueryWithFactory(sql, selectionArgs, editTable, null);
    }

    /**
     * Runs the provided SQL and returns a cursor over the result set.
     *
     * @param sql the SQL query. The SQL string must not be ; terminated
     * @param selectionArgs You may include ?s in where clause in the query,
     *     which will be replaced by the values from selectionArgs.
     * @param editTable the name of the first table, which is editable
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * If the operation is canceled, then {@link OperationCanceledException} will be thrown
     * when the query is executed.
     * @return A {@link SQLiteCursor} object, which is positioned before the first entry. Note that
     * {@link SQLiteCursor}s are not synchronized, see the documentation for more details.
     */
    public SQLiteCursor rawQueryWithFactory(String sql, Object[] selectionArgs, String editTable, CancellationSignal cancellationSignal) {
        acquireReference();
        try {
            SQLitePreparedStatement query = new SQLitePreparedStatement(this, sql, selectionArgs, cancellationSignal);
            final SQLiteCursor cursor;
            try {
                cursor = new SQLiteCursor(editTable, query);
            } catch (Exception ex) {
                query.close();
                throw ex;
            }
            return cursor;
        } finally {
            releaseReference();
        }
    }

    /**
     * Convenience method for inserting a row into the database.
     *
     * @param table the table to insert the row into
     * @param nullColumnHack optional; may be <code>null</code>.
     *            SQL doesn't allow inserting a completely empty row without
     *            naming at least one column name.  If your provided <code>values</code> is
     *            empty, no column names are known and an empty row can't be inserted.
     *            If not set to null, the <code>nullColumnHack</code> parameter
     *            provides the name of nullable column name to explicitly insert a NULL into
     *            in the case where your <code>values</code> is empty.
     * @param values this map contains the initial column values for the
     *            row. The keys should be the column names and the values the
     *            column values
     * @return the row ID of the newly inserted row, or -1 if an error occurred
     */
    public long insert(String table, String nullColumnHack, ContentValues values) {
        try {
            return insertWithOnConflict(table, nullColumnHack, values, CONFLICT_NONE);
        } catch (SQLException e) {
            Log.e(TAG, "Error inserting " + values, e);
            return -1;
        }
    }

    /**
     * Convenience method for inserting a row into the database.
     *
     * @param table the table to insert the row into
     * @param nullColumnHack optional; may be <code>null</code>.
     *            SQL doesn't allow inserting a completely empty row without
     *            naming at least one column name.  If your provided <code>values</code> is
     *            empty, no column names are known and an empty row can't be inserted.
     *            If not set to null, the <code>nullColumnHack</code> parameter
     *            provides the name of nullable column name to explicitly insert a NULL into
     *            in the case where your <code>values</code> is empty.
     * @param values this map contains the initial column values for the
     *            row. The keys should be the column names and the values the
     *            column values
     * @throws SQLException
     * @return the row ID of the newly inserted row, or -1 if an error occurred
     */
    public long insertOrThrow(String table, String nullColumnHack, ContentValues values)
            throws SQLException {
        return insertWithOnConflict(table, nullColumnHack, values, CONFLICT_NONE);
    }

    /**
     * Convenience method for replacing a row in the database.
     *
     * @param table the table in which to replace the row
     * @param nullColumnHack optional; may be <code>null</code>.
     *            SQL doesn't allow inserting a completely empty row without
     *            naming at least one column name.  If your provided <code>initialValues</code> is
     *            empty, no column names are known and an empty row can't be inserted.
     *            If not set to null, the <code>nullColumnHack</code> parameter
     *            provides the name of nullable column name to explicitly insert a NULL into
     *            in the case where your <code>initialValues</code> is empty.
     * @param initialValues this map contains the initial column values for
     *   the row.
     * @return the row ID of the newly inserted row, or -1 if an error occurred
     */
    public long replace(String table, String nullColumnHack, ContentValues initialValues) {
        try {
            return insertWithOnConflict(table, nullColumnHack, initialValues,
                    CONFLICT_REPLACE);
        } catch (SQLException e) {
            Log.e(TAG, "Error inserting " + initialValues, e);
            return -1;
        }
    }

    /**
     * Convenience method for replacing a row in the database.
     *
     * @param table the table in which to replace the row
     * @param nullColumnHack optional; may be <code>null</code>.
     *            SQL doesn't allow inserting a completely empty row without
     *            naming at least one column name.  If your provided <code>initialValues</code> is
     *            empty, no column names are known and an empty row can't be inserted.
     *            If not set to null, the <code>nullColumnHack</code> parameter
     *            provides the name of nullable column name to explicitly insert a NULL into
     *            in the case where your <code>initialValues</code> is empty.
     * @param initialValues this map contains the initial column values for
     *   the row. The key
     * @throws SQLException
     * @return the row ID of the newly inserted row, or -1 if an error occurred
     */
    public long replaceOrThrow(String table, String nullColumnHack,
            ContentValues initialValues) throws SQLException {
        return insertWithOnConflict(table, nullColumnHack, initialValues,
                CONFLICT_REPLACE);
    }

    /**
     * General method for inserting a row into the database.
     *
     * @param table the table to insert the row into
     * @param conflictAlgorithm for insert conflict resolver
     * @param values this map contains the initial column values for the
     *            row. The keys should be the column names and the values the
     *            column values
     * @return the row ID of the newly inserted row
     * OR the primary key of the existing row if the input param 'conflictAlgorithm' =
     * {@link #CONFLICT_IGNORE}
     * OR -1 if any error
     */
    public long insert(String table, @ConflictAlgorithm int conflictAlgorithm,
           ContentValues values) throws SQLException {
        return insertWithOnConflict(table, null, values, conflictAlgorithm);
    }

    /**
     * General method for inserting a row into the database.
     *
     * @param table the table to insert the row into
     * @param nullColumnHack optional; may be <code>null</code>.
     *            SQL doesn't allow inserting a completely empty row without
     *            naming at least one column name.  If your provided <code>initialValues</code> is
     *            empty, no column names are known and an empty row can't be inserted.
     *            If not set to null, the <code>nullColumnHack</code> parameter
     *            provides the name of nullable column name to explicitly insert a NULL into
     *            in the case where your <code>initialValues</code> is empty.
     * @param initialValues this map contains the initial column values for the
     *            row. The keys should be the column names and the values the
     *            column values
     * @param conflictAlgorithm for insert conflict resolver
     * @return the row ID of the newly inserted row
     * OR the primary key of the existing row if the input param 'conflictAlgorithm' =
     * {@link #CONFLICT_IGNORE}
     * OR -1 if any error
     */
    @SuppressWarnings("StringConcatenationInsideStringBufferAppend")
    public long insertWithOnConflict(String table, String nullColumnHack,
            ContentValues initialValues, @ConflictAlgorithm int conflictAlgorithm) {
        acquireReference();
        try {
            StringBuilder sql = new StringBuilder();
            sql.append("INSERT");
            sql.append(CONFLICT_VALUES[conflictAlgorithm]);
            sql.append(" INTO ");
            sql.append(table);
            sql.append('(');

            Object[] bindArgs = null;
            int size = (initialValues != null && initialValues.size() > 0)
                    ? initialValues.size() : 0;
            if (size > 0) {
                bindArgs = new Object[size];
                int i = 0;
                for (Map.Entry<String, Object> entry : initialValues.valueSet()) {
                    sql.append((i > 0) ? "," : "");
                    sql.append(entry.getKey());
                    bindArgs[i++] = entry.getValue();
                }
                sql.append(')');
                sql.append(" VALUES (");
                for (i = 0; i < size; i++) {
                    sql.append((i > 0) ? ",?" : "?");
                }
            } else {
                sql.append(nullColumnHack + ") VALUES (NULL");
            }
            sql.append(')');

            SQLitePreparedStatement statement = new SQLitePreparedStatement(this, sql.toString(), bindArgs, null);
            try {
                return statement.executeInsert();
            } finally {
                statement.close();
            }
        } finally {
            releaseReference();
        }
    }

    /**
     * Convenience method for deleting rows in the database.
     *
     * @param table the table to delete from
     * @param whereClause the optional WHERE clause to apply when deleting.
     *            Passing null will delete all rows.
     * @param whereArgs You may include ?s in the where clause, which
     *            will be replaced by the values from whereArgs. The values
     *            will be bound as Strings.
     * @return the number of rows affected if a whereClause is passed in, 0
     *         otherwise. To remove all rows and get a count pass "1" as the
     *         whereClause.
     */
    public int delete(String table, String whereClause, String[] whereArgs) {
        acquireReference();
        try {
            SQLitePreparedStatement statement =  new SQLitePreparedStatement(this, "DELETE FROM " + table +
                    (!TextUtils.isEmpty(whereClause) ? " WHERE " + whereClause : ""), whereArgs, null);
            try {
                return statement.executeUpdateDelete();
            } finally {
                statement.close();
            }
        } finally {
            releaseReference();
        }
    }

    /**
     * Convenience method for deleting rows in the database.
     *
     * @param table the table to delete from
     * @param whereClause the optional WHERE clause to apply when deleting.
     *            Passing null will delete all rows.
     * @param whereArgs You may include ?s in the where clause, which
     *            will be replaced by the values from whereArgs. The values
     *            will be bound as Strings.
     * @return the number of rows affected if a whereClause is passed in, 0
     *         otherwise. To remove all rows and get a count pass "1" as the
     *         whereClause.
     */
    public int delete(String table, String whereClause, Object[] whereArgs) {
        acquireReference();
        try {
            SQLitePreparedStatement statement =  new SQLitePreparedStatement(this, "DELETE FROM " + table +
                    (!TextUtils.isEmpty(whereClause) ? " WHERE " + whereClause : ""), whereArgs, null);
            try {
                return statement.executeUpdateDelete();
            } finally {
                statement.close();
            }
        } finally {
            releaseReference();
        }
    }

    /**
     * Convenience method for updating rows in the database.
     *
     * @param table the table to update in
     * @param values a map from column names to new column values. null is a
     *            valid value that will be translated to NULL.
     * @param whereClause the optional WHERE clause to apply when updating.
     *            Passing null will update all rows.
     * @param whereArgs You may include ?s in the where clause, which
     *            will be replaced by the values from whereArgs. The values
     *            will be bound as Strings.
     * @return the number of rows affected
     */
    public int update(String table, ContentValues values, String whereClause, String[] whereArgs) {
        return updateWithOnConflict(table, values, whereClause, whereArgs, CONFLICT_NONE);
    }

    /**
     * Convenience method for updating rows in the database.
     *
     * @param table the table to update in
     * @param values a map from column names to new column values. null is a
     *            valid value that will be translated to NULL.
     * @param whereClause the optional WHERE clause to apply when updating.
     *            Passing null will update all rows.
     * @param whereArgs You may include ?s in the where clause, which
     *            will be replaced by the values from whereArgs. The values
     *            will be bound as Strings.
     * @param conflictAlgorithm for update conflict resolver
     * @return the number of rows affected
     */
    public int update(String table, @ConflictAlgorithm int conflictAlgorithm, ContentValues values,
                      String whereClause,  Object[] whereArgs) {
        if (values == null || values.size() == 0) {
            throw new IllegalArgumentException("Empty values");
        }

        acquireReference();
        try {
            StringBuilder sql = new StringBuilder(120);
            sql.append("UPDATE ");
            sql.append(CONFLICT_VALUES[conflictAlgorithm]);
            sql.append(table);
            sql.append(" SET ");

            // move all bind args to one array
            int setValuesSize = values.size();
            int bindArgsSize = (whereArgs == null) ? setValuesSize : (setValuesSize + whereArgs.length);
            Object[] bindArgs = new Object[bindArgsSize];
            int i = 0;
            for (Map.Entry<String, Object> entry : values.valueSet()) {
                sql.append((i > 0) ? "," : "");
                sql.append(entry.getKey());
                bindArgs[i++] = entry.getValue();
                sql.append("=?");
            }
            if (whereArgs != null) {
                for (i = setValuesSize; i < bindArgsSize; i++) {
                    bindArgs[i] = whereArgs[i - setValuesSize];
                }
            }
            if (!TextUtils.isEmpty(whereClause)) {
                sql.append(" WHERE ");
                sql.append(whereClause);
            }

            SQLitePreparedStatement statement = new SQLitePreparedStatement(this, sql.toString(), bindArgs, null);
            try {
                return statement.executeUpdateDelete();
            } finally {
                statement.close();
            }
        } finally {
            releaseReference();
        }
    }

    /**
     * Convenience method for updating rows in the database.
     *
     * @param table the table to update in
     * @param values a map from column names to new column values. null is a
     *            valid value that will be translated to NULL.
     * @param whereClause the optional WHERE clause to apply when updating.
     *            Passing null will update all rows.
     * @param whereArgs You may include ?s in the where clause, which
     *            will be replaced by the values from whereArgs. The values
     *            will be bound as Strings.
     * @param conflictAlgorithm for update conflict resolver
     * @return the number of rows affected
     */
    public int updateWithOnConflict(String table, ContentValues values,
            String whereClause, String[] whereArgs, @ConflictAlgorithm int conflictAlgorithm) {
        if (values == null || values.size() == 0) {
            throw new IllegalArgumentException("Empty values");
        }

        acquireReference();
        try {
            StringBuilder sql = new StringBuilder(120);
            sql.append("UPDATE ");
            sql.append(CONFLICT_VALUES[conflictAlgorithm]);
            sql.append(table);
            sql.append(" SET ");

            // move all bind args to one array
            int setValuesSize = values.size();
            int bindArgsSize = (whereArgs == null) ? setValuesSize : (setValuesSize + whereArgs.length);
            Object[] bindArgs = new Object[bindArgsSize];
            int i = 0;
            for (Map.Entry<String, Object> entry : values.valueSet()) {
                sql.append((i > 0) ? "," : "");
                sql.append(entry.getKey());
                bindArgs[i++] = entry.getValue();
                sql.append("=?");
            }
            if (whereArgs != null) {
                for (i = setValuesSize; i < bindArgsSize; i++) {
                    bindArgs[i] = whereArgs[i - setValuesSize];
                }
            }
            if (!TextUtils.isEmpty(whereClause)) {
                sql.append(" WHERE ");
                sql.append(whereClause);
            }

            SQLitePreparedStatement statement = new SQLitePreparedStatement(this, sql.toString(), bindArgs, null);
            try {
                return statement.executeUpdateDelete();
            } finally {
                statement.close();
            }
        } finally {
            releaseReference();
        }
    }

    /**
     * Execute a single SQL statement that is NOT a SELECT
     * or any other SQL statement that returns data.
     * <p>
     * It has no means to return any data (such as the number of affected rows).
     * Instead, you're encouraged to use {@link #insert(String, String, ContentValues)},
     * {@link #update(String, ContentValues, String, String[])}, et al, when possible.
     * </p>
     *
     * @param sql the SQL statement to be executed. Multiple statements separated by semicolons are
     * not supported.
     * @throws SQLException if the SQL string is invalid
     */
    public void execSQL(String sql) throws SQLException {
        executeSql(sql, null);
    }

    /**
     * Execute a single SQL statement that is NOT a SELECT/INSERT/UPDATE/DELETE.
     * <p>
     * For INSERT statements, use any of the following instead.
     * <ul>
     *   <li>{@link #insert(String, String, ContentValues)}</li>
     *   <li>{@link #insertOrThrow(String, String, ContentValues)}</li>
     *   <li>{@link #insertWithOnConflict(String, String, ContentValues, int)}</li>
     * </ul>
     * <p>
     * For UPDATE statements, use any of the following instead.
     * <ul>
     *   <li>{@link #update(String, ContentValues, String, String[])}</li>
     *   <li>{@link #updateWithOnConflict(String, ContentValues, String, String[], int)}</li>
     * </ul>
     * <p>
     * For DELETE statements, use any of the following instead.
     * <ul>
     *   <li>{@link #delete(String, String, String[])}</li>
     * </ul>
     * <p>
     * For example, the following are good candidates for using this method:
     * <ul>
     *   <li>ALTER TABLE</li>
     *   <li>CREATE or DROP table / trigger / view / index / virtual table</li>
     *   <li>REINDEX</li>
     *   <li>RELEASE</li>
     *   <li>SAVEPOINT</li>
     *   <li>PRAGMA that returns no data</li>
     * </ul>
     * </p>
     *
     * @param sql the SQL statement to be executed. Multiple statements separated by semicolons are
     * not supported.
     * @param bindArgs only byte[], String, Long and Double are supported in bindArgs.
     * @throws SQLException if the SQL string is invalid
     */
    public void execSQL(String sql, Object[] bindArgs) throws SQLException {
        if (bindArgs == null) {
            throw new IllegalArgumentException("Empty bindArgs");
        }
        executeSql(sql, bindArgs);
    }

    private int executeSql(String sql, Object[] bindArgs) throws SQLException {
        acquireReference();
        try {
            SQLitePreparedStatement statement = new SQLitePreparedStatement(this, sql, bindArgs, null);
            try {
                return statement.executeUpdateDelete();
            } finally {
                statement.close();
            }
        } finally {
            releaseReference();
        }
    }

    /**
     * Returns true if the database is opened as read only.
     *
     * @return True if database is opened as read only.
     */
    public boolean isReadOnly() {
        synchronized (mLock) {
            return isReadOnlyLocked();
        }
    }

    private boolean isReadOnlyLocked() {
        return (mConfigurationLocked.openFlags & OPEN_READONLY) == OPEN_READONLY;
    }

    /**
     * Returns true if the database is in-memory db.
     *
     * @return True if the database is in-memory.
     * @hide
     */
    public boolean isInMemoryDatabase() {
        synchronized (mLock) {
            return mConfigurationLocked.isInMemoryDb();
        }
    }

    /**
     * Returns true if the database is currently open.
     *
     * @return True if the database is currently open (has not been closed).
     */
    public boolean isOpen() {
        synchronized (mLock) {
            return mSession != null;
        }
    }

    /**
     * Returns true if the new version code is greater than the current database version.
     *
     * @param newVersion The new version code.
     * @return True if the new version code is greater than the current database version.
     */
    public boolean needUpgrade(int newVersion) {
        return newVersion > getVersion();
    }

    /**
     * Gets the path to the database file.
     *
     * @return The path to the database file.
     */
    public String getPath() {
        synchronized (mLock) {
            if (mConfigurationLocked.isInMemoryDb()) {
                return null;
            }
            return mConfigurationLocked.path;
        }
    }

    private static ArrayList<SQLiteDatabase> getActiveDatabases() {
        synchronized (sActiveDatabases) {
            return new ArrayList<>(sActiveDatabases.keySet());
        }
    }

    /**
     * Runs 'pragma integrity_check' on the given database (and all the attached databases)
     * and returns true if the given database (and all its attached databases) pass integrity_check,
     * false otherwise.
     *<p>
     * If the result is false, then this method logs the errors reported by the integrity_check
     * command execution.
     *<p>
     * Note that 'pragma integrity_check' on a database can take a long time.
     *
     * @return true if the given database (and all its attached databases) pass integrity_check,
     * false otherwise.
     */
    public boolean isDatabaseIntegrityOk() {
        acquireReference();
        try {
            SQLitePreparedStatement prog = null;
            try {
                prog = compileStatement("PRAGMA integrity_check(1);");
                String rslt = prog.simpleQueryForString();
                if (!rslt.equalsIgnoreCase("ok")) {
                    // integrity_checker failed on main or attached databases
                    Log.e(TAG, "PRAGMA integrity_check returned: " + rslt);
                    return false;
                }
            } finally {
                if (prog != null) prog.close();
            }
        } finally {
            releaseReference();
        }
        return true;
    }

    @Override
    public String toString() {
        return "SQLiteDatabase: " + getPath();
    }

    private void throwIfNotOpenLocked() {
        if (mSession == null) {
            throw new IllegalStateException("The database '" + mConfigurationLocked.label
                    + "' is not open.");
        }
    }


    /**
     * Query the table for the number of rows in the table.
     * @param table the name of the table to query
     * @return the number of rows in the table
     */
    public long queryNumEntries(String table) {
        return queryNumEntries(table, null, null);
    }

    /**
     * Query the table for the number of rows in the table.
     * @param table the name of the table to query
     * @param selection A filter declaring which rows to return,
     *              formatted as an SQL WHERE clause (excluding the WHERE itself).
     *              Passing null will count all rows for the given table
     * @return the number of rows in the table filtered by the selection
     */
    public long queryNumEntries(String table, String selection) {
        return queryNumEntries(table, selection, null);
    }

    /**
     * Query the table for the number of rows in the table.
     * @param table the name of the table to query
     * @param selection A filter declaring which rows to return,
     *              formatted as an SQL WHERE clause (excluding the WHERE itself).
     *              Passing null will count all rows for the given table
     * @param selectionArgs You may include ?s in selection,
     *              which will be replaced by the values from selectionArgs,
     *              in order that they appear in the selection.
     *              The values will be bound as Strings.
     * @return the number of rows in the table filtered by the selection
     */
    public long queryNumEntries(String table, String selection, String[] selectionArgs) {
        String s = (!TextUtils.isEmpty(selection)) ? " where " + selection : "";
        return longForQuery("select count(*) from " + table + s, selectionArgs);
    }

    /**
     * Utility method to run the query on the db and return the value in the
     * first column of the first row.
     */
    public long longForQuery(String query, String[] selectionArgs) {
        SQLitePreparedStatement prog = compileStatement(query);
        try {
            return longForQuery(prog, selectionArgs);
        } finally {
            prog.close();
        }
    }

    /**
     * Utility method to run the pre-compiled query and return the value in the
     * first column of the first row.
     */
    private static long longForQuery(SQLitePreparedStatement prog, String[] selectionArgs) {
        prog.bindAllArgsAsStrings(selectionArgs);
        return prog.simpleQueryForLong();
    }

    /**
     * Utility method to run the query on the db and return the value in the
     * first column of the first row.
     */
    public String stringForQuery(String query, String[] selectionArgs) {
        SQLitePreparedStatement prog = compileStatement(query);
        try {
            return stringForQuery(prog, selectionArgs);
        } finally {
            prog.close();
        }
    }

    /**
     * Utility method to run the pre-compiled query and return the value in the
     * first column of the first row.
     */
    public static String stringForQuery(SQLitePreparedStatement prog, String[] selectionArgs) {
        prog.bindAllArgsAsStrings(selectionArgs);
        return prog.simpleQueryForString();
    }
}
