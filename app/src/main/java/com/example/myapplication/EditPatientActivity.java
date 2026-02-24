package com.example.myapplication;

import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class EditPatientActivity extends AppCompatActivity {

    private TextInputEditText etName;
    private TextInputEditText etPhone;
    private TextInputEditText etRelationship;
    private TextInputEditText etMedicalConditions;
    private TextInputLayout tilMedicalConditions;
    private LinearLayout llConditionsList;
    private final List<String> conditionsList = new ArrayList<>();

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
        setupMedicalConditionsInput();
        loadPatientInfo();
        setupButtons();
    }

    private void initViews() {
        etName = findViewById(R.id.etName);
        etPhone = findViewById(R.id.etPhone);
        etRelationship = findViewById(R.id.etRelationship);
        etMedicalConditions = findViewById(R.id.etMedicalConditions);
        tilMedicalConditions = findViewById(R.id.tilMedicalConditions);
        llConditionsList = findViewById(R.id.llConditionsList);

        btnSave = findViewById(R.id.btnSave);
        btnPerformTest = findViewById(R.id.btnPerformTest);
    }

    private void setupMedicalConditionsInput() {
        // End icon → add condition
        tilMedicalConditions.setEndIconOnClickListener(v -> addCondition());

        // Keyboard Done → add condition
        etMedicalConditions.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addCondition();
                return true;
            }
            return false;
        });
    }

    private void loadPatientInfo() {
        Cursor cursor = dbHelper.getCpPatientInfo(patientEmail, providerMedicalId);
        if (cursor != null && cursor.moveToFirst()) {
            String name = cursor.getString(cursor.getColumnIndexOrThrow(UserDatabaseHelper.COL_CP_NAME));
            String phone = cursor.getString(cursor.getColumnIndexOrThrow(UserDatabaseHelper.COL_CP_PHONE));
            String relationship = cursor.getString(cursor.getColumnIndexOrThrow(UserDatabaseHelper.COL_CP_RELATIONSHIP));
            String medicalConditions = cursor.getString(cursor.getColumnIndexOrThrow(UserDatabaseHelper.COL_CP_MEDICAL_CONDITIONS));

            etName.setText(name);
            etPhone.setText(phone);
            etRelationship.setText(relationship);

            // load conditions into list (stored as \n separated)
            if (medicalConditions != null && !medicalConditions.isEmpty()) {
                conditionsList.clear();
                conditionsList.addAll(Arrays.asList(medicalConditions.split("\n")));
                renderConditions();
            }
        }
        if (cursor != null) cursor.close();
    }

    private void setupButtons() {
        btnSave.setOnClickListener(v -> handleSave());
        btnPerformTest.setOnClickListener(v -> handlePerformTest());
    }

    private void addCondition() {
        String condition = etMedicalConditions.getText().toString().trim();
        if (condition.isEmpty()) return;

        conditionsList.add(condition);
        etMedicalConditions.setText("");
        renderConditions();
    }

    private void renderConditions() {
        llConditionsList.removeAllViews();

        for (int i = 0; i < conditionsList.size(); i++) {
            final int index = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            rowParams.setMargins(0, 4, 0, 4);
            row.setLayoutParams(rowParams);

            TextView tvCondition = new TextView(this);
            tvCondition.setText("• " + conditionsList.get(i));
            tvCondition.setTextSize(14);
            tvCondition.setTextColor(getResources().getColor(R.color.text_primary, null));
            tvCondition.setLayoutParams(new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
            ));

            TextView btnRemove = new TextView(this);
            btnRemove.setText("✕");
            btnRemove.setTextSize(14); // same as tvCondition
            btnRemove.setTextColor(getResources().getColor(R.color.text_secondary, null));
            btnRemove.setPadding(8, 0, 0, 0);
            btnRemove.setOnClickListener(v -> {
                conditionsList.remove(index);
                renderConditions();
            });


            row.addView(tvCondition);
            row.addView(btnRemove);


            llConditionsList.addView(row);
        }
    }

    private void handleSave() {
        String name = etName.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();
        String relationship = etRelationship.getText().toString().trim();

        // join bullet list to single string
        String medicalConditions = TextUtils.join("\n", conditionsList);

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

        Intent intent = new Intent(EditPatientActivity.this, BiomarkerInstructions.class);

        String patientEmailExtra = getIntent().getStringExtra("patient_email");
        intent.putExtra("user_email", patientEmailExtra);

        String role = getIntent().getStringExtra("user_role");
        intent.putExtra("user_role", role);

        startActivity(intent);
    }
}
