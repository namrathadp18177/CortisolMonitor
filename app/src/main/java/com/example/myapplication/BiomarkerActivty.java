package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
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

        btnConnectBluetooth = findViewById(R.id.btnConnectBluetooth);
        setupClickListeners();
    }

    private void setupClickListeners() {
        btnConnectBluetooth.setOnClickListener(v -> {

            String role = getIntent().getStringExtra("user_role");

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
