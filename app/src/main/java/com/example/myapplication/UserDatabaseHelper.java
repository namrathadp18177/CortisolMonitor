package com.example.myapplication;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class UserDatabaseHelper extends SQLiteOpenHelper {

    private static final String TAG = "UserDatabaseHelper";

    // Database info
    private static final String DATABASE_NAME = "UserProfile.db";
    private static final int DATABASE_VERSION = 3;

    // Patient table
    private static final String TABLE_USERS = "users";
    private static final String COLUMN_ID = "id";
    private static final String COLUMN_EMAIL = "email";
    private static final String COLUMN_PASSWORD = "password";
    private static final String COLUMN_ROLE = "role";
    private static final String COLUMN_AGE = "age";
    private static final String COLUMN_BIRTH_DATE = "birth_date";
    private static final String COLUMN_SEX = "sex";
    private static final String COLUMN_RACE = "race";
    private static final String COLUMN_RELATIONSHIP_STATUS = "relationship_status";
    private static final String COLUMN_WEIGHT_KG = "weight_kg";
    private static final String COLUMN_HEIGHT_M = "height_m";
    private static final String COLUMN_BMI = "bmi";

    // Care Provider table
    private static final String TABLE_CARE_PROVIDERS = "care_providers";
    private static final String COLUMN_CP_ID = "id";
    private static final String COLUMN_CP_NAME = "name";
    private static final String COLUMN_CP_MEDICAL_ID = "medical_id";
    private static final String COLUMN_CP_EMAIL = "email";
    private static final String COLUMN_CP_PASSWORD = "password";

    // CP patient extra info table
    private static final String TABLE_CP_PATIENT_INFO = "cp_patient_info";

    // ===== CP Patient Info =====

    public static final String COL_CP_EMAIL = "patient_email";
    public static final String COL_CP_PROVIDER_MEDICAL_ID = "provider_medical_id";
    public static final String COL_CP_PATIENT_ID = "patient_id";
    public static final String COL_CP_NAME = "name";
    public static final String COL_CP_PHONE = "phone";
    public static final String COL_CP_RELATIONSHIP = "relationship_status";
    public static final String COL_CP_MEDICAL_CONDITIONS = "medical_conditions";


    // Current user session
    private static String currentUserEmail = "";

    // Singleton
    private static UserDatabaseHelper instance;

    public static synchronized UserDatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new UserDatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    private UserDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        Log.d(TAG, "Database created at: " + context.getDatabasePath(DATABASE_NAME).getAbsolutePath());
    }

    public static void setCurrentUserEmail(String email) {
        currentUserEmail = email;
        Log.d(TAG, "Current user email set to: " + email);
    }

    public static String getCurrentUserEmail() {
        return currentUserEmail;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String CREATE_USERS_TABLE = "CREATE TABLE " + TABLE_USERS + " ("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COLUMN_EMAIL + " TEXT UNIQUE NOT NULL, "
                + COLUMN_PASSWORD + " TEXT NOT NULL, "
                + COLUMN_ROLE + " TEXT NOT NULL DEFAULT 'PATIENT', "
                + COLUMN_AGE + " INTEGER, "
                + COLUMN_BIRTH_DATE + " TEXT, "
                + COLUMN_SEX + " TEXT, "
                + COLUMN_RACE + " TEXT, "
                + COLUMN_RELATIONSHIP_STATUS + " TEXT, "
                + COLUMN_WEIGHT_KG + " REAL, "
                + COLUMN_HEIGHT_M + " REAL, "
                + COLUMN_BMI + " REAL"
                + ")";
        db.execSQL(CREATE_USERS_TABLE);

        String CREATE_CARE_PROVIDERS_TABLE = "CREATE TABLE " + TABLE_CARE_PROVIDERS + " ("
                + COLUMN_CP_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COLUMN_CP_NAME + " TEXT NOT NULL, "
                + COLUMN_CP_MEDICAL_ID + " TEXT UNIQUE NOT NULL, "
                + COLUMN_CP_EMAIL + " TEXT UNIQUE NOT NULL, "
                + COLUMN_CP_PASSWORD + " TEXT NOT NULL"
                + ")";
        db.execSQL(CREATE_CARE_PROVIDERS_TABLE);

        db.execSQL(
                "CREATE TABLE " + TABLE_CP_PATIENT_INFO + " (" +
                        COL_CP_EMAIL + " TEXT NOT NULL, " +
                        COL_CP_PROVIDER_MEDICAL_ID + " TEXT NOT NULL, " +
                        COL_CP_PATIENT_ID + " TEXT NOT NULL, " +
                        COL_CP_NAME + " TEXT, " +
                        COL_CP_PHONE + " TEXT, " +
                        COL_CP_RELATIONSHIP + " TEXT, " +
                        COL_CP_MEDICAL_CONDITIONS + " TEXT, " +
                        "PRIMARY KEY(" + COL_CP_EMAIL + ", " + COL_CP_PROVIDER_MEDICAL_ID + ")" +
                        ");"
        );




        Log.d(TAG, "All tables created successfully");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.w(TAG, "Upgrading database from version " + oldVersion + " to " + newVersion);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CARE_PROVIDERS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_USERS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CP_PATIENT_INFO);

        onCreate(db);
    }

    // ─────────────────────────────────────────────
    // CARE PROVIDER METHODS
    // ─────────────────────────────────────────────

    public long registerCareProvider(String name, String medicalId, String email, String password) {
        if (isCareProviderRegistered(email)) {
            Log.w(TAG, "Care provider with email " + email + " already exists");
            return -1;
        }
        if (isMedicalIdTaken(medicalId)) {
            Log.w(TAG, "Medical ID " + medicalId + " already in use");
            return -2;
        }
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_CP_NAME, name);
        values.put(COLUMN_CP_MEDICAL_ID, medicalId);
        values.put(COLUMN_CP_EMAIL, email);
        values.put(COLUMN_CP_PASSWORD, password);
        long id = db.insert(TABLE_CARE_PROVIDERS, null, values);
        if (id > 0) {
            setCurrentUserEmail(email);
            Log.d(TAG, "Care provider registered successfully with ID: " + id);
        } else {
            Log.e(TAG, "Failed to register care provider");
        }
        return id;
    }

    public boolean loginCareProvider(String email, String password) {
        SQLiteDatabase db = this.getReadableDatabase();
        String selection = COLUMN_CP_EMAIL + " = ? AND " + COLUMN_CP_PASSWORD + " = ?";
        Cursor cursor = db.query(TABLE_CARE_PROVIDERS, new String[]{COLUMN_CP_ID},
                selection, new String[]{email, password}, null, null, null);
        boolean success = cursor.getCount() > 0;
        cursor.close();
        if (success) {
            setCurrentUserEmail(email);
            Log.d(TAG, "Care provider logged in: " + email);
        }
        return success;
    }

    public boolean isCareProviderRegistered(String email) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_CARE_PROVIDERS, new String[]{COLUMN_CP_ID},
                COLUMN_CP_EMAIL + " = ?", new String[]{email}, null, null, null);
        boolean exists = cursor.getCount() > 0;
        cursor.close();
        return exists;
    }

    public String getCareProviderName(String email) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_CARE_PROVIDERS, new String[]{COLUMN_CP_NAME},
                COLUMN_CP_EMAIL + " = ?", new String[]{email}, null, null, null);
        String name = "";
        if (cursor.moveToFirst()) {
            name = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CP_NAME));
        }
        cursor.close();
        return name;
    }

    public String getCareProviderMedicalId(String email) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_CARE_PROVIDERS, new String[]{COLUMN_CP_MEDICAL_ID},
                COLUMN_CP_EMAIL + " = ?", new String[]{email}, null, null, null);
        String medicalId = "";
        if (cursor.moveToFirst()) {
            medicalId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CP_MEDICAL_ID));
        }
        cursor.close();
        return medicalId;
    }

    /**
     * Returns "PATIENT", "CARE_PROVIDER", or null if credentials don't match anything
     */
    public String getUserRole(String email, String password) {
        if (loginUser(email, password)) return "PATIENT";
        if (loginCareProvider(email, password)) return "CARE_PROVIDER";
        return null;
    }

    /**
     * Returns all patient emails in the system (for Care Provider dashboard)
     */
    public List<String> getAllPatientEmails() {
        List<String> emails = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_USERS, new String[]{COLUMN_EMAIL},
                null, null, null, null, null);
        if (cursor.moveToFirst()) {
            do {
                emails.add(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EMAIL)));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return emails;
    }

    // ─────────────────────────────────────────────
    // EXISTING PATIENT METHODS (unchanged)
    // ─────────────────────────────────────────────

    public long registerUser(String email, String password, String birthDate,
                             String sex, String race, String relationshipStatus,
                             float weightKg, float heightM, float bmi) {
        if (isUserRegistered(email)) {
            Log.w(TAG, "User with email " + email + " already exists");
            return -1;
        }
        int age = calculateAgeFromBirthDate(birthDate);
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_EMAIL, email);
        values.put(COLUMN_PASSWORD, password);
        values.put(COLUMN_AGE, age);
        values.put(COLUMN_BIRTH_DATE, birthDate);
        values.put(COLUMN_SEX, sex);
        values.put(COLUMN_RACE, race);
        values.put(COLUMN_RELATIONSHIP_STATUS, relationshipStatus);
        values.put(COLUMN_WEIGHT_KG, weightKg);
        values.put(COLUMN_HEIGHT_M, heightM);
        values.put(COLUMN_BMI, bmi);
        long id = db.insert(TABLE_USERS, null, values);
        if (id > 0) {
            setCurrentUserEmail(email);
            Log.d(TAG, "User registered successfully with ID: " + id);
        } else {
            Log.e(TAG, "Failed to register user");
        }
        return id;
    }

    public boolean loginUser(String email, String password) {
        SQLiteDatabase db = this.getReadableDatabase();
        String selection = COLUMN_EMAIL + " = ? AND " + COLUMN_PASSWORD + " = ?";
        Cursor cursor = db.query(TABLE_USERS, new String[]{COLUMN_ID},
                selection, new String[]{email, password}, null, null, null);
        int count = cursor.getCount();
        cursor.close();
        if (count > 0) {
            setCurrentUserEmail(email);
            Log.d(TAG, "User logged in: " + email);
            return true;
        }
        return false;
    }

    public boolean isUserRegistered(String email) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_USERS, new String[]{COLUMN_ID},
                COLUMN_EMAIL + " = ?", new String[]{email}, null, null, null);
        int count = cursor.getCount();
        cursor.close();
        return count > 0;
    }

    public UserData getUserData(String email) {
        if (email == null || email.isEmpty()) {
            email = currentUserEmail;
        }
        SQLiteDatabase db = this.getReadableDatabase();
        UserData userData = null;
        String[] columns = {
                COLUMN_ID, COLUMN_EMAIL, COLUMN_AGE, COLUMN_BIRTH_DATE,
                COLUMN_SEX, COLUMN_RACE, COLUMN_RELATIONSHIP_STATUS,
                COLUMN_WEIGHT_KG, COLUMN_HEIGHT_M, COLUMN_BMI
        };
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_USERS, columns,
                    COLUMN_EMAIL + " = ?", new String[]{email}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                userData = new UserData();
                userData.setId(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID)));
                userData.setEmail(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EMAIL)));
                userData.setAge(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_AGE)));
                userData.setBirthDate(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_BIRTH_DATE)));
                userData.setSex(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SEX)));
                userData.setRace(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_RACE)));
                userData.setRelationshipStatus(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_RELATIONSHIP_STATUS)));
                userData.setWeightKg(cursor.getFloat(cursor.getColumnIndexOrThrow(COLUMN_WEIGHT_KG)));
                userData.setHeightM(cursor.getFloat(cursor.getColumnIndexOrThrow(COLUMN_HEIGHT_M)));
                userData.setBmi(cursor.getFloat(cursor.getColumnIndexOrThrow(COLUMN_BMI)));
                Log.d(TAG, "User data retrieved for: " + email);
            } else {
                Log.w(TAG, "No user data found for: " + email);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting user data: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return userData;
    }

    public int getUserAge(String email) {
        if (email == null || email.isEmpty()) email = currentUserEmail;
        UserData userData = getUserData(email);
        return userData != null ? userData.getAge() : -1;
    }

    public String getUserBirthDate(String email) {
        if (email == null || email.isEmpty()) email = currentUserEmail;
        UserData userData = getUserData(email);
        return userData != null ? userData.getBirthDate() : null;
    }

    public boolean updateUserDemographics(String email, String sex, String race,
                                          String relationshipStatus, float weightKg,
                                          float heightM, float bmi) {
        if (email == null || email.isEmpty()) email = currentUserEmail;
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_SEX, sex);
        values.put(COLUMN_RACE, race);
        values.put(COLUMN_RELATIONSHIP_STATUS, relationshipStatus);
        values.put(COLUMN_WEIGHT_KG, weightKg);
        values.put(COLUMN_HEIGHT_M, heightM);
        values.put(COLUMN_BMI, bmi);
        int rowsAffected = db.update(TABLE_USERS, values, COLUMN_EMAIL + " = ?", new String[]{email});
        if (rowsAffected > 0) {
            Log.d(TAG, "User demographics updated for: " + email);
            return true;
        }
        Log.e(TAG, "Failed to update demographics for: " + email);
        return false;
    }

    public boolean updateUserAge(String email, int age, String birthDate) {
        if (email == null || email.isEmpty()) email = currentUserEmail;
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_AGE, age);
        values.put(COLUMN_BIRTH_DATE, birthDate);
        int rowsAffected = db.update(TABLE_USERS, values, COLUMN_EMAIL + " = ?", new String[]{email});
        return rowsAffected > 0;
    }

    public boolean hasCompleteDemographicData(String email) {
        if (email == null || email.isEmpty()) email = currentUserEmail;
        UserData userData = getUserData(email);
        if (userData == null) return false;
        boolean hasComplete = userData.getSex() != null && !userData.getSex().isEmpty()
                && userData.getRace() != null && !userData.getRace().isEmpty()
                && userData.getRelationshipStatus() != null && !userData.getRelationshipStatus().isEmpty()
                && userData.getWeightKg() > 0
                && userData.getHeightM() > 0;
        Log.d(TAG, "User " + email + " has complete demographic data: " + hasComplete);
        return hasComplete;
    }

    public int calculateAgeFromBirthDate(String birthDate) {
        if (birthDate == null || birthDate.isEmpty()) return -1;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy", Locale.US);
            Date birth = sdf.parse(birthDate);
            Calendar birthCal = Calendar.getInstance();
            birthCal.setTime(birth);
            Calendar today = Calendar.getInstance();
            int age = today.get(Calendar.YEAR) - birthCal.get(Calendar.YEAR);
            if (today.get(Calendar.DAY_OF_YEAR) < birthCal.get(Calendar.DAY_OF_YEAR)) age--;
            return age;
        } catch (Exception e) {
            Log.e(TAG, "Error calculating age: " + e.getMessage());
            return -1;
        }
    }

    public float poundsToKg(float pounds) {
        return pounds * 0.45359237f;
    }

    public float feetInchesToMeters(int feet, int inches) {
        float totalInches = (feet * 12) + inches;
        return totalInches * 0.0254f;
    }

    public float calculateBMI(float weightKg, float heightM) {
        if (heightM <= 0) return 0;
        return weightKg / (heightM * heightM);
    }

    public boolean deleteUser(String email) {
        SQLiteDatabase db = this.getWritableDatabase();
        int rowsDeleted = db.delete(TABLE_USERS, COLUMN_EMAIL + " = ?", new String[]{email});
        if (rowsDeleted > 0) {
            Log.d(TAG, "User deleted: " + email);
            if (email.equals(currentUserEmail)) currentUserEmail = "";
            return true;
        }
        Log.w(TAG, "No user found to delete: " + email);
        return false;
    }

    public void clearAllUserData() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_USERS, null, null);
        db.delete(TABLE_CARE_PROVIDERS, null, null);
        Log.w(TAG, "All user data cleared");
        currentUserEmail = "";
    }
    public boolean isMedicalIdTaken(String medicalId) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_CARE_PROVIDERS, new String[]{COLUMN_CP_ID},
                COLUMN_CP_MEDICAL_ID + " = ?", new String[]{medicalId}, null, null, null);
        boolean exists = cursor.getCount() > 0;
        cursor.close();
        return exists;
    }
    // Get existing cp_patient_info for this email + provider (if any)
    public Cursor getCpPatientInfo(String email, String providerMedicalId) {
        SQLiteDatabase db = this.getReadableDatabase();
        return db.query(
                TABLE_CP_PATIENT_INFO,
                null,
                COL_CP_EMAIL + "=? AND " + COL_CP_PROVIDER_MEDICAL_ID + "=?",
                new String[]{email, providerMedicalId},
                null, null, null
        );
    }

    // Generate next patient ID for this provider, e.g. CP123-P1, CP123-P2...
    public String getNextPatientIdForProvider(String providerMedicalId) {
        SQLiteDatabase db = this.getReadableDatabase();
        String prefix = "CP" + providerMedicalId + "-P";

        Cursor cursor = db.rawQuery(
                "SELECT " + COL_CP_PATIENT_ID +
                        " FROM " + TABLE_CP_PATIENT_INFO +
                        " WHERE " + COL_CP_PROVIDER_MEDICAL_ID + "=?",
                new String[]{providerMedicalId}
        );

        int max = 0;
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String pid = cursor.getString(cursor.getColumnIndexOrThrow(COL_CP_PATIENT_ID));
                if (pid != null && pid.startsWith(prefix)) {
                    int dashIndex = pid.lastIndexOf("-P");
                    if (dashIndex != -1 && dashIndex + 2 < pid.length()) {
                        try {
                            int num = Integer.parseInt(pid.substring(dashIndex + 2));
                            if (num > max) max = num;
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            cursor.close();
        }
        int next = max + 1;
        return prefix + next;
    }

    // Insert or update cp_patient_info
    public void upsertCpPatientInfo(String email,
                                    String providerMedicalId,
                                    String patientId,
                                    String name,
                                    String phone,
                                    String relationshipStatus,
                                    String medicalConditions) {

        SQLiteDatabase db = this.getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put(COL_CP_EMAIL, email);
        values.put(COL_CP_PROVIDER_MEDICAL_ID, providerMedicalId);
        values.put(COL_CP_PATIENT_ID, patientId);
        values.put(COL_CP_NAME, name);
        values.put(COL_CP_PHONE, phone);
        values.put(COL_CP_RELATIONSHIP, relationshipStatus);
        values.put(COL_CP_MEDICAL_CONDITIONS, medicalConditions);

        int updated = db.update(
                TABLE_CP_PATIENT_INFO,
                values,
                COL_CP_EMAIL + "=? AND " + COL_CP_PROVIDER_MEDICAL_ID + "=?",
                new String[]{email, providerMedicalId}
        );

        if (updated == 0) {
            db.insert(TABLE_CP_PATIENT_INFO, null, values);
        }
    }





    public static class CpPatientInfo {
        public final String email;
        public final String phone;
        public final String medicalConditions;
        public final String patientId;
        public final String name;

        public CpPatientInfo(String email, String phone, String medicalConditions,
                             String patientId, String name) {
            this.email = email;
            this.phone = phone;
            this.medicalConditions = medicalConditions;
            this.patientId = patientId;
            this.name = name;
        }
    }
    public List<CpPatientInfo> getPatientsForProvider(String providerMedicalId) {
        List<CpPatientInfo> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();

        Cursor cursor = db.query(
                TABLE_CP_PATIENT_INFO,
                null,
                COL_CP_PROVIDER_MEDICAL_ID + "=?",
                new String[]{providerMedicalId},
                null, null,
                COL_CP_PATIENT_ID + " ASC"
        );

        if (cursor != null && cursor.moveToFirst()) {
            do {
                String email = cursor.getString(cursor.getColumnIndexOrThrow(COL_CP_EMAIL));
                String phone = cursor.getString(cursor.getColumnIndexOrThrow(COL_CP_PHONE));
                String medicalConditions = cursor.getString(cursor.getColumnIndexOrThrow(COL_CP_MEDICAL_CONDITIONS));
                String patientId = cursor.getString(cursor.getColumnIndexOrThrow(COL_CP_PATIENT_ID));
                String name = cursor.getString(cursor.getColumnIndexOrThrow(COL_CP_NAME));

                list.add(new CpPatientInfo(email, phone, medicalConditions, patientId, name));
            } while (cursor.moveToNext());
            cursor.close();
        }

        return list;
    }


    // Simple wrapper so Activities can ask if a user exists
    public boolean checkUserExists(String email) {
        return isUserRegistered(email);
    }




    public void logAllUsers() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_USERS, new String[]{COLUMN_ID, COLUMN_EMAIL},
                    null, null, null, null, null);
            Log.d(TAG, "Total patients: " + (cursor != null ? cursor.getCount() : 0));
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    int id = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID));
                    String email = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EMAIL));
                    Log.d(TAG, "Patient ID: " + id + ", Email: " + email);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error logging users: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
    }
}
