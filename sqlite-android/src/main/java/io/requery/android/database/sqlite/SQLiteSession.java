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
import android.database.sqlite.SQLiteException;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import org.jetbrains.annotations.NotNull;

/**
 * Provides a single client the ability to use a database.
 *
 * <h2>About database sessions</h2>
 * <p>
 * Database access is always performed using a session.  The session
 * manages the lifecycle of transactions and database connections.
 * </p><p>
 * Sessions can be used to perform both read-only and read-write operations.
 * There is some advantage to knowing when a session is being used for
 * read-only purposes because the connection pool can optimize the use
 * of the available connections to permit multiple read-only operations
 * to execute in parallel whereas read-write operations may need to be serialized.
 * </p><p>
 * When <em>Write Ahead Logging (WAL)</em> is enabled, the database can
 * execute simultaneous read-only and read-write transactions, provided that
 * at most one read-write transaction is performed at a time.  When WAL is not
 * enabled, read-only transactions can execute in parallel but read-write
 * transactions are mutually exclusive.
 * </p>
 *
 * <h2>Ownership and concurrency guarantees</h2>
 * <p>
 * Session objects are not thread-safe.  In fact, session objects are thread-bound.
 * The {@link SQLiteDatabase} uses a thread-local variable to associate a session
 * with each thread for the use of that thread alone.  Consequently, each thread
 * has its own session object and therefore its own transaction state independent
 * of other threads.
 * </p><p>
 * A thread has at most one session per database.  This constraint ensures that
 * a thread can never use more than one database connection at a time for a
 * given database.  As the number of available database connections is limited,
 * if a single thread tried to acquire multiple connections for the same database
 * at the same time, it might deadlock.  Therefore we allow there to be only
 * one session (so, at most one connection) per thread per database.
 * </p>
 *
 * <h2>Transactions</h2>
 * <p>
 * There are two kinds of transaction: implicit transactions and explicit
 * transactions.
 * </p><p>
 * An implicit transaction is created whenever a database operation is requested
 * and there is no explicit transaction currently in progress.  An implicit transaction
 * only lasts for the duration of the database operation in question and then it
 * is ended.  If the database operation was successful, then its changes are committed.
 * </p><p>
 * An explicit transaction is started by calling {@link #beginTransaction} and
 * specifying the desired transaction mode.  Once an explicit transaction has begun,
 * all subsequent database operations will be performed as part of that transaction.
 * To end an explicit transaction, first call {@link #setTransactionSuccessful} if the
 * transaction was successful, then call {@link #endTransaction}.  If the transaction was
 * marked successful, its changes will be committed, otherwise they will be rolled back.
 * </p><p>
 * Explicit transactions can also be nested.  A nested explicit transaction is
 * started with {@link #beginTransaction}, marked successful with
 * {@link #setTransactionSuccessful}and ended with {@link #endTransaction}.
 * If any nested transaction is not marked successful, then the entire transaction
 * including all of its nested transactions will be rolled back
 * when the outermost transaction is ended.
 * </p><p>
 * Recommended usage:
 * <code><pre>
 * // First, begin the transaction.
 * session.beginTransaction(SQLiteSession.TRANSACTION_MODE_DEFERRED, 0);
 * try {
 *     // Then do stuff...
 *     session.execute("INSERT INTO ...", null, 0);
 *
 *     // As the very last step before ending the transaction, mark it successful.
 *     session.setTransactionSuccessful();
 * } finally {
 *     // Finally, end the transaction.
 *     // This statement will commit the transaction if it was marked successful or
 *     // roll it back otherwise.
 *     session.endTransaction();
 * }
 * </pre></code>
 * </p>
 *
 * <h2>Database connections</h2>
 * <p>
 * A {@link SQLiteDatabase} can have multiple active sessions at the same
 * time.  Each session acquires and releases connections to the database
 * as needed to perform each requested database transaction.  If all connections
 * are in use, then database transactions on some sessions will block until a
 * connection becomes available.
 * </p><p>
 * The session acquires a single database connection only for the duration
 * of a single (implicit or explicit) database transaction, then releases it.
 * This characteristic allows a small pool of database connections to be shared
 * efficiently by multiple sessions as long as they are not all trying to perform
 * database transactions at the same time.
 * </p>
 *
 * <h2>Responsiveness</h2>
 * <p>
 * Another important consideration is that transactions that take too long to
 * run may cause the application UI to become unresponsive.  Even if the transaction
 * is executed in a background thread, the user will get bored and
 * frustrated if the application shows no data for several seconds while
 * a transaction runs.
 * </p><p>
 * Guidelines:
 * <ul>
 * <li>Do not perform database transactions on the UI thread.</li>
 * <li>Keep database transactions as short as possible.</li>
 * <li>Simple queries often run faster than complex queries.</li>
 * <li>Measure the performance of your database transactions.</li>
 * <li>Consider what will happen when the size of the data set grows.
 * A query that works well on 100 rows may struggle with 10,000.</li>
 * </ul>
 *
 * <h2>Reentrance</h2>
 * <p>
 * This class must tolerate reentrant execution of SQLite operations because
 * triggers may call custom SQLite functions that perform additional queries.
 * </p>
 *
 * @hide
 */
