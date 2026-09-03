package com.example.myapplication;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LocalDatabaseTest {

    private SQLiteDatabase db;
    private static final String TABLE = "cortisol_readings";

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        // Open the app's real database directly
        db = context.openOrCreateDatabase("cortisol_monitor.db", Context.MODE_PRIVATE, null);
        // Ensure table exists
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE +
                " (id INTEGER PRIMARY KEY AUTOINCREMENT, patient_id TEXT, " +
                "cortisol_level REAL, timestamp INTEGER, is_dirty INTEGER DEFAULT 1)");
    }

    @After
    public void tearDown() {
        db.execSQL("DELETE FROM " + TABLE + " WHERE patient_id='test-patient'");
        db.close();
    }

    @Test
    public void insertReading_canBeRetrieved() {
        ContentValues v = new ContentValues();
        v.put("patient_id", "test-patient");
        v.put("cortisol_level", 18.5f);
        v.put("timestamp", System.currentTimeMillis());
        v.put("is_dirty", 1);
        db.insert(TABLE, null, v);

        Cursor c = db.rawQuery("SELECT * FROM " + TABLE + " WHERE patient_id=?", new String[]{"test-patient"});
        assertTrue(c.getCount() > 0);
        c.close();
    }

    @Test
    public void newReading_hasIsDirtyOne() {
        ContentValues v = new ContentValues();
        v.put("patient_id", "test-patient");
        v.put("cortisol_level", 12.0f);
        v.put("timestamp", System.currentTimeMillis());
        v.put("is_dirty", 1);
        db.insert(TABLE, null, v);

        Cursor c = db.rawQuery("SELECT * FROM " + TABLE + " WHERE patient_id=? AND is_dirty=1", new String[]{"test-patient"});
        assertTrue(c.getCount() > 0);
        c.close();
    }

    @Test
    public void updateIsDirty_setsToZero() {
        ContentValues v = new ContentValues();
        v.put("patient_id", "test-patient");
        v.put("cortisol_level", 22.3f);
        v.put("timestamp", System.currentTimeMillis());
        v.put("is_dirty", 1);
        long id = db.insert(TABLE, null, v);

        ContentValues upd = new ContentValues();
        upd.put("is_dirty", 0);
        db.update(TABLE, upd, "id=?", new String[]{String.valueOf(id)});

        Cursor c = db.rawQuery("SELECT is_dirty FROM " + TABLE + " WHERE id=?", new String[]{String.valueOf(id)});
        c.moveToFirst();
        assertEquals(0, c.getInt(0));
        c.close();
    }

    @Test
    public void emptyQuery_returnsZeroRows() {
        Cursor c = db.rawQuery("SELECT * FROM " + TABLE + " WHERE patient_id=?", new String[]{"nonexistent"});
        assertEquals(0, c.getCount());
        c.close();
    }
}