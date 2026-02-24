package com.example.myapplication;

import android.app.DatePickerDialog;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.content.ContentValues;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Calendar;
import java.util.Locale;

public class CareProviderAccountActivity extends AppCompatActivity {

    private UserDatabaseHelper db;
    private String email;

    private EditText etOrganization, etDesignation, etDepartment,
            etPhone, etDob, etAddress;
    private AutoCompleteTextView actvGender;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_care_provider_account);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("My Account");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        db = UserDatabaseHelper.getInstance(this);
        email = UserDatabaseHelper.getCurrentUserEmail();
        android.util.Log.d("CP_ACCOUNT", "currentUserEmail at My Account = " + email);

        TextView tvEmail = findViewById(R.id.tvCpEmail);
        etOrganization = findViewById(R.id.etOrganization);
        etDesignation = findViewById(R.id.etDesignation);
        etDepartment = findViewById(R.id.etDepartment);
        etPhone = findViewById(R.id.etPhone);
        actvGender = findViewById(R.id.actvGender);
        etDob = findViewById(R.id.etDateOfBirth);
        etAddress = findViewById(R.id.etAddress);
        Button btnSave = findViewById(R.id.btnSaveCp);

        tvEmail.setText(email != null ? email : "N/A");

        setupGenderDropdown();
        setupDobPicker();
        loadExistingData();

        btnSave.setOnClickListener(v -> saveData());
    }

    private void setupGenderDropdown() {
        String[] genderOptions = {"Male", "Female", "Non-binary", "Prefer not to say"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                genderOptions
        );
        actvGender.setAdapter(adapter);
    }

    private void setupDobPicker() {
        etDob.setOnClickListener(v -> {
            Calendar cal = Calendar.getInstance();
            new DatePickerDialog(this, (view, year, month, day) -> {
                String date = String.format(Locale.US, "%02d/%02d/%04d", month + 1, day, year);
                etDob.setText(date);
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
                    .show();
        });
    }

    private void loadExistingData() {
        if (email == null) return;

        SQLiteDatabase sqlDb = db.getReadableDatabase();
        Cursor c = sqlDb.query(
                "care_providers",
                null,
                "email = ?",
                new String[]{email},
                null, null, null
        );
        if (c != null && c.moveToFirst()) {
            etOrganization.setText(c.getString(c.getColumnIndexOrThrow("organization")));
            etDesignation.setText(c.getString(c.getColumnIndexOrThrow("designation")));
            etDepartment.setText(c.getString(c.getColumnIndexOrThrow("department")));
            etPhone.setText(c.getString(c.getColumnIndexOrThrow("phone")));
            actvGender.setText(c.getString(c.getColumnIndexOrThrow("gender")), false);
            etDob.setText(c.getString(c.getColumnIndexOrThrow("dob")));
            etAddress.setText(c.getString(c.getColumnIndexOrThrow("address")));
            c.close();
        }
    }

    private void saveData() {
        if (email == null) {
            Toast.makeText(this, "No logged-in care provider.", Toast.LENGTH_SHORT).show();
            return;
        }

        SQLiteDatabase sqlDb = db.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("organization", etOrganization.getText().toString().trim());
        values.put("designation", etDesignation.getText().toString().trim());
        values.put("department", etDepartment.getText().toString().trim());
        values.put("phone", etPhone.getText().toString().trim());
        values.put("gender", actvGender.getText().toString().trim());
        values.put("dob", etDob.getText().toString().trim());
        values.put("address", etAddress.getText().toString().trim());

        int updated = sqlDb.update(
                "care_providers",
                values,
                "email = ?",
                new String[]{email}
        );

        if (updated > 0) {
            Toast.makeText(this, "Account updated.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Could not update account.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
