package com.darkyen.sqlite;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class DatabaseUsageTest {

    private File mDatabaseFile;
    private SQLiteConnection mDatabase;

    @Before
    public void setUp() {
        File dbDir = ApplicationProvider.getApplicationContext().getDir(this.getClass().getName(), Context.MODE_PRIVATE);
        mDatabaseFile = new File(dbDir, "database_test.db");
        SQLiteDatabase.deleteDatabase(mDatabaseFile);

        final SQLiteDelegate delegate = new SQLiteDelegate(mDatabaseFile) {
            @Override
            public void onCreate(SQLiteConnection db) {}
        };
        mDatabase = SQLiteConnection.open(delegate);
    }

    @After
    public void tearDown() {
        assertTrue(SQLiteConnection.releaseMemory() >= 0);
        mDatabase.close();
        SQLiteDatabase.deleteDatabase(mDatabaseFile);
    }

    @Test
    public void basic() {
        mDatabase.command("CREATE TABLE Testing (Key, Value)");
        try (SQLiteStatement statement = mDatabase.statement("INSERT INTO Testing (Key, Value) VALUES (?, ?)")) {
            statement.bind(1, "Foo");
            statement.bind(2, "Bar");
            assertEquals(1, statement.executeForRowID());
            statement.bind(1, "Foo2");
            assertEquals(2, statement.executeForRowID());
            statement.bind(1, "Number");
            statement.bind(2, 55);
            assertEquals(3, statement.executeForRowID());
        }

        try (SQLiteStatement statement = mDatabase.statement("SELECT Value FROM Testing WHERE Key = ?")) {
            statement.bind(1, "Foo");
            assertEquals("Bar", statement.executeForString());

            statement.bind(1, "Foo2");
            assertEquals("Bar", statement.executeForString());

            statement.bind(1, "Number");
            assertEquals("55", statement.executeForString());
            assertEquals(55, statement.executeForLong(-1));
        }
    }

    //nativeExecuteForLastInsertedRowIDAndReset
    //nativeExecuteForChangedRowsAndReset

    @Test
    public void executeForVoid() {
        mDatabase.command("CREATE TABLE Testing (Key, Value)");
        try (SQLiteStatement statement = mDatabase.statement("INSERT INTO Testing (Key, Value) VALUES (?, ?)")) {
            statement.bind(1, "Foo");
            statement.bind(2, "Bar");
            statement.executeForNothing();
        }
    }

    @Test
    public void executeForLong() {
        mDatabase.command("CREATE TABLE Testing (Key, Value)");
        try (SQLiteStatement statement = mDatabase.statement("INSERT INTO Testing (Key, Value) VALUES (?, ?)")) {
            statement.bind(1, 55L);
            statement.bind(2, Long.MAX_VALUE);
            statement.executeForNothing();
        }

        try (SQLiteStatement statement = mDatabase.statement("SELECT Value FROM Testing WHERE Key = ?")) {
            statement.bind(1, 55L);
            assertEquals(Long.MAX_VALUE, statement.executeForLong(-1));

            statement.bind(1, 66L);
            assertEquals(-1L, statement.executeForLong(-1));
        }
    }

    @Test
    public void executeForDouble() {
        mDatabase.command("CREATE TABLE Testing (Key, Value)");
        try (SQLiteStatement statement = mDatabase.statement("INSERT INTO Testing (Key, Value) VALUES (?, ?)")) {
            statement.bind(1, 55.5);
            statement.bind(2, 123.456);
            statement.executeForNothing();
        }

        try (SQLiteStatement statement = mDatabase.statement("SELECT Value FROM Testing WHERE Key = ?")) {
            statement.bind(1, 55.5);
            assertEquals(123.456, statement.executeForDouble(0.0), 0.00001);

            statement.bind(1, 55.55);
            assertEquals(66.0, statement.executeForDouble(66.0), 0);
        }
    }

    @Test
    public void executeForString() {
        mDatabase.command("CREATE TABLE Testing (Key, Value)");
        try (SQLiteStatement statement = mDatabase.statement("INSERT INTO Testing (Key, Value) VALUES (?, ?)")) {
            statement.bind(1, "55.5");
            statement.bind(2, "123.456");
            statement.executeForNothing();
        }

        try (SQLiteStatement statement = mDatabase.statement("SELECT Value FROM Testing WHERE Key = ?")) {
            statement.bind(1, "55.5");
            assertEquals("123.456", statement.executeForString());

            statement.bind(1, "66.6");
            assertNull(statement.executeForString());
        }
    }

    @Test
    public void executeForBlob() {
        mDatabase.command("CREATE TABLE Testing (Key, Value)");
        try (SQLiteStatement statement = mDatabase.statement("INSERT INTO Testing (Key, Value) VALUES (?, ?)")) {
            statement.bind(1, "55.5".getBytes(StandardCharsets.UTF_8));
            statement.bind(2, "123.456".getBytes(StandardCharsets.UTF_8));
            statement.executeForNothing();
        }

        try (SQLiteStatement statement = mDatabase.statement("SELECT Value FROM Testing WHERE Key = ?")) {
            statement.bind(1, "55.5".getBytes(StandardCharsets.UTF_8));
            assertArrayEquals("123.456".getBytes(StandardCharsets.UTF_8), statement.executeForBlob());

            statement.bind(1, "66.6".getBytes(StandardCharsets.UTF_8));
            assertNull(statement.executeForBlob());
        }
    }

    @Test
    public void executeForLastInsertedRowIDAndChangedRows() {
        mDatabase.command("CREATE TABLE Testing (Key, Value)");
        try (SQLiteStatement statement = mDatabase.statement("INSERT INTO Testing (Key, Value) VALUES (?, ?)")) {
            statement.bind(1, "Foo");
            statement.bind(2, "Bar");
            assertEquals(1L, statement.executeForRowID());
            assertEquals(2L, statement.executeForRowID());
            assertEquals(3L, statement.executeForRowID());
            assertEquals(4L, statement.executeForRowID());
        }

        try (SQLiteStatement statement = mDatabase.statement("DELETE FROM Testing WHERE ROWID = ?")) {
            statement.bind(1, 2L);
            assertEquals(1L, statement.executeForChangedRowCount());
        }

        try (SQLiteStatement statement = mDatabase.statement("INSERT INTO Testing (Key, Value) VALUES (?, ?)")) {
            statement.bind(1, "Foo");
            statement.bind(2, "Bar");
            assertEquals(5L, statement.executeForRowID());
        }

        try (SQLiteStatement statement = mDatabase.statement("DELETE FROM Testing WHERE ROWID = ?")) {
            statement.bind(1, 5L);
            assertEquals(1L, statement.executeForChangedRowCount());
        }

        try (SQLiteStatement statement = mDatabase.statement("INSERT INTO Testing (Key, Value) VALUES (?, ?)")) {
            statement.bind(1, "Foo");
            statement.bind(2, "Bar");
            assertEquals(5L, statement.executeForRowID());
        }

        try (SQLiteStatement statement = mDatabase.statement("DELETE FROM Testing WHERE Key = ?")) {
            statement.bind(1, "Foo");
            assertEquals(4L, statement.executeForChangedRowCount());
        }
    }

    @Test
    public void cursorTest() {
        mDatabase.command("CREATE TABLE Stuff (Thing, Junk)");
        try (SQLiteStatement s = mDatabase.statement("INSERT INTO Stuff (Thing, Junk) VALUES (?, ?)")) {
            s.bind(1, 6L);
            s.bind(2, 66L);
            assertEquals(1, s.executeForRowID());

            s.bind(1, 5.5);
            s.bind(2, 55.5);
            assertEquals(2, s.executeForRowID());

            s.bind(1, "A");
            s.bind(2, "BB");
            assertEquals(3, s.executeForRowID());

            s.bind(1, "C".getBytes(StandardCharsets.UTF_8));
            s.bind(2, "DD".getBytes(StandardCharsets.UTF_8));
            assertEquals(4, s.executeForRowID());

            s.bind(1, true);
            s.bind(2, false);
            assertEquals(5, s.executeForRowID());

            s.bindNull(1);
            s.bindNull(2);
            assertEquals(6, s.executeForRowID());

            s.bind(1, "A");
            s.bind(2, "BB");
            s.clearBindings();
            assertEquals(7, s.executeForRowID());
        }

        try (SQLiteStatement s = mDatabase.statement("SELECT Thing, Junk FROM Stuff ORDER BY ROWID")) {
            for (int repeat=0; repeat < 2; repeat++) {
                assertTrue("Long", s.cursorNextRow());
                assertEquals(6L, s.cursorGetLong(0));
                assertEquals(66L, s.cursorGetLong(1));

                assertTrue("Double", s.cursorNextRow());
                assertEquals(5.5, s.cursorGetDouble(0), 0.0);
                assertEquals(55.5, s.cursorGetDouble(1), 0.0);

                assertTrue("String", s.cursorNextRow());
                assertEquals("A", s.cursorGetString(0));
                assertEquals("BB", s.cursorGetString(1));

                assertTrue("Blob", s.cursorNextRow());
                assertArrayEquals("C".getBytes(StandardCharsets.UTF_8), s.cursorGetBlob(0));
                assertArrayEquals("DD".getBytes(StandardCharsets.UTF_8), s.cursorGetBlob(1));

                assertTrue("Boolean", s.cursorNextRow());
                assertTrue(s.cursorGetBoolean(0));
                assertFalse(s.cursorGetBoolean(1));

                assertTrue("Null 1", s.cursorNextRow());
                assertEquals(0L, s.cursorGetLong(0));
                assertEquals(0.0, s.cursorGetDouble(0), 0.0);
                assertNull(s.cursorGetString(0));
                assertNull(s.cursorGetBlob(0));
                assertEquals(0L, s.cursorGetLong(1));
                assertEquals(0.0, s.cursorGetDouble(1), 0.0);
                assertNull(s.cursorGetString(1));
                assertNull(s.cursorGetBlob(1));

                assertTrue("Null 2", s.cursorNextRow());
                assertEquals(0L, s.cursorGetLong(0));
                assertEquals(0.0, s.cursorGetDouble(0), 0.0);
                assertNull(s.cursorGetString(0));
                assertNull(s.cursorGetBlob(0));
                assertEquals(0L, s.cursorGetLong(1));
                assertEquals(0.0, s.cursorGetDouble(1), 0.0);
                assertNull(s.cursorGetString(1));
                assertNull(s.cursorGetBlob(1));

                assertFalse("End", s.cursorNextRow());// End
                assertFalse("End again", s.cursorNextRow());
                s.cursorReset(repeat == 0);
            }
        }
    }
}
