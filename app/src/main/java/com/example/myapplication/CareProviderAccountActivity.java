package com.example.myapplication;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.content.ContentValues;

public class CareProviderAccountActivity extends AppCompatActivity {

    private UserDatabaseHelper db;
    private String email;
    private EditText etOrganization, etDesignation, etDepartment,
            etPhone, etGender, etDob, etAddress;

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

        TextView tvEmail = findViewById(R.id.tvCpEmail);
        etOrganization = findViewById(R.id.etOrganization);
        etDesignation = findViewById(R.id.etDesignation);
        etDepartment = findViewById(R.id.etDepartment);
        etPhone = findViewById(R.id.etPhone);
        etGender = findViewById(R.id.etGender);
        etDob = findViewById(R.id.etDob);
        etAddress = findViewById(R.id.etAddress);
        Button btnSave = findViewById(R.id.btnSaveCp);

        tvEmail.setText(email != null ? email : "N/A");

        loadExistingData();

        btnSave.setOnClickListener(v -> saveData());
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
            etGender.setText(c.getString(c.getColumnIndexOrThrow("gender")));
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
        values.put("gender", etGender.getText().toString().trim());
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
