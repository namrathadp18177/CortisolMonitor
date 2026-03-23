package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class BiomarkerActivty extends AppCompatActivity {

    private Button btnConnectBluetooth;

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

        String role     = getIntent().getStringExtra("user_role");
        int startStep   = getIntent().getIntExtra("start_step", 0);
        String patientEmail = getIntent().getStringExtra("patient_email");

        android.util.Log.d("STEP_DEBUG", "BiomarkerActivty received start_step: " + startStep);

        // Care provider with a step selected — skip Bluetooth screen entirely
        if ("CARE_PROVIDER".equals(role) && startStep > 0) {
            android.util.Log.d("STEP_DEBUG", "BiomarkerActivty skipping to QRWizard with step: " + startStep);
            Intent intent = new Intent(this, QRWizardActivity.class);
            intent.putExtra("user_email", patientEmail);
            intent.putExtra("user_role", role);
            intent.putExtra("start_step", startStep);
            startActivity(intent);
            finish();
            return; // stop onCreate here, don't set up the button
        }

        // Normal flow — patient or care provider starting from step 0
        btnConnectBluetooth = findViewById(R.id.btnConnectBluetooth);
        setupClickListeners();
    }

    private void setupClickListeners() {
        btnConnectBluetooth.setOnClickListener(v -> {

            String role = getIntent().getStringExtra("user_role");
            int startStep = getIntent().getIntExtra("start_step", 0);

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
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
