package com.darkyen.sqlite;

import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;

import static com.darkyen.sqlite.SQLiteNative.nativeClose;
import static com.darkyen.sqlite.SQLiteNative.nativeExecutePragma;
import static com.darkyen.sqlite.SQLiteNative.nativeOpen;
import static com.darkyen.sqlite.SQLiteNative.nativePrepareStatement;
import static com.darkyen.sqlite.SQLiteNative.nativeReleaseMemory;

public class SQLiteConnection implements AutoCloseable {
    private long connectionPtr;

    private boolean inTransaction = false;
    private boolean transactionSuccessful = false;

    private static final int STATEMENT_BEGIN_DEFERRED_TRANSACTION = 0;
    private static final int STATEMENT_BEGIN_IMMEDIATE_TRANSACTION = 1;
    private static final int STATEMENT_BEGIN_EXCLUSIVE_TRANSACTION = 2;
    private static final int STATEMENT_COMMIT_TRANSACTION = 3;
    private static final int STATEMENT_ROLLBACK_TRANSACTION = 4;
    private static final int STATEMENT_COUNT = 5;
    private final SQLiteStatement[] statementCache = new SQLiteStatement[STATEMENT_COUNT];

    private final ArrayList<SQLiteStatement> managedStatements = new ArrayList<>();

    private SQLiteConnection(long connectionPtr) {
        this.connectionPtr = connectionPtr;
    }

    long connectionPtr() {
        final long ptr = connectionPtr;
        if (ptr == 0) throw new IllegalStateException("Connection already closed");
        return ptr;
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
        beginTransaction(STATEMENT_BEGIN_EXCLUSIVE_TRANSACTION);
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
        beginTransaction(STATEMENT_BEGIN_IMMEDIATE_TRANSACTION);
    }

    /**
     * Begins a transaction in DEFERRED mode.
     */
    public void beginTransactionDeferred() {
        beginTransaction(STATEMENT_BEGIN_DEFERRED_TRANSACTION);
    }

    private void beginTransaction(int statement) {
        if (inTransaction) {
            throw new IllegalStateException("Can't begin nested transaction");
        }
        executeCacheStatement(statement);
        inTransaction = true;
        transactionSuccessful = false;
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
        if (!inTransaction) {
            throw new IllegalStateException("No transaction to mark successful");
        }
        if (transactionSuccessful) {
            throw new IllegalStateException("Transaction is already successful");
        }
        transactionSuccessful = true;
    }

    /**
     * End a transaction. See beginTransaction for notes about how to use this and when transactions
     * are committed and rolled back.
     */
    public void endTransaction() {
        if (!inTransaction) {
            throw new IllegalStateException("No transaction in progress to end");
        }

        inTransaction = false;
        if (transactionSuccessful) {
            executeCacheStatement(STATEMENT_COMMIT_TRANSACTION);
        } else {
            executeCacheStatement(STATEMENT_ROLLBACK_TRANSACTION);
        }
    }

    private void executeCacheStatement(int statementIndex) {
        SQLiteStatement stmt = statementCache[statementIndex];
        if (stmt == null) {
            @Language("RoomSql") String sql;
            switch (statementIndex) {
                case STATEMENT_BEGIN_DEFERRED_TRANSACTION:
                    sql = "BEGIN DEFERRED TRANSACTION";
                    break;
                case STATEMENT_BEGIN_IMMEDIATE_TRANSACTION:
                    sql = "BEGIN IMMEDIATE TRANSACTION";
                    break;
                case STATEMENT_BEGIN_EXCLUSIVE_TRANSACTION:
                    sql = "BEGIN EXCLUSIVE TRANSACTION";
                    break;
                case STATEMENT_COMMIT_TRANSACTION:
                    sql = "COMMIT TRANSACTION";
                    break;
                case STATEMENT_ROLLBACK_TRANSACTION:
                    sql = "ROLLBACK TRANSACTION";
                    break;
                default: throw new AssertionError("statement "+statementIndex);
            }
            //noinspection resource
            statementCache[statementIndex] = stmt = unmanagedStatement(sql);
        }
        stmt.executeForNothing();
    }

    /**
     * Create a new statement that is not automatically closed with the database.
     */
    private @NotNull SQLiteStatement unmanagedStatement(
            @NotNull
            @Language("RoomSql"/*Should be just SQL, but that is not supported on community :( */)
            String sql) {
        long statementPtr = nativePrepareStatement(connectionPtr(), sql);
        return new SQLiteStatement(this, statementPtr);
    }

    /**
     * Create a new statement.
     * The statement can be closed either manually, or will be closed together with the database.
     * You should still close the statement as soon as you know that you will not need it anymore.
     *
     * @param sql one SQL command, without trailing semicolon
     */
    public @NotNull SQLiteStatement statement(
            @NotNull
            @Language("RoomSql"/*Should be just SQL, but that is not supported on community :( */)
            String sql) {
        final SQLiteStatement statement = unmanagedStatement(sql);
        statement.managementIndex = managedStatements.size();
        managedStatements.add(statement);
        return statement;
    }

