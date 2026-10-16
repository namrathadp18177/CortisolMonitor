package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

/**
 * Ties a watch recording + questionnaire + biomarker test together under one app_session_id
 * (see {@link DatabaseHelper#createSession}), so results from all three can later be grouped
 * as one session instead of just by calendar day.
 *
 * The session is created once, as soon as this screen opens, and its id is threaded into
 * whichever of the three sub-flows the user actually taps -- all three are optional and can be
 * run in any order or skipped; nothing forces all three to be done in one sitting.
 *
 * Reached either from HomeFragment's "Start Session" card (patient running their own session,
 * no Intent extras), or from a care provider's "Start Test" flow (with "patient_email" set --
 * see ModifyPatientActivity's existing launchBiomarker(), same extras convention).
 */
public class StartSessionActivity extends AppCompatActivity {

    private String patientEmail;
    private String createdByEmail;
    private boolean isCareProviderRun;
    private String appSessionId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start_session);

        String incomingPatientEmail = getIntent().getStringExtra("patient_email");
        isCareProviderRun = incomingPatientEmail != null && !incomingPatientEmail.isEmpty();

        if (isCareProviderRun) {
            patientEmail = incomingPatientEmail;
            createdByEmail = DatabaseHelper.getCurrentUserEmail(); // the logged-in care provider
        } else {
            patientEmail = DatabaseHelper.getCurrentUserEmail();
            createdByEmail = patientEmail;
        }

        appSessionId = DatabaseHelper.getInstance(this).createSession(patientEmail, createdByEmail, null);

        TextView tvSessionFor = findViewById(R.id.tvSessionFor);
        tvSessionFor.setText("Session for: " + (patientEmail != null ? patientEmail : "--"));

        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        MaterialButton btnWatch = findViewById(R.id.btnSessionWatch);
        btnWatch.setOnClickListener(v -> {
            Intent intent = new Intent(this, LiveVitalsActivity.class);
            intent.putExtra("app_session_id", appSessionId);
            startActivity(intent);
        });

        MaterialButton btnQuestionnaire = findViewById(R.id.btnSessionQuestionnaire);
        btnQuestionnaire.setOnClickListener(v -> {
            Intent intent = new Intent(this, QuestionnaireMain.class);
            intent.putExtra("app_session_id", appSessionId);
            startActivity(intent);
        });

        MaterialButton btnBiomarker = findViewById(R.id.btnSessionBiomarker);
        btnBiomarker.setOnClickListener(v -> {
            Intent intent = new Intent(this, BiomarkerActivty.class);
            intent.putExtra("app_session_id", appSessionId);
            if (isCareProviderRun) {
                intent.putExtra("user_role", "CARE_PROVIDER");
                intent.putExtra("patient_email", patientEmail);
            } else {
                intent.putExtra("user_role", "PATIENT");
            }
            startActivity(intent);
        });

        findViewById(R.id.btnSessionDone).setOnClickListener(v -> {
            Toast.makeText(this, "Session saved", Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}
