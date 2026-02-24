package com.example.myapplication;

import android.database.Cursor;
import android.os.Bundle;
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

import java.util.List;
import java.util.Locale;

public class PatientHistoryDetailActivity extends AppCompatActivity {

    private TextView tvName;
    private TextView tvEmail;
    private TextView tvBmi;
    private TextView tvWeight;
    private TextView tvHeight;
    private TextView tvConditions;
    private TextView tvCortisolHeader;
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
        appDb = DatabaseHelper.getInstance(this);

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
        tvName = findViewById(R.id.tvName);
        tvEmail = findViewById(R.id.tvEmail);
        tvBmi = findViewById(R.id.tvBmi);
        tvWeight = findViewById(R.id.tvWeight);
        tvHeight = findViewById(R.id.tvHeight);
        tvConditions = findViewById(R.id.tvConditions);
        tvCortisolHeader = findViewById(R.id.tvCortisolHeader);
        llCortisolHistory = findViewById(R.id.llCortisolHistory);
    }

    private void loadPatientOverview() {
        // 1) basic info from users table
        float weightKg = 0f;
        float heightM = 0f;
        float bmi = 0f;

        Cursor c = userDb.getReadableDatabase().query(
                "users",
                new String[]{"email", "weight_kg", "height_m", "bmi"},
                "email = ?",
                new String[]{patientEmail},
                null, null, null
        );
        if (c != null && c.moveToFirst()) {
            weightKg = c.getFloat(c.getColumnIndexOrThrow("weight_kg"));
            heightM = c.getFloat(c.getColumnIndexOrThrow("height_m"));
            bmi = c.getFloat(c.getColumnIndexOrThrow("bmi"));
        }
        if (c != null) c.close();

        // 2) name + conditions from cp_patient_info (any CP)
        String name = null;
        String conditions = null;
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
                    null, null, null,
                    "1"
            );
            if (cp != null && cp.moveToFirst()) {
                name = cp.getString(cp.getColumnIndexOrThrow(UserDatabaseHelper.COL_CP_NAME));
                conditions = cp.getString(cp.getColumnIndexOrThrow(UserDatabaseHelper.COL_CP_MEDICAL_CONDITIONS));
            }
        } finally {
            if (cp != null) cp.close();
        }

        if (name == null || name.isEmpty()) {
            name = "(no name)";
        }

        tvName.setText(name);
        tvEmail.setText(patientEmail);
        tvWeight.setText(String.format(Locale.US, "Weight: %.1f kg", weightKg));
        tvHeight.setText(String.format(Locale.US, "Height: %.2f m", heightM));
        tvBmi.setText(String.format(Locale.US, "BMI: %.1f", bmi));

        if (conditions != null && !conditions.isEmpty()) {
            String[] lines = conditions.split("\n");
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                sb.append("• ").append(line.trim()).append("\n");
            }
            tvConditions.setText(sb.toString().trim());
        } else {
            tvConditions.setText("No medical conditions recorded");
        }
    }

    private void loadCortisolHistory() {
        llCortisolHistory.removeAllViews();

        // get last 10 experiments for this patient
        List<DatabaseHelper.BiomarkerExperimentSummary> exps =
                appDb.getLatestBiomarkerExperiments(patientEmail, 10);

        if (exps == null || exps.isEmpty()) {
            tvCortisolHeader.setText("Cortisol history (no data)");
            return;
        }

        tvCortisolHeader.setText("Cortisol history");

        // format: date + time
        SimpleDateFormat dateTimeFormat =
                new SimpleDateFormat("MMM dd, yyyy  HH:mm", Locale.getDefault());
        dateTimeFormat.setTimeZone(TimeZone.getDefault());

        for (DatabaseHelper.BiomarkerExperimentSummary exp : exps) {
            long correctedTs = correctTimestamp(exp.timestamp); // reuse your helper if you copy it here
            String dateTime = dateTimeFormat.format(new Date(correctedTs));

            // use same formula as ResultActivity
            double cortisol = calculateCortisol(exp.maxValue);

            String displayLine;
            if (cortisol <= 0) {
                displayLine = String.format(
                        Locale.US,
                        "• %s  —  Level: Invalid",
                        dateTime
                );
            } else {
                displayLine = String.format(
                        Locale.US,
                        "• %s  —  Level: %.2f ng/mL",
                        dateTime,
                        cortisol
                );
            }

            TextView tv = new TextView(this);
            tv.setText(displayLine);
            tv.setTextSize(14);
            tv.setTextColor(getResources().getColor(R.color.text_primary, null));
            tv.setPadding(0, 4, 0, 4);

            llCortisolHistory.addView(tv);
        }
    }
    private long correctTimestamp(long timestamp) {
        if (timestamp < 100000000000L) {
            timestamp = timestamp * 1000L;
        }
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
