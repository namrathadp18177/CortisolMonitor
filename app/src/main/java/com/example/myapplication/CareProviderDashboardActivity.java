package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;
import java.util.Calendar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.card.MaterialCardView;

public class CareProviderDashboardActivity extends AppCompatActivity {

    private TextView tvWelcome;
    private TextView tvWelcomeSubtitle;
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
        // Trigger background sync on dashboard load
        new Thread(() -> {
            SyncManager syncManager = new SyncManager(getApplicationContext());
            syncManager.pushAllDirtyData(new SyncManager.SyncCallback() {
                @Override
                public void onSuccess() {
                    Log.d("Dashboard", "Sync completed successfully");
                }
                @Override
                public void onFailure(String error) {
                    Log.e("Dashboard", "Sync failed: " + error);
                }
            });
        }).start();

    }

    private void initializeViews() {
        tvWelcome = findViewById(R.id.tvWelcome);
        tvWelcomeSubtitle = findViewById(R.id.tvWelcomeSubtitle);
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
                ? capitalize(email.substring(0, email.indexOf("@")))
                : "Provider")
                : name;

        // Time-based greeting
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String timeGreeting = hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";

        tvWelcome.setText(timeGreeting + ", Dr. " + displayName + "!");
        if (tvWelcomeSubtitle != null) {
            tvWelcomeSubtitle.setText("Here's your patient management overview.");
        }
        tvMedicalId.setText("Medical ID: " + (medicalId == null ? "-" : medicalId));
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
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
