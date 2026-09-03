package com.example.myapplication;

import android.app.DatePickerDialog;
import android.database.Cursor;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;

import java.util.Calendar;
import java.util.Locale;
import java.util.UUID;

public class RegisterPatientActivity extends AppCompatActivity {

    private EditText etFullName;
    private EditText etEmail;
    private EditText etPhone;
    private EditText etDob;
    private EditText etWeightLbs;
    private EditText etHeightFt;
    private EditText etHeightIn;
    private EditText etMedicalConditions;

    private RadioGroup rgSex;
    private RadioButton rbMale, rbFemale;
    private AutoCompleteTextView spinnerRelationshipStatus;

    private Button btnRegister;

    private UserDatabaseHelper dbHelper;
    private String currentCpEmail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!RoleGuard.requireCareProvider(this)) return;
        setContentView(R.layout.activity_register_new_patient);

        dbHelper = UserDatabaseHelper.getInstance(this);
        currentCpEmail = UserDatabaseHelper.getCurrentUserEmail();

        // Set up toolbar with back navigation
        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            }
        }

        initViews();
        setupDobPicker();
        setupRelationshipDropdown();
        setupRegisterButton();
    }

    private void initViews() {
        etFullName = findViewById(R.id.etFullName);
        etEmail = findViewById(R.id.etEmail);
        etPhone = findViewById(R.id.etPhone);
        etDob = findViewById(R.id.etDob);
        etWeightLbs = findViewById(R.id.etWeightLbs);
        etHeightFt = findViewById(R.id.etHeightFt);
        etHeightIn = findViewById(R.id.etHeightIn);
        etMedicalConditions = findViewById(R.id.etMedicalConditions);

        rgSex = findViewById(R.id.rgSex);
        rbMale = findViewById(R.id.rbMale);
        rbFemale = findViewById(R.id.rbFemale);

        spinnerRelationshipStatus = findViewById(R.id.spinnerRelationshipStatus);

        btnRegister = findViewById(R.id.btnRegisterPatient);
    }

    private void setupDobPicker() {
        etDob.setOnClickListener(v -> {
            final Calendar calendar = Calendar.getInstance();
            int year = calendar.get(Calendar.YEAR);
            int month = calendar.get(Calendar.MONTH);
            int day = calendar.get(Calendar.DAY_OF_MONTH);

            DatePickerDialog dpd = new DatePickerDialog(
                    RegisterPatientActivity.this,
                    new DatePickerDialog.OnDateSetListener() {
                        @Override
                        public void onDateSet(DatePicker view, int y, int m, int d) {
                            String dateStr = String.format(Locale.US, "%02d/%02d/%04d", m + 1, d, y);
                            etDob.setText(dateStr);
                        }
                    },
                    year, month, day
            );
            dpd.show();
        });
    }

    private void setupRelationshipDropdown() {
        String[] relationshipOptions = {"Single", "Married", "Divorced", "Other"};
        android.widget.ArrayAdapter<String> relAdapter = new android.widget.ArrayAdapter<>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                relationshipOptions
        );
        spinnerRelationshipStatus.setAdapter(relAdapter);
    }

    private void setupRegisterButton() {
        btnRegister.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleRegisterPatient();
            }
        });
    }

    private void handleRegisterPatient() {
        String name = etFullName.getText().toString().trim();
        String email = etEmail.getText().toString().trim().toLowerCase();
        String phone = etPhone.getText().toString().trim();
        String dob = etDob.getText().toString().trim();   // MM/dd/yyyy
        String weightLbsStr = etWeightLbs.getText().toString().trim();
        String heightFtStr = etHeightFt.getText().toString().trim();
        String heightInStr = etHeightIn.getText().toString().trim();
        String relationship = spinnerRelationshipStatus.getText().toString().trim();
        String medicalConditions = etMedicalConditions.getText().toString().trim();

        // Sex from radio buttons
        String sex;
        int checkedId = rgSex.getCheckedRadioButtonId();
        if (checkedId == R.id.rbFemale) {
            sex = "Female";
        } else {
            sex = "Male";
        }

        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(dob)
                || TextUtils.isEmpty(weightLbsStr)
                || TextUtils.isEmpty(heightFtStr) || TextUtils.isEmpty(heightInStr)) {

            Toast.makeText(this, "Fill all required fields", Toast.LENGTH_SHORT).show();
            return;
        }

        float weightLbs;
        int heightFt;
        int heightIn;

        try {
            weightLbs = Float.parseFloat(weightLbsStr);
            heightFt = Integer.parseInt(heightFtStr);
            heightIn = Integer.parseInt(heightInStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid weight or height", Toast.LENGTH_SHORT).show();
            return;
        }

        float weightKg = dbHelper.poundsToKg(weightLbs);
        float heightM = dbHelper.feetInchesToMeters(heightFt, heightIn);
        float bmi = dbHelper.calculateBMI(weightKg, heightM);

        String providerMedicalId = dbHelper.getCareProviderMedicalId(currentCpEmail);
        if (TextUtils.isEmpty(providerMedicalId)) {
            Toast.makeText(this, "Provider medical ID not found", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean userExists = dbHelper.checkUserExists(email);
        String placeholderPassword = UUID.randomUUID() + "Aa1!";

        if (!userExists) {
            String race = "Unknown";
            long newId = dbHelper.registerUser(
                    email,
                    placeholderPassword,
                    dob,
                    sex,
                    race,
                    relationship,
                    weightKg,
                    heightM,
                    bmi
            );

            if (newId <= 0) {
                Toast.makeText(this, "Failed to register patient user", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Cursor cpCursor = dbHelper.getCpPatientInfo(email, providerMedicalId);
        String patientId;

        if (cpCursor != null && cpCursor.moveToFirst()) {
            int idx = cpCursor.getColumnIndexOrThrow(UserDatabaseHelper.COL_CP_PATIENT_ID);
            patientId = cpCursor.getString(idx);
            cpCursor.close();
        } else {
            if (cpCursor != null) cpCursor.close();
            patientId = dbHelper.getNextPatientIdForProvider(providerMedicalId);
        }

        dbHelper.upsertCpPatientInfo(
                email,
                providerMedicalId,
                patientId,
                name,
                phone,
                relationship,
                medicalConditions
        );

        btnRegister.setEnabled(false);
        provisionFirebasePatient(email, placeholderPassword, patientId);
    }

    /**
     * Creates a Firebase Auth user on a secondary FirebaseApp so the care provider
     * session is not replaced. The patient sets their own password via the reset email.
     */
    private void provisionFirebasePatient(String email, String placeholderPassword, String patientId) {
        try {
            FirebaseApp secondary;
            try {
                secondary = FirebaseApp.getInstance("patientProvisioning");
            } catch (IllegalStateException e) {
                secondary = FirebaseApp.initializeApp(
                        getApplicationContext(),
                        FirebaseApp.getInstance().getOptions(),
                        "patientProvisioning");
            }
            FirebaseAuth secondaryAuth = FirebaseAuth.getInstance(secondary);
            secondaryAuth.createUserWithEmailAndPassword(email, placeholderPassword)
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            secondaryAuth.sendPasswordResetEmail(email);
                            if (secondaryAuth.getCurrentUser() != null) {
                                secondaryAuth.getCurrentUser().sendEmailVerification();
                            }
                            secondaryAuth.signOut();
                            finishRegistration(patientId,
                                    "Patient ID: " + patientId
                                            + "\nA password-reset email was sent so they can log in as themselves.");
                        } else {
                            Exception ex = task.getException();
                            secondaryAuth.signOut();
                            if (ex instanceof FirebaseAuthUserCollisionException) {
                                finishRegistration(patientId,
                                        "Patient ID: " + patientId
                                                + "\nThis email already has a login; they can sign in with that account.");
                            } else {
                                finishRegistration(patientId,
                                        "Patient ID: " + patientId
                                                + "\nSaved locally. Cloud login could not be created — try again when online.");
                            }
                        }
                    });
        } catch (Exception e) {
            finishRegistration(patientId,
                    "Patient ID: " + patientId
                            + "\nSaved locally. Cloud login could not be created.");
        }
    }

    private void finishRegistration(String patientId, String message) {
        btnRegister.setEnabled(true);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
