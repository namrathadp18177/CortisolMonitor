package com.example.myapplication;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Day-wise history for one vital signal (heart rate/HRV, EDA, or skin temperature), reached
 * by tapping the corresponding card on {@link LiveVitalsActivity}. Mirrors
 * {@link CortisolGraphActivity}'s day-list pattern: one row per calendar day this signal has
 * data for, newest first; tapping a day opens {@link VitalsDayDetailActivity} for the graph
 * (+ HRV metrics, for heart rate).
 */
public class VitalsDayListActivity extends AppCompatActivity {

    public static final String VITAL_TYPE_HR = VitalsDatabaseHelper.VITAL_TYPE_HR;
    public static final String VITAL_TYPE_EDA = VitalsDatabaseHelper.VITAL_TYPE_EDA;
    public static final String VITAL_TYPE_SKIN_TEMP = VitalsDatabaseHelper.VITAL_TYPE_SKIN_TEMP;

    public static final String EXTRA_VITAL_TYPE = "vital_type";
    public static final String EXTRA_TITLE = "title";

    private TableLayout tableDays;
    private TextView tvNoData;
    private String vitalType;
    private String title;
    private ExecutorService executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vitals_day_list);

        vitalType = getIntent().getStringExtra(EXTRA_VITAL_TYPE);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        if (vitalType == null) vitalType = VITAL_TYPE_HR;
        if (title == null) title = "Vitals History";

        Toolbar toolbar = findViewById(R.id.vitalsDayListToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(title);
        }

        tableDays = findViewById(R.id.tableVitalsDays);
        tvNoData = findViewById(R.id.tvNoVitalsDaysData);

        executor = Executors.newSingleThreadExecutor();
        loadDayList();
    }

    private void loadDayList() {
        String userEmail = DatabaseHelper.getCurrentUserEmail();
        String type = vitalType;
        Handler main = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            List<VitalsDatabaseHelper.DaySummary> days =
                    VitalsDatabaseHelper.getInstance(this).getDaySummaries(userEmail, type);
            main.post(() -> renderDayList(days));
        });
    }

    private void renderDayList(List<VitalsDatabaseHelper.DaySummary> days) {
        if (days == null || days.isEmpty()) {
            tvNoData.setVisibility(View.VISIBLE);
            tableDays.setVisibility(View.GONE);
            return;
        }
        tvNoData.setVisibility(View.GONE);
        tableDays.setVisibility(View.VISIBLE);
        tableDays.removeAllViews();

        SimpleDateFormat dayFormat = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());

        TableRow headerRow = new TableRow(this);
        headerRow.setBackgroundColor(Color.parseColor("#F5F5F5"));

        TextView tvHeaderDate = new TextView(this);
        tvHeaderDate.setText("Date");
        tvHeaderDate.setTypeface(null, Typeface.BOLD);
        tvHeaderDate.setTextColor(Color.parseColor("#333333"));
        tvHeaderDate.setTextSize(14f);
        tvHeaderDate.setPadding(16, 20, 8, 20);

        TextView tvHeaderAvg = new TextView(this);
        tvHeaderAvg.setText("Average");
        tvHeaderAvg.setTypeface(null, Typeface.BOLD);
        tvHeaderAvg.setTextColor(Color.parseColor("#333333"));
        tvHeaderAvg.setTextSize(14f);
        tvHeaderAvg.setPadding(8, 20, 16, 20);

        TextView tvHeaderArrow = new TextView(this);
        tvHeaderArrow.setText("");
        tvHeaderArrow.setPadding(8, 20, 16, 20);

        headerRow.addView(tvHeaderDate);
        headerRow.addView(tvHeaderAvg);
        headerRow.addView(tvHeaderArrow);
        tableDays.addView(headerRow);
        addDivider();

        boolean alternate = false;
        for (VitalsDatabaseHelper.DaySummary day : days) {
            TableRow row = new TableRow(this);
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackground(getResources().getDrawable(
                    android.R.drawable.list_selector_background, getTheme()));
            row.setBackgroundColor(alternate ? Color.parseColor("#FAFAFA") : Color.WHITE);

            TextView tvDate = new TextView(this);
            tvDate.setText(dayFormat.format(new Date(day.dayStartMs)));
            tvDate.setTextColor(Color.parseColor("#1565C0"));
            tvDate.setTextSize(15f);
            tvDate.setPadding(16, 20, 8, 20);

            TextView tvAvg = new TextView(this);
            tvAvg.setText(formatSummary(day));
            tvAvg.setTextColor(Color.parseColor("#4CAF50"));
            tvAvg.setTextSize(14f);
            tvAvg.setPadding(8, 20, 8, 20);

            TextView tvArrow = new TextView(this);
            tvArrow.setText("›");
            tvArrow.setTextSize(24f);
            tvArrow.setTextColor(Color.parseColor("#999999"));
            tvArrow.setPadding(8, 20, 16, 20);

            row.addView(tvDate);
            row.addView(tvAvg);
            row.addView(tvArrow);

            row.setOnClickListener(v -> openDayDetail(day));

            tableDays.addView(row);
            addDivider();
            alternate = !alternate;
        }
    }

    private String formatSummary(VitalsDatabaseHelper.DaySummary day) {
        if (VITAL_TYPE_HR.equals(vitalType)) {
            return String.format(Locale.US, "%.0f bpm avg (%d samples)", day.avgValue, day.sampleCount);
        } else if (VITAL_TYPE_EDA.equals(vitalType)) {
            return String.format(Locale.US, "%.2f µS avg (%d samples)", day.avgValue, day.sampleCount);
        } else {
            return String.format(Locale.US, "%.1f °C avg (%d samples)", day.avgValue, day.sampleCount);
        }
    }

    private void addDivider() {
        View divider = new View(this);
        divider.setLayoutParams(new TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(Color.parseColor("#E0E0E0"));
        tableDays.addView(divider);
    }

    private void openDayDetail(VitalsDatabaseHelper.DaySummary day) {
        Intent intent = new Intent(this, VitalsDayDetailActivity.class);
        intent.putExtra(VitalsDayDetailActivity.EXTRA_VITAL_TYPE, vitalType);
        intent.putExtra(VitalsDayDetailActivity.EXTRA_TITLE, title);
        intent.putExtra(VitalsDayDetailActivity.EXTRA_DAY_START_MS, day.dayStartMs);
        intent.putExtra(VitalsDayDetailActivity.EXTRA_DAY_END_MS, day.dayEndMs);
        startActivity(intent);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdown();
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
