package com.darkyen.sqlite;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.requery.android.database.sqlite.SQLiteCursor;
import io.requery.android.database.sqlite.SQLitePreparedStatement;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Random;
import java.util.function.IntConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class DatabaseBenchmarkTest {

    private SQLiteDatabase mDatabaseAndroid;
    private io.requery.android.database.sqlite.SQLiteDatabase mDatabaseRequery;
    private SQLiteConnection mDatabaseLight;

    @Before
    public void setUp() {
        mDatabaseAndroid = SQLiteDatabase.create(null);
        mDatabaseRequery = io.requery.android.database.sqlite.SQLiteDatabase.create();
        mDatabaseLight = SQLiteConnection.open(new SQLiteDelegate(null) {
            @Override
            public void onCreate(SQLiteConnection db) {}
        });

        // Make sure all databases have the same settings.
        // We are not benchmarking SQLite but only the bindings
        mDatabaseAndroid.rawQuery("PRAGMA foreign_keys=0", null).close();
        mDatabaseRequery.rawQuery("PRAGMA foreign_keys=0", null).close();
        mDatabaseLight.command("PRAGMA foreign_keys=0");

        mDatabaseAndroid.rawQuery("PRAGMA journal_mode=OFF", null).close();
        mDatabaseRequery.rawQuery("PRAGMA journal_mode=OFF", null).close();
        mDatabaseLight.command("PRAGMA journal_mode=OFF");

        mDatabaseAndroid.rawQuery("PRAGMA locking_mode=NORMAL", null).close();
        mDatabaseRequery.rawQuery("PRAGMA locking_mode=NORMAL", null).close();
        mDatabaseLight.command("PRAGMA locking_mode=NORMAL");

        mDatabaseAndroid.rawQuery("PRAGMA synchronous=0", null).close();
        mDatabaseRequery.rawQuery("PRAGMA synchronous=0", null).close();
        mDatabaseLight.command("PRAGMA synchronous=0");
    }

    @Test
    public void writeBenchmark() {
        final int roundCycles = 10_000;

        final double android = measureThroughput(roundCycles, () -> {
            mDatabaseAndroid.execSQL("CREATE TABLE Benchmark (Cycle, Entry)");
        }, () -> {
            mDatabaseAndroid.execSQL("DROP TABLE Benchmark");
        }, (cycle) -> {
            mDatabaseAndroid.beginTransaction();
            try (SQLiteStatement statement = mDatabaseAndroid.compileStatement("INSERT INTO Benchmark (Cycle, Entry) VALUES (?, ?)")) {
                for (int i = 0; i < 10; i++) {
                    statement.bindLong(1, cycle);
                    statement.bindLong(2, i);
                    statement.executeInsert();
                }
                mDatabaseAndroid.setTransactionSuccessful();
            } finally {
                mDatabaseAndroid.endTransaction();
            }
        });

        final double requery = measureThroughput(roundCycles, () -> {
            mDatabaseRequery.execSQL("CREATE TABLE Benchmark (Cycle, Entry)");
        }, () -> {
            mDatabaseRequery.execSQL("DROP TABLE Benchmark");
        }, (cycle) -> {
            mDatabaseRequery.beginTransactionExclusive();
            try (SQLitePreparedStatement statement = mDatabaseRequery.compileStatement("INSERT INTO Benchmark (Cycle, Entry) VALUES (?, ?)")) {
                for (int i = 0; i < 10; i++) {
                    statement.bindLong(1, cycle);
                    statement.bindLong(2, i);
                    statement.executeInsert();
                }
                mDatabaseRequery.setTransactionSuccessful();
            } finally {
                mDatabaseRequery.endTransaction();
            }
        });


        final double light = measureThroughput(roundCycles, () -> {
            mDatabaseLight.command("CREATE TABLE Benchmark (Cycle, Entry)");
        }, () -> {
            mDatabaseLight.command("DROP TABLE Benchmark");
        }, (cycle) -> {
            mDatabaseLight.beginTransactionExclusive();
            try (com.darkyen.sqlite.SQLiteStatement statement = mDatabaseLight.statement("INSERT INTO Benchmark (Cycle, Entry) VALUES (?, ?)")) {
                for (int i = 0; i < 10; i++) {
                    statement.bind(1, cycle);
                    statement.bind(2, i);
                    statement.executeForNothing();
                }
                mDatabaseLight.setTransactionSuccessful();
            } finally {
                mDatabaseLight.endTransaction();
            }
        });

        System.out.println("WRITE BENCHMARK RESULTS");
        System.out.printf("%10s: %10.2f transactions/second%n", "Android", android);
        System.out.printf("%10s: %10.2f transactions/second%n", "Requery", requery);
        System.out.printf("%10s: %10.2f transactions/second%n", "Light", light);
        /*
            Android:    3346.13 transactions/second
            Requery:    8771.93 transactions/second
              Light:   40679.90 transactions/second
         */
    }


    @Test
    public void readBigBenchmark() {
        final int roundCycles = 2;
        final int entries = 100_000;
        final byte[] blob = new byte[800];
        new Random().nextBytes(blob);

        System.out.println("BENCHMARK PROGRESS ANDROID BEGIN");
        mDatabaseAndroid.execSQL("CREATE TABLE Benchmark (Entry1, Entry2, Entry3)");
        mDatabaseAndroid.beginTransaction();
        try (SQLiteStatement statement = mDatabaseAndroid.compileStatement("INSERT INTO Benchmark (Entry1, Entry2, Entry3) VALUES (?, ?, ?)")) {
            for (int i = 0; i < entries; i++) {
                statement.bindLong(1, i);
                statement.bindString(2, "RESOLUTION"+i);
                statement.bindBlob(3, blob);
                assertEquals(i + 1L, statement.executeInsert());
            }
            mDatabaseAndroid.setTransactionSuccessful();
        } finally {
            mDatabaseAndroid.endTransaction();
        }
        System.out.println("BENCHMARK PROGRESS ANDROID START");
        final double android = measureThroughput(roundCycles, () -> {}, () -> {}, (cycle) -> {
            try (Cursor cursor = mDatabaseAndroid.rawQuery("SELECT Entry1, Entry2, Entry3 FROM Benchmark ORDER BY ROWID", null)) {
                int i = 0;
                assertTrue(cursor.moveToFirst());
                do {
                    final long entry1 = cursor.getLong(0);
                    final String entry2 = cursor.getString(1);
                    final byte[] entry3 = cursor.getBlob(2);
                    Assert.assertEquals(i, entry1);
                    assertTrue(entry2.startsWith("RESOLUTION"));
                    Assert.assertEquals(blob.length, entry3.length);
                    Assert.assertEquals(blob[2], entry3[2]);
                    i++;
                } while (cursor.moveToNext());
                Assert.assertEquals(i, entries);
            }
        });
        System.out.println("BENCHMARK PROGRESS ANDROID STOP");
        mDatabaseAndroid.execSQL("DROP TABLE Benchmark");
        System.out.println("BENCHMARK PROGRESS ANDROID DONE");



        System.out.println("BENCHMARK PROGRESS REQUERY BEGIN");
        mDatabaseRequery.execSQL("CREATE TABLE Benchmark (Entry1, Entry2, Entry3)");
        mDatabaseRequery.beginTransactionExclusive();
        try (SQLitePreparedStatement statement = mDatabaseRequery.compileStatement("INSERT INTO Benchmark (Entry1, Entry2, Entry3) VALUES (?, ?, ?)")) {
            for (int i = 0; i < entries; i++) {
                statement.bindLong(1, i);
                statement.bindString(2, "RESOLUTION"+i);
                statement.bindBlob(3, blob);
                assertEquals(i + 1L, statement.executeInsert());
            }
            mDatabaseRequery.setTransactionSuccessful();
        } finally {
            mDatabaseRequery.endTransaction();
        }
        System.out.println("BENCHMARK PROGRESS REQUERY START");
        final double requery = measureThroughput(roundCycles, () -> {}, () -> {}, (cycle) -> {
            try (SQLiteCursor cursor = mDatabaseRequery.rawQuery("SELECT Entry1, Entry2, Entry3 FROM Benchmark ORDER BY ROWID", null)) {
                int i = 0;
                assertTrue(cursor.moveToFirst());
                do {
                    final long entry1 = cursor.getLong(0);
                    final String entry2 = cursor.getString(1);
                    final byte[] entry3 = cursor.getBlob(2);
                    Assert.assertEquals(i, entry1);
                    assertTrue(entry2.startsWith("RESOLUTION"));
                    Assert.assertEquals(blob.length, entry3.length);
                    Assert.assertEquals(blob[2], entry3[2]);
                    i++;
                } while (cursor.moveToNext());
                Assert.assertEquals(i, entries);
            }
        });
        System.out.println("BENCHMARK PROGRESS REQUERY STOP");
        mDatabaseRequery.execSQL("DROP TABLE Benchmark");
        System.out.println("BENCHMARK PROGRESS REQUERY DONE");



        System.out.println("BENCHMARK PROGRESS LIGHT BEGIN");
        mDatabaseLight.command("CREATE TABLE Benchmark (Entry1, Entry2, Entry3)");
        mDatabaseLight.beginTransactionExclusive();
        try (com.darkyen.sqlite.SQLiteStatement statement = mDatabaseLight.statement("INSERT INTO Benchmark (Entry1, Entry2, Entry3) VALUES (?, ?, ?)")) {
            for (int i = 0; i < entries; i++) {
                statement.bind(1, i);
                statement.bind(2, "RESOLUTION"+i);
                statement.bind(3, blob);
                assertEquals(i + 1L, statement.executeForRowID());
            }
            mDatabaseLight.setTransactionSuccessful();
        } finally {
            mDatabaseLight.endTransaction();
        }
        System.out.println("BENCHMARK PROGRESS LIGHT START");
        final double light = measureThroughput(roundCycles, () -> {}, () -> {}, (cycle) -> {
            try (com.darkyen.sqlite.SQLiteStatement cursor = mDatabaseLight.statement("SELECT Entry1, Entry2, Entry3 FROM Benchmark ORDER BY ROWID")) {
                int i = 0;
                while (cursor.cursorNextRow()) {
                    final long entry1 = cursor.cursorGetLong(0);
                    final String entry2 = cursor.cursorGetString(1);
                    final byte[] entry3 = cursor.cursorGetBlob(2);
                    Assert.assertEquals(i, entry1);
                    //noinspection DataFlowIssue
                    assertTrue(entry2.startsWith("RESOLUTION"));
                    //noinspection DataFlowIssue
                    Assert.assertEquals(blob.length, entry3.length);
                    Assert.assertEquals(blob[2], entry3[2]);
                    i++;
                }
                Assert.assertEquals(i, entries);
            }
        });
        System.out.println("BENCHMARK PROGRESS LIGHT STOP");
        mDatabaseLight.command("DROP TABLE Benchmark");
        System.out.println("BENCHMARK PROGRESS LIGHT DONE");

        System.out.println("READ BIG BENCHMARK RESULTS");
        System.out.printf("%10s: %10.2f reads/second%n", "Android", android);
        System.out.printf("%10s: %10.2f reads/second%n", "Requery", requery);
        System.out.printf("%10s: %10.2f reads/second%n", "Light", light);
        /*
            Android:       0.46 reads/second
            Requery:       0.50 reads/second
              Light:       2.30 reads/second
         */
    }


    @Test
    public void readSmallBenchmark() {
        final int roundCycles = 2;
        final int entries = 100_000;
        final byte[] blob = new byte[3];
        new Random().nextBytes(blob);

        System.out.println("BENCHMARK PROGRESS ANDROID BEGIN");
        mDatabaseAndroid.execSQL("CREATE TABLE Benchmark (Entry1, Entry2, Entry3)");
        mDatabaseAndroid.beginTransaction();
        try (SQLiteStatement statement = mDatabaseAndroid.compileStatement("INSERT INTO Benchmark (Entry1, Entry2, Entry3) VALUES (?, ?, ?)")) {
            for (int i = 0; i < entries; i++) {
                statement.bindLong(1, i);
                statement.bindString(2, "RESOLUTION"+i);
                statement.bindBlob(3, blob);
                assertEquals(i + 1L, statement.executeInsert());
            }
            mDatabaseAndroid.setTransactionSuccessful();
        } finally {
            mDatabaseAndroid.endTransaction();
        }
        System.out.println("BENCHMARK PROGRESS ANDROID START");
        final double android = measureThroughput(roundCycles, () -> {}, () -> {}, (cycle) -> {
            try (Cursor cursor = mDatabaseAndroid.rawQuery("SELECT Entry1, Entry2, Entry3 FROM Benchmark ORDER BY ROWID", null)) {
                int i = 0;
                assertTrue(cursor.moveToFirst());
                do {
                    final long entry1 = cursor.getLong(0);
                    final String entry2 = cursor.getString(1);
                    final byte[] entry3 = cursor.getBlob(2);
                    Assert.assertEquals(i, entry1);
                    assertTrue(entry2.startsWith("RESOLUTION"));
                    Assert.assertEquals(blob.length, entry3.length);
                    Assert.assertEquals(blob[2], entry3[2]);
                    i++;
                } while (cursor.moveToNext());
                Assert.assertEquals(i, entries);
            }
        });
        System.out.println("BENCHMARK PROGRESS ANDROID STOP");
        mDatabaseAndroid.execSQL("DROP TABLE Benchmark");
        System.out.println("BENCHMARK PROGRESS ANDROID DONE");



        System.out.println("BENCHMARK PROGRESS REQUERY BEGIN");
        mDatabaseRequery.execSQL("CREATE TABLE Benchmark (Entry1, Entry2, Entry3)");
        mDatabaseRequery.beginTransactionExclusive();
        try (SQLitePreparedStatement statement = mDatabaseRequery.compileStatement("INSERT INTO Benchmark (Entry1, Entry2, Entry3) VALUES (?, ?, ?)")) {
            for (int i = 0; i < entries; i++) {
                statement.bindLong(1, i);
                statement.bindString(2, "RESOLUTION"+i);
                statement.bindBlob(3, blob);
                assertEquals(i + 1L, statement.executeInsert());
            }
            mDatabaseRequery.setTransactionSuccessful();
        } finally {
            mDatabaseRequery.endTransaction();
        }
        System.out.println("BENCHMARK PROGRESS REQUERY START");
        final double requery = measureThroughput(roundCycles, () -> {}, () -> {}, (cycle) -> {
            try (SQLiteCursor cursor = mDatabaseRequery.rawQuery("SELECT Entry1, Entry2, Entry3 FROM Benchmark ORDER BY ROWID", null)) {
                int i = 0;
                assertTrue(cursor.moveToFirst());
                do {
                    final long entry1 = cursor.getLong(0);
                    final String entry2 = cursor.getString(1);
                    final byte[] entry3 = cursor.getBlob(2);
                    Assert.assertEquals(i, entry1);
                    assertTrue(entry2.startsWith("RESOLUTION"));
                    Assert.assertEquals(blob.length, entry3.length);
                    Assert.assertEquals(blob[2], entry3[2]);
                    i++;
                } while (cursor.moveToNext());
                Assert.assertEquals(i, entries);
            }
        });
        System.out.println("BENCHMARK PROGRESS REQUERY STOP");
        mDatabaseRequery.execSQL("DROP TABLE Benchmark");
        System.out.println("BENCHMARK PROGRESS REQUERY DONE");



        System.out.println("BENCHMARK PROGRESS LIGHT BEGIN");
        mDatabaseLight.command("CREATE TABLE Benchmark (Entry1, Entry2, Entry3)");
        mDatabaseLight.beginTransactionExclusive();
        try (com.darkyen.sqlite.SQLiteStatement statement = mDatabaseLight.statement("INSERT INTO Benchmark (Entry1, Entry2, Entry3) VALUES (?, ?, ?)")) {
            for (int i = 0; i < entries; i++) {
                statement.bind(1, i);
                statement.bind(2, "RESOLUTION"+i);
                statement.bind(3, blob);
                assertEquals(i + 1L, statement.executeForRowID());
            }
            mDatabaseLight.setTransactionSuccessful();
        } finally {
            mDatabaseLight.endTransaction();
        }
        System.out.println("BENCHMARK PROGRESS LIGHT START");
        final double light = measureThroughput(roundCycles, () -> {}, () -> {}, (cycle) -> {
            try (com.darkyen.sqlite.SQLiteStatement cursor = mDatabaseLight.statement("SELECT Entry1, Entry2, Entry3 FROM Benchmark ORDER BY ROWID")) {
                int i = 0;
                while (cursor.cursorNextRow()) {
                    final long entry1 = cursor.cursorGetLong(0);
                    final String entry2 = cursor.cursorGetString(1);
                    final byte[] entry3 = cursor.cursorGetBlob(2);
                    Assert.assertEquals(i, entry1);
                    //noinspection DataFlowIssue
                    assertTrue(entry2.startsWith("RESOLUTION"));
                    //noinspection DataFlowIssue
                    Assert.assertEquals(blob.length, entry3.length);
                    Assert.assertEquals(blob[2], entry3[2]);
                    i++;
                }
                Assert.assertEquals(i, entries);
            }
        });
        System.out.println("BENCHMARK PROGRESS LIGHT STOP");
        mDatabaseLight.command("DROP TABLE Benchmark");
        System.out.println("BENCHMARK PROGRESS LIGHT DONE");

        System.out.println("READ SMALL BENCHMARK RESULTS");
        System.out.printf("%10s: %10.2f reads/second%n", "Android", android);
        System.out.printf("%10s: %10.2f reads/second%n", "Requery", requery);
        System.out.printf("%10s: %10.2f reads/second%n", "Light", light);
        /*
            Android:       2.00 reads/second
            Requery:       0.99 reads/second
              Light:       3.11 reads/second
         */
    }

    /**
     * Run operation many times to measure how fast it is.
     * @return how many times per second operation can run
     */
    private static double measureThroughput(int roundCycles, Runnable setup, Runnable reset, IntConsumer operation) {
        final int rounds = 15;
        long bestDurationNs = Long.MAX_VALUE;

        // Measured
        for (int round = 0; round < rounds; round++) {
            setup.run();
            final long start = System.nanoTime();
            for (int i = 0; i < roundCycles; i++) {
                operation.accept(i);
            }
            final long durationRound = System.nanoTime() - start;
            reset.run();

            if (durationRound < bestDurationNs) {
                bestDurationNs = durationRound;
            }
        }
        final double durationSec = bestDurationNs / 1_000_000_000.0;
        return roundCycles / durationSec;
    }

    @After
    public void tearDown() {
        mDatabaseAndroid.close();
        mDatabaseRequery.close();
        mDatabaseLight.close();
    }
}
