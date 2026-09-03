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

    private static final String DATABASE_NAME = "UserProfile.db";
    private static final int DATABASE_VERSION = 4;

    // ── Users (patients) table ──────────────────────────────────────────────
    private static final String TABLE_USERS             = "users";
    private static final String COLUMN_ID               = "id";
    private static final String COLUMN_EMAIL            = "email";
    private static final String COLUMN_PASSWORD         = "password";
    private static final String COLUMN_ROLE             = "role";
    private static final String COLUMN_AGE              = "age";
    private static final String COLUMN_BIRTH_DATE       = "birth_date";
    private static final String COLUMN_SEX              = "sex";
    private static final String COLUMN_RACE             = "race";
    private static final String COLUMN_RELATIONSHIP_STATUS = "relationship_status";
    private static final String COLUMN_WEIGHT_KG        = "weight_kg";
    private static final String COLUMN_HEIGHT_M         = "height_m";
    private static final String COLUMN_BMI              = "bmi";

    // ── Care-provider extra profile columns ─────────────────────────────────
    private static final String COLUMN_CP_ORGANIZATION  = "organization";
    private static final String COLUMN_CP_DESIGNATION   = "designation";
    private static final String COLUMN_CP_DEPARTMENT    = "department";
    private static final String COLUMN_CP_PHONE         = "phone";
    private static final String COLUMN_CP_GENDER        = "gender";
    private static final String COLUMN_CP_DOB           = "dob";
    private static final String COLUMN_CP_ADDRESS       = "address";

    // ── Care-providers table ────────────────────────────────────────────────
    private static final String TABLE_CARE_PROVIDERS    = "care_providers";
    private static final String COLUMN_CP_ID            = "id";
    private static final String COLUMN_CP_NAME          = "name";
    private static final String COLUMN_CP_MEDICAL_ID    = "medical_id";
    private static final String COLUMN_CP_EMAIL         = "email";
    private static final String COLUMN_CP_PASSWORD      = "password";

    // ── CP ↔ Patient link table ─────────────────────────────────────────────
    private static final String TABLE_CP_PATIENT_INFO   = "cp_patient_info";

    public static final String COL_CP_EMAIL              = "patient_email";
    public static final String COL_CP_PROVIDER_MEDICAL_ID= "provider_medical_id";
    public static final String COL_CP_PATIENT_ID         = "patient_id";
    public static final String COL_CP_NAME               = "name";
    public static final String COL_CP_PHONE              = "phone";
    public static final String COL_CP_RELATIONSHIP       = "relationship_status";
    public static final String COL_CP_MEDICAL_CONDITIONS = "medical_conditions";

    // Auto-assigned password for patients pre-registered by a care provider (see
    // RegisterPatientActivity), before the patient sets their own during activation.
    // Shared here as a single source of truth rather than a literal repeated in two files.
    /** Legacy shared password used only to detect old clinic-created accounts so they can
     *  still log in once. New patients are given a random unguessable placeholder and a
     *  Firebase password-reset email instead. */
    public static final String DEFAULT_TEMP_PASSWORD = "Temp123!";

    // ── Session / singleton ─────────────────────────────────────────────────
    private static String currentUserEmail = "";
    private static UserDatabaseHelper instance;

    public static synchronized UserDatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new UserDatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    private UserDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        Log.d(TAG, "Database path: " + context.getDatabasePath(DATABASE_NAME).getAbsolutePath());
    }

    public static void setCurrentUserEmail(String email) {
        currentUserEmail = email;
        Log.d(TAG, "currentUserEmail " + (email == null || email.isEmpty() ? "cleared" : "set"));
    }

    public static String getCurrentUserEmail() {
        return currentUserEmail;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SCHEMA
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void onCreate(SQLiteDatabase db) {

        db.execSQL("CREATE TABLE " + TABLE_USERS + " ("
                + COLUMN_ID                + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COLUMN_EMAIL             + " TEXT UNIQUE NOT NULL, "
                + COLUMN_PASSWORD          + " TEXT NOT NULL, "
                + COLUMN_ROLE              + " TEXT NOT NULL DEFAULT 'PATIENT', "
                + COLUMN_AGE               + " INTEGER, "
                + COLUMN_BIRTH_DATE        + " TEXT, "
                + COLUMN_SEX               + " TEXT, "
                + COLUMN_RACE              + " TEXT, "
                + COLUMN_RELATIONSHIP_STATUS + " TEXT, "
                + COLUMN_WEIGHT_KG         + " REAL, "
                + COLUMN_HEIGHT_M          + " REAL, "
                + COLUMN_BMI               + " REAL, "
                + "cloud_id TEXT, updated_at TEXT, dirty INTEGER DEFAULT 1)");

        db.execSQL("CREATE TABLE " + TABLE_CARE_PROVIDERS + " ("
                + COLUMN_CP_ID           + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COLUMN_CP_NAME         + " TEXT NOT NULL, "
                + COLUMN_CP_MEDICAL_ID   + " TEXT UNIQUE NOT NULL, "
                + COLUMN_CP_EMAIL        + " TEXT UNIQUE NOT NULL, "
                + COLUMN_CP_PASSWORD     + " TEXT NOT NULL, "
                + COLUMN_CP_ORGANIZATION + " TEXT, "
                + COLUMN_CP_DESIGNATION  + " TEXT, "
                + COLUMN_CP_DEPARTMENT   + " TEXT, "
                + COLUMN_CP_PHONE        + " TEXT, "
                + COLUMN_CP_GENDER       + " TEXT, "
                + COLUMN_CP_DOB          + " TEXT, "
                + COLUMN_CP_ADDRESS      + " TEXT, "
                + "cloud_id TEXT, updated_at TEXT, dirty INTEGER DEFAULT 1)");

        db.execSQL("CREATE TABLE " + TABLE_CP_PATIENT_INFO + " ("
                + COL_CP_EMAIL               + " TEXT NOT NULL, "
                + COL_CP_PROVIDER_MEDICAL_ID + " TEXT NOT NULL, "
                + COL_CP_PATIENT_ID          + " TEXT NOT NULL, "
                + COL_CP_NAME                + " TEXT, "
                + COL_CP_PHONE               + " TEXT, "
                + COL_CP_RELATIONSHIP        + " TEXT, "
                + COL_CP_MEDICAL_CONDITIONS  + " TEXT, "
                + "cloud_id TEXT, updated_at TEXT, dirty INTEGER DEFAULT 1, "
                + "PRIMARY KEY(" + COL_CP_EMAIL + ", " + COL_CP_PROVIDER_MEDICAL_ID + "))");

        Log.d(TAG, "All tables created");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.w(TAG, "Upgrading DB " + oldVersion + " → " + newVersion);

        if (oldVersion < 4) {
            safeAddColumn(db, TABLE_USERS,            "cloud_id",   "TEXT");
            safeAddColumn(db, TABLE_USERS,            "updated_at", "TEXT");
            safeAddColumn(db, TABLE_USERS,            "dirty",      "INTEGER DEFAULT 1");
            safeAddColumn(db, TABLE_CARE_PROVIDERS,   "cloud_id",   "TEXT");
            safeAddColumn(db, TABLE_CARE_PROVIDERS,   "updated_at", "TEXT");
            safeAddColumn(db, TABLE_CARE_PROVIDERS,   "dirty",      "INTEGER DEFAULT 1");
            safeAddColumn(db, TABLE_CP_PATIENT_INFO,  "cloud_id",   "TEXT");
            safeAddColumn(db, TABLE_CP_PATIENT_INFO,  "updated_at", "TEXT");
            safeAddColumn(db, TABLE_CP_PATIENT_INFO,  "dirty",      "INTEGER DEFAULT 1");

            String now = getCurrentIsoTimeStatic();
            db.execSQL("UPDATE " + TABLE_USERS           + " SET updated_at='" + now + "', dirty=1");
            db.execSQL("UPDATE " + TABLE_CARE_PROVIDERS  + " SET updated_at='" + now + "', dirty=1");
            db.execSQL("UPDATE " + TABLE_CP_PATIENT_INFO + " SET updated_at='" + now + "', dirty=1");
        }
    }

    /** ALTER TABLE only if the column does not already exist (safe for repeated upgrades). */
    private void safeAddColumn(SQLiteDatabase db, String table, String column, String type) {
        try {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        } catch (Exception e) {
            Log.w(TAG, "safeAddColumn: " + column + " already exists in " + table + " (skipped)");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ROLE DETECTION  ← FIX 3: never default to PATIENT on null
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns "CARE_PROVIDER", "PATIENT", or null if credentials don't match either table.
     *
     * IMPORTANT: callers must treat null as "wrong credentials" — never fall
     * through to a PATIENT flow when null is returned.
     */
    public String getUserRole(String email, String password) {
        // Check care_providers first — a CP email must NEVER reach the users table check.
        if (isCareProviderRegistered(email)) {
            if (loginCareProvider(email, password)) {
                Log.d(TAG, "getUserRole → CARE_PROVIDER");
                return "CARE_PROVIDER";
            } else {
                // Email exists as CP but password is wrong — return null, not PATIENT.
                Log.w(TAG, "getUserRole → CP email matched but wrong password");
                return null;
            }
        }
        if (loginUser(email, password)) {
            Log.d(TAG, "getUserRole → PATIENT");
            return "PATIENT";
        }
        Log.d(TAG, "getUserRole → no match");
        return null;
    }

    /**
     * Convenience: given just an email (no password), return the account type.
     * Useful for showing the correct login error message.
     * Returns "CARE_PROVIDER", "PATIENT", or null if unknown.
     */
    public String getAccountTypeByEmail(String email) {
        if (email != null) email = email.trim().toLowerCase();
        if (isCareProviderRegistered(email)) return "CARE_PROVIDER";
        if (isUserRegistered(email))         return "PATIENT";
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CARE PROVIDER METHODS
    // ═══════════════════════════════════════════════════════════════════════

    public long registerCareProvider(String name, String medicalId, String email, String password) {
        if (email != null) email = email.trim().toLowerCase();
        if (isCareProviderRegistered(email)) {
            Log.w(TAG, "CP registration rejected: email already exists");
            return -1;
        }
        if (isMedicalIdTaken(medicalId)) {
            Log.w(TAG, "Medical ID taken: " + medicalId);
            return -2;
        }
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(COLUMN_CP_NAME,       name);
        v.put(COLUMN_CP_MEDICAL_ID, medicalId);
        v.put(COLUMN_CP_EMAIL,      email);
        v.put(COLUMN_CP_PASSWORD,   PasswordHasher.hash(password));
        v.put("updated_at", getCurrentIsoTime());
        v.put("dirty", 1);
        long id = db.insert(TABLE_CARE_PROVIDERS, null, v);
        if (id > 0) {
            setCurrentUserEmail(email);
            Log.d(TAG, "CP registered id=" + id);
        } else {
            Log.e(TAG, "CP registration failed");
        }
        return id;
    }

    public boolean loginCareProvider(String email, String password) {
        if (email != null) email = email.trim().toLowerCase();
        SQLiteDatabase db = this.getReadableDatabase();
        // Fetch by email only — passwords are salted, so they can't be matched inside the
        // SQL WHERE clause. Verification happens in Java below.
        Cursor c = db.query(TABLE_CARE_PROVIDERS, new String[]{COLUMN_CP_ID, COLUMN_CP_PASSWORD},
                COLUMN_CP_EMAIL + "=?", new String[]{email}, null, null, null);
        boolean ok = false;
        if (c.moveToFirst()) {
            long id = c.getLong(0);
            String stored = c.getString(1);
            if (PasswordHasher.isHashed(stored)) {
                ok = PasswordHasher.verify(password, stored);
            } else {
                // Legacy plaintext row from before hashing was added. Compare directly so
                // existing accounts keep working, then silently upgrade this row to a proper
                // hash so the plaintext value is never stored again after this login.
                ok = stored != null && stored.equals(password);
                if (ok) {
                    ContentValues upgrade = new ContentValues();
                    upgrade.put(COLUMN_CP_PASSWORD, PasswordHasher.hash(password));
                    db.update(TABLE_CARE_PROVIDERS, upgrade, COLUMN_CP_ID + "=?",
                            new String[]{String.valueOf(id)});
                    Log.i(TAG, "Upgraded CP password to hashed storage on login");
                }
            }
        }
        c.close();
        if (ok) setCurrentUserEmail(email);
        return ok;
    }

    public boolean isCareProviderRegistered(String email) {
        if (email != null) email = email.trim().toLowerCase();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.query(TABLE_CARE_PROVIDERS, new String[]{COLUMN_CP_ID},
                COLUMN_CP_EMAIL + "=?", new String[]{email}, null, null, null);
        boolean exists = c.getCount() > 0;
        c.close();
        return exists;
    }

    public boolean isMedicalIdTaken(String medicalId) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.query(TABLE_CARE_PROVIDERS, new String[]{COLUMN_CP_ID},
                COLUMN_CP_MEDICAL_ID + "=?", new String[]{medicalId}, null, null, null);
        boolean exists = c.getCount() > 0;
        c.close();
        return exists;
    }

    public String getCareProviderName(String email) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.query(TABLE_CARE_PROVIDERS, new String[]{COLUMN_CP_NAME},
                COLUMN_CP_EMAIL + "=?", new String[]{email}, null, null, null);
        String name = "";
        if (c.moveToFirst()) name = c.getString(c.getColumnIndexOrThrow(COLUMN_CP_NAME));
        c.close();
        return name;
    }

    public String getCareProviderMedicalId(String email) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.query(TABLE_CARE_PROVIDERS, new String[]{COLUMN_CP_MEDICAL_ID},
                COLUMN_CP_EMAIL + "=?", new String[]{email}, null, null, null);
        String id = "";
        if (c.moveToFirst()) id = c.getString(c.getColumnIndexOrThrow(COLUMN_CP_MEDICAL_ID));
        c.close();
        return id;
    }

    public String getCareProviderNameByMedicalId(String medicalId) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.query(TABLE_CARE_PROVIDERS, new String[]{COLUMN_CP_NAME},
                COLUMN_CP_MEDICAL_ID + "=?", new String[]{medicalId}, null, null, null);
        String name = null;
        if (c != null && c.moveToFirst()) {
            name = c.getString(c.getColumnIndexOrThrow(COLUMN_CP_NAME));
            c.close();
        }
        return name;
    }

    public String getCareProviderOrganizationByMedicalId(String medicalId) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.query(TABLE_CARE_PROVIDERS, new String[]{COLUMN_CP_ORGANIZATION},
                COLUMN_CP_MEDICAL_ID + "=?", new String[]{medicalId}, null, null, null);
        String org = null;
        if (c != null && c.moveToFirst()) {
            org = c.getString(c.getColumnIndexOrThrow(COLUMN_CP_ORGANIZATION));
            c.close();
        }
        return org;
    }

    public String getCareProviderDesignationByMedicalId(String medicalId) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.query(TABLE_CARE_PROVIDERS, new String[]{COLUMN_CP_DESIGNATION},
                COLUMN_CP_MEDICAL_ID + "=?", new String[]{medicalId}, null, null, null);
        String desig = null;
        if (c != null && c.moveToFirst()) {
            desig = c.getString(c.getColumnIndexOrThrow(COLUMN_CP_DESIGNATION));
            c.close();
        }
        return desig;
    }

    public String getCareProviderMedicalIdForPatient(String patientEmail) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.query(TABLE_CP_PATIENT_INFO, new String[]{COL_CP_PROVIDER_MEDICAL_ID},
                COL_CP_EMAIL + "=?", new String[]{patientEmail}, null, null, null);
        String medId = null;
        if (c != null && c.moveToFirst()) {
            medId = c.getString(c.getColumnIndexOrThrow(COL_CP_PROVIDER_MEDICAL_ID));
            c.close();
        }
        return medId;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PATIENT METHODS
    // ═══════════════════════════════════════════════════════════════════════

    public long registerUser(String email, String password, String birthDate,
                             String sex, String race, String relationshipStatus,
                             float weightKg, float heightM, float bmi) {
        if (email != null) email = email.trim().toLowerCase();
        if (isUserRegistered(email)) {
            Log.w(TAG, "Patient registration rejected: email already exists");
            return -1;
        }
        int age = calculateAgeFromBirthDate(birthDate);
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(COLUMN_EMAIL,               email);
        v.put(COLUMN_PASSWORD,            PasswordHasher.hash(password));
        v.put(COLUMN_AGE,                 age);
        v.put(COLUMN_BIRTH_DATE,          birthDate);
        v.put(COLUMN_SEX,                 sex);
        v.put(COLUMN_RACE,                race);
        v.put(COLUMN_RELATIONSHIP_STATUS, relationshipStatus);
        v.put(COLUMN_WEIGHT_KG,           weightKg);
        v.put(COLUMN_HEIGHT_M,            heightM);
        v.put(COLUMN_BMI,                 bmi);
        v.put("updated_at", getCurrentIsoTime());
        v.put("dirty", 1);
        long id = db.insert(TABLE_USERS, null, v);
        if (id > 0) Log.d(TAG, "Patient registered id=" + id);
        else        Log.e(TAG, "Patient registration failed");
        return id;
    }

    public boolean loginUser(String email, String password) {
        if (email != null) email = email.trim().toLowerCase();
        SQLiteDatabase db = this.getReadableDatabase();
        // Fetch by email only — passwords are salted, so they can't be matched inside the
        // SQL WHERE clause. Verification happens in Java below.
        Cursor c = db.query(TABLE_USERS, new String[]{COLUMN_ID, COLUMN_PASSWORD},
                COLUMN_EMAIL + "=?", new String[]{email}, null, null, null);
        boolean ok = false;
        if (c.moveToFirst()) {
            long id = c.getLong(0);
            String stored = c.getString(1);
            if (PasswordHasher.isHashed(stored)) {
                ok = PasswordHasher.verify(password, stored);
            } else {
                // Legacy plaintext row from before hashing was added. Compare directly so
                // existing accounts keep working, then silently upgrade this row to a proper
                // hash so the plaintext value is never stored again after this login.
                ok = stored != null && stored.equals(password);
                if (ok) {
                    ContentValues upgrade = new ContentValues();
                    upgrade.put(COLUMN_PASSWORD, PasswordHasher.hash(password));
                    db.update(TABLE_USERS, upgrade, COLUMN_ID + "=?",
                            new String[]{String.valueOf(id)});
                    Log.i(TAG, "Upgraded patient password to hashed storage on login");
                }
            }
        }
        c.close();
        if (ok) {
            setCurrentUserEmail(email);
            Log.d(TAG, "Patient logged in");
        }
        return ok;
    }

    public boolean isUserRegistered(String email) {
        if (email != null) email = email.trim().toLowerCase();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.query(TABLE_USERS, new String[]{COLUMN_ID},
                COLUMN_EMAIL + "=?", new String[]{email}, null, null, null);
        int count = c.getCount();
        c.close();
        return count > 0;
    }

    public boolean checkUserExists(String email) {
        return isUserRegistered(email);
    }

    public UserData getUserData(String email) {
        if (email == null || email.isEmpty()) email = currentUserEmail;
        SQLiteDatabase db = this.getReadableDatabase();
        UserData userData = null;
        String[] cols = {COLUMN_ID, COLUMN_EMAIL, COLUMN_AGE, COLUMN_BIRTH_DATE,
                COLUMN_SEX, COLUMN_RACE, COLUMN_RELATIONSHIP_STATUS,
                COLUMN_WEIGHT_KG, COLUMN_HEIGHT_M, COLUMN_BMI};
        Cursor c = null;
        try {
            c = db.query(TABLE_USERS, cols, COLUMN_EMAIL + "=?", new String[]{email},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                userData = new UserData();
                userData.setId(c.getInt(c.getColumnIndexOrThrow(COLUMN_ID)));
                userData.setEmail(c.getString(c.getColumnIndexOrThrow(COLUMN_EMAIL)));
                userData.setAge(c.getInt(c.getColumnIndexOrThrow(COLUMN_AGE)));
                userData.setBirthDate(c.getString(c.getColumnIndexOrThrow(COLUMN_BIRTH_DATE)));
                userData.setSex(c.getString(c.getColumnIndexOrThrow(COLUMN_SEX)));
                userData.setRace(c.getString(c.getColumnIndexOrThrow(COLUMN_RACE)));
                userData.setRelationshipStatus(c.getString(c.getColumnIndexOrThrow(COLUMN_RELATIONSHIP_STATUS)));
                userData.setWeightKg(c.getFloat(c.getColumnIndexOrThrow(COLUMN_WEIGHT_KG)));
                userData.setHeightM(c.getFloat(c.getColumnIndexOrThrow(COLUMN_HEIGHT_M)));
                userData.setBmi(c.getFloat(c.getColumnIndexOrThrow(COLUMN_BMI)));
                Log.d(TAG, "getUserData ok");
            } else {
                Log.w(TAG, "getUserData: no matching row");
            }
        } catch (Exception e) {
            Log.e(TAG, "getUserData error: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return userData;
    }

    public int getUserAge(String email) {
        if (email == null || email.isEmpty()) email = currentUserEmail;
        UserData d = getUserData(email);
        return d != null ? d.getAge() : -1;
    }

    public String getUserBirthDate(String email) {
        if (email == null || email.isEmpty()) email = currentUserEmail;
        UserData d = getUserData(email);
        return d != null ? d.getBirthDate() : null;
    }

    public List<String> getAllPatientEmails() {
        List<String> emails = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.query(TABLE_USERS, new String[]{COLUMN_EMAIL},
                null, null, null, null, null);
        if (c.moveToFirst()) {
            do { emails.add(c.getString(c.getColumnIndexOrThrow(COLUMN_EMAIL))); }
            while (c.moveToNext());
        }
        c.close();
        return emails;
    }

    public boolean updateUserDemographics(String email, String sex, String race,
                                          String relationshipStatus, float weightKg,
                                          float heightM, float bmi) {
        if (email == null || email.isEmpty()) email = currentUserEmail;
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(COLUMN_SEX,                 sex);
        v.put(COLUMN_RACE,                race);
        v.put(COLUMN_RELATIONSHIP_STATUS, relationshipStatus);
        v.put(COLUMN_WEIGHT_KG,           weightKg);
        v.put(COLUMN_HEIGHT_M,            heightM);
        v.put(COLUMN_BMI,                 bmi);
        v.put("updated_at", getCurrentIsoTime());
        v.put("dirty", 1);
        int rows = db.update(TABLE_USERS, v, COLUMN_EMAIL + "=?", new String[]{email});
        if (rows > 0) { Log.d(TAG, "Demographics updated"); return true; }
        Log.e(TAG, "Demographics update failed");
        return false;
    }

    public boolean updateUserAge(String email, int age, String birthDate) {
        if (email == null || email.isEmpty()) email = currentUserEmail;
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(COLUMN_AGE,        age);
        v.put(COLUMN_BIRTH_DATE, birthDate);
        v.put("updated_at", getCurrentIsoTime());
        v.put("dirty", 1);
        return db.update(TABLE_USERS, v, COLUMN_EMAIL + "=?", new String[]{email}) > 0;
    }

    public boolean updateUserPassword(String email, String newPassword) {
        if (email == null || email.isEmpty()) return false;
        email = email.trim().toLowerCase();
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(COLUMN_PASSWORD, PasswordHasher.hash(newPassword));
        return db.update(TABLE_USERS, v, COLUMN_EMAIL + "=?", new String[]{email}) > 0;
    }

    public boolean hasPasswordSet(String email) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.query(TABLE_USERS, new String[]{COLUMN_PASSWORD},
                COLUMN_EMAIL + "=?", new String[]{email}, null, null, null);
        boolean has = false;
        if (c != null && c.moveToFirst()) {
            String pwd = c.getString(c.getColumnIndexOrThrow(COLUMN_PASSWORD));
            has = pwd != null && !pwd.isEmpty();
            c.close();
        }
        return has;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // calculateAgeFromBirthDate
    //
    // OLD: only parsed "MM/dd/yyyy" → legacy "yyyy-MM-dd" rows returned -1
    //      → hasCompleteDemographicData returned false every login.
    //
    // NEW: tries "MM/dd/yyyy" then "yyyy-MM-dd" fallback.
    //      Month/day comparison replaces the broken DAY_OF_YEAR check that
    //      mis-calculated ages across leap-year boundaries.
    // ═══════════════════════════════════════════════════════════════════════
    public int calculateAgeFromBirthDate(String birthDate) {
        if (birthDate == null || birthDate.isEmpty()) return -1;

        Date birth = tryParse(birthDate, "MM/dd/yyyy");
        if (birth == null) birth = tryParse(birthDate, "yyyy-MM-dd");
        if (birth == null) {
            Log.e(TAG, "calculateAgeFromBirthDate: cannot parse '" + birthDate + "'");
            return -1;
        }

        Calendar bCal  = Calendar.getInstance();
        bCal.setTime(birth);
        Calendar today = Calendar.getInstance();

        int age = today.get(Calendar.YEAR) - bCal.get(Calendar.YEAR);
        // Subtract 1 if birthday hasn't occurred yet this calendar year
        if (today.get(Calendar.MONTH) < bCal.get(Calendar.MONTH) ||
                (today.get(Calendar.MONTH) == bCal.get(Calendar.MONTH) &&
                        today.get(Calendar.DAY_OF_MONTH) < bCal.get(Calendar.DAY_OF_MONTH))) {
            age--;
        }
        Log.d(TAG, "calculateAgeFromBirthDate('" + birthDate + "') = " + age);
        return age;
    }

    /** Null-safe date parser; returns null on any failure. */
    private Date tryParse(String date, String pattern) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.US);
            sdf.setLenient(false);
            return sdf.parse(date);
        } catch (Exception e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // FIX 3 — isPatientPreRegisteredByProvider
    //
    // NEW helper: returns true when a CP has added this email to
    // cp_patient_info but the patient has NOT yet self-registered in users.
    // Use this in LoginActivity / RegisterActivity to route them to
    // UserProfileActivity with activation_mode=true instead of letting them
    // create a duplicate account.
    // ═══════════════════════════════════════════════════════════════════════
    public boolean isPatientPreRegisteredByProvider(String email) {
        if (email == null || email.isEmpty()) return false;
        return isPatientLinkedToAnyProvider(email) && !isUserRegistered(email);
    }

    public boolean isPatientLinkedToAnyProvider(String email) {
        if (email == null || email.isEmpty()) return false;
        email = email.trim().toLowerCase();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.query(TABLE_CP_PATIENT_INFO, new String[]{COL_CP_EMAIL},
                COL_CP_EMAIL + "=?", new String[]{email}, null, null, null);
        boolean linked = c != null && c.moveToFirst();
        if (c != null) c.close();
        Log.d(TAG, "isPatientLinkedToAnyProvider=" + linked);
        return linked;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ONE-TIME EMAIL NORMALISATION + DEDUPLICATION MIGRATION
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Cleans up the UserProfile.db to remove duplicate rows that were created
     * because the same email was stored in different cases (e.g. "John@gmail.com"
     * and "john@gmail.com" became two separate rows).
     *
     * Rules for picking the survivor when duplicates exist:
     *   1. Prefer the row whose password is NOT the auto-assigned "Temp123!"
     *      (i.e. the patient already self-registered or set their own password).
     *   2. Among equals, prefer the row with a higher age value (more complete).
     *   3. Final tie-break: keep the row with the lower id (earliest registration).
     *
     * After deduplication, ALL email columns across every table are normalised
     * to lowercase so future comparisons are always case-insensitive.
     *
     * This method is idempotent — safe to call on every app launch.
     */
    public void normalizeAndDeduplicateEmails() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {

            // ── STEP 1: Merge duplicate rows in the users table ───────────────
            // Find every lowercase-email that appears more than once.
            Cursor dupCursor = db.rawQuery(
                    "SELECT LOWER(TRIM(email)) AS le "
                    + "FROM " + TABLE_USERS
                    + " GROUP BY LOWER(TRIM(email)) HAVING COUNT(*) > 1",
                    null);

            while (dupCursor.moveToNext()) {
                String lowerEmail = dupCursor.getString(0);

                // Passwords are now salted hashes, so "is this row still on the default
                // Temp123! password" can no longer be checked inside SQL (a hash never
                // literally equals 'Temp123!'). Fetch candidates and rank them in Java
                // instead, using PasswordHasher to check hashed rows and a direct compare
                // for any not-yet-upgraded legacy plaintext rows.
                Cursor rows = db.rawQuery(
                        "SELECT id, " + COLUMN_PASSWORD + ", " + COLUMN_AGE + " FROM " + TABLE_USERS
                        + " WHERE LOWER(TRIM(email)) = ?",
                        new String[]{lowerEmail});

                List<Object[]> candidates = new ArrayList<>(); // {id, isDefaultPassword, age}
                while (rows.moveToNext()) {
                    long id = rows.getLong(0);
                    String storedPassword = rows.getString(1);
                    int age = rows.getInt(2);
                    boolean isDefaultPassword = PasswordHasher.isHashed(storedPassword)
                            ? PasswordHasher.verify(DEFAULT_TEMP_PASSWORD, storedPassword)
                            : DEFAULT_TEMP_PASSWORD.equals(storedPassword);
                    candidates.add(new Object[]{id, isDefaultPassword, age});
                }
                rows.close();

                // Survivor ranking, same rules as before: prefer a row that's NOT still on
                // the default password, then higher age, then lower id (earliest).
                candidates.sort((a, b) -> {
                    boolean aDefault = (Boolean) a[1], bDefault = (Boolean) b[1];
                    if (aDefault != bDefault) return aDefault ? 1 : -1;
                    int aAge = (Integer) a[2], bAge = (Integer) b[2];
                    if (aAge != bAge) return bAge - aAge;
                    return Long.compare((Long) a[0], (Long) b[0]);
                });

                if (!candidates.isEmpty()) {
                    long keepId = (Long) candidates.get(0)[0];
                    for (int i = 1; i < candidates.size(); i++) {
                        long losingId = (Long) candidates.get(i)[0];
                        db.execSQL("DELETE FROM " + TABLE_USERS + " WHERE id = ?",
                                new Object[]{losingId});
                        Log.i(TAG, "Email dedup: deleted duplicate user row id=" + losingId
                                + " (kept id=" + keepId + ")");
                    }
                }
            }
            dupCursor.close();

            // ── STEP 2: Normalise users.email to lowercase ────────────────────
            db.execSQL("UPDATE " + TABLE_USERS
                    + " SET email = LOWER(TRIM(email)), dirty = 1"
                    + " WHERE email != LOWER(TRIM(email))");

            // ── STEP 3: Normalise care_providers.email to lowercase ───────────
            db.execSQL("UPDATE " + TABLE_CARE_PROVIDERS
                    + " SET email = LOWER(TRIM(email)), dirty = 1"
                    + " WHERE email != LOWER(TRIM(email))");

            // ── STEP 4: Remove duplicate cp_patient_info rows ─────────────────
            // The PRIMARY KEY is (patient_email, provider_medical_id).  After
            // lowercasing, two rows with different-case emails for the same
            // provider would collide.  Keep the oldest (MIN rowid) for each combo.
            db.execSQL(
                    "DELETE FROM " + TABLE_CP_PATIENT_INFO
                    + " WHERE rowid NOT IN ("
                    + "   SELECT MIN(rowid) FROM " + TABLE_CP_PATIENT_INFO
                    + "   GROUP BY LOWER(TRIM(" + COL_CP_EMAIL + ")), "
                    +                    COL_CP_PROVIDER_MEDICAL_ID
                    + ")");

            // ── STEP 5: Normalise cp_patient_info.patient_email to lowercase ───
            db.execSQL("UPDATE " + TABLE_CP_PATIENT_INFO
                    + " SET " + COL_CP_EMAIL + " = LOWER(TRIM(" + COL_CP_EMAIL + ")), dirty = 1"
                    + " WHERE " + COL_CP_EMAIL + " != LOWER(TRIM(" + COL_CP_EMAIL + "))");

            db.setTransactionSuccessful();
            Log.i(TAG, "normalizeAndDeduplicateEmails: migration complete");
        } catch (Exception e) {
            Log.e(TAG, "normalizeAndDeduplicateEmails failed: " + e.getMessage(), e);
        } finally {
            db.endTransaction();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UNIT HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    public float poundsToKg(float pounds)                { return pounds * 0.45359237f; }
    public float feetInchesToMeters(int feet, int inches){ return ((feet * 12) + inches) * 0.0254f; }
    public float calculateBMI(float weightKg, float heightM) {
        return heightM <= 0 ? 0 : weightKg / (heightM * heightM);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DELETE / CLEAR
    // ═══════════════════════════════════════════════════════════════════════

    public boolean deleteUser(String email) {
        SQLiteDatabase db = this.getWritableDatabase();
        int rows = db.delete(TABLE_USERS, COLUMN_EMAIL + "=?", new String[]{email});
        if (rows > 0) {
            Log.d(TAG, "Patient deleted");
            if (email.equals(currentUserEmail)) currentUserEmail = "";
            return true;
        }
        Log.w(TAG, "deleteUser: not found");
        return false;
    }

    public void clearAllUserData() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_USERS,           null, null);
        db.delete(TABLE_CARE_PROVIDERS,  null, null);
        db.delete(TABLE_CP_PATIENT_INFO, null, null);
        Log.w(TAG, "All user data cleared");
        currentUserEmail = "";
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CP ↔ PATIENT LINK TABLE
    // ═══════════════════════════════════════════════════════════════════════

    public Cursor getCpPatientInfo(String email, String providerMedicalId) {
        return this.getReadableDatabase().query(TABLE_CP_PATIENT_INFO, null,
                COL_CP_EMAIL + "=? AND " + COL_CP_PROVIDER_MEDICAL_ID + "=?",
                new String[]{email, providerMedicalId}, null, null, null);
    }

    public String getNextPatientIdForProvider(String providerMedicalId) {
        String prefix = "CP" + providerMedicalId + "-P";
        Cursor c = this.getReadableDatabase().rawQuery(
                "SELECT " + COL_CP_PATIENT_ID + " FROM " + TABLE_CP_PATIENT_INFO
                        + " WHERE " + COL_CP_PROVIDER_MEDICAL_ID + "=?",
                new String[]{providerMedicalId});
        int max = 0;
        if (c != null) {
            while (c.moveToNext()) {
                String pid = c.getString(c.getColumnIndexOrThrow(COL_CP_PATIENT_ID));
                if (pid != null && pid.startsWith(prefix)) {
                    int dash = pid.lastIndexOf("-P");
                    if (dash != -1 && dash + 2 < pid.length()) {
                        try {
                            int n = Integer.parseInt(pid.substring(dash + 2));
                            if (n > max) max = n;
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            c.close();
        }
        return prefix + (max + 1);
    }

    public void upsertCpPatientInfo(String email, String providerMedicalId, String patientId,
                                    String name, String phone, String relationshipStatus,
                                    String medicalConditions) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(COL_CP_EMAIL,               email);
        v.put(COL_CP_PROVIDER_MEDICAL_ID, providerMedicalId);
        v.put(COL_CP_PATIENT_ID,          patientId);
        v.put(COL_CP_NAME,                name);
        v.put(COL_CP_PHONE,               phone);
        v.put(COL_CP_RELATIONSHIP,        relationshipStatus);
        v.put(COL_CP_MEDICAL_CONDITIONS,  medicalConditions);
        v.put("updated_at", getCurrentIsoTime());
        v.put("dirty", 1);
        int updated = db.update(TABLE_CP_PATIENT_INFO, v,
                COL_CP_EMAIL + "=? AND " + COL_CP_PROVIDER_MEDICAL_ID + "=?",
                new String[]{email, providerMedicalId});
        if (updated == 0) db.insert(TABLE_CP_PATIENT_INFO, null, v);
    }

    public static class CpPatientInfo {
        public final String email;
        public final String phone;
        public final String medicalConditions;
        public final String patientId;
        public final String name;

        public CpPatientInfo(String email, String phone, String medicalConditions,
                             String patientId, String name) {
            this.email             = email;
            this.phone             = phone;
            this.medicalConditions = medicalConditions;
            this.patientId         = patientId;
            this.name              = name;
        }
    }

    public List<CpPatientInfo> getPatientsForProvider(String providerMedicalId) {
        List<CpPatientInfo> list = new ArrayList<>();
        Cursor c = this.getReadableDatabase().query(TABLE_CP_PATIENT_INFO, null,
                COL_CP_PROVIDER_MEDICAL_ID + "=?", new String[]{providerMedicalId},
                null, null, COL_CP_PATIENT_ID + " ASC");
        if (c != null && c.moveToFirst()) {
            do {
                list.add(new CpPatientInfo(
                        c.getString(c.getColumnIndexOrThrow(COL_CP_EMAIL)),
                        c.getString(c.getColumnIndexOrThrow(COL_CP_PHONE)),
                        c.getString(c.getColumnIndexOrThrow(COL_CP_MEDICAL_CONDITIONS)),
                        c.getString(c.getColumnIndexOrThrow(COL_CP_PATIENT_ID)),
                        c.getString(c.getColumnIndexOrThrow(COL_CP_NAME))
                ));
            } while (c.moveToNext());
            c.close();
        }
        return list;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SYNC HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    public Cursor getDirtyRows(String tableName) {
        return this.getReadableDatabase().query(tableName, null,
                "dirty=1", null, null, null, null);
    }

    public void markAsSynced(String tableName, String idColumn, long rowId, String cloudId) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("cloud_id", cloudId);
        v.put("dirty", 0);
        db.update(tableName, v, idColumn + "=?", new String[]{String.valueOf(rowId)});
    }

    public void markAsSyncedByEmail(String tableName, String email, String cloudId) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("cloud_id", cloudId);
        v.put("dirty", 0);
        db.update(tableName, v, "patient_email=?", new String[]{email});
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INTERNAL HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private String getCurrentIsoTime() {
        return getCurrentIsoTimeStatic();
    }

    private static String getCurrentIsoTimeStatic() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .format(new Date());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DEBUG
    // ═══════════════════════════════════════════════════════════════════════

    public void logAllUsers() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query(TABLE_USERS, new String[]{COLUMN_ID, COLUMN_EMAIL},
                    null, null, null, null, null);
            Log.d(TAG, "Total patients: " + (c != null ? c.getCount() : 0));
            if (c != null && c.moveToFirst()) {
                do {
                    Log.d(TAG, "  id=" + c.getInt(c.getColumnIndexOrThrow(COLUMN_ID))
                            + " email=" + c.getString(c.getColumnIndexOrThrow(COLUMN_EMAIL)));
                } while (c.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "logAllUsers error: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
    }
}