package com.example.myapplication;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import android.util.Log;
import android.view.View;
import android.view.animation.BounceInterpolator;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import android.graphics.Paint;
import com.google.firebase.firestore.FirebaseFirestore;

public class RegisterLoginActivity extends AppCompatActivity {

    private static final String TAG = "RegisterLoginActivity";
    private static final String PREF_REMEMBER = "remember_me";
    private static final String PREF_EMAIL = "saved_email";
    private static final String PREF_PASSWORD = "saved_password";

    private TextInputEditText etEmail, etPassword, etConfirmPassword, etDateOfBirth, etAge;
    private TextInputEditText etWeightPounds, etHeightFeet, etHeightInches;
    private TextInputLayout dobInputLayout, ageInputLayout;
    private TextInputLayout weightInputLayout, heightFeetInputLayout, heightInchesInputLayout;
    private RadioGroup rgSex;
    private AutoCompleteTextView spinnerRace, spinnerRelationshipStatus;
    private MaterialButton btnSubmit;
    private TextView tvToggleMode;
    private ImageView ivWellnessIcon;
    private CheckBox cbRememberMe;
    private TextView tvSexLabel, tvRaceLabel, tvRelationshipLabel, tvWeightLabel, tvHeightLabel;
    private LinearLayout weightLayout, heightLayout;
    private TextInputLayout raceInputLayout, relationshipInputLayout;

    private TextInputEditText etCpName, etCpMedicalId, etCpEmail, etCpPassword, etCpConfirmPassword;
    private LinearLayout layoutCareProviderForm, layoutPatientForm;
    private TabLayout tabLayout;
    private LinearLayout tabLayoutContainer;
    private LinearLayout emailVerificationBanner;

    private boolean isLoginMode = true;
    private boolean isCareProviderTab = false;
    private UserDatabaseHelper userDbHelper;
    private Calendar selectedDate;
    private String selectedSex = "Male";
    private String selectedRace = "";
    private String selectedRelationshipStatus = "";

