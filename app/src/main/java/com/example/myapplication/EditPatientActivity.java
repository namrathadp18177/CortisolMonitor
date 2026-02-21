package com.example.myapplication;

import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class EditPatientActivity extends AppCompatActivity {

    private EditText etName;
    private EditText etPhone;
    private EditText etRelationship;
    private EditText etMedicalConditions;

    private Button btnSave;
    private Button btnPerformTest;

    private UserDatabaseHelper dbHelper;
    private String patientEmail;
    private String patientId;
    private String currentCpEmail;
    private String providerMedicalId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_patient);

        dbHelper = UserDatabaseHelper.getInstance(this);
        currentCpEmail = UserDatabaseHelper.getCurrentUserEmail();
        providerMedicalId = dbHelper.getCareProviderMedicalId(currentCpEmail);

        patientEmail = getIntent().getStringExtra("patient_email");
        patientId = getIntent().getStringExtra("patient_id");

        initViews();
        loadPatientInfo();
        setupButtons();
    }

    private void initViews() {
        etName = findViewById(R.id.etName);
        etPhone = findViewById(R.id.etPhone);
        etRelationship = findViewById(R.id.etRelationship);
        etMedicalConditions = findViewById(R.id.etMedicalConditions);

        btnSave = findViewById(R.id.btnSave);
        btnPerformTest = findViewById(R.id.btnPerformTest);
    }

    private void loadPatientInfo() {
        // Load CP-side info from cp_patient_info
        Cursor cursor = dbHelper.getCpPatientInfo(patientEmail, providerMedicalId);
        if (cursor != null && cursor.moveToFirst()) {
            String name = cursor.getString(cursor.getColumnIndexOrThrow(UserDatabaseHelper.COL_CP_NAME));
            String phone = cursor.getString(cursor.getColumnIndexOrThrow(UserDatabaseHelper.COL_CP_PHONE));
            String relationship = cursor.getString(cursor.getColumnIndexOrThrow(UserDatabaseHelper.COL_CP_RELATIONSHIP));
            String medicalConditions = cursor.getString(cursor.getColumnIndexOrThrow(UserDatabaseHelper.COL_CP_MEDICAL_CONDITIONS));

            etName.setText(name);
            etPhone.setText(phone);
            etRelationship.setText(relationship);
            etMedicalConditions.setText(medicalConditions);
        }
        if (cursor != null) cursor.close();
    }

    private void setupButtons() {
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleSave();
            }
        });

        btnPerformTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handlePerformTest();
            }
        });
    }

    private void handleSave() {
        String name = etName.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();
        String relationship = etRelationship.getText().toString().trim();
        String medicalConditions = etMedicalConditions.getText().toString().trim();

        if (TextUtils.isEmpty(patientEmail) || TextUtils.isEmpty(providerMedicalId)) {
            Toast.makeText(this, "Missing patient or provider info", Toast.LENGTH_SHORT).show();
            return;
        }

        dbHelper.upsertCpPatientInfo(
                patientEmail,
                providerMedicalId,
                patientId,
                name,
                phone,
                relationship,
                medicalConditions
        );

        Toast.makeText(this, "Patient details updated", Toast.LENGTH_SHORT).show();
    }

    private void handlePerformTest() {
        if (TextUtils.isEmpty(patientEmail)) {
            Toast.makeText(this, "Patient email missing", Toast.LENGTH_SHORT).show();
            return;
        }

        UserDatabaseHelper.setCurrentUserEmail(patientEmail);
            Intent intent = new Intent(EditPatientActivity.this, BiomarkerInstructions.class);

            // patient email from ModifyPatientActivity
            String patientEmail = getIntent().getStringExtra("patient_email");
            intent.putExtra("user_email", patientEmail);

            // provider role from ModifyPatientActivity
            String role = getIntent().getStringExtra("user_role");  // should be CARE_PROVIDER
            intent.putExtra("user_role", role);

            startActivity(intent);


    }
}