@SuppressWarnings({"unused", "JavaDoc"})
@SuppressLint("Assert")
public final class SQLiteSession {

    final SQLiteConnection mConnection;
    private boolean inTransaction = false;
    private boolean transactionSuccessful = false;

    /**
     * Transaction mode: Deferred.
     * <p>
     * In a deferred transaction, no locks are acquired on the database
     * until the first operation is performed.  If the first operation is
     * read-only, then a <code>SHARED</code> lock is acquired, otherwise
     * a <code>RESERVED</code> lock is acquired.
     * </p><p>
     * While holding a <code>SHARED</code> lock, this session is only allowed to
     * read but other sessions are allowed to read or write.
     * While holding a <code>RESERVED</code> lock, this session is allowed to read
     * or write but other sessions are only allowed to read.
     * </p><p>
     * Because the lock is only acquired when needed in a deferred transaction,
     * it is possible for another session to write to the database first before
     * this session has a chance to do anything.
     * </p><p>
     * Corresponds to the SQLite <code>BEGIN DEFERRED</code> transaction mode.
     * </p>
     */
    public static final int TRANSACTION_MODE_DEFERRED = 0;

    /**
     * Transaction mode: Immediate.
     * <p>
     * When an immediate transaction begins, the session acquires a
     * <code>RESERVED</code> lock.
     * </p><p>
     * While holding a <code>RESERVED</code> lock, this session is allowed to read
     * or write but other sessions are only allowed to read.
     * </p><p>
     * Corresponds to the SQLite <code>BEGIN IMMEDIATE</code> transaction mode.
     * </p>
     */
    public static final int TRANSACTION_MODE_IMMEDIATE = 1;

    /**
     * Transaction mode: Exclusive.
     * <p>
     * When an exclusive transaction begins, the session acquires an
     * <code>EXCLUSIVE</code> lock.
     * </p><p>
     * While holding an <code>EXCLUSIVE</code> lock, this session is allowed to read
     * or write but no other sessions are allowed to access the database.
     * </p><p>
     * Corresponds to the SQLite <code>BEGIN EXCLUSIVE</code> transaction mode.
     * </p>
     */
    public static final int TRANSACTION_MODE_EXCLUSIVE = 2;

    /**
     * Creates a session bound to the specified connection pool.
     */
    public SQLiteSession(@NotNull SQLiteDatabase database) {
        mConnection = SQLiteConnection.open(database, database.mConfigurationLocked);
    }

    /**
     * Returns true if the session has a transaction in progress.
     *
     * @return True if the session has a transaction in progress.
     */
    public boolean hasTransaction() {
        return inTransaction;
    }

