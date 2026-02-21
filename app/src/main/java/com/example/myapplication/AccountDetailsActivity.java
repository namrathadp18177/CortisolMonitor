package com.example.myapplication;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

public class AccountDetailsActivity extends AppCompatActivity {

    private UserDatabaseHelper db;
    private String email;
    private String providerMedicalId;
    private TextView tvNameValue, tvPhoneValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_details);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Account Details");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        TextView tvEmail = findViewById(R.id.tvEmail);
        TextView tvCareProviderName = findViewById(R.id.tvCareProviderName);
        tvNameValue = findViewById(R.id.tvNameValue);
        tvPhoneValue = findViewById(R.id.tvPhoneValue);
        ImageView ivEditName = findViewById(R.id.ivEditName);
        ImageView ivEditPhone = findViewById(R.id.ivEditPhone);
        TextView tvEditNameLabel = findViewById(R.id.tvEditNameLabel);
        TextView tvEditPhoneLabel = findViewById(R.id.tvEditPhoneLabel);
        Button btnSave = findViewById(R.id.btnSave);

        ivEditName.setOnClickListener(v -> showEditDialog(
                "Edit name",
                tvNameValue.getText().toString().equals("-") ? "" : tvNameValue.getText().toString(),
                newValue -> tvNameValue.setText(newValue.isEmpty() ? "-" : newValue)
        ));

        ivEditPhone.setOnClickListener(v -> showEditDialog(
                "Edit phone number",
                tvPhoneValue.getText().toString().equals("-") ? "" : tvPhoneValue.getText().toString(),
                newValue -> tvPhoneValue.setText(newValue.isEmpty() ? "-" : newValue)
        ));

        // Make the “Edit” text behave like the icon
        tvEditNameLabel.setOnClickListener(v -> ivEditName.performClick());
        tvEditPhoneLabel.setOnClickListener(v -> ivEditPhone.performClick());

        db = UserDatabaseHelper.getInstance(this);
        email = UserDatabaseHelper.getCurrentUserEmail();
        tvEmail.setText("Email: " + (email != null ? email : "N/A"));

        providerMedicalId = null;
        if (email != null) {
            providerMedicalId = db.getCareProviderMedicalIdForPatient(email);
        }

        if (providerMedicalId != null) {
            String providerName = db.getCareProviderNameByMedicalId(providerMedicalId);
            if (providerName == null) providerName = "N/A";
            tvCareProviderName.setText(" Dr " + providerName);
        } else {
            tvCareProviderName.setText("Care Provider: Not linked");
        }

        // Load existing name/phone from cp_patient_info
        String displayName = "";
        String displayPhone = "";
        if (providerMedicalId != null && email != null) {
            List<UserDatabaseHelper.CpPatientInfo> patients = db.getPatientsForProvider(providerMedicalId);
            for (UserDatabaseHelper.CpPatientInfo p : patients) {
                if (p.email != null && p.email.equalsIgnoreCase(email)) {
                    if (p.name != null) displayName = p.name;
                    if (p.phone != null) displayPhone = p.phone;
                    break;
                }
            }
        }
        tvNameValue.setText(displayName.isEmpty() ? "-" : displayName);
        tvPhoneValue.setText(displayPhone.isEmpty() ? "-" : displayPhone);

        btnSave.setOnClickListener(v -> saveChanges());
    }

    private interface OnValueSaved {
        void onSaved(String newValue);
    }

    private void showEditDialog(String title, String currentValue, OnValueSaved callback) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle(title);

        final EditText input = new EditText(this);
        input.setText(currentValue);
        input.setSelection(currentValue.length());
        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String value = input.getText().toString().trim();
            callback.onSaved(value);
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

        builder.show();
    }

    private void saveChanges() {
        if (email == null || providerMedicalId == null) {
            Toast.makeText(this, "No linked care provider to save against.", Toast.LENGTH_SHORT).show();
            return;
        }

        String name = tvNameValue.getText().toString();
        String phone = tvPhoneValue.getText().toString();

        if (name.equals("-")) name = "";
        if (phone.equals("-")) phone = "";

        String patientId = db.getNextPatientIdForProvider(providerMedicalId);

        db.upsertCpPatientInfo(
                email,
                providerMedicalId,
                patientId,
                name,
                phone,
                null,
                null
        );

        Toast.makeText(this, "Account details updated", Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
