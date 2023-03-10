package com.darkyen.sqlitelite;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class DatabaseMigrationTest {

    private File mDatabaseFile;

    @Before
    public void setUp() {
        File dbDir = ApplicationProvider.getApplicationContext().getDir(this.getClass().getName(), Context.MODE_PRIVATE);
        mDatabaseFile = new File(dbDir, "database_test.db");
        SQLiteDatabase.deleteDatabase(mDatabaseFile);
    }

    @After
    public void tearDown() {
        SQLiteDatabase.deleteDatabase(mDatabaseFile);
    }

    @Test
    public void basicOpen() {
        int[] stage = {0};
        final SQLiteDelegate delegate = new SQLiteDelegate(mDatabaseFile) {
            @Override
            public void onConfigure(SQLiteConnection db) {
                Assert.assertEquals(0, stage[0]);
                stage[0] = 1;
            }

            @Override
            public void onUpgrade(SQLiteConnection db, int oldVersion, int newVersion) {
                fail("Not expected onUpgade("+oldVersion+", "+newVersion+")");
            }

            @Override
            public void onDowngrade(SQLiteConnection db, int oldVersion, int newVersion) {
                fail("Not expected onDowngrade("+oldVersion+", "+newVersion+")");
            }

            @Override
            public void onOpen(SQLiteConnection db) {
                Assert.assertEquals(1, stage[0]);
                stage[0] = 2;
            }

            @Override
            public void onCorruption(SQLiteConnection dbObj) {
                fail("Not expected onCorruption()");
            }

            @Override
            public void onCreate(SQLiteConnection db) {
                fail("Not expected onCreate()");
            }
        };
        try (SQLiteConnection connection = SQLiteConnection.open(delegate)) {
            Assert.assertEquals(2, stage[0]);
        }
    }


    @Test
    public void versionedOpen() {
        int[] stage = {0};
        final SQLiteDelegate delegate = new SQLiteDelegate(mDatabaseFile) {
            {
                version = 1;
            }
            @Override
            public void onConfigure(SQLiteConnection db) {
                Assert.assertEquals(0, stage[0]);
                stage[0] = 1;
            }

            @Override
            public void onUpgrade(SQLiteConnection db, int oldVersion, int newVersion) {
                fail("Not expected onUpgade("+oldVersion+", "+newVersion+")");
            }

            @Override
            public void onDowngrade(SQLiteConnection db, int oldVersion, int newVersion) {
                fail("Not expected onDowngrade("+oldVersion+", "+newVersion+")");
            }

            @Override
            public void onOpen(SQLiteConnection db) {
                Assert.assertEquals(2, stage[0]);
                stage[0] = 3;
            }

            @Override
            public void onCorruption(SQLiteConnection dbObj) {
                fail("Not expected onCorruption()");
            }

            @Override
            public void onCreate(SQLiteConnection db) {
                Assert.assertEquals(1, stage[0]);
                stage[0] = 2;
            }
        };
        try (SQLiteConnection connection = SQLiteConnection.open(delegate)) {
            Assert.assertEquals(3, stage[0]);
        }
    }


    @Test
    public void versionedOpenUpgradeDowngrade() {
        int[] stage = {0};
        // First open
        final SQLiteDelegate firstDelegate = new SQLiteDelegate(mDatabaseFile) {
            {
                version = 1;
            }
            @Override
            public void onConfigure(SQLiteConnection db) {
                Assert.assertEquals(0, stage[0]);
                stage[0] = 1;
                System.out.println("Stage: "+stage[0]);
            }

            @Override
            public void onUpgrade(SQLiteConnection db, int oldVersion, int newVersion) {
                fail("Not expected onUpgade("+oldVersion+", "+newVersion+")");
            }

            @Override
            public void onDowngrade(SQLiteConnection db, int oldVersion, int newVersion) {
                fail("Not expected onDowngrade("+oldVersion+", "+newVersion+")");
            }

            @Override
            public void onOpen(SQLiteConnection db) {
                Assert.assertEquals(2, stage[0]);
                stage[0] = 3;
                System.out.println("Stage: "+stage[0]);
            }

            @Override
            public void onCorruption(SQLiteConnection dbObj) {
                fail("Not expected onCorruption()");
            }

            @Override
            public void onCreate(SQLiteConnection db) {
                Assert.assertEquals(1, stage[0]);
                stage[0] = 2;
                System.out.println("Stage: "+stage[0]);

                db.command("CREATE TABLE Settings (Key, Value)");
                try (SQLiteStatement s = db.statement("INSERT INTO Settings (Key, Value) VALUES (?, ?)")) {
                    s.bind(1, "MyKey");
                    s.bind(2, "MyValue");
                    assertEquals(1, s.executeForChangedRowCount());
                }
            }
        };
        try (SQLiteConnection connection = SQLiteConnection.open(firstDelegate)) {
            Assert.assertEquals(3, stage[0]);
            stage[0] = 4;
            System.out.println("Stage: "+stage[0]);
        }

        // Upgrade to version 1 to 5
        final SQLiteDelegate secondDelegate = new SQLiteDelegate(mDatabaseFile) {
            {
                version = 5;
            }
            @Override
            public void onConfigure(SQLiteConnection db) {
                Assert.assertEquals(4, stage[0]);
                stage[0] = 5;
                System.out.println("Stage: "+stage[0]);
            }

            @Override
            public void onUpgrade(SQLiteConnection db, int oldVersion, int newVersion) {
                Assert.assertEquals("oldVersion", 1, oldVersion);
                Assert.assertEquals("newVersion", 5, newVersion);
                Assert.assertEquals(5, stage[0]);
                stage[0] = 6;
                System.out.println("Stage: "+stage[0]);

                db.command("ALTER TABLE Settings ADD COLUMN Timestamp");
                try (SQLiteStatement s = db.statement("UPDATE Settings SET Timestamp = ?002 WHERE Key = ?001")) {
                    s.bind(1, "MyKey");
                    s.bind(2, 555L);
                    assertEquals(1, s.executeForChangedRowCount());
                }
            }

            @Override
            public void onDowngrade(SQLiteConnection db, int oldVersion, int newVersion) {
                fail("Not expected onDowngrade("+oldVersion+", "+newVersion+")");
            }

            @Override
            public void onOpen(SQLiteConnection db) {
                Assert.assertEquals(6, stage[0]);
                stage[0] = 7;
                System.out.println("Stage: "+stage[0]);
            }

            @Override
            public void onCorruption(SQLiteConnection dbObj) {
                fail("Not expected onCorruption()");
            }

            @Override
            public void onCreate(SQLiteConnection db) {
                fail("Not expected onCreate()");
            }
        };
        try (SQLiteConnection connection = SQLiteConnection.open(secondDelegate)) {
            Assert.assertEquals(7, stage[0]);
            stage[0] = 8;
            System.out.println("Stage: "+stage[0]);
        }

        // Downgrade version from 5 to 4
        final SQLiteDelegate downgradeDelegate = new SQLiteDelegate(mDatabaseFile) {
            {
                version = 4;
            }
            @Override
            public void onConfigure(SQLiteConnection db) {
                Assert.assertEquals(8, stage[0]);
                stage[0] = 9;
                System.out.println("Stage: "+stage[0]);
            }

            @Override
            public void onUpgrade(SQLiteConnection db, int oldVersion, int newVersion) {
                fail("Not expected onUpgade("+oldVersion+", "+newVersion+")");
            }

            @Override
            public void onDowngrade(SQLiteConnection db, int oldVersion, int newVersion) {
                Assert.assertEquals("oldVersion", 5, oldVersion);
                Assert.assertEquals("newVersion", 4, newVersion);
                Assert.assertEquals(9, stage[0]);
                stage[0] = 10;
                System.out.println("Stage: "+stage[0]);

                db.command("ALTER TABLE Settings ADD COLUMN TimestampLegacy");
                try (SQLiteStatement s = db.statement("UPDATE Settings SET TimestampLegacy = Timestamp")) {
                    assertEquals(1, s.executeForChangedRowCount());
                }
                db.command("ALTER TABLE Settings DROP COLUMN Timestamp");
            }

            @Override
            public void onOpen(SQLiteConnection db) {
                Assert.assertEquals(10, stage[0]);
                stage[0] = 11;
                System.out.println("Stage: "+stage[0]);
            }

            @Override
            public void onCorruption(SQLiteConnection dbObj) {
                fail("Not expected onCorruption()");
            }

            @Override
            public void onCreate(SQLiteConnection db) {
                fail("Not expected onCreate()");
            }
        };
        try (SQLiteConnection db = SQLiteConnection.open(downgradeDelegate)) {
            Assert.assertEquals(11, stage[0]);
            stage[0] = 12;
            System.out.println("Stage: "+stage[0]);

            try (SQLiteStatement s = db.statement("SELECT Value FROM Settings WHERE Key = ?")) {
                s.bind(1, "MyKey");
                assertEquals("MyValue", s.executeForString());
            }
            try (SQLiteStatement s = db.statement("SELECT TimestampLegacy FROM Settings WHERE Key = ?")) {
                s.bind(1, "MyKey");
                assertEquals(555L, s.executeForLong(-1));
            }
        }
    }
}
