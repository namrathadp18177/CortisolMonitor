package com.example.myapplication;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class PatientHistoryDetailActivity extends AppCompatActivity {

    private TextView tvName, tvEmail, tvBmi, tvWeight, tvHeight,
            tvConditions, tvCortisolHeader;
    private LinearLayout llCortisolHistory;

    private UserDatabaseHelper userDb;
    private DatabaseHelper appDb;
    private String patientEmail;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_patient_history_detail);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Patient Overview");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        userDb = UserDatabaseHelper.getInstance(this);
        appDb  = DatabaseHelper.getInstance(this);

        patientEmail = getIntent().getStringExtra("patient_email");
        if (patientEmail == null || patientEmail.isEmpty()) {
            Toast.makeText(this, "Missing patient email", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initViews();
        loadPatientOverview();
        loadCortisolHistory();
    }

    private void initViews() {
        tvName            = findViewById(R.id.tvName);
        tvEmail           = findViewById(R.id.tvEmail);
        tvBmi             = findViewById(R.id.tvBmi);
        tvWeight          = findViewById(R.id.tvWeight);
        tvHeight          = findViewById(R.id.tvHeight);
        tvConditions      = findViewById(R.id.tvConditions);
        tvCortisolHeader  = findViewById(R.id.tvCortisolHeader);
        llCortisolHistory = findViewById(R.id.llCortisolHistory);
    }

    private void loadPatientOverview() {
        float weightKg = 0f, heightM = 0f, bmi = 0f;

        Cursor c = userDb.getReadableDatabase().query(
                "users",
                new String[]{"email", "weight_kg", "height_m", "bmi"},
                "email = ?",
                new String[]{patientEmail},
                null, null, null
        );
        if (c != null && c.moveToFirst()) {
            weightKg = c.getFloat(c.getColumnIndexOrThrow("weight_kg"));
            heightM  = c.getFloat(c.getColumnIndexOrThrow("height_m"));
            bmi      = c.getFloat(c.getColumnIndexOrThrow("bmi"));
        }
        if (c != null) c.close();

        String name = null, conditions = null;
        Cursor cp = null;
        try {
            cp = userDb.getReadableDatabase().query(
                    "cp_patient_info",
                    new String[]{
                            UserDatabaseHelper.COL_CP_NAME,
                            UserDatabaseHelper.COL_CP_MEDICAL_CONDITIONS
                    },
                    UserDatabaseHelper.COL_CP_EMAIL + "=?",
                    new String[]{patientEmail},
                    null, null, null, "1"
            );
            if (cp != null && cp.moveToFirst()) {
                name       = cp.getString(cp.getColumnIndexOrThrow(UserDatabaseHelper.COL_CP_NAME));
                conditions = cp.getString(cp.getColumnIndexOrThrow(UserDatabaseHelper.COL_CP_MEDICAL_CONDITIONS));
            }
        } finally {
            if (cp != null) cp.close();
        }

        if (name == null || name.isEmpty()) name = "(no name)";

        tvName.setText(name);
        tvEmail.setText(patientEmail);
        tvWeight.setText(String.format(Locale.US, "Weight: %.1f kg", weightKg));
        tvHeight.setText(String.format(Locale.US, "Height: %.2f m", heightM));
        tvBmi.setText(String.format(Locale.US, "BMI: %.1f", bmi));

        if (conditions != null && !conditions.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String line : conditions.split("\n")) {
                if (!line.trim().isEmpty())
                    sb.append("• ").append(line.trim()).append("\n");
            }
            tvConditions.setText(sb.toString().trim());
        } else {
            tvConditions.setText("No medical conditions recorded");
        }
    }

    private void loadCortisolHistory() {
        llCortisolHistory.removeAllViews();

        List<DatabaseHelper.BiomarkerExperimentSummary> exps =
                appDb.getLatestBiomarkerExperiments(patientEmail, 10);

        if (exps == null || exps.isEmpty()) {
            tvCortisolHeader.setText("Cortisol History (no data)");
            return;
        }

        tvCortisolHeader.setText("Cortisol History  —  Tap a result to view details");

        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy  HH:mm", Locale.getDefault());
        sdf.setTimeZone(TimeZone.getDefault());

        for (DatabaseHelper.BiomarkerExperimentSummary exp : exps) {
            long correctedTs = correctTimestamp(exp.timestamp);
            double cortisol  = calculateCortisol(exp.maxValue);

            // ── Row ──
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(16, 14, 16, 14);
            row.setBackgroundColor(Color.WHITE);

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, 0, 0, 2);
            row.setLayoutParams(rowParams);

            // ── Date/time (left) ──
            TextView tvDate = new TextView(this);
            tvDate.setText(sdf.format(new Date(correctedTs)));
            tvDate.setTextSize(14f);
            tvDate.setTextColor(Color.parseColor("#546E7A"));
            LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tvDate.setLayoutParams(dateParams);
            row.addView(tvDate);

            // ── Cortisol badge (right) ──
            TextView tvLevel = new TextView(this);
            tvLevel.setTextSize(13f);
            tvLevel.setTypeface(null, Typeface.BOLD);
            tvLevel.setPadding(16, 6, 16, 6);

            if (cortisol > 0) {
                tvLevel.setText(String.format(Locale.US, "%.2f ng/mL", cortisol));
                if (cortisol < 5.0) {
                    tvLevel.setTextColor(Color.WHITE);
                    tvLevel.setBackgroundColor(Color.parseColor("#2196F3")); // low - blue
                } else if (cortisol <= 25.0) {
                    tvLevel.setTextColor(Color.WHITE);
                    tvLevel.setBackgroundColor(Color.parseColor("#4CAF50")); // normal - green
                } else {
                    tvLevel.setTextColor(Color.WHITE);
                    tvLevel.setBackgroundColor(Color.parseColor("#F44336")); // high - red
                }
            } else {
                tvLevel.setText("View Details");
                tvLevel.setTextColor(Color.WHITE);
                tvLevel.setBackgroundColor(Color.parseColor("#9E9E9E")); // gray
            }

            row.addView(tvLevel);

            // ── Tap → PatientResultsActivity ──
            final long tapTimestamp = correctedTs;
            row.setOnClickListener(v -> {
                Intent intent = new Intent(
                        PatientHistoryDetailActivity.this,
                        PatientResultsActivity.class);
                intent.putExtra("useremail", patientEmail);
                intent.putExtra("selected_date", tapTimestamp);
                startActivity(intent);
            });

            row.setClickable(true);
            row.setFocusable(true);
            llCortisolHistory.addView(row);

            // ── Divider ──
            View divider = new View(this);
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
            divider.setBackgroundColor(Color.parseColor("#EEEEEE"));
            llCortisolHistory.addView(divider);
        }
    }

    private long correctTimestamp(long timestamp) {
        if (timestamp < 100000000000L) timestamp = timestamp * 1000L;
        return timestamp;
    }

    private double calculateCortisol(double voltage) {
        double yValue = voltage * 1000.0;
        if (yValue >= 428.0 && yValue <= 478.6686) {
            return Math.pow((yValue - 482.9265) / (-4.2579), 1.0 / 0.5553);
        }
        return -1.0;
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
