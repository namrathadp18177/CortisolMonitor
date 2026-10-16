package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

public class BiomarkerActivty extends AppCompatActivity {

    private MaterialButton   btnConnectBluetooth;
    private MaterialCardView cardGammaLink;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_biomarker_activty);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        String role         = getIntent().getStringExtra("user_role");
        int    startStep    = getIntent().getIntExtra("start_step", 0);
        String patientEmail = getIntent().getStringExtra("patient_email");

        // If this test was launched from StartSessionActivity, tag whichever biomarker
        // experiment ends up getting created (deep inside OutputBluetooth.java, several
        // screens away) with the session that started it -- see
        // DatabaseHelper#setCurrentAppSessionId for how this gets picked up and consumed.
        String appSessionId = getIntent().getStringExtra("app_session_id");
        if (appSessionId != null && !appSessionId.isEmpty()) {
            DatabaseHelper.setCurrentAppSessionId(appSessionId);
        }

        android.util.Log.d("STEP_DEBUG", "BiomarkerActivty received start_step: " + startStep);

        if ("CARE_PROVIDER".equals(role) && startStep > 0) {
            android.util.Log.d("STEP_DEBUG", "BiomarkerActivty skipping to QRWizard with step: " + startStep);
            Intent intent = new Intent(this, QRWizardActivity.class);
            intent.putExtra("user_email", patientEmail);
            intent.putExtra("user_role", role);
            intent.putExtra("start_step", startStep);
            startActivity(intent);
            finish();
            return;
        }

        btnConnectBluetooth = findViewById(R.id.btnConnectBluetooth);
        cardGammaLink       = findViewById(R.id.cardGammaLink);

        cardGammaLink.setOnClickListener(v -> {
            Intent webIntent = new Intent(BiomarkerActivty.this, WebViewActivity.class);
            webIntent.putExtra(WebViewActivity.EXTRA_URL, "https://cortisol-monitor-kit-5jrqcwk.gamma.site/");
            webIntent.putExtra(WebViewActivity.EXTRA_TITLE, "Quick Overview Guide");
            startActivity(webIntent);
        });

        setupClickListeners();
    }

    private void setupClickListeners() {
        btnConnectBluetooth.setOnClickListener(v -> {

            String role      = getIntent().getStringExtra("user_role");
            int    startStep = getIntent().getIntExtra("start_step", 0);

            String currentUserEmail;
            if ("CARE_PROVIDER".equals(role)) {
                currentUserEmail = getIntent().getStringExtra("patient_email");
            } else {
                currentUserEmail = DatabaseHelper
                        .getInstance(BiomarkerActivty.this)
                        .getCurrentUserEmail();
            }

            if (currentUserEmail == null || currentUserEmail.isEmpty()) {
                Toast.makeText(BiomarkerActivty.this,
                        "Missing patient email", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent intent = new Intent(BiomarkerActivty.this, QRWizardActivity.class);
            intent.putExtra("user_email", currentUserEmail);
            intent.putExtra("user_role", role);
            intent.putExtra("start_step", startStep);
            startActivity(intent);
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}