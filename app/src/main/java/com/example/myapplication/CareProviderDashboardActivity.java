package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.card.MaterialCardView;

public class CareProviderDashboardActivity extends AppCompatActivity {

    private TextView tvWelcome;
    private TextView tvMedicalId;
    private MaterialCardView cardRegisterPatient;
    private MaterialCardView cardModifyPatient;
    private MaterialCardView cardViewHistory;

    private UserDatabaseHelper userDbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_care_provider_dashboard);

        userDbHelper = UserDatabaseHelper.getInstance(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Care Provider Dashboard");
            getSupportActionBar().setElevation(8f);
        }

        initializeViews();
        setupWelcomeCard();
        setupClickListeners();
    }

    private void initializeViews() {
        tvWelcome = findViewById(R.id.tvWelcome);
        tvMedicalId = findViewById(R.id.tvMedicalId);
        cardRegisterPatient = findViewById(R.id.cardRegisterPatient);
        cardModifyPatient = findViewById(R.id.cardModifyPatient);
        cardViewHistory = findViewById(R.id.cardViewHistory);
    }

    private void setupWelcomeCard() {
        String email = UserDatabaseHelper.getCurrentUserEmail();
        String name = userDbHelper.getCareProviderName(email);
        String medicalId = userDbHelper.getCareProviderMedicalId(email);

        String displayName = (name == null || name.isEmpty())
                ? (email != null && email.contains("@")
                ? email.substring(0, email.indexOf("@"))
                : "Provider")
                : name;

        tvWelcome.setText("Welcome, Dr. " + displayName);
        tvMedicalId.setText("Medical ID: " + (medicalId == null ? "-" : medicalId));
    }

    private void setupClickListeners() {
        cardRegisterPatient.setOnClickListener(v -> {
            Intent intent = new Intent(this, RegisterPatientActivity.class);
            startActivity(intent);
        });

        cardModifyPatient.setOnClickListener(v -> {
            Intent intent = new Intent(this, ModifyPatientActivity.class);
            startActivity(intent);
        });

        cardViewHistory.setOnClickListener(v -> {
            Intent intent = new Intent(this, ViewHistoryActivity.class);
            startActivity(intent);
        });

        View.OnTouchListener cardTouch = (v, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                    break;
            }
            return false;
        };

        cardRegisterPatient.setOnTouchListener(cardTouch);
        cardModifyPatient.setOnTouchListener(cardTouch);
        cardViewHistory.setOnTouchListener(cardTouch);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.care_provider_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_cp_account) {
            Intent intent = new Intent(this, CareProviderAccountActivity.class);
            startActivity(intent);
            return true;
        }

        if (id == R.id.action_logout) {
            logout();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void logout() {
        getSharedPreferences("session", MODE_PRIVATE).edit().clear().apply();
        getSharedPreferences("user_prefs", MODE_PRIVATE).edit()
                .putBoolean("remember_me", false).apply();
        UserDatabaseHelper.setCurrentUserEmail("");
        Intent intent = new Intent(this, RegisterLoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    public void onBackPressed() {
        Toast.makeText(this, "Use logout to exit", Toast.LENGTH_SHORT).show();
        super.onBackPressed();
    }
}
