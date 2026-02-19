package com.example.myapplication;


import android.content.Intent;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CareProviderDashboardActivity extends AppCompatActivity {

    private static final String TAG = "CareProviderDashboard";

    private TextView tvWelcome;
    private TextView tvMedicalId;
    private TextView tvNoPatients;
    private RecyclerView rvPatients;
    private PatientListAdapter adapter;
    private ExecutorService executor;
    private Handler handler = new Handler(Looper.getMainLooper());
    private UserDatabaseHelper userDbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_care_provider_dashboard);

        userDbHelper = UserDatabaseHelper.getInstance(this);
        executor = Executors.newSingleThreadExecutor();

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Care Provider Dashboard");
            getSupportActionBar().setElevation(8f);
        }

        initializeViews();
        loadProviderInfo();
        loadPatients();
    }

    private void initializeViews() {
        tvWelcome = findViewById(R.id.tvWelcome);
        tvMedicalId = findViewById(R.id.tvMedicalId);
        tvNoPatients = findViewById(R.id.tvNoPatients);
        rvPatients = findViewById(R.id.rvPatients);

        rvPatients.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PatientListAdapter(patientEmail -> {
            // On patient tap: set as current user and open ResponsesViewerActivity
            DatabaseHelper.setCurrentUserEmail(patientEmail);
            Intent intent = new Intent(this, ResponsesViewerActivity.class);
            intent.putExtra("patient_email", patientEmail);
            startActivity(intent);
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        });
        rvPatients.setAdapter(adapter);

        // Prepare animation state
        tvWelcome.setAlpha(0f);
        tvMedicalId.setAlpha(0f);
        rvPatients.setAlpha(0f);
        rvPatients.setTranslationY(50f);
    }

    private void loadProviderInfo() {
        String email = UserDatabaseHelper.getCurrentUserEmail();
        String name = userDbHelper.getCareProviderName(email);
        String medicalId = userDbHelper.getCareProviderMedicalId(email);

        tvWelcome.setText("Welcome, Dr. " + (name.isEmpty() ? email.split("@")[0] : name) + "!");
        tvMedicalId.setText("Medical ID: " + medicalId);

        tvWelcome.animate().alpha(1f).setDuration(800)
                .setInterpolator(new OvershootInterpolator()).start();
        tvMedicalId.animate().alpha(1f).setDuration(800).setStartDelay(200).start();
    }

    private void loadPatients() {
        executor.execute(() -> {
            List<String> patientEmails = userDbHelper.getAllPatientEmails();
            handler.post(() -> {
                if (patientEmails == null || patientEmails.isEmpty()) {
                    tvNoPatients.setVisibility(View.VISIBLE);
                    rvPatients.setVisibility(View.GONE);
                } else {
                    tvNoPatients.setVisibility(View.GONE);
                    rvPatients.setVisibility(View.VISIBLE);
                    adapter.setPatients(patientEmails);
                    rvPatients.animate().alpha(1f).translationY(0f)
                            .setDuration(500).setStartDelay(300)
                            .setInterpolator(new OvershootInterpolator()).start();
                }
            });
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.care_provider_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_logout) {
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


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdown();
    }
}
