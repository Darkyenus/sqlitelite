package com.darkyen.sqlitelite;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.Suppress;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class ThreadingTest {

    private File mDatabaseFile;
    private SQLiteDelegate delegate;

    @Before
    public void setUp() {
        File dbDir = ApplicationProvider.getApplicationContext().getDir(this.getClass().getName(), Context.MODE_PRIVATE);
        mDatabaseFile = new File(dbDir, "database_test.db");
        SQLiteDatabase.deleteDatabase(mDatabaseFile);

        final SQLiteDelegate delegate = new SQLiteDelegate(mDatabaseFile) {
            @Override
            public void onCreate(SQLiteConnection db) {}
        };
        this.delegate = delegate;
    }

    @After
    public void tearDown() {
        assertTrue(SQLiteConnection.releaseMemory() >= 0);
        SQLiteDatabase.deleteDatabase(mDatabaseFile);
    }

    // Write threads continuously increment Counter value by one
    // Read threads continuously read and check that it increases monotonically
    // Rollback threads prepare transactions setting counter value to something stupid and roll it back

    private Thread createWriteThread(int thread, int increments) {
        return new Thread("write thread "+thread) {
            @Override
            public void run() {
                final Random random = new Random();
                int i = 0;
                try {
                    setupSemaphore.acquire();
                } catch (InterruptedException e) {
                    throw new AssertionError("write thread failed (i="+i+")", e);
                }
                try (SQLiteConnection connection = SQLiteConnection.open(delegate);
                     SQLiteStatement select = connection.statement("SELECT Value FROM Counter");
                     SQLiteStatement update = connection.statement("UPDATE Counter SET Value = ?")) {

                    connection.pragma("PRAGMA busy_timeout = 100000");
                    setupSemaphore.release();
                    startBarrier.await();

                    for (i = 0; i < increments; i++) {
                        Thread.sleep(random.nextInt(100));
                        try {
                            connection.beginTransactionImmediate();

                            final long currentCounter = select.executeForLong(-1);
                            Thread.sleep(1);
                            update.bind(1, currentCounter + 1);
                            assertEquals(1, update.executeForChangedRowCount());

                            connection.setTransactionSuccessful();
                        } finally {
                            connection.endTransaction();
                        }
                    }
                } catch (Throwable e) {
                    throw new AssertionError("write thread "+thread+" failed (i="+i+")", e);
                }
            }
        };
    }

    private Thread createReadThread(int thread) {
        return new Thread("read thread " + thread) {
            @Override
            public void run() {
                final Random random = new Random();
                long lastSeenValue = 0;
                try {
                    setupSemaphore.acquire();
                } catch (InterruptedException e) {
                    throw new AssertionError("read thread "+thread+" failed", e);
                }
                try (SQLiteConnection connection = SQLiteConnection.open(delegate);
                     SQLiteStatement select = connection.statement("SELECT Value FROM Counter")) {

                    connection.pragma("PRAGMA busy_timeout = 100000");
                    setupSemaphore.release();
                    startBarrier.await();

                    while (!Thread.interrupted()) {
                        Thread.sleep(random.nextInt(100));
                        final long currentCounter = select.executeForLong(-1);
                        if (currentCounter < lastSeenValue) {
                            fail(thread+" Last seen value = "+lastSeenValue+", currentCounter = "+currentCounter);
                        }
                        lastSeenValue = currentCounter;
                        System.out.println(thread+" last seen "+currentCounter);
                    }
                } catch (InterruptedException ignored) {
                } catch (BrokenBarrierException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }


    private Thread createRollbackThread() {
        return new Thread("rollback thread") {
            @Override
            public void run() {
                final Random random = new Random();
                int i = 0;
                try {
                    setupSemaphore.acquire();
                } catch (InterruptedException e) {
                    throw new AssertionError("rollback thread failed", e);
                }
                try (SQLiteConnection connection = SQLiteConnection.open(delegate);
                     SQLiteStatement update = connection.statement("UPDATE Counter SET Value = ?")) {

                    connection.pragma("PRAGMA busy_timeout = 100000");
                    setupSemaphore.release();
                    startBarrier.await();

                    while (!Thread.interrupted()) {
                        Thread.sleep(random.nextInt(100));
                        try {
                            connection.beginTransactionImmediate();

                            update.bind(1, -555);
                            assertEquals(1, update.executeForChangedRowCount());

                            Thread.sleep(random.nextInt(100));
                            // Do not call connection.setTransactionSuccessful(); for rollback
                        } finally {
                            connection.endTransaction();
                        }
                    }
                } catch (InterruptedException ignored) {
                } catch (Throwable e) {
                    throw new AssertionError("write thread failed (i="+i+")", e);
                }
            }
        };
    }

    private final Semaphore setupSemaphore = new Semaphore(1);
    private final int READERS = 2;
    private final int ROLLBACKERS = 2;
    private final int WRITERS = 2;
    private final CyclicBarrier startBarrier = new CyclicBarrier(
            READERS + ROLLBACKERS + WRITERS
    );

    //@Suppress
    @Test
    public void threadingStressTest() throws InterruptedException {
        try (SQLiteConnection connection = SQLiteConnection.open(delegate)) {
            // Create table
            connection.command("CREATE TABLE Counter (Value)");
            try (SQLiteStatement s = connection.statement("INSERT INTO Counter (Value) VALUES (?)")) {
                s.bind(1, 0);
                s.executeForNothing();
            }
        }

        final int WRITER_INCREMENTS = 100;
        final Thread[] readers = new Thread[READERS];
        final Thread[] rollbackers = new Thread[ROLLBACKERS];
        final Thread[] writers = new Thread[WRITERS];


        for (int i = 0; i < readers.length; i++) {
            (readers[i] = createReadThread(i)).start();
        }
        for (int i = 0; i < rollbackers.length; i++) {
            (rollbackers[i] = createRollbackThread()).start();
        }
        for (int i = 0; i < writers.length; i++) {
            (writers[i] = createWriteThread(i, WRITER_INCREMENTS)).start();
        }

        for (Thread writer : writers) {
            writer.join();
        }
        Thread.sleep(100);
        for (Thread rollbacker : rollbackers) {
            rollbacker.interrupt();
        }
        for (Thread reader : readers) {
            reader.interrupt();
            reader.join();
        }
        for (Thread rollbacker : rollbackers) {
            rollbacker.join();
        }

        try (SQLiteConnection connection = SQLiteConnection.open(delegate);
             SQLiteStatement select = connection.statement("SELECT Value FROM Counter")) {

            final long currentCounter = select.executeForLong(-1);
            assertEquals(writers.length * WRITER_INCREMENTS, currentCounter);
        }
    }

    @Test
    public void lockingTest() {
        SQLiteConnection conn1 = SQLiteConnection.open(delegate);
        SQLiteConnection conn2 = SQLiteConnection.open(delegate);
        conn1.close();
        conn2.close();
    }
}
