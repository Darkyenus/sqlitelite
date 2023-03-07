/*
 * Copyright (C) 2007 The Android Open Source Project
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

package io.requery.android.database;

import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;
import io.requery.android.database.sqlite.SQLiteCursor;
import io.requery.android.database.sqlite.SQLiteDatabase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class DatabaseLocaleTest {

    private SQLiteDatabase mDatabase;

    private static final String[] STRINGS = {
        "côté",
        "cote",
        "côte",
        "coté",
        "boy",
        "dog",
        "COTE",
    };

    @Before
    public void setUp() {
        mDatabase = SQLiteDatabase.create();
        mDatabase.execSQL(
                "CREATE TABLE test (id INTEGER PRIMARY KEY, data TEXT);");
    }

    private void insertStrings() {
        for (String s : STRINGS) {
            mDatabase.execSQL("INSERT INTO test (data) VALUES('" + s + "');");
        }
    }

    @After
    public void tearDown() {
        mDatabase.close();
    }

    private String[] query(String sql) {
        Log.i("LocaleTest", "Querying: " + sql);
        SQLiteCursor c = mDatabase.rawQuery(sql, null);
        Assert.assertNotNull(c);
        ArrayList<String> items = new ArrayList<>();
        while (c.moveToNext()) {
            items.add(c.getString(0));
            Log.i("LocaleTest", "...." + c.getString(0));
        }
        String[] result = items.toArray(new String[0]);
        assertEquals(STRINGS.length, result.length);
        c.close();
        return result;
    }

    @MediumTest
    @Test
    public void testLocaleInsertOrder() {
        insertStrings();
        String[] results = query("SELECT data FROM test");
        assertArrayEquals(STRINGS, results);
    }

    @SmallTest
    @Test
    public void testHoge() {
        SQLiteCursor cursor = null;
        try {
            String expectedString = new String(new int[] {0xFE000}, 0, 1);
            mDatabase.execSQL("INSERT INTO test(id, data) VALUES(1, '" + expectedString + "')");
            cursor = mDatabase.rawQuery("SELECT data FROM test WHERE id = 1", null);
            
            assertNotNull(cursor);
            assertTrue(cursor.moveToFirst());
            String actualString = cursor.getString(0);
            assertEquals(expectedString.length(), actualString.length());
            for (int i = 0; i < expectedString.length(); i++) {
                assertEquals((int)expectedString.charAt(i), (int)actualString.charAt(i));
            }
            assertEquals(expectedString, actualString);
        } finally {
            if (cursor != null) cursor.close();
        }
    }
}