    private TextView tvForgotPassword;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register_login);
        userDbHelper = UserDatabaseHelper.getInstance(this);
        initializeViews();
        setupDropdowns();
        checkAutoFill();
        setupClickListeners();
        updateUI();
    }

    private void initializeViews() {
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        etConfirmPassword = findViewById(R.id.etConfirmPassword);
        etDateOfBirth = findViewById(R.id.etDateOfBirth);
        etAge = findViewById(R.id.etAge);
        etWeightPounds = findViewById(R.id.etWeightPounds);
        etHeightFeet = findViewById(R.id.etHeightFeet);
        etHeightInches = findViewById(R.id.etHeightInches);
        dobInputLayout = findViewById(R.id.dobInputLayout);
        ageInputLayout = findViewById(R.id.ageInputLayout);
        weightInputLayout = findViewById(R.id.weightInputLayout);
        heightFeetInputLayout = findViewById(R.id.heightFeetInputLayout);
        heightInchesInputLayout = findViewById(R.id.heightInchesInputLayout);
        raceInputLayout = findViewById(R.id.raceInputLayout);
        relationshipInputLayout = findViewById(R.id.relationshipInputLayout);
        tvSexLabel = findViewById(R.id.tvSexLabel);
        tvRaceLabel = findViewById(R.id.tvRaceLabel);
        tvRelationshipLabel = findViewById(R.id.tvRelationshipLabel);
        tvWeightLabel = findViewById(R.id.tvWeightLabel);
        tvHeightLabel = findViewById(R.id.tvHeightLabel);
        weightLayout = findViewById(R.id.weightLayout);
        heightLayout = findViewById(R.id.heightLayout);
        rgSex = findViewById(R.id.rgSex);
        spinnerRace = findViewById(R.id.spinnerRace);
        spinnerRelationshipStatus = findViewById(R.id.spinnerRelationshipStatus);
        btnSubmit = findViewById(R.id.btnSubmit);
        tvToggleMode = findViewById(R.id.tvToggleMode);
        ivWellnessIcon = findViewById(R.id.ivWellnessIcon);
        cbRememberMe = findViewById(R.id.cbRememberMe);
        tvForgotPassword = findViewById(R.id.tvForgotPassword);

        tvForgotPassword.setPaintFlags(
                tvForgotPassword.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG
        );

        etCpName = findViewById(R.id.etCpName);
        etCpMedicalId = findViewById(R.id.etCpMedicalId);
        etCpEmail = findViewById(R.id.etCpEmail);
        etCpPassword = findViewById(R.id.etCpPassword);
        etCpConfirmPassword = findViewById(R.id.etCpConfirmPassword);
        layoutCareProviderForm = findViewById(R.id.layoutCareProviderForm);
        layoutPatientForm = findViewById(R.id.layoutPatientForm);
        tabLayoutContainer = findViewById(R.id.tabLayout);
        tabLayout = findViewById(R.id.tabLayoutInner);
        emailVerificationBanner = findViewById(R.id.emailVerificationBanner);

        selectedDate = Calendar.getInstance();

        rgSex.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton radioButton = findViewById(checkedId);
            if (radioButton != null) {
                selectedSex = radioButton.getText().toString();
            }
        });

        if (etHeightInches != null) {
            etHeightInches.setText("0");
        }
    }

    private void checkAutoFill() {
        SharedPreferences prefs = getSharedPreferences("user_prefs", MODE_PRIVATE);
        boolean rememberMe = prefs.getBoolean(PREF_REMEMBER, false);
        if (rememberMe) {
            String savedEmail = prefs.getString(PREF_EMAIL, "");
            String savedPassword = prefs.getString(PREF_PASSWORD, "");
            if (!savedEmail.isEmpty() && !savedPassword.isEmpty()) {
                etEmail.setText(savedEmail);
                etPassword.setText(savedPassword);
                cbRememberMe.setChecked(true);
                // Auto-login — user should not have to press Login again
                btnSubmit.setEnabled(false);
                btnSubmit.setText("Logging in...");
                handleLogin(savedEmail, savedPassword);
            }
        }
    }

    private void setupDropdowns() {
        String[] raceOptions = getResources().getStringArray(R.array.race_options);
        ArrayAdapter<String> raceAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_dropdown_item_1line, raceOptions);
        spinnerRace.setAdapter(raceAdapter);
        // REMOVE: spinnerRace.setText(raceOptions[0], false);  ← delete this line
        spinnerRace.setHint("Select race");                     // ← ADD this
        spinnerRace.setOnClickListener(v -> spinnerRace.showDropDown());
        spinnerRace.setOnItemClickListener((parent, view, position, id) ->
                selectedRace = raceOptions[position]);

        String[] relationshipOptions = getResources().getStringArray(R.array.relationship_status_options);
        ArrayAdapter<String> relationshipAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_dropdown_item_1line, relationshipOptions);
        spinnerRelationshipStatus.setAdapter(relationshipAdapter);
        // REMOVE: spinnerRelationshipStatus.setText(relationshipOptions[0], false);  ← delete this line
        spinnerRelationshipStatus.setHint("Select relationship status");              // ← ADD this
        spinnerRelationshipStatus.setOnClickListener(v -> spinnerRelationshipStatus.showDropDown());
        spinnerRelationshipStatus.setOnItemClickListener((parent, view, position, id) ->
                selectedRelationshipStatus = relationshipOptions[position]);
    }



    private void setupDatePicker() {
        etDateOfBirth.setOnClickListener(v -> {
            hideSoftKeyboard(v);
            DatePickerDialog datePickerDialog = new DatePickerDialog(
                    this,
                    (view, year, month, dayOfMonth) -> {
                        selectedDate.set(Calendar.YEAR, year);
                        selectedDate.set(Calendar.MONTH, month);
                        selectedDate.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                        // FIX: Always use MM/dd/yyyy — matches calculateAgeFromBirthDate primary format
                        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy", Locale.US);
                        String formattedDate = sdf.format(selectedDate.getTime());
                        etDateOfBirth.setText(formattedDate);
                        int age = userDbHelper.calculateAgeFromBirthDate(formattedDate);
                        etAge.setText(String.valueOf(age));
                    },
                    selectedDate.get(Calendar.YEAR),
                    selectedDate.get(Calendar.MONTH),
                    selectedDate.get(Calendar.DAY_OF_MONTH)
            );
            datePickerDialog.getDatePicker().setMaxDate(System.currentTimeMillis());
            Calendar minDate = Calendar.getInstance();
            minDate.add(Calendar.YEAR, -100);
            datePickerDialog.getDatePicker().setMinDate(minDate.getTimeInMillis());
            datePickerDialog.show();
        });
    }

    private void hideSoftKeyboard(View view) {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private void setupClickListeners() {
        setupDatePicker();

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                isCareProviderTab = tab.getPosition() == 1;
                clearForm();
                updateTabUI();
                updateUI();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        btnSubmit.setOnClickListener(v -> {
            hideSoftKeyboard(v);

            if (!isLoginMode && isCareProviderTab) {
                handleCareProviderRegistration();
                return;
            }

            if (isLoginMode) {
                String email = etEmail.getText() != null ? etEmail.getText().toString().trim().toLowerCase() : "";
                String password = etPassword.getText() != null ? etPassword.getText().toString().trim() : "";
                if (email.isEmpty() || password.isEmpty()) {
                    Toast.makeText(this, "Please fill all required fields", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!isValidEmail(email)) {
                    Toast.makeText(this, "Please enter a valid email", Toast.LENGTH_SHORT).show();
                    return;
                }
                handleLogin(email, password);
                return;
            }

            // Registration validation
            String email = etEmail.getText() != null ? etEmail.getText().toString().trim().toLowerCase() : "";
            String password = etPassword.getText() != null ? etPassword.getText().toString().trim() : "";
            String confirmPassword = etConfirmPassword.getText() != null ?
                    etConfirmPassword.getText().toString().trim() : "";
            String dateOfBirth = etDateOfBirth.getText() != null ? etDateOfBirth.getText().toString().trim() : "";

            if (email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
                Toast.makeText(this, "Please fill all required fields", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isValidEmail(email)) {
                Toast.makeText(this, "Please enter a valid email", Toast.LENGTH_SHORT).show();
                return;
            }
            if (password.length() < 6) {
                Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!password.equals(confirmPassword)) {
                Toast.makeText(this, "Passwords do not match!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (dateOfBirth.isEmpty()) {
                Toast.makeText(this, "Please select your date of birth", Toast.LENGTH_SHORT).show();
                return;
            }
            // FIX: Validate that the parsed age is sane before allowing registration
            int parsedAge = userDbHelper.calculateAgeFromBirthDate(dateOfBirth);
            if (parsedAge <= 0 || parsedAge > 120) {
                Toast.makeText(this, "Invalid date of birth. Please re-select.", Toast.LENGTH_SHORT).show();
                return;
            }
            String weightText = etWeightPounds.getText() != null ?
                    etWeightPounds.getText().toString().trim() : "";
            if (weightText.isEmpty()) {
                Toast.makeText(this, "Please enter your weight", Toast.LENGTH_SHORT).show();
                return;
            }
            String heightFeetText = etHeightFeet.getText() != null ?
                    etHeightFeet.getText().toString().trim() : "";
            if (heightFeetText.isEmpty()) {
                Toast.makeText(this, "Please enter your height in feet", Toast.LENGTH_SHORT).show();
                return;
            }
            if (etHeightInches.getText() == null || etHeightInches.getText().toString().trim().isEmpty()) {
                etHeightInches.setText("0");
            }
            handleRegistration(email, password, dateOfBirth);
        });

        tvForgotPassword.setOnClickListener(v -> showForgotPasswordDialog());

        tvToggleMode.setOnClickListener(v -> {
            clearForm();
            isLoginMode = !isLoginMode;
            updateTabUI();
            updateUI();
        });
    }

    // ─────────────────────────────────────────────
    // FIX: handleLogin — correct routing for CP vs Patient vs incomplete profile
    // OLD: role was fetched from SQLite only; CP accounts silently fell through
    //      to hasCompleteDemographicData which returned false → looped to UserProfileActivity.
    // NEW: CP detected first → skip profile check entirely.
    //      Patient with incomplete demographics → UserProfileActivity.
    //      Patient with complete demographics → onboarding or dashboard.
    // ─────────────────────────────────────────────
    private void handleLogin(String email, String password) {
        btnSubmit.setEnabled(false);
        btnSubmit.setText("Logging in...");

        FirebaseAuthHelper.login(email, password, new FirebaseAuthHelper.AuthCallback() {
            @Override
            public void onSuccess(String uid) {
                // Check CP first — avoids false PATIENT role assignment
                String role = userDbHelper.getUserRole(email, password);
                if (role == null) {
                    // Don't silently demote to PATIENT.
                    // Check if the email belongs to a CP at all (email-only lookup).
                    String accountType = userDbHelper.getAccountTypeByEmail(email);
                    if ("CARE_PROVIDER".equals(accountType)) {
                        // CP email found but password mismatch in local DB — still route as CP
                        role = "CARE_PROVIDER";
                    } else {
                        // Not found in local SQLite at all. This happens on a fresh install
                        // or after app-data clear when SQLite was wiped but Firebase Auth
                        // session survived. Fetch the role from Firestore (written during
                        // registration) so John the CP is not incorrectly treated as a patient.
                        fetchRoleFromFirestoreAndFinish(uid, email, password);
                        return;
                    }
                }
                // Role was determined synchronously from local SQLite — finish login now.
                finishLogin(uid, email, password, role);
            }

            @Override
            public void onFailure(String errorMessage) {
                btnSubmit.setEnabled(true);
                btnSubmit.setText("Login");
                animateError(btnSubmit);

                if ("EMAIL_NOT_VERIFIED".equals(errorMessage)) {
                    // Show dialog with option to resend the verification link
                    showEmailNotVerifiedDialog(email, password);
                } else if (errorMessage != null && errorMessage.toLowerCase().contains("password")) {
                    // FIX: Give a specific message if the account type is known but password is wrong
                    Toast.makeText(RegisterLoginActivity.this,
                            "Incorrect password. Please try again.", Toast.LENGTH_LONG).show();
                } else if (errorMessage != null && errorMessage.toLowerCase().contains("no user")) {
                    Toast.makeText(RegisterLoginActivity.this,
                            "No account found with this email.", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(RegisterLoginActivity.this,
                            "Login failed: " + errorMessage, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void showVerificationSentDialog(String email) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Verify Your Email")
                .setMessage("Account created successfully!\n\n"
                        + "A verification link has been sent to:\n\n"
                        + email + "\n\n"
                        + "Please open your email and click the link to verify your account "
                        + "before logging in.\n\n"
                        + " Don't forget to check your spam/junk folder if you don't see it.")
                .setPositiveButton("Got it!", null)
                .setCancelable(false)
                .show();
    }

    private void showEmailNotVerifiedDialog(String email, String password) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Email Not Verified")
                .setMessage("Your email address (" + email + ") has not been verified yet.\n\n"
                        + "Please check your inbox for the verification link and click it, "
                        + "then try logging in again.")
                .setPositiveButton("Resend Email", (dialog, which) -> {
                    FirebaseAuthHelper.resendVerificationEmail(email, password,
                            new FirebaseAuthHelper.AuthCallback() {
                                @Override
                                public void onSuccess(String uid) {
                                    Toast.makeText(RegisterLoginActivity.this,
                                            "Verification email sent to " + email,
                                            Toast.LENGTH_LONG).show();
                                }
                                @Override
                                public void onFailure(String errorMessage) {
                                    Toast.makeText(RegisterLoginActivity.this,
                                            "Failed to resend: " + errorMessage,
                                            Toast.LENGTH_LONG).show();
                                }
                            });
                })
                .setNegativeButton("OK", null)
                .show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fallback: when the user's email is not found in local SQLite at all,
    // look up the role stored in Firestore (pushed there at registration time).
    // Runs on the main thread via the default Firestore listener executor.
    // ─────────────────────────────────────────────────────────────────────────
    private void fetchRoleFromFirestoreAndFinish(String uid, String email, String password) {
        FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("profile").document("data")
                .get()
                .addOnSuccessListener(doc -> {
                    String role = "PATIENT";
                    if (doc != null && doc.exists()) {
                        String firestoreRole = doc.getString("role");
                        if ("CARE_PROVIDER".equals(firestoreRole)) {
                            role = "CARE_PROVIDER";
                            // Restore the CP record to SQLite so future logins work without
                            // needing another Firestore round-trip.
                            String name      = doc.getString("name");
                            String medicalId = doc.getString("medical_id");
                            if (name != null && medicalId != null
                                    && !name.isEmpty() && !medicalId.isEmpty()) {
                                userDbHelper.registerCareProvider(name, medicalId, email, password);
                            }
                        }
                    }
                    finishLogin(uid, email, password, role);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Firestore role fetch failed: " + e.getMessage());
                    // Cannot determine role — default to PATIENT and let the normal
                    // demographic-data check run. The user can contact support if needed.
                    finishLogin(uid, email, password, "PATIENT");
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Common login-completion logic used by both the synchronous SQLite path
    // and the asynchronous Firestore fallback path.
    // ─────────────────────────────────────────────────────────────────────────
    private void finishLogin(String uid, String email, String password, String role) {
        DatabaseHelper.setCurrentUserEmail(email);
        UserDatabaseHelper.setCurrentUserEmail(email);

        SharedPreferences sessionPrefs = getSharedPreferences("session", MODE_PRIVATE);
        sessionPrefs.edit()
                .putString("current_user_email", email)
                .putString("user_role", role)
                .apply();

        FirebaseAuthHelper.saveSession(getApplicationContext(), uid, role);
        userDbHelper.updateUserPassword(email, password);

        new SyncManager(getApplicationContext()).pushAllDirtyData(new SyncManager.SyncCallback() {
            @Override public void onSuccess() {
                Log.d(TAG, "Sync completed for: " + email + " role: " + role);
            }
            @Override public void onFailure(String error) {
                Log.e(TAG, "Sync failed: " + error);
            }
        });

        // Single editor for all user_prefs changes — avoids any race between two apply() calls
        SharedPreferences.Editor editor = getSharedPreferences("user_prefs", MODE_PRIVATE).edit();
        if (cbRememberMe.isChecked()) {
            editor.putBoolean(PREF_REMEMBER, true);
            editor.putString(PREF_EMAIL, email);
            editor.putString(PREF_PASSWORD, password);
        } else {
            editor.putBoolean(PREF_REMEMBER, false);
            editor.remove(PREF_EMAIL);
            editor.remove(PREF_PASSWORD);
        }
        // Also clear the email verification banner in the same commit
        editor.putBoolean("email_verification_pending", false);
        editor.apply();

        animateSuccess(btnSubmit);
        btnSubmit.setEnabled(true);
        btnSubmit.setText("Login");

        // Care providers bypass ALL demographic checks — they have no row in TABLE_USERS
        if ("CARE_PROVIDER".equals(role)) {
            startActivity(new Intent(RegisterLoginActivity.this,
                    CareProviderDashboardActivity.class));
            finish();
            return;
        }

        // Patient: route directly to onboarding/dashboard. The previous
        // demographic-completeness gate (UserProfileActivity) has been removed —
        // any user with a missing DOB / sex / weight / height will simply get
        // less-accurate stress predictions until they update their account.
        SharedPreferences userPrefs = getSharedPreferences("user_prefs", MODE_PRIVATE);
        boolean hasCompletedOnboarding = userPrefs.getBoolean(
                "completed_onboarding_" + email, false);
        if (hasCompletedOnboarding) {
            startActivity(new Intent(RegisterLoginActivity.this, PatientMainActivity.class));
        } else {
            startActivity(new Intent(RegisterLoginActivity.this, StepsActivity.class));
        }
        finish();
    }

    private void handleCareProviderRegistration() {
        String name = etCpName.getText() != null ? etCpName.getText().toString().trim() : "";
        String medicalId = etCpMedicalId.getText() != null ? etCpMedicalId.getText().toString().trim() : "";
        String email = etCpEmail.getText() != null ? etCpEmail.getText().toString().trim().toLowerCase() : "";
        String password = etCpPassword.getText() != null ? etCpPassword.getText().toString().trim() : "";
        String confirmPassword = etCpConfirmPassword.getText() != null ?
                etCpConfirmPassword.getText().toString().trim() : "";

        if (name.isEmpty() || medicalId.isEmpty() || email.isEmpty() ||
                password.isEmpty() || confirmPassword.isEmpty()) {
            Toast.makeText(this, "Please fill all required fields", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isValidEmail(email)) {
            Toast.makeText(this, "Please enter a valid email", Toast.LENGTH_SHORT).show();
            return;
        }
        if (password.length() < 6) {
            Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!password.equals(confirmPassword)) {
            Toast.makeText(this, "Passwords do not match!", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSubmit.setEnabled(false);
        btnSubmit.setText("Registering...");

        FirebaseAuthHelper.signUp(email, password, new FirebaseAuthHelper.AuthCallback() {
            @Override
            public void onSuccess(String uid) {
                long id = userDbHelper.registerCareProvider(name, medicalId, email, password);

                FirebaseAuthHelper.saveSession(getApplicationContext(), uid, "CARE_PROVIDER");
                new SyncManager(getApplicationContext()).pushAllDirtyData(null);
                UserDatabaseHelper.setCurrentUserEmail(email);
                DatabaseHelper.setCurrentUserEmail(email);

                btnSubmit.setEnabled(true);
                btnSubmit.setText("Register");

                if (id > 0) {
                    // Sign out so the care provider must verify their email before logging in
                    com.google.firebase.auth.FirebaseAuth.getInstance().signOut();
                    // Mark that this user needs to verify their email (drives the login banner)
                    getSharedPreferences("user_prefs", MODE_PRIVATE).edit()
                            .putBoolean("email_verification_pending", true).apply();
                    animateSuccess(btnSubmit);
                    isLoginMode = true;
                    isCareProviderTab = false;
                    tabLayout.getTabAt(0).select();
                    updateTabUI();
                    updateUI();
                    etEmail.setText(email);
                    etPassword.setText("");
                    showVerificationSentDialog(email);
                } else if (id == -2) {
                    Toast.makeText(RegisterLoginActivity.this,
                            "Medical ID already in use", Toast.LENGTH_SHORT).show();
                    animateError(btnSubmit);
                } else {
                    Toast.makeText(RegisterLoginActivity.this,
                            "Registration failed. Please try again.", Toast.LENGTH_SHORT).show();
                    animateError(btnSubmit);
                }
            }

            @Override
            public void onFailure(String errorMessage) {
                btnSubmit.setEnabled(true);
                btnSubmit.setText("Register");
                animateError(btnSubmit);
                if (errorMessage != null && errorMessage.contains("email address is already in use")) {
                    Toast.makeText(RegisterLoginActivity.this,
                            "Email already registered", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(RegisterLoginActivity.this,
                            "Registration failed: " + errorMessage, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void handleRegistration(String email, String password, String dateOfBirth) {
        float weightPounds;
        int heightFeet, heightInches;
        try {
            weightPounds = Float.parseFloat(etWeightPounds.getText().toString());
            heightFeet = Integer.parseInt(etHeightFeet.getText().toString());
            heightInches = Integer.parseInt(
                    etHeightInches.getText() != null &&
                            !etHeightInches.getText().toString().trim().isEmpty()
                            ? etHeightInches.getText().toString().trim() : "0");

            if (weightPounds <= 0 || weightPounds > 1000) {
                Toast.makeText(this, "Please enter a valid weight (1-1000 pounds)",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (heightFeet <= 0 || heightFeet > 8) {
                Toast.makeText(this, "Please enter a valid height (1-8 feet)",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (heightInches < 0 || heightInches > 11) {
                Toast.makeText(this, "Please enter valid inches (0-11)",
                        Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Please enter valid numbers for height and weight",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        btnSubmit.setEnabled(false);
        btnSubmit.setText("Registering...");

        FirebaseAuthHelper.signUp(email, password, new FirebaseAuthHelper.AuthCallback() {
            @Override
            public void onSuccess(String uid) {
                float weightKg = userDbHelper.poundsToKg(weightPounds);
                float heightM = userDbHelper.feetInchesToMeters(heightFeet, heightInches);
                float bmi = userDbHelper.calculateBMI(weightKg, heightM);

                long userId = userDbHelper.registerUser(
                        email, password, dateOfBirth,
                        selectedSex, selectedRace, selectedRelationshipStatus,
                        weightKg, heightM, bmi
                );

                FirebaseAuthHelper.saveSession(getApplicationContext(), uid, "PATIENT");
                new SyncManager(getApplicationContext()).pushAllDirtyData(null);
                UserDatabaseHelper.setCurrentUserEmail(email);
                DatabaseHelper.setCurrentUserEmail(email);

                btnSubmit.setEnabled(true);
                btnSubmit.setText("Register");

                if (userId > 0) {
                    // Sign out so the user must verify their email before logging in
                    com.google.firebase.auth.FirebaseAuth.getInstance().signOut();
                    // Mark that this user needs to verify their email (drives the login banner)
                    getSharedPreferences("user_prefs", MODE_PRIVATE).edit()
                            .putBoolean("email_verification_pending", true).apply();
                    animateSuccess(btnSubmit);
                    isLoginMode = true;
                    updateUI();
                    etEmail.setText(email);
                    etPassword.setText("");
                    showVerificationSentDialog(email);
                } else if (userId == -1) {
                    // FIX: -1 means email already in SQLite (either real duplicate or pre-registered by CP)
                    if (userDbHelper.isPatientLinkedToAnyProvider(email)) {
                        // Patient was pre-registered by a care provider — activate account
                        Intent intent = new Intent(RegisterLoginActivity.this,
                                ActivateAccountActivity.class);
                        intent.putExtra("email", email);
                        startActivity(intent);
                    } else {
                        // Genuine duplicate — tell user to log in
                        Toast.makeText(RegisterLoginActivity.this,
                                "An account with this email already exists. Please log in.",
                                Toast.LENGTH_LONG).show();
                        animateError(btnSubmit);
                        isLoginMode = true;
                        updateUI();
                        etEmail.setText(email);
                        etPassword.setText("");
                    }
                } else {
                    Toast.makeText(RegisterLoginActivity.this,
                            "Registration failed. Please try again.", Toast.LENGTH_SHORT).show();
                    animateError(btnSubmit);
                }
            }

            @Override
            public void onFailure(String errorMessage) {
                btnSubmit.setEnabled(true);
                btnSubmit.setText("Register");

                // If Firebase already has this email, the account may have been
                // created previously (e.g. the patient already attempted once).
                // Still check if they were pre-registered by a CP so we can
                // route them to ActivateAccountActivity instead of just saying
                // "email already in use".
                if (errorMessage != null && errorMessage.contains("email address is already in use")) {
                    if (userDbHelper.isPatientLinkedToAnyProvider(email)) {
                        Intent intent = new Intent(RegisterLoginActivity.this,
                                ActivateAccountActivity.class);
                        intent.putExtra("email", email);
                        startActivity(intent);
                    } else {
                        animateError(btnSubmit);
                        Toast.makeText(RegisterLoginActivity.this,
                                "An account with this email already exists. Please log in.",
                                Toast.LENGTH_LONG).show();
                    }
                } else {
                    animateError(btnSubmit);
                    Toast.makeText(RegisterLoginActivity.this,
                            "Registration failed: " + errorMessage, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void clearForm() {
        etEmail.setText("");
        etPassword.setText("");
        etConfirmPassword.setText("");
        etDateOfBirth.setText("");
        etAge.setText("");
        etWeightPounds.setText("");
        etHeightFeet.setText("");
        etHeightInches.setText("0");
        rgSex.check(R.id.rbMale);
        spinnerRace.setText(getResources().getStringArray(R.array.race_options)[0], false);
        spinnerRelationshipStatus.setText(
                getResources().getStringArray(R.array.relationship_status_options)[0], false);

        if (etCpName != null) etCpName.setText("");
        if (etCpMedicalId != null) etCpMedicalId.setText("");
        if (etCpEmail != null) etCpEmail.setText("");
        if (etCpPassword != null) etCpPassword.setText("");
        if (etCpConfirmPassword != null) etCpConfirmPassword.setText("");
    }

    private void updateTabUI() {
        if (isLoginMode) {
            tabLayoutContainer.setVisibility(View.GONE);
            layoutPatientForm.setVisibility(View.VISIBLE);
            layoutCareProviderForm.setVisibility(View.GONE);
        } else {
            tabLayoutContainer.setVisibility(View.VISIBLE);
            if (isCareProviderTab) {
                layoutPatientForm.setVisibility(View.GONE);
                layoutCareProviderForm.setVisibility(View.VISIBLE);
            } else {
                layoutPatientForm.setVisibility(View.VISIBLE);
                layoutCareProviderForm.setVisibility(View.GONE);
            }
        }
    }

    private void updateUI() {
        btnSubmit.setText(isLoginMode ? "Login" : "Register");
        tvToggleMode.setText(isLoginMode ? "New user? Register here" : "Already have an account? Login");

        TextView tvTitle = findViewById(R.id.tvTitle);
        TextView tvSubtitle = findViewById(R.id.tvSubtitle);
        if (tvTitle != null) tvTitle.setText(isLoginMode ? "Welcome back" : "Create Account");
        if (tvSubtitle != null) tvSubtitle.setText(isLoginMode ? "Sign in to continue" : "Fill in your basic information");

        int regVisibility = (!isLoginMode && !isCareProviderTab) ? View.VISIBLE : View.GONE;
        findViewById(R.id.confirmPasswordInputLayout).setVisibility(regVisibility);
        dobInputLayout.setVisibility(regVisibility);
        ageInputLayout.setVisibility(regVisibility);
        tvSexLabel.setVisibility(regVisibility);
        rgSex.setVisibility(regVisibility);
        tvRaceLabel.setVisibility(regVisibility);
        raceInputLayout.setVisibility(regVisibility);
        tvRelationshipLabel.setVisibility(regVisibility);
        relationshipInputLayout.setVisibility(regVisibility);
        tvWeightLabel.setVisibility(regVisibility);
        weightLayout.setVisibility(regVisibility);
        tvHeightLabel.setVisibility(regVisibility);
        heightLayout.setVisibility(regVisibility);
        cbRememberMe.setVisibility(isLoginMode ? View.VISIBLE : View.GONE);
        tvForgotPassword.setVisibility(isLoginMode ? View.VISIBLE : View.GONE);

        // Show verification banner in login mode if a recent registration is pending verification
        if (emailVerificationBanner != null) {
            boolean pendingVerification = getSharedPreferences("user_prefs", MODE_PRIVATE)
                    .getBoolean("email_verification_pending", false);
            emailVerificationBanner.setVisibility(
                    (isLoginMode && pendingVerification) ? View.VISIBLE : View.GONE);
        }

        updateTabUI();
    }

    private void animateSuccess(Button button) {
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(button, "scaleX", 1f, 1.1f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(button, "scaleY", 1f, 1.1f, 1f);
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(scaleX, scaleY);
        animatorSet.setDuration(500);
        animatorSet.setInterpolator(new BounceInterpolator());
        animatorSet.start();
    }

    private void animateError(Button button) {
        ObjectAnimator animator = ObjectAnimator.ofFloat(button, "translationX",
                0, -15, 15, -15, 15, -15, 15, -15, 15, 0);
        animator.setDuration(500);
        animator.start();
    }

    private void showForgotPasswordDialog() {
        TextInputEditText emailInput = new TextInputEditText(this);
        emailInput.setHint("Enter your email");
        emailInput.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);

        String currentEmail = etEmail.getText() != null ? etEmail.getText().toString().trim() : "";
        if (!currentEmail.isEmpty()) emailInput.setText(currentEmail);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Reset Password")
                .setMessage("Enter your email and we'll send a reset link.")
                .setView(emailInput)
                .setPositiveButton("Send Reset Link", (dialog, which) -> {
                    String email = emailInput.getText() != null ?
                            emailInput.getText().toString().trim() : "";
                    if (email.isEmpty() || !isValidEmail(email)) {
                        Toast.makeText(this, "Please enter a valid email",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    sendPasswordResetEmail(email);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendPasswordResetEmail(String email) {
        com.google.firebase.auth.FirebaseAuth.getInstance()
                .sendPasswordResetEmail(email)
                .addOnSuccessListener(unused ->
                        new androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("Email Sent")
                                .setMessage("A password reset link has been sent to:\n\n" + email
                                        + "\n\nCheck your inbox and follow the link to reset your password.")
                                .setPositiveButton("OK", null)
                                .show()
                )
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed to send reset email: "
                                + e.getMessage(), Toast.LENGTH_LONG).show()
                );
    }

    private boolean isValidEmail(String email) {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