    void close(@NotNull SQLiteStatement statement) {
        final int managementIndex = statement.managementIndex;
        statement.managementIndex = -1;

        final int lastIndex = managedStatements.size() - 1;
        final SQLiteStatement removedStatement;
        if (managementIndex == lastIndex) {
            // Just remove
            //noinspection resource
            removedStatement = managedStatements.remove(managementIndex);
        } else {
            final SQLiteStatement movedStatement = managedStatements.remove(lastIndex);
            movedStatement.managementIndex = managementIndex;
            removedStatement = managedStatements.set(managementIndex, movedStatement);
        }
        if (removedStatement != statement) throw new AssertionError("Statement mismanagement");
    }

    /**
     * Perform a DDL command (CREATE, DROP, ALTER, etc.) that returns no rows.
     */
    public void command(@NotNull
                        @Language("RoomSql"/*Should be just SQL, but that is not supported on community :( */)
                        String sql) {
        try (SQLiteStatement statement = unmanagedStatement(sql)) {
            statement.execute();
        }
    }

    @Override
    public void close() throws SQLException {
        final long connectionPtr = this.connectionPtr;
        if (connectionPtr == 0) return;// Already closed

        Throwable result = null;
        for (int i = 0; i < statementCache.length; i++) {
            final SQLiteStatement statement = statementCache[i];
            if (statement != null) {
                try {
                    statement.close();
                } catch (Throwable e) {
                    if (result == null) {
                        result = e;
                    } else {
                        result.addSuppressed(e);
                    }
                }
                statementCache[i] = null;
            }
        }

        for (final SQLiteStatement statement : managedStatements) {
            statement.managementIndex = -1;// Don't bother removing yourself from the list
            try {
                statement.close();
            } catch (Throwable e) {
                if (result == null) {
                    result = e;
                } else {
                    result.addSuppressed(e);
                }
            }
        }
        managedStatements.clear();

        try {
            nativeClose(connectionPtr);
        } catch (Throwable t) {
            if (result != null) {
                t.addSuppressed(result);
            }
            throw t;
        }
        this.connectionPtr = 0;// Clear after, because we don't want to lose ptr if everything is not closed yet
    }

    public static @NotNull SQLiteConnection open(SQLiteDelegate delegate) throws SQLiteException {
        final File file = delegate.file;
        long connectionPtr = nativeOpen(
                file == null ? ":memory:" : file.getAbsolutePath(),
                delegate.openFlags);
        final SQLiteConnection connection = new SQLiteConnection(connectionPtr);

        // Initialize the database, possibly failing in the process
        try {
            final int currentVersion = Integer.parseInt(nativeExecutePragma(connectionPtr, "PRAGMA user_version"));
            final int targetVersion = delegate.version;

            boolean readOnly = (delegate.openFlags & SQLiteDatabase.OPEN_READONLY) != 0;
            if (!readOnly) {
                nativeExecutePragma(connectionPtr, "PRAGMA foreign_keys=" + (delegate.foreignKeyConstraintsEnabled ? "1" : "0"));
                if (file != null) {
                    nativeExecutePragma(connectionPtr, "PRAGMA journal_mode=wal");
                }
                delegate.onConfigure(connection);


                if (targetVersion > 0 && currentVersion != targetVersion) {
                    try {
                        connection.beginTransactionExclusive();
                        if (currentVersion == 0) {
                            delegate.onCreate(connection);
                        } else {
                            if (targetVersion > currentVersion) {
                                delegate.onUpgrade(connection, currentVersion, targetVersion);
                            } else {
                                delegate.onDowngrade(connection, currentVersion, targetVersion);
                            }
                        }
                        nativeExecutePragma(connectionPtr, "PRAGMA user_version="+targetVersion);
                        connection.setTransactionSuccessful();
                    } finally {
                        connection.endTransaction();
                    }
                }
            } else {
                if (targetVersion > 0 && currentVersion != targetVersion) {
                    throw new SQLiteException("Can't upgrade read-only database from version " +
                            currentVersion + " to " + targetVersion + ": " + file);
                }
            }
            delegate.onOpen(connection);
        } catch (Throwable t) {
            try {
                connection.close();
            } catch (Throwable closeT) {
                t.addSuppressed(closeT);
            }
            throw t;
        }
        return connection;
    }


    /**
     * Attempts to release memory that SQLite holds but does not require to
     * operate properly. Typically, this memory will come from the page cache.
     *
     * @return the number of bytes actually released
     */
    public static int releaseMemory() {
        return nativeReleaseMemory();
    }

    public static final int SQLITE_OPEN_READONLY       = 0x00000001;
    public static final int SQLITE_OPEN_READWRITE      = 0x00000002;
    public static final int SQLITE_OPEN_CREATE         = 0x00000004;
    public static final int SQLITE_OPEN_MEMORY         = 0x00000080;
    public static final int SQLITE_OPEN_NOMUTEX        = 0x00008000;
    public static final int SQLITE_OPEN_FULLMUTEX      = 0x00010000;
    public static final int SQLITE_OPEN_NOFOLLOW       = 0x01000000;
}
