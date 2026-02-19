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
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class RegisterLoginActivity extends AppCompatActivity {
    private static final String TAG = "RegisterLoginActivity";
    private static final String PREF_REMEMBER = "remember_me";
    private static final String PREF_EMAIL = "saved_email";
    private static final String PREF_PASSWORD = "saved_password";

    // UI Elements
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

    // Labels and layout groups for demographics
    private TextView tvSexLabel, tvRaceLabel, tvRelationshipLabel, tvWeightLabel, tvHeightLabel;
    private LinearLayout weightLayout, heightLayout;
    private TextInputLayout raceInputLayout, relationshipInputLayout;

    // State variables
    private boolean isLoginMode = true;
    private UserDatabaseHelper userDbHelper;
    private Calendar selectedDate;

    // Variables to store selected values
    private String selectedSex = "Male";
    private String selectedRace = "White";
    private String selectedRelationshipStatus = "Single";

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
        // Find all EditText fields
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        etConfirmPassword = findViewById(R.id.etConfirmPassword);
        etDateOfBirth = findViewById(R.id.etDateOfBirth);
        etAge = findViewById(R.id.etAge);
        etWeightPounds = findViewById(R.id.etWeightPounds);
        etHeightFeet = findViewById(R.id.etHeightFeet);
        etHeightInches = findViewById(R.id.etHeightInches);

        // Find all TextInputLayouts
        dobInputLayout = findViewById(R.id.dobInputLayout);
        ageInputLayout = findViewById(R.id.ageInputLayout);
        weightInputLayout = findViewById(R.id.weightInputLayout);
        heightFeetInputLayout = findViewById(R.id.heightFeetInputLayout);
        heightInchesInputLayout = findViewById(R.id.heightInchesInputLayout);
        raceInputLayout = findViewById(R.id.raceInputLayout);
        relationshipInputLayout = findViewById(R.id.relationshipInputLayout);

        // Find all labels and containers
        tvSexLabel = findViewById(R.id.tvSexLabel);
        tvRaceLabel = findViewById(R.id.tvRaceLabel);
        tvRelationshipLabel = findViewById(R.id.tvRelationshipLabel);
        tvWeightLabel = findViewById(R.id.tvWeightLabel);
        tvHeightLabel = findViewById(R.id.tvHeightLabel);
        weightLayout = findViewById(R.id.weightLayout);
        heightLayout = findViewById(R.id.heightLayout);

        // Other UI elements
        rgSex = findViewById(R.id.rgSex);
        spinnerRace = findViewById(R.id.spinnerRace);
        spinnerRelationshipStatus = findViewById(R.id.spinnerRelationshipStatus);
        btnSubmit = findViewById(R.id.btnSubmit);
        tvToggleMode = findViewById(R.id.tvToggleMode);
        ivWellnessIcon = findViewById(R.id.ivWellnessIcon);
        cbRememberMe = findViewById(R.id.cbRememberMe);

        // Initialize calendar for date picker
        selectedDate = Calendar.getInstance();

        // Setup radio group listener
        rgSex.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton radioButton = findViewById(checkedId);
            if (radioButton != null) {
                selectedSex = radioButton.getText().toString();
            }
        });

        // Initialize the height inches field with a default value
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
            }
        }
    }

    private void setupDropdowns() {
        // Setup Race dropdown
        String[] raceOptions = getResources().getStringArray(R.array.race_options);
        ArrayAdapter<String> raceAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_dropdown_item_1line, raceOptions);
        spinnerRace.setAdapter(raceAdapter);
        spinnerRace.setText(raceOptions[0], false);
        spinnerRace.setOnItemClickListener((parent, view, position, id) -> {
            selectedRace = raceOptions[position];
        });

        // Setup Relationship Status dropdown
        String[] relationshipOptions = getResources().getStringArray(R.array.relationship_status_options);
        ArrayAdapter<String> relationshipAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_dropdown_item_1line, relationshipOptions);
        spinnerRelationshipStatus.setAdapter(relationshipAdapter);
        spinnerRelationshipStatus.setText(relationshipOptions[0], false);
        spinnerRelationshipStatus.setOnItemClickListener((parent, view, position, id) -> {
            selectedRelationshipStatus = relationshipOptions[position];
        });
    }

    private void setupDatePicker() {
        etDateOfBirth.setOnClickListener(v -> {
            hideSoftKeyboard(v);

            DatePickerDialog datePickerDialog = new DatePickerDialog(
                    this,
                    new DatePickerDialog.OnDateSetListener() {
                        @Override
                        public void onDateSet(DatePicker view, int year, int month, int dayOfMonth) {
                            selectedDate.set(Calendar.YEAR, year);
                            selectedDate.set(Calendar.MONTH, month);
                            selectedDate.set(Calendar.DAY_OF_MONTH, dayOfMonth);

                            SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy", Locale.getDefault());
                            String formattedDate = sdf.format(selectedDate.getTime());
                            etDateOfBirth.setText(formattedDate);

                            int age = userDbHelper.calculateAgeFromBirthDate(formattedDate);
                            etAge.setText(String.valueOf(age));
                        }
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
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private void setupClickListeners() {
        setupDatePicker();

        btnSubmit.setOnClickListener(v -> {
            hideSoftKeyboard(v);

            String email = etEmail.getText() != null ? etEmail.getText().toString().trim() : "";
            String password = etPassword.getText() != null ? etPassword.getText().toString().trim() : "";
            String confirmPassword = etConfirmPassword.getText() != null ?
                    etConfirmPassword.getText().toString().trim() : "";
            String dateOfBirth = etDateOfBirth.getText() != null ? etDateOfBirth.getText().toString().trim() : "";

            // Validate inputs
            if (email.isEmpty() || password.isEmpty() ||
                    (!isLoginMode && confirmPassword.isEmpty())) {
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

            if (!isLoginMode && !password.equals(confirmPassword)) {
                Toast.makeText(this, "Passwords do not match!", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!isLoginMode && dateOfBirth.isEmpty()) {
                Toast.makeText(this, "Please select your date of birth", Toast.LENGTH_SHORT).show();
                return;
            }

            // Additional validation for demographic data (only in register mode)
            if (!isLoginMode) {
                String weightText = etWeightPounds.getText() != null ?
                        etWeightPounds.getText().toString().trim() : "";
                if (weightText.isEmpty()) {
                    Toast.makeText(this, "Please enter your weight", Toast.LENGTH_SHORT).show();
                    return;
                }

                String heightFeetText = etHeightFeet.getText() != null ?
                        etHeightFeet.getText().toString().trim() : "";
                String heightInchesText = etHeightInches.getText() != null ?
                        etHeightInches.getText().toString().trim() : "";
                if (heightFeetText.isEmpty()) {
                    Toast.makeText(this, "Please enter your height in feet", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (heightInchesText.isEmpty()) {
                    etHeightInches.setText("0");
                    heightInchesText = "0";
                }
            }

            if (isLoginMode) {
                handleLogin(email, password);
            } else {
                handleRegistration(email, password, dateOfBirth);
            }
        });

        tvToggleMode.setOnClickListener(v -> {
            clearForm();
            isLoginMode = !isLoginMode;
            updateUI();
        });
    }

    private void handleLogin(String email, String password) {
        if (userDbHelper.loginUser(email, password)) {
            DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
            DatabaseHelper.setCurrentUserEmail(email);

            SharedPreferences sessionPrefs =
                    getSharedPreferences("session", MODE_PRIVATE);

            sessionPrefs.edit()
                    .putString("current_user_email", email)
                    .apply();



            Log.d(TAG, "Set current user email in DatabaseHelper: [" + email + "]");
            // Save credentials if Remember Me is checked
            SharedPreferences prefs = getSharedPreferences("user_prefs", MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            if (cbRememberMe.isChecked()) {
                editor.putBoolean(PREF_REMEMBER, true);
                editor.putString(PREF_EMAIL, email);
                editor.putString(PREF_PASSWORD, password);
            } else {
                editor.putBoolean(PREF_REMEMBER, false);
                editor.remove(PREF_EMAIL);
                editor.remove(PREF_PASSWORD);
            }
            editor.apply();

            animateSuccess(btnSubmit);

            boolean hasCompleteProfile = userDbHelper.hasCompleteDemographicData(email);

            if (!hasCompleteProfile) {
                Intent intent = new Intent(RegisterLoginActivity.this, UserProfileActivity.class);
                intent.putExtra("from_login", true);
                startActivity(intent);
            } else {
                boolean hasCompletedOnboarding = prefs.getBoolean("completed_onboarding_" + email, false);

                if (hasCompletedOnboarding) {
                    startActivity(new Intent(RegisterLoginActivity.this, DashboardActivity.class));
                } else {
                    startActivity(new Intent(RegisterLoginActivity.this, StepsActivity.class));
                }
            }
            finish();
        } else {
            animateError(btnSubmit);
            Toast.makeText(this, "Invalid email or password", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleRegistration(String email, String password, String dateOfBirth) {
        float weightPounds;
        int heightFeet, heightInches;

        try {
            weightPounds = Float.parseFloat(etWeightPounds.getText().toString());
            heightFeet = Integer.parseInt(etHeightFeet.getText().toString());
            heightInches = Integer.parseInt(etHeightInches.getText().toString());

            if (weightPounds <= 0 || weightPounds > 1000) {
                Toast.makeText(this, "Please enter a valid weight (1-1000 pounds)", Toast.LENGTH_SHORT).show();
                return;
            }

            if (heightFeet <= 0 || heightFeet > 8) {
                Toast.makeText(this, "Please enter a valid height (1-8 feet)", Toast.LENGTH_SHORT).show();
                return;
            }

            if (heightInches < 0 || heightInches > 11) {
                Toast.makeText(this, "Please enter valid inches (0-11)", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Please enter valid numbers for height and weight", Toast.LENGTH_SHORT).show();
            return;
        }

        float weightKg = userDbHelper.poundsToKg(weightPounds);
        float heightM = userDbHelper.feetInchesToMeters(heightFeet, heightInches);
        float bmi = userDbHelper.calculateBMI(weightKg, heightM);

        long userId = userDbHelper.registerUser(
                email,
                password,
                dateOfBirth,
                selectedSex,
                selectedRace,
                selectedRelationshipStatus,
                weightKg,
                heightM,
                bmi
        );

        if (userId > 0) {
            Toast.makeText(this, "Registration successful! Please login.", Toast.LENGTH_LONG).show();
            animateSuccess(btnSubmit);

            isLoginMode = true;
            updateUI();

            etEmail.setText(email);
            etPassword.setText("");
        } else {
            if (userDbHelper.isUserRegistered(email)) {
                Toast.makeText(this, "Email already registered", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Registration failed. Please try again.", Toast.LENGTH_SHORT).show();
            }
            animateError(btnSubmit);
        }
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
        spinnerRelationshipStatus.setText(getResources().getStringArray(R.array.relationship_status_options)[0], false);
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

    private void updateUI() {
        btnSubmit.setText(isLoginMode ? "Login" : "Register");
        tvToggleMode.setText(isLoginMode ? "New user? Register here" : "Already have an account? Login");

        int registrationFieldsVisibility = isLoginMode ? View.GONE : View.VISIBLE;

        etConfirmPassword.setVisibility(registrationFieldsVisibility);
        findViewById(R.id.confirmPasswordInputLayout).setVisibility(registrationFieldsVisibility);

        dobInputLayout.setVisibility(registrationFieldsVisibility);
        ageInputLayout.setVisibility(registrationFieldsVisibility);

        tvSexLabel.setVisibility(registrationFieldsVisibility);
        rgSex.setVisibility(registrationFieldsVisibility);

        tvRaceLabel.setVisibility(registrationFieldsVisibility);
        raceInputLayout.setVisibility(registrationFieldsVisibility);

        tvRelationshipLabel.setVisibility(registrationFieldsVisibility);
        relationshipInputLayout.setVisibility(registrationFieldsVisibility);

        tvWeightLabel.setVisibility(registrationFieldsVisibility);
        weightLayout.setVisibility(registrationFieldsVisibility);

        tvHeightLabel.setVisibility(registrationFieldsVisibility);
        heightLayout.setVisibility(registrationFieldsVisibility);

        // Show/hide Remember Me checkbox (login only)
        cbRememberMe.setVisibility(isLoginMode ? View.VISIBLE : View.GONE);
    }

    private boolean isValidEmail(String email) {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
