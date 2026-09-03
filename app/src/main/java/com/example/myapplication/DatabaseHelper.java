package com.example.myapplication;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import android.content.ContentValues;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Collections;
import java.util.Arrays;
import java.util.HashMap;
import java.lang.StringBuilder;

import com.example.myapplication.data.QuestionnaireDao;
import com.example.myapplication.data.QuestionnaireResponse;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String TAG = "DatabaseHelper";
    private static final String DATABASE_NAME = "AppData.db"; // Renamed to reflect its purpose
    private static final int DATABASE_VERSION = 6; // v6: added session_id to questionnaire_responses
    private final Context context;

    // Store current user email (kept for questionnaire and biomarker association)
    private static String currentUserEmail = "";

    // Constants for biomarker tables
    public static final String TABLE_BIOMARKER_EXPERIMENTS = "biomarker_experiments";
    public static final String TABLE_BIOMARKER_DATA = "biomarker_data";

    // Biomarker experiment table columns
    public static final String COLUMN_ID = "id";
    public static final String COLUMN_USERNAME = "username";
    public static final String COLUMN_SOURCE = "source";
    public static final String COLUMN_TIMESTAMP = "timestamp";
    public static final String COLUMN_SAMPLING_RATE = "sampling_rate";
    public static final String COLUMN_MOVING_AVG = "moving_avg";
    public static final String COLUMN_DURATION = "duration";
    public static final String COLUMN_MAX_VALUE = "max_value";
    public static final String COLUMN_DATA_SIZE = "data_size";

    // Biomarker data table columns
    public static final String COLUMN_EXPERIMENT_ID = "experiment_id";
    public static final String COLUMN_DATA_POINT = "data_point";
    public static final String COLUMN_TIME_SEC = "time_sec";
    public static final String COLUMN_PORT0 = "port0";
    public static final String COLUMN_PORT1 = "port1";
    public static final String COLUMN_PORT2 = "port2";
    public static final String COLUMN_AVG = "avg";
    public static final String COLUMN_TUBE_ID = "tube_id";
    public static final String COLUMN_CARTRIDGE_ID = "cartridge_id";


    // Create table statements for biomarker data
    private static final String CREATE_TABLE_BIOMARKER_EXPERIMENTS = "CREATE TABLE " + TABLE_BIOMARKER_EXPERIMENTS + "("
            + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
            + COLUMN_USERNAME + " TEXT,"
            + COLUMN_SOURCE + " TEXT,"
            + COLUMN_TIMESTAMP + " INTEGER,"
            + COLUMN_SAMPLING_RATE + " INTEGER,"
            + COLUMN_MOVING_AVG + " INTEGER,"
            + COLUMN_DURATION + " REAL,"
            + COLUMN_MAX_VALUE + " REAL,"
            + COLUMN_DATA_SIZE + " INTEGER,"
            + COLUMN_TUBE_ID + " TEXT,"
            + COLUMN_CARTRIDGE_ID + " TEXT,"
            + "cloud_id TEXT,"
            + "updated_at TEXT,"
            + "dirty INTEGER DEFAULT 1"
            + ")";

    private static final String CREATE_TABLE_BIOMARKER_DATA = "CREATE TABLE " + TABLE_BIOMARKER_DATA + "("
            + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
            + COLUMN_EXPERIMENT_ID + " INTEGER,"
            + COLUMN_DATA_POINT + " INTEGER,"
            + COLUMN_TIME_SEC + " REAL,"
            + COLUMN_PORT0 + " REAL,"
            + COLUMN_PORT1 + " REAL,"
            + COLUMN_PORT2 + " REAL,"
            + COLUMN_AVG + " REAL,"
            + "FOREIGN KEY(" + COLUMN_EXPERIMENT_ID + ") REFERENCES " + TABLE_BIOMARKER_EXPERIMENTS + "(" + COLUMN_ID + ")"
            + ")";

    // Singleton instance
    private static DatabaseHelper instance;

    // Get singleton instance (matching pattern used in UserDatabaseHelper)
    public static synchronized DatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new DatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    // Private constructor for singleton pattern
    private DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        this.context = context.getApplicationContext();
        Log.d(TAG, "Constructor called. Database path: " + context.getDatabasePath(DATABASE_NAME).getAbsolutePath());
    }

    // Method to set current user email (used by UserDatabaseHelper)
    public static void setCurrentUserEmail(String email) {
        currentUserEmail = email;
        Log.d(TAG, "Current user email set (" + (email == null || email.isEmpty() ? "cleared" : "set") + ")");
    }

    // Method to get current user email
    public static String getCurrentUserEmail() {
        return currentUserEmail;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        try {
            // Add questionnaire_responses table
            db.execSQL("CREATE TABLE IF NOT EXISTS questionnaire_responses (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "section TEXT," +
                    "question_text TEXT," +
                    "answer INTEGER," +
                    "timestamp INTEGER," +
                    "questionnaire_type TEXT," +
                    "user_email TEXT," +
                    "session_id TEXT," +
                    "cloud_id TEXT," +
                    "updated_at TEXT," +
                    "dirty INTEGER DEFAULT 1)");

            // Create biomarker tables
            db.execSQL(CREATE_TABLE_BIOMARKER_EXPERIMENTS);
            db.execSQL(CREATE_TABLE_BIOMARKER_DATA);

            // Create questionnaire summary table
            db.execSQL("CREATE TABLE IF NOT EXISTS questionnaire_summaries (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "user_email TEXT UNIQUE," +
                    "score_a INTEGER DEFAULT 0," +
                    "score_b INTEGER DEFAULT 0," +
                    "score_c INTEGER DEFAULT 0," +
                    "score_d INTEGER DEFAULT 0," +
                    "score_e INTEGER DEFAULT 0," +
                    "score_f INTEGER DEFAULT 0," +
                    "total_score INTEGER DEFAULT 0," +
                    "phq9_score INTEGER DEFAULT -1," +
                    "bdi_score INTEGER DEFAULT -1," +
                    "gad7_score INTEGER DEFAULT -1," +
                    "brs_score REAL DEFAULT -1," +
                    "phq9_filled INTEGER DEFAULT 0," +
                    "bdi_filled INTEGER DEFAULT 0," +
                    "gad7_filled INTEGER DEFAULT 0," +
                    "brs_filled INTEGER DEFAULT 0," +
                    "phq9_triggered INTEGER DEFAULT 0," +
                    "bdi_triggered INTEGER DEFAULT 0," +
                    "gad7_triggered INTEGER DEFAULT 0," +
                    "brs_triggered INTEGER DEFAULT 0," +
                    "cloud_id TEXT," +
                    "updated_at TEXT," +
                    "dirty INTEGER DEFAULT 1" +
                    ")");

            Log.d(TAG, "Database tables created successfully");

        } catch (Exception e) {
            Log.e(TAG, "Error creating database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.w(TAG, "Upgrading database from version " + oldVersion + " to " + newVersion);

        if (oldVersion < 3) {
            // Add sync columns to biomarkerexperiments
            db.execSQL("ALTER TABLE " + TABLE_BIOMARKER_EXPERIMENTS + " ADD COLUMN cloud_id TEXT");
            db.execSQL("ALTER TABLE " + TABLE_BIOMARKER_EXPERIMENTS + " ADD COLUMN updated_at TEXT");
            db.execSQL("ALTER TABLE " + TABLE_BIOMARKER_EXPERIMENTS + " ADD COLUMN dirty INTEGER DEFAULT 1");

            // Add sync columns to questionnaireresponses
            db.execSQL("ALTER TABLE questionnaire_responses ADD COLUMN cloud_id TEXT");
            db.execSQL("ALTER TABLE questionnaire_responses ADD COLUMN updated_at TEXT");
            db.execSQL("ALTER TABLE questionnaire_responses ADD COLUMN dirty INTEGER DEFAULT 1");

            // Add sync columns to questionnairesummaries
            db.execSQL("ALTER TABLE questionnaire_summaries ADD COLUMN cloud_id TEXT");
            db.execSQL("ALTER TABLE questionnaire_summaries ADD COLUMN updated_at TEXT");
            db.execSQL("ALTER TABLE questionnaire_summaries ADD COLUMN dirty INTEGER DEFAULT 1");

            // Mark all existing rows dirty with current timestamp
            String now = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    .format(new java.util.Date());

            db.execSQL("UPDATE " + TABLE_BIOMARKER_EXPERIMENTS + " SET updated_at = '" + now + "', dirty = 1");
            db.execSQL("UPDATE questionnaire_responses SET updated_at = '" + now + "', dirty = 1");
            db.execSQL("UPDATE questionnaire_summaries SET updated_at = '" + now + "', dirty = 1");
        }
        if (oldVersion < 4) {
            // Fresh installs already have these columns from onCreate
            // This handles users upgrading from version 3
            try {
                db.execSQL("ALTER TABLE " + TABLE_BIOMARKER_EXPERIMENTS + " ADD COLUMN cloud_id TEXT");
                db.execSQL("ALTER TABLE " + TABLE_BIOMARKER_EXPERIMENTS + " ADD COLUMN updated_at TEXT");
                db.execSQL("ALTER TABLE " + TABLE_BIOMARKER_EXPERIMENTS + " ADD COLUMN dirty INTEGER DEFAULT 1");
            } catch (Exception ignored) {} // columns may already exist from v3 upgrade

            String now = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    .format(new java.util.Date());
            db.execSQL("UPDATE " + TABLE_BIOMARKER_EXPERIMENTS +
                    " SET updated_at = '" + now + "', dirty = 1 WHERE dirty IS NULL");
        }
        if (oldVersion < 5) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_BIOMARKER_EXPERIMENTS +
                        " ADD COLUMN " + COLUMN_TUBE_ID + " TEXT");
            } catch (Exception ignored) {}
            try {
                db.execSQL("ALTER TABLE " + TABLE_BIOMARKER_EXPERIMENTS +
                        " ADD COLUMN " + COLUMN_CARTRIDGE_ID + " TEXT");
            } catch (Exception ignored) {}
        }
        if (oldVersion < 6) {
            // Add session_id column for grouping responses by assessment run
            try {
                db.execSQL("ALTER TABLE questionnaire_responses ADD COLUMN session_id TEXT");
            } catch (Exception ignored) {}
        }
    }


    private void checkDatabase() {
        SQLiteDatabase db = getReadableDatabase();

        // Check if questionnaire_responses table exists
        try (Cursor tableCheckCursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='questionnaire_responses'", null)) {
            boolean tableExists = tableCheckCursor != null && tableCheckCursor.moveToFirst();
            Log.d(TAG, "questionnaire_responses table exists: " + tableExists);
        } catch (Exception e) {
            Log.e(TAG, "Error checking questionnaire_responses table: " + e.getMessage());
        }

        // Log all tables
        try (Cursor tablesListCursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)) {
            StringBuilder tables = new StringBuilder("Tables: ");
            if (tablesListCursor != null && tablesListCursor.moveToFirst()) {
                do {
                    tables.append(tablesListCursor.getString(0)).append(", ");
                } while (tablesListCursor.moveToNext());
            }
            Log.d(TAG, tables.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error listing tables: " + e.getMessage());
        }

        // Check number of entries in questionnaire_responses
        try (Cursor responsesCountCursor = db.rawQuery("SELECT COUNT(*) FROM questionnaire_responses", null)) {
            int count = 0;
            if (responsesCountCursor != null && responsesCountCursor.moveToFirst()) {
                count = responsesCountCursor.getInt(0);
            }
            Log.d(TAG, "Number of questionnaire responses: " + count);
        } catch (Exception e) {
            Log.e(TAG, "Error counting responses: " + e.getMessage());
        }

        // Check if biomarker tables exist
        try (Cursor biomarkerTablesCursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND (name='" +
                        TABLE_BIOMARKER_EXPERIMENTS + "' OR name='" + TABLE_BIOMARKER_DATA + "')", null)) {

            int tableCount = biomarkerTablesCursor != null ? biomarkerTablesCursor.getCount() : 0;
            Log.d(TAG, "Biomarker tables found: " + tableCount);
        } catch (Exception e) {
            Log.e(TAG, "Error checking biomarker tables: " + e.getMessage());
        }

        // Check if questionnaire_summaries table exists
        try (Cursor summariesTableCursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='questionnaire_summaries'", null)) {
            boolean tableExists = summariesTableCursor != null && summariesTableCursor.moveToFirst();
            Log.d(TAG, "questionnaire_summaries table exists: " + tableExists);
        } catch (Exception e) {
            Log.e(TAG, "Error checking questionnaire_summaries table: " + e.getMessage());
        }
    }
    public void updateExperimentSampleIds(long experimentId, String tubeId, String cartridgeId) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        if (tubeId != null) values.put(COLUMN_TUBE_ID, tubeId);
        if (cartridgeId != null) values.put(COLUMN_CARTRIDGE_ID, cartridgeId);
        values.put("updated_at", getCurrentIsoTime());
        values.put("dirty", 1);
        db.update(TABLE_BIOMARKER_EXPERIMENTS, values,
                COLUMN_ID + " = ?", new String[]{String.valueOf(experimentId)});
        Log.d(TAG, "Updated experiment " + experimentId + " with tube_id=" + tubeId
                + " cartridge_id=" + cartridgeId);
    }


    public QuestionnaireDao getQuestionnaireDao() {
        return new QuestionnaireDao() {
            @Override
            public long insertResponse(QuestionnaireResponse response) {
                SQLiteDatabase db = getWritableDatabase();
                ContentValues values = new ContentValues();

                // Don't include ID as it's auto-generated
                values.put("section", response.getSection());
                values.put("question_text", response.getQuestionText());
                values.put("answer", response.getAnswer());
                values.put("timestamp", response.getTimestamp());
                values.put("questionnaire_type", response.getQuestionnaireType());

                // Make sure we're using the current user email
                String userEmail = currentUserEmail;
                if (userEmail == null || userEmail.isEmpty()) {
                    userEmail = response.getUserEmail();
                }
                if (userEmail == null || userEmail.isEmpty()) {
                    Log.w(TAG, "No user email available, using default empty string");
                    userEmail = "";
                }

                values.put("user_email", userEmail);
                if (response.getSessionId() != null) {
                    values.put("session_id", response.getSessionId());
                }
                values.put("updated_at", getCurrentIsoTime());
                values.put("dirty", 1);

                // Not logging user_email or question/answer content here — questionnaire
                // responses can include mental-health screener data, so real values must
                // never land in logcat. Log only non-identifying structural info.
                Log.d(TAG, "Inserting response for section: " + response.getSection());

                long id = db.insert("questionnaire_responses", null, values);

                // Check if this completed a questionnaire and update summary if needed
                String questionnaireType = response.getQuestionnaireType();
                if (questionnaireType != null && !questionnaireType.isEmpty()) {
                    if (isQuestionnaireCompleted(questionnaireType)) {
                        updateQuestionnaireSummaryFromResponses();
                        Log.d(TAG, "Questionnaire " + questionnaireType + " completed, summary updated");
                    }
                }

                return id;
            }

            @Override
            public void insertResponses(List<QuestionnaireResponse> responses) {
                if (responses == null || responses.isEmpty()) {
                    return;
                }

                SQLiteDatabase db = getWritableDatabase();
                db.beginTransaction();
                try {
                    for (QuestionnaireResponse response : responses) {
                        ContentValues values = new ContentValues();
                        // Don't include ID as it's auto-generated
                        values.put("section", response.getSection());
                        values.put("question_text", response.getQuestionText());
                        values.put("answer", response.getAnswer());
                        values.put("timestamp", response.getTimestamp());
                        values.put("questionnaire_type", response.getQuestionnaireType());

                        // Make sure we're using the current user email
                        String userEmail = currentUserEmail;
                        if (userEmail == null || userEmail.isEmpty()) {
                            userEmail = response.getUserEmail();
                        }
                        values.put("user_email", userEmail);
                        if (response.getSessionId() != null) {
                            values.put("session_id", response.getSessionId());
                        }
                        values.put("updated_at", getCurrentIsoTime());
                        values.put("dirty", 1);

                        db.insert("questionnaire_responses", null, values);
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }

                // Update questionnaire summaries after batch insertion
                // Check the questionnaire type of the first response as a representative
                String questionnaireType = responses.get(0).getQuestionnaireType();
                if (questionnaireType != null && !questionnaireType.isEmpty()) {
                    if (isQuestionnaireCompleted(questionnaireType)) {
                        updateQuestionnaireSummaryFromResponses();
                        Log.d(TAG, "Questionnaire " + questionnaireType + " completed via batch insert, summary updated");
                    }
                }
            }

            @Override
            public List<QuestionnaireResponse> getAllResponses() {
                List<QuestionnaireResponse> responses = new ArrayList<>();
                SQLiteDatabase db = getReadableDatabase();

                // Get responses for current user only, ordered by timestamp (most recent first)
                String selection = "user_email = ?";
                String[] selectionArgs = { currentUserEmail };

                Cursor cursor = db.query(
                        "questionnaire_responses",
                        null,
                        selection,
                        selectionArgs,
                        null,
                        null,
                        "timestamp DESC"
                );

                // Create a map to store the most recent responses by question
                Map<String, QuestionnaireResponse> latestResponsesByQuestion = new HashMap<>();

                if (cursor.moveToFirst()) {
                    do {
                        long id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
                        String section = cursor.getString(cursor.getColumnIndexOrThrow("section"));
                        String questionText = cursor.getString(cursor.getColumnIndexOrThrow("question_text"));
                        int answer = cursor.getInt(cursor.getColumnIndexOrThrow("answer"));
                        long timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"));

                        // Get questionnaire type and user email (with fallbacks for older records)
                        String questionnaireType = "MENTAL_HEALTH_SCREENER";
                        String userEmail = "";

                        try {
                            questionnaireType = cursor.getString(cursor.getColumnIndexOrThrow("questionnaire_type"));
                            userEmail = cursor.getString(cursor.getColumnIndexOrThrow("user_email"));
                        } catch (Exception e) {
                            Log.w(TAG, "Column not found, using default values");
                        }

                        QuestionnaireResponse response = new QuestionnaireResponse(id, section, questionText, answer);
                        response.setTimestamp(timestamp);
                        response.setQuestionnaireType(questionnaireType);
                        response.setUserEmail(userEmail);
                        try {
                            int sidx = cursor.getColumnIndex("session_id");
                            if (sidx >= 0) response.setSessionId(cursor.getString(sidx));
                        } catch (Exception ignored) {}

                        // Only keep the most recent response for each question
                        if (!latestResponsesByQuestion.containsKey(questionText)) {
                            latestResponsesByQuestion.put(questionText, response);
                        }
                    } while (cursor.moveToNext());
                }

                cursor.close();

                // Define the order of parts
                List<String> partOrder = Arrays.asList(
                        "Part A: Mood and Energy",
                        "Part B: Anxiety and Stress",
                        "Part C: Sleep and Appetite",
                        "Part D: Cognition and Thinking",
                        "Part E: Repetitive Thoughts and Behaviors",
                        "Part F: Physical Symptoms",
                        "Depression Screening (PHQ-9)",
                        "Beck Depression Inventory (BDI-II)",
                        "Generalized Anxiety Disorder (GAD-7)",
                        "Resilience Scale"
                );

                // Sort responses by part and question number
                List<QuestionnaireResponse> sortedResponses = new ArrayList<>();

                for (String part : partOrder) {
                    // For each part, find all questions that belong to it
                    List<QuestionnaireResponse> partResponses = new ArrayList<>();

                    for (QuestionnaireResponse response : latestResponsesByQuestion.values()) {
                        if (response.getSection().equals(part)) {
                            partResponses.add(response);
                        }
                    }

                    // Sort questions within this part by their number
                    Collections.sort(partResponses, (r1, r2) -> {
                        // Extract question numbers from the question text (e.g., "1. Question text")
                        int num1 = extractQuestionNumber(r1.getQuestionText());
                        int num2 = extractQuestionNumber(r2.getQuestionText());
                        return Integer.compare(num1, num2);
                    });

                    // Add sorted questions for this part to the final list
                    sortedResponses.addAll(partResponses);
                }

                return sortedResponses;
            }

            @Override
            public int deleteAllResponses() {
                SQLiteDatabase db = getWritableDatabase();
                int count = db.delete("questionnaire_responses", null, null);
                Log.d(TAG, "Deleted " + count + " responses");
                return count;
            }
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session-based questionnaire history
    // ─────────────────────────────────────────────────────────────────────────

    /** One assessment run (a unique session_id) with its metadata. */
    public static class AssessmentSession {
        public final String sessionId;
        public final long   timestamp;       // latest timestamp among responses in this session
        public final String questionnaireTypes; // comma-separated types, e.g. "MENTAL_HEALTH_SCREENER, PHQ"

        public AssessmentSession(String sessionId, long timestamp, String questionnaireTypes) {
            this.sessionId          = sessionId;
            this.timestamp          = timestamp;
            this.questionnaireTypes = questionnaireTypes;
        }
    }

    /**
     * Returns all distinct sessions for the current user, newest first.
     * Rows with a null or empty session_id are skipped (legacy data).
     */
    public List<AssessmentSession> getDistinctSessions() {
        List<AssessmentSession> sessions = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        String sql = "SELECT session_id, MAX(timestamp) AS session_time, " +
                "GROUP_CONCAT(DISTINCT questionnaire_type) AS types " +
                "FROM questionnaire_responses " +
                "WHERE user_email = ? AND session_id IS NOT NULL AND session_id != '' " +
                "GROUP BY session_id " +
                "ORDER BY session_time DESC";

        Cursor cursor = null;
        try {
            cursor = db.rawQuery(sql, new String[]{ currentUserEmail });
            while (cursor.moveToNext()) {
                String sid   = cursor.getString(0);
                long   ts    = cursor.getLong(1);
                String types = cursor.getString(2);
                sessions.add(new AssessmentSession(sid, ts, types != null ? types : ""));
            }
        } catch (Exception e) {
            Log.e(TAG, "getDistinctSessions error: " + e.getMessage(), e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return sessions;
    }

    /**
     * Returns every response belonging to the given session, ordered by
     * questionnaire type then by rowid (insertion order).
     */
    public List<QuestionnaireResponse> getResponsesForSession(String sessionId) {
        List<QuestionnaireResponse> list = new ArrayList<>();
        if (sessionId == null || sessionId.isEmpty()) return list;

        SQLiteDatabase db = getReadableDatabase();
        String sql = "SELECT id, section, question_text, answer, timestamp, " +
                "questionnaire_type, user_email, session_id " +
                "FROM questionnaire_responses " +
                "WHERE session_id = ? " +
                "ORDER BY questionnaire_type, id ASC";

        Cursor cursor = null;
        try {
            cursor = db.rawQuery(sql, new String[]{ sessionId });
            while (cursor.moveToNext()) {
                long   id              = cursor.getLong(0);
                String section         = cursor.getString(1);
                String questionText    = cursor.getString(2);
                int    answer          = cursor.getInt(3);
                long   timestamp       = cursor.getLong(4);
                String questionnaireType = cursor.getString(5);
                String userEmail       = cursor.getString(6);
                String sid             = cursor.getString(7);

                QuestionnaireResponse r = new QuestionnaireResponse(id, section, questionText, answer);
                r.setTimestamp(timestamp);
                r.setQuestionnaireType(questionnaireType != null ? questionnaireType : "");
                r.setUserEmail(userEmail != null ? userEmail : "");
                r.setSessionId(sid);
                list.add(r);
            }
        } catch (Exception e) {
            Log.e(TAG, "getResponsesForSession error: " + e.getMessage(), e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return list;
    }

    // Helper method to extract question number from question text
    private int extractQuestionNumber(String questionText) {
        try {
            // Extract the number before the first dot
            int dotIndex = questionText.indexOf('.');
            if (dotIndex > 0) {
                String numberStr = questionText.substring(0, dotIndex).trim();
                return Integer.parseInt(numberStr);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting question number: " + e.getMessage());
        }
        return 999; // Default high number for questions without numbers
    }

    // Method to check if a user has completed a specific questionnaire
    public boolean hasCompletedQuestionnaire(String questionnaireType) {
        SQLiteDatabase db = getReadableDatabase();
        String query = "SELECT COUNT(*) FROM questionnaire_responses WHERE questionnaire_type = ? AND user_email = ?";
        String[] selectionArgs = { questionnaireType, currentUserEmail };

        try (Cursor cursor = db.rawQuery(query, selectionArgs)) {
            if (cursor.moveToFirst()) {
                int count = cursor.getInt(0);
                Log.d(TAG, "Found " + count + " responses for " + questionnaireType);
                return count > 0;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking completed questionnaire: " + e.getMessage());
        }
        return false;
    }

    // Method to get the total score from the Mental Health Screener
    public int getMentalHealthScreenerTotalScore() {
        int totalScore = 0;

        // Sum all part scores
        totalScore += getMentalHealthScreenerPartScore("A");
        totalScore += getMentalHealthScreenerPartScore("B");
        totalScore += getMentalHealthScreenerPartScore("C");
        totalScore += getMentalHealthScreenerPartScore("D");
        totalScore += getMentalHealthScreenerPartScore("E");
        totalScore += getMentalHealthScreenerPartScore("F");

        // Not logging the actual score — clinical screener data.
        return totalScore;
    }

    // Method to get the score for a specific part of the Mental Health Screener
    public int getMentalHealthScreenerPartScore(String part) {
        SQLiteDatabase db = getReadableDatabase();
        int partScore = 0;

        // Map part letter to section name
        String sectionName;
        switch (part) {
            case "A":
                sectionName = "Part A: Mood and Energy";
                break;
            case "B":
                sectionName = "Part B: Anxiety and Stress";
                break;
            case "C":
                sectionName = "Part C: Sleep and Appetite";
                break;
            case "D":
                sectionName = "Part D: Cognition and Thinking";
                break;
            case "E":
                sectionName = "Part E: Repetitive Thoughts and Behaviors";
                break;
            case "F":
                sectionName = "Part F: Physical Symptoms";
                break;
            default:
                Log.e(TAG, "Invalid part: " + part);
                return 0;
        }

        // Query to get all answers for the current user, the specified questionnaire type, and section
        String query = "SELECT answer FROM questionnaire_responses " +
                "WHERE questionnaire_type = 'MENTAL_HEALTH_SCREENER' " +
                "AND user_email = ? " +
                "AND section = ?";
        String[] selectionArgs = { currentUserEmail, sectionName };

        try (Cursor cursor = db.rawQuery(query, selectionArgs)) {
            while (cursor.moveToNext()) {
                int answer = cursor.getInt(cursor.getColumnIndexOrThrow("answer"));
                partScore += answer;
            }
            // Not logging the actual score — clinical screener data.
        } catch (Exception e) {
            Log.e(TAG, "Error calculating part score: " + e.getMessage());
        }

        return partScore;
    }

    // Method to get response count for a specific questionnaire type
    public int getQuestionnaireResponseCount(String questionnaireType) {
        SQLiteDatabase db = getReadableDatabase();
        String query = "SELECT COUNT(*) FROM questionnaire_responses WHERE questionnaire_type = ? AND user_email = ?";
        String[] selectionArgs = { questionnaireType, currentUserEmail };

        try (Cursor cursor = db.rawQuery(query, selectionArgs)) {
            if (cursor.moveToFirst()) {
                int count = cursor.getInt(0);
                Log.d(TAG, "Response count for " + questionnaireType + ": " + count);
                return count;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting questionnaire response count: " + e.getMessage());
        }
        return 0;
    }

    // Method to check if a questionnaire is partially completed
    public boolean isQuestionnairePartiallyCompleted(String questionnaireType) {
        int responseCount = getQuestionnaireResponseCount(questionnaireType);
        int expectedQuestionCount = getExpectedQuestionCount(questionnaireType);

        Log.d(TAG, "Questionnaire " + questionnaireType + " has " + responseCount + "/" + expectedQuestionCount + " responses");

        return responseCount > 0 && responseCount < expectedQuestionCount;
    }

    // Method to get expected question count for different questionnaire types
    private int getExpectedQuestionCount(String questionnaireType) {
        switch (questionnaireType) {
            case "MENTAL_HEALTH_SCREENER":
                return 22; // Part A(5) + Part B(5) + Part C(2) + Part D(5) + Part E(3) + Part F(2) = 22 questions
            case "PHQ":
                return 9; // PHQ-9 has 9 questions
            case "BDI":
                return 21; // Beck Depression Inventory has 21 questions
            case "GAD":
                return 7; // GAD-7 has 7 questions
            case "RESILIENCE":
                return 10; // Resilience Scale has 10 questions (as per implementation)
            default:
                return 10; // Default fallback
        }
    }

    // Method to check if questionnaire is fully completed
    public boolean isQuestionnaireCompleted(String questionnaireType) {
        int responseCount = getQuestionnaireResponseCount(questionnaireType);
        int expectedQuestionCount = getExpectedQuestionCount(questionnaireType);

        boolean isCompleted = responseCount >= expectedQuestionCount;
        Log.d(TAG, "Questionnaire " + questionnaireType + " completed: " + isCompleted +
                " (" + responseCount + "/" + expectedQuestionCount + ")");

        return isCompleted;
    }

    // Method to get questionnaire status: 0 = Not Started, 1 = Partially Completed, 2 = Completed
    public int getQuestionnaireStatus(String questionnaireType) {
        if (isQuestionnaireCompleted(questionnaireType)) {
            return 2; // Completed
        } else if (isQuestionnairePartiallyCompleted(questionnaireType)) {
            return 1; // Partially Completed
        } else {
            return 0; // Not Started
        }
    }

    /**
     * Returns every raw questionnaire response for every user, each row tagged with the
     * start/end timestamp of the test-taking session it belongs to (MIN/MAX of that same
     * session_id + questionnaire_type + user combination). This makes each row
     * self-describing — you can tell exactly when that particular sitting started and
     * ended — even if the same person takes the same questionnaire multiple times in one
     * day, since each sitting has its own session_id.
     *
     * Used for CSV export (see VitalsCsvExporter) so a single export captures every
     * participant's full questionnaire history, not just whoever is currently logged in.
     */
    public Cursor getAllQuestionnaireResponsesWithSessionBounds() {
        SQLiteDatabase db = getReadableDatabase();
        String sql = "SELECT r.user_email, r.questionnaire_type, r.session_id, r.section, " +
                "r.question_text, r.answer, r.timestamp AS response_timestamp, " +
                "(SELECT MIN(r2.timestamp) FROM questionnaire_responses r2 " +
                "   WHERE r2.session_id = r.session_id " +
                "     AND r2.questionnaire_type = r.questionnaire_type " +
                "     AND r2.user_email = r.user_email) AS session_start_ms, " +
                "(SELECT MAX(r2.timestamp) FROM questionnaire_responses r2 " +
                "   WHERE r2.session_id = r.session_id " +
                "     AND r2.questionnaire_type = r.questionnaire_type " +
                "     AND r2.user_email = r.user_email) AS session_end_ms " +
                "FROM questionnaire_responses r " +
                "ORDER BY r.user_email ASC, r.session_id ASC, r.timestamp ASC";
        return db.rawQuery(sql, null);
    }

    /** Same as {@link #getAllQuestionnaireResponsesWithSessionBounds()} but scoped to one
     *  user — the app is single-patient-per-login, so bulk exports (Combined Results, Live
     *  Vitals, Questionnaires tab "export all" buttons) should only ever cover whoever is
     *  currently logged in, not every account that has ever used this phone. */
    public Cursor getQuestionnaireResponsesForUserWithSessionBounds(String userEmail) {
        SQLiteDatabase db = getReadableDatabase();
        String sql = "SELECT r.user_email, r.questionnaire_type, r.session_id, r.section, " +
                "r.question_text, r.answer, r.timestamp AS response_timestamp, " +
                "(SELECT MIN(r2.timestamp) FROM questionnaire_responses r2 " +
                "   WHERE r2.session_id = r.session_id " +
                "     AND r2.questionnaire_type = r.questionnaire_type " +
                "     AND r2.user_email = r.user_email) AS session_start_ms, " +
                "(SELECT MAX(r2.timestamp) FROM questionnaire_responses r2 " +
                "   WHERE r2.session_id = r.session_id " +
                "     AND r2.questionnaire_type = r.questionnaire_type " +
                "     AND r2.user_email = r.user_email) AS session_end_ms " +
                "FROM questionnaire_responses r " +
                "WHERE r.user_email = ? " +
                "ORDER BY r.session_id ASC, r.timestamp ASC";
        return db.rawQuery(sql, new String[]{userEmail});
    }

    /** Same as {@link #getQuestionnaireResponsesForUserWithSessionBounds(String)} but scoped
     *  to a single session_id — used for the per-session export button on the assessment
     *  detail screen (SessionDetailActivity), so exporting one sitting doesn't pull in the
     *  user's entire questionnaire history. */
    public Cursor getQuestionnaireResponsesForSessionWithBounds(String sessionId) {
        SQLiteDatabase db = getReadableDatabase();
        String sql = "SELECT r.user_email, r.questionnaire_type, r.session_id, r.section, " +
                "r.question_text, r.answer, r.timestamp AS response_timestamp, " +
                "(SELECT MIN(r2.timestamp) FROM questionnaire_responses r2 " +
                "   WHERE r2.session_id = r.session_id) AS session_start_ms, " +
                "(SELECT MAX(r2.timestamp) FROM questionnaire_responses r2 " +
                "   WHERE r2.session_id = r.session_id) AS session_end_ms " +
                "FROM questionnaire_responses r " +
                "WHERE r.session_id = ? " +
                "ORDER BY r.timestamp ASC";
        return db.rawQuery(sql, new String[]{sessionId});
    }

    // Method to get specific question answers for debugging
    public Map<String, Integer> getMentalHealthScreenerAnswers() {
        SQLiteDatabase db = getReadableDatabase();
        Map<String, Integer> answers = new HashMap<>();

        String query = "SELECT question_text, answer FROM questionnaire_responses " +
                "WHERE questionnaire_type = 'MENTAL_HEALTH_SCREENER' " +
                "AND user_email = ? " +
                "ORDER BY id";
        String[] selectionArgs = { currentUserEmail };

        try (Cursor cursor = db.rawQuery(query, selectionArgs)) {
            while (cursor.moveToNext()) {
                String question = cursor.getString(cursor.getColumnIndexOrThrow("question_text"));
                int answer = cursor.getInt(cursor.getColumnIndexOrThrow("answer"));
                answers.put(question, answer);
                // Not logging question text/answer — this is mental-health screener content.
            }
        } catch (Exception e) {
            Log.e(TAG, "Error retrieving questionnaire answers: " + e.getMessage());
        }

        return answers;
    }

    // Method to get the latest response time for debugging purposes
    public long getLatestResponseTime(String questionnaireType) {
        SQLiteDatabase db = getReadableDatabase();
        long latestTime = 0;

        String query = "SELECT MAX(timestamp) FROM questionnaire_responses " +
                "WHERE questionnaire_type = ? AND user_email = ?";
        String[] selectionArgs = { questionnaireType, currentUserEmail };

        try (Cursor cursor = db.rawQuery(query, selectionArgs)) {
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                latestTime = cursor.getLong(0);
                Log.d(TAG, "Latest response time for " + questionnaireType + ": " + latestTime);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error retrieving latest response time: " + e.getMessage());
        }

        return latestTime;
    }

    // Method to log all questionnaire responses for the current user (for debugging)
    public void logUserResponses() {
        SQLiteDatabase db = getReadableDatabase();

        String query = "SELECT * FROM questionnaire_responses WHERE user_email = ? ORDER BY timestamp DESC";
        String[] selectionArgs = { currentUserEmail };

        try (Cursor cursor = db.rawQuery(query, selectionArgs)) {
            // Logging only a count — not the email, and not per-row question/answer content,
            // since this table can hold mental-health screener responses.
            Log.d(TAG, "Found " + cursor.getCount() + " responses for current user");
        } catch (Exception e) {
            Log.e(TAG, "Error logging user responses: " + e.getMessage());
        }
    }

    /**
     * Updates the questionnaire summary based on completed questionnaires
     */
    public void updateQuestionnaireSummaryFromResponses() {
        // Check if we have a current user
        if (currentUserEmail == null || currentUserEmail.isEmpty()) {
            Log.w(TAG, "Cannot update summaries: No current user");
            return;
        }

        // Get or create summary for current user
        QuestionnaireSummary summary = getQuestionnaireSummary(currentUserEmail);
        if (summary == null) {
            summary = new QuestionnaireSummary();
            summary.setUserEmail(currentUserEmail);
        }

        // Update Mental Health Screener scores
        if (isQuestionnaireCompleted("MENTAL_HEALTH_SCREENER")) {
            summary.setScoreA(getMentalHealthScreenerPartScore("A"));
            summary.setScoreB(getMentalHealthScreenerPartScore("B"));
            summary.setScoreC(getMentalHealthScreenerPartScore("C"));
            summary.setScoreD(getMentalHealthScreenerPartScore("D"));
            summary.setScoreE(getMentalHealthScreenerPartScore("E"));
            summary.setScoreF(getMentalHealthScreenerPartScore("F"));
            summary.setTotalScore(getMentalHealthScreenerTotalScore());
        }

        // Update PHQ-9 score if completed
        if (isQuestionnaireCompleted("PHQ")) {
            int phq9Score = calculatePhq9Score();
            summary.setPhq9Score(phq9Score);
            summary.setPhq9Filled(true);
            // Set triggered flag if score is above threshold (e.g., 10)
            summary.setPhq9Triggered(phq9Score >= 10 ? 1 : 0);
        }

        // Update BDI score if completed
        if (isQuestionnaireCompleted("BDI")) {
            int bdiScore = calculateBdiScore();
            summary.setBdiScore(bdiScore);
            summary.setBdiFilled(true);
            // Set triggered flag if score is above threshold (e.g., 20)
            summary.setBdiTriggered(bdiScore >= 20 ? 1 : 0);
        }

        // Update GAD-7 score if completed
        if (isQuestionnaireCompleted("GAD")) {
            int gad7Score = calculateGad7Score();
            summary.setGad7Score(gad7Score);
            summary.setGad7Filled(true);
            // Set triggered flag if score is above threshold (e.g., 10)
            summary.setGad7Triggered(gad7Score >= 10 ? 1 : 0);
        }

        // Update Resilience Scale score if completed
        if (isQuestionnaireCompleted("RESILIENCE")) {
            double brsScore = calculateBrsScore();
            summary.setBrsScore(brsScore);
            summary.setBrsFilled(true);
            // Set triggered flag if score is below threshold (e.g., 3.0)
            summary.setBrsTriggered(brsScore < 3.0 ? 1 : 0);
        }

        // Save the updated summary
        saveQuestionnaireSummary(summary);

        Log.d(TAG, "Updated questionnaire summary for current user");
    }

    /**
     * Force update of all questionnaire summaries for the current user
     * Call this method when you want to ensure the summary is up-to-date
     */
    public void forceUpdateQuestionnaireSummaries() {
        // Check if we have a current user
        if (currentUserEmail == null || currentUserEmail.isEmpty()) {
            Log.w(TAG, "Cannot update summaries: No current user");
            return;
        }

        updateQuestionnaireSummaryFromResponses();
        Log.d(TAG, "Forced update of questionnaire summaries for current user");
    }

    /**
     * Debug method to log the current questionnaire summary status.
     * Deliberately logs only "filled" flags, never scores/triggered values or the user's
     * email — PHQ-9/BDI/GAD-7/BRS scores and risk-triggered flags are clinical data and must
     * never land in logcat, even in a debug build.
     */
    public void logQuestionnaireSummaryStatus() {
        QuestionnaireSummary summary = getQuestionnaireSummary(currentUserEmail);
        if (summary == null) {
            Log.d(TAG, "No questionnaire summary found for current user");
            return;
        }

        Log.d(TAG, "Questionnaire summary present. Filled: " +
                "PHQ-9=" + summary.isPhq9Filled() +
                ", BDI=" + summary.isBdiFilled() +
                ", GAD-7=" + summary.isGad7Filled() +
                ", BRS=" + summary.isBrsFilled());
    }

    // Helper method to calculate PHQ-9 score
    private int calculatePhq9Score() {
        SQLiteDatabase db = getReadableDatabase();
        int totalScore = 0;

        String query = "SELECT answer FROM questionnaire_responses " +
                "WHERE questionnaire_type = 'PHQ' " +
                "AND user_email = ?";

        try (Cursor cursor = db.rawQuery(query, new String[]{currentUserEmail})) {
            while (cursor.moveToNext()) {
                totalScore += cursor.getInt(cursor.getColumnIndexOrThrow("answer"));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error calculating PHQ-9 score: " + e.getMessage());
        }

        return totalScore;
    }

    // Helper method to calculate BDI score
    private int calculateBdiScore() {
        SQLiteDatabase db = getReadableDatabase();
        int totalScore = 0;

        String query = "SELECT answer FROM questionnaire_responses " +
                "WHERE questionnaire_type = 'BDI' " +
                "AND user_email = ?";

        try (Cursor cursor = db.rawQuery(query, new String[]{currentUserEmail})) {
            while (cursor.moveToNext()) {
                totalScore += cursor.getInt(cursor.getColumnIndexOrThrow("answer"));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error calculating BDI score: " + e.getMessage());
        }

        return totalScore;
    }

    // Helper method to calculate GAD-7 score
    private int calculateGad7Score() {
        SQLiteDatabase db = getReadableDatabase();
        int totalScore = 0;

        String query = "SELECT answer FROM questionnaire_responses " +
                "WHERE questionnaire_type = 'GAD' " +
                "AND user_email = ?";

        try (Cursor cursor = db.rawQuery(query, new String[]{currentUserEmail})) {
            while (cursor.moveToNext()) {
                totalScore += cursor.getInt(cursor.getColumnIndexOrThrow("answer"));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error calculating GAD-7 score: " + e.getMessage());
        }

        return totalScore;
    }

    // Helper method to calculate BRS score
    private double calculateBrsScore() {
        SQLiteDatabase db = getReadableDatabase();
        double totalScore = 0;
        int count = 0;

        String query = "SELECT answer FROM questionnaire_responses " +
                "WHERE questionnaire_type = 'RESILIENCE' " +
                "AND user_email = ?";

        try (Cursor cursor = db.rawQuery(query, new String[]{currentUserEmail})) {
            while (cursor.moveToNext()) {
                totalScore += cursor.getInt(cursor.getColumnIndexOrThrow("answer"));
                count++;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error calculating BRS score: " + e.getMessage());
        }

        // BRS is calculated as average score
        return count > 0 ? totalScore / count : 0;
    }

    // ====================== BIOMARKER DATA METHODS ======================

    /**
     * Creates a new biomarker experiment record and returns its ID
     */
    public long createBiomarkerExperiment(String username,
                                          String source,
                                          int samplingRate,
                                          int movingAvg,
                                          double duration,
                                          double maxValuePort1,
                                          int dataSize,
                                          long sampleTimestamp,
                                          String tubeId,
                                          String cartridgeId) {

        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        Log.d("DB_DEBUG", "Saving experiment for username: [" + username + "]");


        if (username == null || username.isEmpty()) {
            username = currentUserEmail;
        }

        values.put(COLUMN_USERNAME, username);
        values.put(COLUMN_SOURCE, source);

        // use sample time instead of current time
        values.put(COLUMN_TIMESTAMP, sampleTimestamp);

        values.put(COLUMN_SAMPLING_RATE, samplingRate);
        values.put(COLUMN_MOVING_AVG, movingAvg);
        values.put(COLUMN_DURATION, duration);
        values.put(COLUMN_MAX_VALUE, maxValuePort1);
        values.put(COLUMN_DATA_SIZE, dataSize);
        values.put("updated_at", getCurrentIsoTime());
        values.put("dirty", 1);

        long id = db.insert(TABLE_BIOMARKER_EXPERIMENTS, null, values);
        Log.d(TAG, "Created biomarker experiment with ID " + id);
        return id;
    }

    /**
     * Adds biomarker data points in bulk (more efficient)
     */
    public void addBiomarkerDataBatch(long experimentId, ArrayList<Double> xaxis,
                                      ArrayList<Double> dataPoints0, ArrayList<Double> dataPoints1,
                                      ArrayList<Double> dataPoints2, ArrayList<Double> dataPointsavgd) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();

        try {
            int size = Math.min(xaxis.size(),
                    Math.min(dataPoints0.size(),
                            Math.min(dataPoints1.size(),
                                    Math.min(dataPoints2.size(), dataPointsavgd.size()))));

            for (int i = 0; i < size; i++) {
                ContentValues values = new ContentValues();
                values.put(COLUMN_EXPERIMENT_ID, experimentId);
                values.put(COLUMN_DATA_POINT, i + 1);
                values.put(COLUMN_TIME_SEC, xaxis.get(i));
                values.put(COLUMN_PORT0, dataPoints0.get(i));
                values.put(COLUMN_PORT1, dataPoints1.get(i));
                values.put(COLUMN_PORT2, dataPoints2.get(i));
                values.put(COLUMN_AVG, dataPointsavgd.get(i));

                db.insert(TABLE_BIOMARKER_DATA, null, values);
            }
            db.setTransactionSuccessful();

            Log.d(TAG, "Added " + size + " biomarker data points for experiment " + experimentId);
        } catch (Exception e) {
            Log.e(TAG, "Error adding biomarker data batch: " + e.getMessage(), e);
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Updates an experiment's max value
     */
    public void updateBiomarkerExperimentMaxValue(long experimentId, double maxValue) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_MAX_VALUE, maxValue);
        values.put("updated_at", getCurrentIsoTime());
        values.put("dirty", 1);

        int rowsUpdated = db.update(TABLE_BIOMARKER_EXPERIMENTS, values, COLUMN_ID + " = ?",
                new String[]{String.valueOf(experimentId)});

        Log.d(TAG, "Updated max value to " + maxValue + " for experiment " +
                experimentId + ", rows affected: " + rowsUpdated);
    }

    /**
     * Gets all biomarker experiments for a user
     */
    public Cursor getBiomarkerExperimentsForUser(String username) {
        SQLiteDatabase db = this.getReadableDatabase();

        if (username == null || username.isEmpty()) {
            username = currentUserEmail;
        }

        return db.query(TABLE_BIOMARKER_EXPERIMENTS, null,
                COLUMN_USERNAME + " = ?", new String[]{username},
                null, null, COLUMN_TIMESTAMP + " DESC");
    }

    /**
     * Gets every biomarker experiment for every user, not just the currently logged-in one.
     * Used for CSV export so a single export at the end of a multi-participant testing
     * session captures everyone's results, not just whoever happens to be logged in at
     * export time (see VitalsCsvExporter).
     */
    public Cursor getAllBiomarkerExperiments() {
        SQLiteDatabase db = this.getReadableDatabase();
        return db.query(TABLE_BIOMARKER_EXPERIMENTS, null, null, null,
                null, null, COLUMN_USERNAME + " ASC, " + COLUMN_TIMESTAMP + " ASC");
    }

    /** Biomarker experiments for one user within a timestamp range (inclusive) — used for
     *  the per-day export button on PatientResultsActivity, so exporting one day's tests
     *  doesn't pull in the patient's entire history. */
    public Cursor getBiomarkerExperimentsForUserAndRange(String username, long startMs, long endMs) {
        SQLiteDatabase db = this.getReadableDatabase();
        return db.query(TABLE_BIOMARKER_EXPERIMENTS, null,
                COLUMN_USERNAME + " = ? AND " + COLUMN_TIMESTAMP + " BETWEEN ? AND ?",
                new String[]{username, String.valueOf(startMs), String.valueOf(endMs)},
                null, null, COLUMN_TIMESTAMP + " ASC");
    }

    /**
     * Gets all data points for a biomarker experiment
     */
    public Cursor getBiomarkerDataForExperiment(long experimentId) {
        SQLiteDatabase db = this.getReadableDatabase();
        return db.query(TABLE_BIOMARKER_DATA, null,
                COLUMN_EXPERIMENT_ID + " = ?", new String[]{String.valueOf(experimentId)},
                null, null, COLUMN_DATA_POINT + " ASC");
    }
    // Simple holder for history rows
    public static class BiomarkerExperimentSummary {
        public long id;
        public long timestamp;
        public double maxValue;

        public BiomarkerExperimentSummary(long id, long timestamp, double maxValue) {
            this.id = id;
            this.timestamp = timestamp;
            this.maxValue = maxValue;
        }
    }
    /**
     * Returns the latest N biomarker experiments for the current user
     * (ordered newest → oldest).
     */
    public List<BiomarkerExperimentSummary> getLatestBiomarkerExperiments(String userEmail, int limit) {
        List<BiomarkerExperimentSummary> results = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();

        //  Use the provided userEmail, or fall back to currentUserEmail
        String username = userEmail;
        if (username == null || username.isEmpty()) {
            username = currentUserEmail;
        }

        Log.d(TAG, "getLatestBiomarkerExperiments - Filtering by username: [" + username + "]");


        String sql = "SELECT " + COLUMN_ID + ", " + COLUMN_TIMESTAMP + ", " + COLUMN_MAX_VALUE +
                " FROM " + TABLE_BIOMARKER_EXPERIMENTS +
                " WHERE " + COLUMN_USERNAME + " = ?" +
                " ORDER BY " + COLUMN_TIMESTAMP + " DESC" +
                " LIMIT " + limit;

        Cursor cursor = null;
        try {
            cursor = db.rawQuery(sql, new String[]{username});
            Log.d(TAG, "Query returned " + cursor.getCount() + " experiments for user: [" + username + "]");

            while (cursor.moveToNext()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID));
                long ts = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP));
                double maxVal = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_MAX_VALUE));
                results.add(new BiomarkerExperimentSummary(id, ts, maxVal));
                Log.d(TAG, "  - User: [" + username + "], Experiment ID: " + id + ", MaxValue: " + maxVal);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting latest biomarker experiments", e);
            e.printStackTrace();
        } finally {
            if (cursor != null) cursor.close();
        }

        Log.d(TAG, "Returning " + results.size() + " experiments for user: [" + username + "]");
        return results;
    }




    /**
     * Gets the latest biomarker experiment for the current user
     */
    public long getLatestBiomarkerExperimentId() {
        SQLiteDatabase db = this.getReadableDatabase();
        long experimentId = -1;

        String query = "SELECT " + COLUMN_ID + " FROM " + TABLE_BIOMARKER_EXPERIMENTS +
                " WHERE " + COLUMN_USERNAME + " = ? " +
                " ORDER BY " + COLUMN_TIMESTAMP + " DESC LIMIT 1";

        try (Cursor cursor = db.rawQuery(query, new String[]{currentUserEmail})) {
            if (cursor.moveToFirst()) {
                experimentId = cursor.getLong(0);
                Log.d(TAG, "Latest biomarker experiment ID: " + experimentId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting latest biomarker experiment: " + e.getMessage());
        }

        return experimentId;
    }

    /**
     * Gets the max biomarker value for the latest experiment
     */
    public double getLatestBiomarkerMaxValue() {
        SQLiteDatabase db = this.getReadableDatabase();
        double maxValue = 0.0;

        long experimentId = getLatestBiomarkerExperimentId();
        if (experimentId == -1) {
            return maxValue;
        }

        String query = "SELECT " + COLUMN_MAX_VALUE + " FROM " + TABLE_BIOMARKER_EXPERIMENTS +
                " WHERE " + COLUMN_ID + " = ?";

        try (Cursor cursor = db.rawQuery(query, new String[]{String.valueOf(experimentId)})) {
            if (cursor.moveToFirst()) {
                maxValue = cursor.getDouble(0);
                Log.d(TAG, "Latest biomarker max value: " + maxValue);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting biomarker max value: " + e.getMessage());
        }

        return maxValue;
    }
    /**
     * Returns all biomarker experiments for a patient, one per day (latest per day).
     * Ordered oldest to newest for graph plotting.
     */
    public List<BiomarkerExperimentSummary> getAllExperimentsForGraph(String userEmail) {
        List<BiomarkerExperimentSummary> results = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();

        if (userEmail == null || userEmail.isEmpty()) {
            userEmail = currentUserEmail;
        }

        // Group by calendar day, pick the latest experiment per day
        String sql = "SELECT " + COLUMN_ID + ", " + COLUMN_TIMESTAMP + ", " + COLUMN_MAX_VALUE +
                " FROM " + TABLE_BIOMARKER_EXPERIMENTS +
                " WHERE " + COLUMN_USERNAME + " = ?" +
                " AND " + COLUMN_ID + " IN (" +
                "   SELECT " + COLUMN_ID + " FROM " + TABLE_BIOMARKER_EXPERIMENTS +
                "   WHERE " + COLUMN_USERNAME + " = ?" +
                "   GROUP BY DATE(" + COLUMN_TIMESTAMP + " / 1000, 'unixepoch')" +
                "   HAVING " + COLUMN_TIMESTAMP + " = MAX(" + COLUMN_TIMESTAMP + ")" +
                " )" +
                " ORDER BY " + COLUMN_TIMESTAMP + " ASC";

        Cursor cursor = null;
        try {
            cursor = db.rawQuery(sql, new String[]{userEmail, userEmail});
            Log.d(TAG, "getAllExperimentsForGraph - " + cursor.getCount() + " day entries");

            while (cursor.moveToNext()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID));
                long ts = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP));
                double maxVal = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_MAX_VALUE));
                results.add(new BiomarkerExperimentSummary(id, ts, maxVal));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in getAllExperimentsForGraph: " + e.getMessage(), e);
        } finally {
            if (cursor != null) cursor.close();
        }

        return results;
    }
    public List<BiomarkerExperimentSummary> getExperimentById(String userEmail, long experimentId) {
        List<BiomarkerExperimentSummary> results = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();

        String sql = "SELECT " + COLUMN_ID + ", " + COLUMN_TIMESTAMP + ", " + COLUMN_MAX_VALUE +
                " FROM " + TABLE_BIOMARKER_EXPERIMENTS +
                " WHERE " + COLUMN_ID + " = ? AND " + COLUMN_USERNAME + " = ?";

        Cursor cursor = null;
        try {
            cursor = db.rawQuery(sql, new String[]{
                    String.valueOf(experimentId), userEmail
            });
            if (cursor.moveToFirst()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID));
                long ts = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP));
                double maxVal = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_MAX_VALUE));
                results.add(new BiomarkerExperimentSummary(id, ts, maxVal));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in getExperimentById: " + e.getMessage(), e);
        } finally {
            if (cursor != null) cursor.close();
        }

        return results;
    }



    // ====================== QUESTIONNAIRE SUMMARY METHODS ======================

    /**
     * Ensures the questionnaire_summaries table exists
     */
    public void ensureQuestionnaireSummaryTableExists() {
        SQLiteDatabase db = this.getWritableDatabase();

        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS questionnaire_summaries (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "user_email TEXT UNIQUE," +
                    "score_a INTEGER DEFAULT 0," +
                    "score_b INTEGER DEFAULT 0," +
                    "score_c INTEGER DEFAULT 0," +
                    "score_d INTEGER DEFAULT 0," +
                    "score_e INTEGER DEFAULT 0," +
                    "score_f INTEGER DEFAULT 0," +
                    "total_score INTEGER DEFAULT 0," +
                    "phq9_score INTEGER DEFAULT -1," +
                    "bdi_score INTEGER DEFAULT -1," +
                    "gad7_score INTEGER DEFAULT -1," +
                    "brs_score REAL DEFAULT -1," +
                    "phq9_filled INTEGER DEFAULT 0," +
                    "bdi_filled INTEGER DEFAULT 0," +
                    "gad7_filled INTEGER DEFAULT 0," +
                    "brs_filled INTEGER DEFAULT 0," +
                    "phq9_triggered INTEGER DEFAULT 0," +
                    "bdi_triggered INTEGER DEFAULT 0," +
                    "gad7_triggered INTEGER DEFAULT 0," +
                    "brs_triggered INTEGER DEFAULT 0" +
                    ")");

            Log.d(TAG, "Questionnaire summary table verified");
        } catch (Exception e) {
            Log.e(TAG, "Error creating questionnaire_summaries table: " + e.getMessage());
        }
    }

    /**
     * Gets the questionnaire summary for the specified user email
     */
    public QuestionnaireSummary getQuestionnaireSummary(String userEmail) {
        ensureQuestionnaireSummaryTableExists();
        SQLiteDatabase db = this.getReadableDatabase();
        QuestionnaireSummary summary = null;

        // If no email provided, use current user
        if (userEmail == null || userEmail.isEmpty()) {
            userEmail = currentUserEmail;
        }

        String query = "SELECT * FROM questionnaire_summaries WHERE user_email = ?";
        try (Cursor cursor = db.rawQuery(query, new String[]{userEmail})) {
            if (cursor.moveToFirst()) {
                summary = new QuestionnaireSummary();
                summary.setId(cursor.getInt(cursor.getColumnIndexOrThrow("id")));
                summary.setUserEmail(userEmail);

                // Section scores
                summary.setScoreA(cursor.getInt(cursor.getColumnIndexOrThrow("score_a")));
                summary.setScoreB(cursor.getInt(cursor.getColumnIndexOrThrow("score_b")));
                summary.setScoreC(cursor.getInt(cursor.getColumnIndexOrThrow("score_c")));
                summary.setScoreD(cursor.getInt(cursor.getColumnIndexOrThrow("score_d")));
                summary.setScoreE(cursor.getInt(cursor.getColumnIndexOrThrow("score_e")));
                summary.setScoreF(cursor.getInt(cursor.getColumnIndexOrThrow("score_f")));
                summary.setTotalScore(cursor.getInt(cursor.getColumnIndexOrThrow("total_score")));

                // Filled status
                summary.setPhq9Filled(cursor.getInt(cursor.getColumnIndexOrThrow("phq9_filled")) > 0);
                summary.setBdiFilled(cursor.getInt(cursor.getColumnIndexOrThrow("bdi_filled")) > 0);
                summary.setGad7Filled(cursor.getInt(cursor.getColumnIndexOrThrow("gad7_filled")) > 0);
                summary.setBrsFilled(cursor.getInt(cursor.getColumnIndexOrThrow("brs_filled")) > 0);

                // Questionnaire scores - only set if filled
                if (summary.isPhq9Filled()) {
                    summary.setPhq9Score(cursor.getInt(cursor.getColumnIndexOrThrow("phq9_score")));
                }

                if (summary.isBdiFilled()) {
                    summary.setBdiScore(cursor.getInt(cursor.getColumnIndexOrThrow("bdi_score")));
                }

                if (summary.isGad7Filled()) {
                    summary.setGad7Score(cursor.getInt(cursor.getColumnIndexOrThrow("gad7_score")));
                }

                if (summary.isBrsFilled()) {
                    summary.setBrsScore(cursor.getDouble(cursor.getColumnIndexOrThrow("brs_score")));
                }

                // Triggered flags
                summary.setPhq9Triggered(cursor.getInt(cursor.getColumnIndexOrThrow("phq9_triggered")));
                summary.setBdiTriggered(cursor.getInt(cursor.getColumnIndexOrThrow("bdi_triggered")));
                summary.setGad7Triggered(cursor.getInt(cursor.getColumnIndexOrThrow("gad7_triggered")));
                summary.setBrsTriggered(cursor.getInt(cursor.getColumnIndexOrThrow("brs_triggered")));

                Log.d(TAG, "Retrieved questionnaire summary");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error retrieving questionnaire summary: " + e.getMessage());
        }

        return summary;
    }

    /**
     * Saves or updates a questionnaire summary
     */
    public void saveQuestionnaireSummary(QuestionnaireSummary summary) {
        ensureQuestionnaireSummaryTableExists();
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();

        // Set user email
        values.put("user_email", summary.getUserEmail());

        // Section scores
        values.put("score_a", summary.getScoreA());
        values.put("score_b", summary.getScoreB());
        values.put("score_c", summary.getScoreC());
        values.put("score_d", summary.getScoreD());
        values.put("score_e", summary.getScoreE());
        values.put("score_f", summary.getScoreF());
        values.put("total_score", summary.getTotalScore());

        // Filled status
        values.put("phq9_filled", summary.isPhq9Filled() ? 1 : 0);
        values.put("bdi_filled", summary.isBdiFilled() ? 1 : 0);
        values.put("gad7_filled", summary.isGad7Filled() ? 1 : 0);
        values.put("brs_filled", summary.isBrsFilled() ? 1 : 0);

        // Questionnaire scores
        // For filled questionnaires, use actual score; for unfilled, use -1
        values.put("phq9_score", summary.getPhq9Score());  // getPhq9Score() already returns -1 if not filled
        values.put("bdi_score", summary.getBdiScore());    // getBdiScore() already returns -1 if not filled
        values.put("gad7_score", summary.getGad7Score());  // getGad7Score() already returns -1 if not filled
        values.put("brs_score", summary.getBrsScore());    // getBrsScore() already returns -1 if not filled

        // Triggered flags
        values.put("phq9_triggered", summary.getPhq9Triggered());
        values.put("bdi_triggered", summary.getBdiTriggered());
        values.put("gad7_triggered", summary.getGad7Triggered());
        values.put("brs_triggered", summary.getBrsTriggered());
        values.put("updated_at", getCurrentIsoTime());
        values.put("dirty", 1);

        // Check if this user already has a summary
        boolean exists = false;
        String query = "SELECT id FROM questionnaire_summaries WHERE user_email = ?";
        try (Cursor cursor = db.rawQuery(query, new String[]{summary.getUserEmail()})) {
            exists = cursor.moveToFirst();
        } catch (Exception e) {
            Log.e(TAG, "Error checking for existing questionnaire summary: " + e.getMessage());
        }

        if (exists) {
            // Update existing record
            db.update("questionnaire_summaries", values, "user_email = ?",
                    new String[]{summary.getUserEmail()});
            Log.d(TAG, "Updated existing questionnaire summary");
        } else {
            // Insert new record
            db.insert("questionnaire_summaries", null, values);
            Log.d(TAG, "Inserted new questionnaire summary");
        }
    }
    private String getCurrentIsoTime() {
        return new java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .format(new java.util.Date());
    }
    public Cursor getDirtyRows(String tableName) {
        SQLiteDatabase db = this.getReadableDatabase();
        return db.query(tableName, null, "dirty = 1", null, null, null, null);
    }
    public void markAsSynced(String tableName, String idColumn, long rowId, String cloudId) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("cloud_id", cloudId);
        values.put("dirty", 0);
        db.update(tableName, values, idColumn + " = ?", new String[]{String.valueOf(rowId)});
    }




    /**
     * Gets all questionnaire summaries as a formatted table for export
     */
    public String getAllQuestionnaireSummariesForExport() {
        ensureQuestionnaireSummaryTableExists();
        SQLiteDatabase db = this.getReadableDatabase();
        StringBuilder result = new StringBuilder();

        // Create header row
        result.append("User_ID\tScore_A\tScore_B\tScore_C\tScore_D\tScore_E\tScore_F\tTOTAL_SCORE\t")
                .append("PHQ9_Filled\tBDI_Filled\tGAD7_Filled\tBRS_Filled\t")
                .append("PHQ9_Score\tBDI_Score\tGAD7_Score\tBRS_Score\t")
                .append("PHQ9_Triggered\tBDI_Triggered\tGAD7_Triggered\tBRS_Triggered\n");

        try (Cursor cursor = db.rawQuery("SELECT * FROM questionnaire_summaries ORDER BY id", null)) {
            int rowNumber = 1;
            while (cursor.moveToNext()) {
                // User ID (row number)
                result.append(rowNumber).append("\t");

                // Section scores
                result.append(cursor.getInt(cursor.getColumnIndexOrThrow("score_a"))).append("\t")
                        .append(cursor.getInt(cursor.getColumnIndexOrThrow("score_b"))).append("\t")
                        .append(cursor.getInt(cursor.getColumnIndexOrThrow("score_c"))).append("\t")
                        .append(cursor.getInt(cursor.getColumnIndexOrThrow("score_d"))).append("\t")
                        .append(cursor.getInt(cursor.getColumnIndexOrThrow("score_e"))).append("\t")
                        .append(cursor.getInt(cursor.getColumnIndexOrThrow("score_f"))).append("\t")
                        .append(cursor.getInt(cursor.getColumnIndexOrThrow("total_score"))).append("\t");

                // Filled status
                boolean phq9Filled = cursor.getInt(cursor.getColumnIndexOrThrow("phq9_filled")) > 0;
                boolean bdiFilled = cursor.getInt(cursor.getColumnIndexOrThrow("bdi_filled")) > 0;
                boolean gad7Filled = cursor.getInt(cursor.getColumnIndexOrThrow("gad7_filled")) > 0;
                boolean brsFilled = cursor.getInt(cursor.getColumnIndexOrThrow("brs_filled")) > 0;

                result.append(phq9Filled ? 1 : 0).append("\t")
                        .append(bdiFilled ? 1 : 0).append("\t")
                        .append(gad7Filled ? 1 : 0).append("\t")
                        .append(brsFilled ? 1 : 0).append("\t");

                // Questionnaire scores (-1 if not filled)
                int phq9Score = phq9Filled ? cursor.getInt(cursor.getColumnIndexOrThrow("phq9_score")) : -1;
                int bdiScore = bdiFilled ? cursor.getInt(cursor.getColumnIndexOrThrow("bdi_score")) : -1;
                int gad7Score = gad7Filled ? cursor.getInt(cursor.getColumnIndexOrThrow("gad7_score")) : -1;
                double brsScore = brsFilled ? cursor.getDouble(cursor.getColumnIndexOrThrow("brs_score")) : -1;

                result.append(phq9Score).append("\t")
                        .append(bdiScore).append("\t")
                        .append(gad7Score).append("\t")
                        .append(String.format("%.2f", brsScore)).append("\t");

                // Triggered flags
                result.append(cursor.getInt(cursor.getColumnIndexOrThrow("phq9_triggered"))).append("\t")
                        .append(cursor.getInt(cursor.getColumnIndexOrThrow("bdi_triggered"))).append("\t")
                        .append(cursor.getInt(cursor.getColumnIndexOrThrow("gad7_triggered"))).append("\t")
                        .append(cursor.getInt(cursor.getColumnIndexOrThrow("brs_triggered"))).append("\n");

                rowNumber++;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error exporting questionnaire summaries: " + e.getMessage());
            result.append("Error exporting data: ").append(e.getMessage());
        }

        return result.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ONE-TIME EMAIL NORMALISATION MIGRATION
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Normalises all user-email columns in AppData.db to lowercase.
     *
     * Handles:
     *  • biomarker_experiments.username       (stores user email)
     *  • questionnaire_responses.user_email
     *  • questionnaire_summaries.user_email   (UNIQUE — deduplicates first,
     *                                          keeping the most recent row)
     *
     * Idempotent — safe to call on every app launch.
     */
    public void normalizeEmailsToLowercase() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {
            // ── biomarker_experiments.username ────────────────────────────────
            db.execSQL("UPDATE " + TABLE_BIOMARKER_EXPERIMENTS
                    + " SET " + COLUMN_USERNAME + " = LOWER(TRIM(" + COLUMN_USERNAME + "))"
                    + " WHERE " + COLUMN_USERNAME + " != LOWER(TRIM(" + COLUMN_USERNAME + "))");

            // ── questionnaire_responses.user_email ────────────────────────────
            db.execSQL("UPDATE questionnaire_responses"
                    + " SET user_email = LOWER(TRIM(user_email))"
                    + " WHERE user_email != LOWER(TRIM(user_email))");

            // ── questionnaire_summaries.user_email (UNIQUE) ───────────────────
            // If two rows share the same lowercase email, keep the newer one
            // (MAX id) to preserve the most recent questionnaire state.
            db.execSQL(
                    "DELETE FROM questionnaire_summaries"
                            + " WHERE id NOT IN ("
                            + "   SELECT MAX(id) FROM questionnaire_summaries"
                            + "   GROUP BY LOWER(TRIM(user_email))"
                            + ")");
            db.execSQL("UPDATE questionnaire_summaries"
                    + " SET user_email = LOWER(TRIM(user_email))"
                    + " WHERE user_email != LOWER(TRIM(user_email))");

            db.setTransactionSuccessful();
            Log.d(TAG, "normalizeEmailsToLowercase: AppData.db migration complete");
        } catch (Exception e) {
            Log.e(TAG, "normalizeEmailsToLowercase failed: " + e.getMessage(), e);
        } finally {
            db.endTransaction();
        }
    }
}