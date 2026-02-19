package com.example.myapplication;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class UserProfileActivity extends AppCompatActivity {

    private static final String TAG = "UserProfileActivity";

    private EditText etAge;
    private EditText etBirthDate;
    private Button btnSave, btnCancel;
    private UserDatabaseHelper dbHelper;
    private String userEmail;
    private Calendar selectedDate;
    private SimpleDateFormat dateFormat;
    private boolean fromLogin = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_profile);

        dbHelper = UserDatabaseHelper.getInstance(this);
        userEmail = DatabaseHelper.getCurrentUserEmail();
        selectedDate = Calendar.getInstance();
        dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

        // Check if called from login
        Intent intent = getIntent();
        if (intent != null && intent.getBooleanExtra("from_login", false)) {
            fromLogin = true;
        }

        initializeViews();
        loadCurrentUserData();
        setupClickListeners();
    }

    private void initializeViews() {
        etAge = findViewById(R.id.etAge);
        etBirthDate = findViewById(R.id.etBirthDate);
        btnSave = findViewById(R.id.btnSave);
        btnCancel = findViewById(R.id.btnCancel);
    }

    private void loadCurrentUserData() {
        if (userEmail != null && !userEmail.isEmpty()) {
            // Load current birth date first
            String currentBirthDate = dbHelper.getUserBirthDate(userEmail);
            if (currentBirthDate != null && !currentBirthDate.isEmpty()) {
                etBirthDate.setText(currentBirthDate);
                try {
                    Date date = dateFormat.parse(currentBirthDate);
                    selectedDate.setTime(date);
                    
                    // Auto-calculate and display age
                    int calculatedAge = dbHelper.calculateAgeFromBirthDate(currentBirthDate);
                    if (calculatedAge > 0) {
                        etAge.setText(String.valueOf(calculatedAge));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing birth date: " + e.getMessage());
                }
            } else {
                etBirthDate.setText("");
                etAge.setText("");
            }
        }
    }

    private void setupClickListeners() {
        // Make birth date field clickable to show date picker
        etBirthDate.setOnClickListener(v -> showDatePicker());

        btnSave.setOnClickListener(v -> saveUserProfile());

        btnCancel.setOnClickListener(v -> {
            if (fromLogin) {
                // If called from login and user cancels, go back to login
                finish();
            } else {
                // Normal cancel behavior
                finish();
            }
        });
    }

    private void showDatePicker() {
        DatePickerDialog datePickerDialog = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    selectedDate.set(Calendar.YEAR, year);
                    selectedDate.set(Calendar.MONTH, month);
                    selectedDate.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                    
                    String formattedDate = dateFormat.format(selectedDate.getTime());
                    etBirthDate.setText(formattedDate);
                    
                    // Auto-calculate age
                    int calculatedAge = dbHelper.calculateAgeFromBirthDate(formattedDate);
                    if (calculatedAge > 0) {
                        etAge.setText(String.valueOf(calculatedAge));
                    }
                },
                selectedDate.get(Calendar.YEAR),
                selectedDate.get(Calendar.MONTH),
                selectedDate.get(Calendar.DAY_OF_MONTH)
        );
        
        // Set maximum date to today
        datePickerDialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        
        // Set minimum date (reasonable minimum age)
        Calendar minDate = Calendar.getInstance();
        minDate.add(Calendar.YEAR, -120); // Maximum age 120
        datePickerDialog.getDatePicker().setMinDate(minDate.getTimeInMillis());
        
        datePickerDialog.show();
    }

    private void saveUserProfile() {
        String birthDateText = etBirthDate.getText().toString().trim();
        String ageText = etAge.getText().toString().trim();

        // Birth date is mandatory
        if (birthDateText.isEmpty()) {
            Toast.makeText(this, "Please select your birth date", Toast.LENGTH_SHORT).show();
            etBirthDate.requestFocus();
            return;
        }

        // Age should be calculated from birth date
        if (ageText.isEmpty()) {
            Toast.makeText(this, "Please select a valid birth date", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            int age = Integer.parseInt(ageText);
            if (age < 1 || age > 120) {
                Toast.makeText(this, "Please select a valid birth date", Toast.LENGTH_SHORT).show();
                return;
            }

            if (userEmail != null && !userEmail.isEmpty()) {
                boolean success = dbHelper.updateUserAge(userEmail, age, birthDateText);
                if (success) {
                    Toast.makeText(this, "Profile updated successfully", Toast.LENGTH_SHORT).show();
                    Log.i(TAG, "User profile updated - Age: " + age + ", Birth Date: " + birthDateText);
                    
                    if (fromLogin) {
                        // If called from login, redirect to StepsActivity
                        Intent intent = new Intent(UserProfileActivity.this, StepsActivity.class);
                        startActivity(intent);
                        finish();
                    } else {
                        // Normal save behavior
                        finish();
                    }
                } else {
                    Toast.makeText(this, "Failed to update profile", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "User not logged in", Toast.LENGTH_SHORT).show();
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Please select a valid birth date", Toast.LENGTH_SHORT).show();
        }
    }
} 