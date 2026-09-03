package com.example.myapplication;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stand-alone local store for raw samples streamed from the Galaxy Watch8 (EDA, heart
 * rate + IBI, skin temperature, accelerometer) via {@link WatchDataListenerService}.
 *
 * Kept in its own database file (separate from {@link DatabaseHelper}) so this new
 * pipeline can't touch the existing schema/migrations used by the rest of the app.
 *
 * Every row carries session_id (one value per Start tap on the watch, see
 * VitalsStreamingService.kt) and participant_email (the phone's currently logged-in user,
 * via {@link DatabaseHelper#getCurrentUserEmail()}), so multiple recordings — or multiple
 * participants sharing the same phone during development — can be told apart later.
 */
public class VitalsDatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "watch_vitals.db";
    private static final int DB_VERSION = 2;

    public static final String TABLE_EDA = "eda_samples";
    public static final String TABLE_HR = "hr_samples";
    public static final String TABLE_SKIN_TEMP = "skin_temp_samples";
    public static final String TABLE_ACCEL = "accel_samples";

    // Generic vital-type identifiers shared by the day-wise browsing screens
    // (VitalsDayListActivity / VitalsDayDetailActivity) so they don't need one code path
    // per signal.
    public static final String VITAL_TYPE_HR = "hr";
    public static final String VITAL_TYPE_EDA = "eda";
    public static final String VITAL_TYPE_SKIN_TEMP = "skin_temp";

    private static volatile VitalsDatabaseHelper instance;

    public static synchronized VitalsDatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new VitalsDatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    private VitalsDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_EDA + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "session_id TEXT, " +
                "participant_email TEXT, " +
                "timestamp_ms INTEGER NOT NULL, " +
                "conductance REAL NOT NULL, " +
                "status INTEGER, " +
                "received_at INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE " + TABLE_HR + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "session_id TEXT, " +
                "participant_email TEXT, " +
                "timestamp_ms INTEGER NOT NULL, " +
                "heart_rate INTEGER, " +
                "hr_status INTEGER, " +
                "ibi_list TEXT, " +
                "ibi_status_list TEXT, " +
                "received_at INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE " + TABLE_SKIN_TEMP + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "session_id TEXT, " +
                "participant_email TEXT, " +
                "timestamp_ms INTEGER NOT NULL, " +
                "ambient_c REAL, " +
                "skin_c REAL, " +
                "status INTEGER, " +
                "received_at INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE " + TABLE_ACCEL + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "session_id TEXT, " +
                "participant_email TEXT, " +
                "timestamp_ms INTEGER NOT NULL, " +
                "x REAL, " +
                "y REAL, " +
                "z REAL, " +
                "status INTEGER, " +
                "received_at INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Pre-production schema — no real data worth migrating yet, so just rebuild clean.
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_EDA);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_HR);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_SKIN_TEMP);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_ACCEL);
        onCreate(db);
    }

    public void insertEda(String sessionId, String participantEmail, long timestampMs, double conductance, int status) {
        ContentValues cv = new ContentValues();
        cv.put("session_id", sessionId);
        cv.put("participant_email", participantEmail);
        cv.put("timestamp_ms", timestampMs);
        cv.put("conductance", conductance);
        cv.put("status", status);
        cv.put("received_at", System.currentTimeMillis());
        getWritableDatabase().insert(TABLE_EDA, null, cv);
    }

    public void insertHeartRate(String sessionId, String participantEmail, long timestampMs, int heartRate,
                                 int hrStatus, String ibiListJson, String ibiStatusListJson) {
        ContentValues cv = new ContentValues();
        cv.put("session_id", sessionId);
        cv.put("participant_email", participantEmail);
        cv.put("timestamp_ms", timestampMs);
        cv.put("heart_rate", heartRate);
        cv.put("hr_status", hrStatus);
        cv.put("ibi_list", ibiListJson);
        cv.put("ibi_status_list", ibiStatusListJson);
        cv.put("received_at", System.currentTimeMillis());
        getWritableDatabase().insert(TABLE_HR, null, cv);
    }

    public void insertSkinTemp(String sessionId, String participantEmail, long timestampMs,
                                double ambientC, double skinC, int status) {
        ContentValues cv = new ContentValues();
        cv.put("session_id", sessionId);
        cv.put("participant_email", participantEmail);
        cv.put("timestamp_ms", timestampMs);
        cv.put("ambient_c", ambientC);
        cv.put("skin_c", skinC);
        cv.put("status", status);
        cv.put("received_at", System.currentTimeMillis());
        getWritableDatabase().insert(TABLE_SKIN_TEMP, null, cv);
    }

    public void insertAccel(String sessionId, String participantEmail, long timestampMs,
                             double x, double y, double z, int status) {
        ContentValues cv = new ContentValues();
        cv.put("session_id", sessionId);
        cv.put("participant_email", participantEmail);
        cv.put("timestamp_ms", timestampMs);
        cv.put("x", x);
        cv.put("y", y);
        cv.put("z", z);
        cv.put("status", status);
        cv.put("received_at", System.currentTimeMillis());
        getWritableDatabase().insert(TABLE_ACCEL, null, cv);
    }

    /** One past watch-streaming session, summarized across all four signal tables. */
    public static class SessionSummary {
        public final String sessionId;
        public final long startMs;
        public final long endMs;
        public final int sampleCount;

        public SessionSummary(String sessionId, long startMs, long endMs, int sampleCount) {
            this.sessionId = sessionId;
            this.startMs = startMs;
            this.endMs = endMs;
            this.sampleCount = sampleCount;
        }
    }

    /** Every distinct recording session (one per Start/Stop cycle) for one participant,
     *  newest first, with the earliest/latest sample timestamp across EDA + HR + skin temp +
     *  accelerometer combined and a total sample count. Used by the vitals session history
     *  screen so past recordings can be browsed and exported individually instead of only
     *  ever exporting the full history at once. */
    public List<SessionSummary> getDistinctSessions(String participantEmail) {
        List<SessionSummary> sessions = new ArrayList<>();
        String sql = "SELECT session_id, MIN(timestamp_ms) AS start_ms, MAX(timestamp_ms) AS end_ms, " +
                "COUNT(*) AS sample_count FROM (" +
                "  SELECT session_id, timestamp_ms FROM " + TABLE_EDA + " WHERE participant_email = ?" +
                "  UNION ALL" +
                "  SELECT session_id, timestamp_ms FROM " + TABLE_HR + " WHERE participant_email = ?" +
                "  UNION ALL" +
                "  SELECT session_id, timestamp_ms FROM " + TABLE_SKIN_TEMP + " WHERE participant_email = ?" +
                "  UNION ALL" +
                "  SELECT session_id, timestamp_ms FROM " + TABLE_ACCEL + " WHERE participant_email = ?" +
                ") combined " +
                "GROUP BY session_id " +
                "ORDER BY start_ms DESC";
        try (Cursor c = getReadableDatabase().rawQuery(sql,
                new String[]{participantEmail, participantEmail, participantEmail, participantEmail})) {
            while (c.moveToNext()) {
                sessions.add(new SessionSummary(
                        c.getString(0), c.getLong(1), c.getLong(2), c.getInt(3)));
            }
        }
        return sessions;
    }

    /** Per-signal sample counts for one session — used on the vitals session detail screen. */
    public static class SessionSignalCounts {
        public final int edaCount;
        public final int hrCount;
        public final int skinTempCount;
        public final int accelCount;

        public SessionSignalCounts(int edaCount, int hrCount, int skinTempCount, int accelCount) {
            this.edaCount = edaCount;
            this.hrCount = hrCount;
            this.skinTempCount = skinTempCount;
            this.accelCount = accelCount;
        }
    }

    public SessionSignalCounts getSessionSignalCounts(String sessionId) {
        return new SessionSignalCounts(
                countRowsForSession(TABLE_EDA, sessionId),
                countRowsForSession(TABLE_HR, sessionId),
                countRowsForSession(TABLE_SKIN_TEMP, sessionId),
                countRowsForSession(TABLE_ACCEL, sessionId));
    }

    private int countRowsForSession(String table, String sessionId) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + table + " WHERE session_id = ?", new String[]{sessionId})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Wipes all stored samples — call after a successful CSV export to start the next
     *  recording session with a clean slate. */
    public void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_EDA, null, null);
        db.delete(TABLE_HR, null, null);
        db.delete(TABLE_SKIN_TEMP, null, null);
        db.delete(TABLE_ACCEL, null, null);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Day-wise browsing — Live Vitals screen → tap Heart Rate/EDA/Skin Temp → day list →
    // day graph (+ HRV summary for heart rate). Generic across the three vital types so
    // VitalsDayListActivity / VitalsDayDetailActivity only need one code path.
    // ═══════════════════════════════════════════════════════════════════════

    /** One (timestamp, value) sample of whichever vital was requested. */
    public static class VitalPoint {
        public final long timestampMs;
        public final double value;

        public VitalPoint(long timestampMs, double value) {
            this.timestampMs = timestampMs;
            this.value = value;
        }
    }

    /** One calendar day's summary for one vital — sample count plus avg/min/max — used by the
     *  day-list screen. dayStartMs/dayEndMs bound the device's local calendar day. */
    public static class DaySummary {
        public final long dayStartMs;
        public final long dayEndMs;
        public final int sampleCount;
        public final double avgValue;
        public final double minValue;
        public final double maxValue;

        public DaySummary(long dayStartMs, long dayEndMs, int sampleCount,
                           double avgValue, double minValue, double maxValue) {
            this.dayStartMs = dayStartMs;
            this.dayEndMs = dayEndMs;
            this.sampleCount = sampleCount;
            this.avgValue = avgValue;
            this.minValue = minValue;
            this.maxValue = maxValue;
        }
    }

    /** Loads every (timestamp, value) sample of the given vital for one participant, ordered
     *  oldest-first, across ALL of their recordings (not scoped to one session_id) — the raw
     *  series both day-bucketing and day-detail graphing build on. HR values are filtered to
     *  hr_status == 1 (a valid reading, per Watch8_Data_Collection_Guide.md §B5); EDA/skin
     *  temp have no comparable "valid" flag applied here, matching how the CSV export already
     *  treats them. */
    public List<VitalPoint> getVitalSeries(String participantEmail, String vitalType) {
        List<VitalPoint> points = new ArrayList<>();
        String table;
        String valueColumn;
        String where = "participant_email = ?";
        if (VITAL_TYPE_HR.equals(vitalType)) {
            table = TABLE_HR;
            valueColumn = "heart_rate";
            where += " AND hr_status = 1";
        } else if (VITAL_TYPE_EDA.equals(vitalType)) {
            table = TABLE_EDA;
            valueColumn = "conductance";
        } else if (VITAL_TYPE_SKIN_TEMP.equals(vitalType)) {
            table = TABLE_SKIN_TEMP;
            valueColumn = "skin_c";
        } else {
            throw new IllegalArgumentException("Unknown vitalType: " + vitalType);
        }
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT timestamp_ms, " + valueColumn + " FROM " + table +
                        " WHERE " + where + " ORDER BY timestamp_ms ASC",
                new String[]{participantEmail})) {
            while (c.moveToNext()) {
                points.add(new VitalPoint(c.getLong(0), c.getDouble(1)));
            }
        }
        return points;
    }

    /** Groups {@link #getVitalSeries} into local calendar days, newest first, with per-day
     *  sample count and avg/min/max — used by the day-list screen. Bucketing happens in Java
     *  (not SQL) so it can use the device's actual local calendar-day boundaries via
     *  {@link Calendar}, rather than fighting SQLite's UTC-based date() function. */
    public List<DaySummary> getDaySummaries(String participantEmail, String vitalType) {
        List<VitalPoint> points = getVitalSeries(participantEmail, vitalType);
        LinkedHashMap<Long, List<Double>> byDayStart = new LinkedHashMap<>();
        Calendar cal = Calendar.getInstance();

        for (VitalPoint p : points) {
            cal.setTimeInMillis(p.timestampMs);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long dayStart = cal.getTimeInMillis();
            List<Double> bucket = byDayStart.get(dayStart);
            if (bucket == null) {
                bucket = new ArrayList<>();
                byDayStart.put(dayStart, bucket);
            }
            bucket.add(p.value);
        }

        List<DaySummary> summaries = new ArrayList<>();
        for (Map.Entry<Long, List<Double>> entry : byDayStart.entrySet()) {
            long dayStart = entry.getKey();
            List<Double> values = entry.getValue();
            double sum = 0;
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (double v : values) {
                sum += v;
                if (v < min) min = v;
                if (v > max) max = v;
            }
            summaries.add(new DaySummary(dayStart, dayStart + 86_400_000L - 1,
                    values.size(), sum / values.size(), min, max));
        }
        Collections.reverse(summaries); // newest day first
        return summaries;
    }

    /** The subset of {@link #getVitalSeries} falling within [startMsInclusive, endMsInclusive]
     *  — used by the day-detail graph screen. */
    public List<VitalPoint> getVitalSeriesForRange(String participantEmail, String vitalType,
                                                    long startMsInclusive, long endMsInclusive) {
        List<VitalPoint> inRange = new ArrayList<>();
        for (VitalPoint p : getVitalSeries(participantEmail, vitalType)) {
            if (p.timestampMs >= startMsInclusive && p.timestampMs <= endMsInclusive) {
                inRange.add(p);
            }
        }
        return inRange;
    }

    /** Every valid IBI value (ibiStatus == 0, per Watch8_Data_Collection_Guide.md §B5) for one
     *  participant within [startMsInclusive, endMsInclusive], in chronological order — the
     *  series {@link HrvCalculator#compute} runs on. Values are concatenated in HR-sample
     *  timestamp order, then in within-sample list order (see HrvCalculator's class doc for
     *  the caveat this implies for very short windows). */
    public List<Integer> getValidIbiSeriesForRange(String participantEmail,
                                                    long startMsInclusive, long endMsInclusive) {
        List<Integer> ibiValues = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                TABLE_HR,
                new String[]{"timestamp_ms", "ibi_list", "ibi_status_list"},
                "participant_email = ? AND timestamp_ms >= ? AND timestamp_ms <= ?",
                new String[]{participantEmail, String.valueOf(startMsInclusive), String.valueOf(endMsInclusive)},
                null, null, "timestamp_ms ASC")) {
            while (c.moveToNext()) {
                String ibiJson = c.getString(1);
                String statusJson = c.getString(2);
                if (ibiJson == null || ibiJson.isEmpty()) continue;
                try {
                    JSONArray ibiArr = new JSONArray(ibiJson);
                    JSONArray statusArr = (statusJson != null && !statusJson.isEmpty())
                            ? new JSONArray(statusJson) : null;
                    for (int i = 0; i < ibiArr.length(); i++) {
                        int status = (statusArr != null && i < statusArr.length())
                                ? statusArr.optInt(i, -1) : -1;
                        if (status == 0) {
                            int ibi = ibiArr.optInt(i, -1);
                            if (ibi > 0) ibiValues.add(ibi);
                        }
                    }
                } catch (JSONException e) {
                    // Malformed row (shouldn't happen — written by WatchDataListenerService
                    // itself) — skip it rather than fail the whole query.
                }
            }
        }
        return ibiValues;
    }
}