    /**
     * Begins a transaction.
     * <p>
     * Transactions may nest.  If the transaction is not in progress,
     * then a database connection is obtained and a new transaction is started.
     * Otherwise, a nested transaction is started.
     * </p><p>
     * Each call to {@link #beginTransaction} must be matched exactly by a call
     * to {@link #endTransaction}.  To mark a transaction as successful,
     * call {@link #setTransactionSuccessful} before calling {@link #endTransaction}.
     * If the transaction is not successful, or if any of its nested
     * transactions were not successful, then the entire transaction will
     * be rolled back when the outermost transaction is ended.
     * </p>
     *
     * @param transactionMode The transaction mode.  One of: {@link #TRANSACTION_MODE_DEFERRED},
     * {@link #TRANSACTION_MODE_IMMEDIATE}, or {@link #TRANSACTION_MODE_EXCLUSIVE}.
     * Ignored when creating a nested transaction.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     *
     * @throws IllegalStateException if {@link #setTransactionSuccessful} has already been
     * called for the current transaction.
     * @throws SQLiteException if an error occurs.
     * @throws OperationCanceledException if the operation was canceled.
     *
     * @see #setTransactionSuccessful
     * @see #endTransaction
     */
    public void beginTransaction(int transactionMode, CancellationSignal cancellationSignal) {
        if (inTransaction) {
            throw new IllegalStateException("Can't begin nested transaction");
        }
        if (cancellationSignal != null) {
            cancellationSignal.throwIfCanceled();
        }

        // Execute SQL might throw a runtime exception.
        switch (transactionMode) {
            case TRANSACTION_MODE_IMMEDIATE:
                mConnection.execute("BEGIN IMMEDIATE;", null, cancellationSignal); // might throw
                break;
            case TRANSACTION_MODE_EXCLUSIVE:
                mConnection.execute("BEGIN EXCLUSIVE;", null, cancellationSignal); // might throw
                break;
            default:
                mConnection.execute("BEGIN;", null, cancellationSignal); // might throw
                break;
        }

        inTransaction = true;
        transactionSuccessful = false;
    }

    /**
     * Marks the current transaction as having completed successfully.
     * <p>
     * This method can be called at most once between {@link #beginTransaction} and
     * {@link #endTransaction} to indicate that the changes made by the transaction should be
     * committed.  If this method is not called, the changes will be rolled back
     * when the transaction is ended.
     * </p>
     *
     * @throws IllegalStateException if there is no current transaction, or if
     * {@link #setTransactionSuccessful} has already been called for the current transaction.
     *
     * @see #beginTransaction
     * @see #endTransaction
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
     * Ends the current transaction and commits or rolls back changes.
     * <p>
     * If this is the outermost transaction (not nested within any other
     * transaction), then the changes are committed if {@link #setTransactionSuccessful}
     * was called or rolled back otherwise.
     * </p><p>
     * This method must be called exactly once for each call to {@link #beginTransaction}.
     * </p>
     *
     * @throws IllegalStateException if there is no current transaction.
     * @throws SQLiteException if an error occurs.
     * @throws OperationCanceledException if the operation was canceled.
     *
     * @see #beginTransaction
     * @see #setTransactionSuccessful
     */
    public void endTransaction() {
        if (!inTransaction) {
            throw new IllegalStateException("No transaction in progress to end");
        }

        inTransaction = false;
        if (transactionSuccessful) {
            mConnection.execute("COMMIT;", null, null); // might throw
        } else {
            mConnection.execute("ROLLBACK;", null, null); // might throw
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
        mConnection.execute(sql, bindArgs, cancellationSignal); // might throw
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
    public long executeForLong(String sql, Object[] bindArgs, CancellationSignal cancellationSignal) {
        return mConnection.executeForLong(sql, bindArgs, cancellationSignal); // might throw
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
    public String executeForString(String sql, Object[] bindArgs, CancellationSignal cancellationSignal) {
        return mConnection.executeForString(sql, bindArgs, cancellationSignal); // might throw
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
    public int executeForChangedRowCount(String sql, Object[] bindArgs, CancellationSignal cancellationSignal) {
        return mConnection.executeForChangedRowCount(sql, bindArgs, cancellationSignal); // might throw
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
    public long executeForLastInsertedRowId(String sql, Object[] bindArgs, CancellationSignal cancellationSignal) {
        return mConnection.executeForLastInsertedRowId(sql, bindArgs, cancellationSignal); // might throw
    }

    public void close() {
        mConnection.close();
    }
}
