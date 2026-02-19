package com.example.myapplication;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class UserDatabaseHelper extends SQLiteOpenHelper {
    private static final String TAG = "UserDatabaseHelper";

    // Database info
    private static final String DATABASE_NAME = "UserProfile.db";
    private static final int DATABASE_VERSION = 1;

    // Table name
    private static final String TABLE_USERS = "users";

    private static final String COLUMN_ROLE = "role"; // NEW


    // User table columns
    private static final String COLUMN_ID = "id";
    private static final String COLUMN_EMAIL = "email";
    private static final String COLUMN_PASSWORD = "password";
    private static final String COLUMN_AGE = "age";
    private static final String COLUMN_BIRTH_DATE = "birth_date";
    private static final String COLUMN_SEX = "sex";
    private static final String COLUMN_RACE = "race";
    private static final String COLUMN_RELATIONSHIP_STATUS = "relationship_status";
    private static final String COLUMN_WEIGHT_KG = "weight_kg";
    private static final String COLUMN_HEIGHT_M = "height_m";
    private static final String COLUMN_BMI = "bmi";

    // Store current user email
    private static String currentUserEmail = "";

    // Singleton instance
    private static UserDatabaseHelper instance;

    // Get singleton instance
    public static synchronized UserDatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new UserDatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    // Private constructor for singleton pattern
    private UserDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        Log.d(TAG, "Database created at: " + context.getDatabasePath(DATABASE_NAME).getAbsolutePath());
    }

    // Method to set current user email
    public static void setCurrentUserEmail(String email) {
        currentUserEmail = email;
        Log.d(TAG, "Current user email set to: " + email);
    }

    // Method to get current user email
    public static String getCurrentUserEmail() {
        return currentUserEmail;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // Create users table with all demographic fields
        String CREATE_USERS_TABLE = "CREATE TABLE " + TABLE_USERS + " ("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COLUMN_EMAIL + " TEXT UNIQUE NOT NULL, "
                + COLUMN_PASSWORD + " TEXT NOT NULL, "
                + COLUMN_ROLE + " TEXT NOT NULL DEFAULT 'PATIENT', " // NEW
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
        Log.d(TAG, "Users table created successfully");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Simple upgrade policy: drop and recreate tables
        Log.w(TAG, "Upgrading database from version " + oldVersion + " to " + newVersion);

        // In a production app, you might want to implement a more sophisticated migration
        // that preserves user data across upgrades
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_USERS);
        onCreate(db);
    }

    /**
     * Register a new user with all demographic information
     */
    public long registerUser(String email, String password, String birthDate,
                             String sex, String race, String relationshipStatus,
                             float weightKg, float heightM, float bmi) {

        // Check if user already exists
        if (isUserRegistered(email)) {
            Log.w(TAG, "User with email " + email + " already exists");
            return -1;
        }

        // Calculate age from birth date
        int age = calculateAgeFromBirthDate(birthDate);

        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();

        // Add user data to values
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

        // Insert row
        long id = db.insert(TABLE_USERS, null, values);

        if (id > 0) {
            Log.d(TAG, "User registered successfully with ID: " + id);
        } else {
            Log.e(TAG, "Failed to register user");
        }

        // Set current user
        if (id > 0) {
            setCurrentUserEmail(email);
        }

        return id;
    }

    /**
     * Check if user exists and credentials are valid
     */
    public boolean loginUser(String email, String password) {
        SQLiteDatabase db = this.getReadableDatabase();

        String[] columns = { COLUMN_ID };
        String selection = COLUMN_EMAIL + " = ? AND " + COLUMN_PASSWORD + " = ?";
        String[] selectionArgs = { email, password };

        Cursor cursor = db.query(
                TABLE_USERS,
                columns,
                selection,
                selectionArgs,
                null,
                null,
                null
        );

        int count = cursor.getCount();
        cursor.close();

        if (count > 0) {
            // Set current user
            setCurrentUserEmail(email);
            Log.d(TAG, "User logged in: " + email);
            return true;
        }

        return false;
    }

    /**
     * Check if a user is already registered
     */
    public boolean isUserRegistered(String email) {
        SQLiteDatabase db = this.getReadableDatabase();

        String[] columns = { COLUMN_ID };
        String selection = COLUMN_EMAIL + " = ?";
        String[] selectionArgs = { email };

        Cursor cursor = db.query(
                TABLE_USERS,
                columns,
                selection,
                selectionArgs,
                null,
                null,
                null
        );

        int count = cursor.getCount();
        cursor.close();

        return count > 0;
    }

    /**
     * Get user data by email
     */
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

        String selection = COLUMN_EMAIL + " = ?";
        String[] selectionArgs = { email };

        Cursor cursor = null;

        try {
            cursor = db.query(
                    TABLE_USERS,
                    columns,
                    selection,
                    selectionArgs,
                    null,
                    null,
                    null
            );

            if (cursor != null && cursor.moveToFirst()) {
                // Get column indices safely
                int idIdx = cursor.getColumnIndexOrThrow(COLUMN_ID);
                int emailIdx = cursor.getColumnIndexOrThrow(COLUMN_EMAIL);
                int ageIdx = cursor.getColumnIndexOrThrow(COLUMN_AGE);
                int birthDateIdx = cursor.getColumnIndexOrThrow(COLUMN_BIRTH_DATE);
                int sexIdx = cursor.getColumnIndexOrThrow(COLUMN_SEX);
                int raceIdx = cursor.getColumnIndexOrThrow(COLUMN_RACE);
                int relationshipIdx = cursor.getColumnIndexOrThrow(COLUMN_RELATIONSHIP_STATUS);
                int weightIdx = cursor.getColumnIndexOrThrow(COLUMN_WEIGHT_KG);
                int heightIdx = cursor.getColumnIndexOrThrow(COLUMN_HEIGHT_M);
                int bmiIdx = cursor.getColumnIndexOrThrow(COLUMN_BMI);

                // Create UserData object
                userData = new UserData();
                userData.setId(cursor.getInt(idIdx));
                userData.setEmail(cursor.getString(emailIdx));
                userData.setAge(cursor.getInt(ageIdx));
                userData.setBirthDate(cursor.getString(birthDateIdx));
                userData.setSex(cursor.getString(sexIdx));
                userData.setRace(cursor.getString(raceIdx));
                userData.setRelationshipStatus(cursor.getString(relationshipIdx));
                userData.setWeightKg(cursor.getFloat(weightIdx));
                userData.setHeightM(cursor.getFloat(heightIdx));
                userData.setBmi(cursor.getFloat(bmiIdx));

                Log.d(TAG, "User data retrieved successfully for: " + email);
            } else {
                Log.w(TAG, "No user data found for: " + email);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting user data: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return userData;
    }

    /**
     * Get user age by email
     */
    public int getUserAge(String email) {
        if (email == null || email.isEmpty()) {
            email = currentUserEmail;
        }

        UserData userData = getUserData(email);
        if (userData != null) {
            return userData.getAge();
        }

        return -1; // Return -1 if user not found
    }

    /**
     * Get user birth date by email
     */
    public String getUserBirthDate(String email) {
        if (email == null || email.isEmpty()) {
            email = currentUserEmail;
        }

        UserData userData = getUserData(email);
        if (userData != null) {
            return userData.getBirthDate();
        }

        return null; // Return null if user not found
    }

    /**
     * Update user demographic information
     */
    public boolean updateUserDemographics(String email, String sex, String race,
                                          String relationshipStatus, float weightKg,
                                          float heightM, float bmi) {
        if (email == null || email.isEmpty()) {
            email = currentUserEmail;
        }

        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();

        // Add demographic values
        values.put(COLUMN_SEX, sex);
        values.put(COLUMN_RACE, race);
        values.put(COLUMN_RELATIONSHIP_STATUS, relationshipStatus);
        values.put(COLUMN_WEIGHT_KG, weightKg);
        values.put(COLUMN_HEIGHT_M, heightM);
        values.put(COLUMN_BMI, bmi);

        // Update row
        String whereClause = COLUMN_EMAIL + " = ?";
        String[] whereArgs = { email };

        int rowsAffected = db.update(TABLE_USERS, values, whereClause, whereArgs);

        if (rowsAffected > 0) {
            Log.d(TAG, "User demographics updated successfully for: " + email);
            return true;
        } else {
            Log.e(TAG, "Failed to update user demographics for: " + email);
            return false;
        }
    }

    /**
     * Update user age and birth date
     */
    public boolean updateUserAge(String email, int age, String birthDate) {
        if (email == null || email.isEmpty()) {
            email = currentUserEmail;
        }

        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();

        values.put(COLUMN_AGE, age);
        values.put(COLUMN_BIRTH_DATE, birthDate);

        String whereClause = COLUMN_EMAIL + " = ?";
        String[] whereArgs = { email };

        int rowsAffected = db.update(TABLE_USERS, values, whereClause, whereArgs);

        if (rowsAffected > 0) {
            Log.d(TAG, "User age and birth date updated successfully for: " + email);
            return true;
        } else {
            Log.e(TAG, "Failed to update user age and birth date for: " + email);
            return false;
        }
    }

    /**
     * Check if user has complete demographic data
     */
    public boolean hasCompleteDemographicData(String email) {
        if (email == null || email.isEmpty()) {
            email = currentUserEmail;
        }

        UserData userData = getUserData(email);

        if (userData == null) {
            return false;
        }

        // Check if all demographic fields are filled
        boolean hasComplete = userData.getSex() != null && !userData.getSex().isEmpty() &&
                userData.getRace() != null && !userData.getRace().isEmpty() &&
                userData.getRelationshipStatus() != null && !userData.getRelationshipStatus().isEmpty() &&
                userData.getWeightKg() > 0 &&
                userData.getHeightM() > 0;

        Log.d(TAG, "User " + email + " has complete demographic data: " + hasComplete);

        return hasComplete;
    }

    /**
     * Calculate age from birth date
     */
    public int calculateAgeFromBirthDate(String birthDate) {
        if (birthDate == null || birthDate.isEmpty()) {
            return -1;
        }

        try {
            SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy", Locale.US);
            Date birth = sdf.parse(birthDate);
            Calendar birthCal = Calendar.getInstance();
            birthCal.setTime(birth);

            Calendar today = Calendar.getInstance();

            int age = today.get(Calendar.YEAR) - birthCal.get(Calendar.YEAR);

            // Check if birthday has occurred this year
            if (today.get(Calendar.DAY_OF_YEAR) < birthCal.get(Calendar.DAY_OF_YEAR)) {
                age--;
            }

            return age;
        } catch (Exception e) {
            Log.e(TAG, "Error calculating age from birth date: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Helper methods for unit conversions
     */
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

    /**
     * Delete a user by email
     */
    public boolean deleteUser(String email) {
        SQLiteDatabase db = this.getWritableDatabase();

        String whereClause = COLUMN_EMAIL + " = ?";
        String[] whereArgs = { email };

        int rowsDeleted = db.delete(TABLE_USERS, whereClause, whereArgs);

        if (rowsDeleted > 0) {
            Log.d(TAG, "User deleted successfully: " + email);

            // Clear current user if it's the deleted one
            if (email.equals(currentUserEmail)) {
                currentUserEmail = "";
            }

            return true;
        } else {
            Log.w(TAG, "No user found to delete with email: " + email);
            return false;
        }
    }

    /**
     * Clear all user data (for testing or reset purposes)
     */
    public void clearAllUserData() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_USERS, null, null);
        Log.w(TAG, "All user data cleared");
        currentUserEmail = "";
    }



    /**
     * Log all users (for debugging)
     */
    public void logAllUsers() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;

        try {
            cursor = db.query(TABLE_USERS, new String[]{COLUMN_ID, COLUMN_EMAIL}, null, null, null, null, null);

            Log.d(TAG, "Total users: " + (cursor != null ? cursor.getCount() : 0));

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    int id = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID));
                    String email = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EMAIL));
                    Log.d(TAG, "User ID: " + id + ", Email: " + email);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error logging users: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }
}