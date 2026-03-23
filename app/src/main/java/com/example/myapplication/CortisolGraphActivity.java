package com.example.myapplication;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;

public class CortisolGraphActivity extends AppCompatActivity {

    private TableLayout tableDays;
    private TextView tvNoData;
    private DatabaseHelper dbHelper;
    private String userEmail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cortisol_graph);

        userEmail = getIntent().getStringExtra("useremail");
        dbHelper = DatabaseHelper.getInstance(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        tableDays = findViewById(R.id.tableDays);
        tvNoData = findViewById(R.id.tvNoData);

        loadDayList();
    }

    private void loadDayList() {
        List<DatabaseHelper.BiomarkerExperimentSummary> allExperiments =
                dbHelper.getAllExperimentsForGraph(userEmail);

        if (allExperiments == null || allExperiments.isEmpty()) {
            tvNoData.setVisibility(View.VISIBLE);
            tableDays.setVisibility(View.GONE);
            return;
        }

        SimpleDateFormat dayFormat =
                new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());

        // Group by day, keep latest per day (already handled by query, but group for display)
        LinkedHashMap<String, DatabaseHelper.BiomarkerExperimentSummary> byDay =
                new LinkedHashMap<>();

        for (DatabaseHelper.BiomarkerExperimentSummary exp : allExperiments) {
            String dayKey = dayFormat.format(new Date(exp.timestamp));
            byDay.put(dayKey, exp); // latest per day since query orders ASC, last write wins
        }

        // Header row
        TableRow headerRow = new TableRow(this);
        headerRow.setBackgroundColor(Color.parseColor("#F5F5F5"));

        TextView tvHeaderDate = new TextView(this);
        tvHeaderDate.setText("Date");
        tvHeaderDate.setTypeface(null, Typeface.BOLD);
        tvHeaderDate.setTextColor(Color.parseColor("#333333"));
        tvHeaderDate.setTextSize(14f);
        tvHeaderDate.setPadding(16, 20, 8, 20);

        TextView tvHeaderPeak = new TextView(this);
        tvHeaderPeak.setText("Peak Cortisol");
        tvHeaderPeak.setTypeface(null, Typeface.BOLD);
        tvHeaderPeak.setTextColor(Color.parseColor("#333333"));
        tvHeaderPeak.setTextSize(14f);
        tvHeaderPeak.setPadding(8, 20, 16, 20);

        TextView tvHeaderArrow = new TextView(this);
        tvHeaderArrow.setText("");
        tvHeaderArrow.setPadding(8, 20, 16, 20);

        headerRow.addView(tvHeaderDate);
        headerRow.addView(tvHeaderPeak);
        headerRow.addView(tvHeaderArrow);
        tableDays.addView(headerRow);

        // Divider
        addDivider();

        // One row per day
        boolean alternate = false;
        for (Map.Entry<String, DatabaseHelper.BiomarkerExperimentSummary> entry
                : byDay.entrySet()) {

            String dayLabel = entry.getKey();
            DatabaseHelper.BiomarkerExperimentSummary exp = entry.getValue();
            long dayTimestamp = exp.timestamp;

            TableRow row = new TableRow(this);
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackground(getResources().getDrawable(
                    android.R.drawable.list_selector_background, getTheme()));
            row.setBackgroundColor(alternate
                    ? Color.parseColor("#FAFAFA")
                    : Color.WHITE);

            TextView tvDate = new TextView(this);
            tvDate.setText(dayLabel);
            tvDate.setTextColor(Color.parseColor("#1565C0"));
            tvDate.setTextSize(15f);
            tvDate.setPadding(16, 20, 8, 20);

            TextView tvPeak = new TextView(this);
            if (exp.maxValue > 0) {
                tvPeak.setText(String.format(Locale.getDefault(),
                        "%.2f ng/mL", exp.maxValue));
                tvPeak.setTextColor(Color.parseColor("#4CAF50"));
            } else {
                tvPeak.setText("--");
                tvPeak.setTextColor(Color.parseColor("#999999"));
            }
            tvPeak.setTextSize(15f);
            tvPeak.setPadding(8, 20, 8, 20);

            TextView tvArrow = new TextView(this);
            tvArrow.setText("›");
            tvArrow.setTextSize(24f);
            tvArrow.setTextColor(Color.parseColor("#999999"));
            tvArrow.setPadding(8, 20, 16, 20);

            row.addView(tvDate);
            row.addView(tvPeak);
            row.addView(tvArrow);

            row.setOnClickListener(v -> openResultForDay(dayTimestamp));

            tableDays.addView(row);
            addDivider();

            alternate = !alternate;
        }
    }

    private void addDivider() {
        View divider = new View(this);
        divider.setLayoutParams(new TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(Color.parseColor("#E0E0E0"));
        tableDays.addView(divider);
    }


    private void openResultForDay(long dayTimestamp) {
        Intent intent = new Intent(this, PatientResultsActivity.class);
        intent.putExtra("useremail", userEmail);
        intent.putExtra("selected_date", dayTimestamp);
        startActivity(intent);
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
