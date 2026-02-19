package com.example.myapplication;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;
import android.content.SharedPreferences;


import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;
import com.jjoe64.graphview.series.PointsGraphSeries;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class ResultActivity extends AppCompatActivity {

    private static final String TAG = "ResultActivity";

    // UI Elements
    private TextView tvCortisol, tvTimestamp, tvPreviousResults;
    private Button btnFinalResults;
    private ImageButton btnBack;
    private TableLayout tablePreviousResults;
    private GraphView graphViewResults;

    // Data values
    private double maxValue = -1.0;
    private double maxValuePort0 = -1.0;
    private double maxValuePort1 = -1.0;
    private String userEmail = "";
    private long timestamp;

    // Biomarker values
    private double cortisolLevel = 0.0;

    // Database
    private DatabaseHelper dbHelper;

    // Selected date for graph display
    private long selectedDateTimestamp = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);

        Log.d(TAG, "onCreate: ResultActivity started");
        timestamp = System.currentTimeMillis();
        selectedDateTimestamp = timestamp;

        dbHelper = DatabaseHelper.getInstance(this);

        initializeViews();

        Intent intent = getIntent();
        if (intent != null) {
            processIntentData(intent);
        } else {
            Log.e(TAG, "Intent is null");
            Toast.makeText(this, "Error: No data received", Toast.LENGTH_LONG).show();
        }

        Log.d(TAG, "Current user email: [" + userEmail + "]");

        calculateBiomarkerValues();
        displayResults();
        loadAndShowHistoryFromDb();
        setupCortisolGraph(selectedDateTimestamp);
    }

    private void initializeViews() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> onBackPressed());

        tvCortisol = findViewById(R.id.tvCortisol);
        tvTimestamp = findViewById(R.id.tvTimestamp);
        tvPreviousResults = findViewById(R.id.tvPreviousResults);
        tablePreviousResults = findViewById(R.id.tablePreviousResults);
        graphViewResults = findViewById(R.id.graphViewResults);

        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault());
        sdf.setTimeZone(TimeZone.getDefault());

        String formattedTime = sdf.format(new Date(timestamp));
        tvTimestamp.setText("Measured on: " + formattedTime);

        Log.d(TAG, "Timestamp (ms): " + timestamp);
        Log.d(TAG, "Formatted time: " + formattedTime);

        btnFinalResults = findViewById(R.id.btnBiomarker);
        btnFinalResults.setOnClickListener(v -> navigateToFinalResults());
    }

    private void navigateToFinalResults() {
        Intent intent = new Intent(this, DashboardActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void processIntentData(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras != null) {
            for (String key : extras.keySet()) {
                Log.d(TAG, "Intent extra: " + key + " = " + extras.get(key));
            }
        }

        if (intent.hasExtra("user_email")) {
            userEmail = intent.getStringExtra("user_email");
            Log.d(TAG, "User email retrieved from Intent: [" + userEmail + "]");
        } else {
            SharedPreferences sessionPrefs =
                    getSharedPreferences("session", MODE_PRIVATE);

            userEmail = sessionPrefs.getString("current_user_email", "");

            Log.d(TAG, "No email in Intent, using current user: [" + userEmail + "]");
        }

        if (userEmail != null) {
            userEmail = userEmail.trim();
        }

        if (intent.hasExtra("max_value_port0")) {
            maxValuePort0 = intent.getDoubleExtra("max_value_port0", -1.0);
        }

        if (intent.hasExtra("max_value_port1")) {
            maxValuePort1 = intent.getDoubleExtra("max_value_port1", -1.0);
        }
    }

    private void calculateBiomarkerValues() {
        if (maxValuePort1 > 0) {
            double yValue = maxValuePort1 * 1000.0;

            if (yValue < 428.0 || yValue > 478.6686) {
                cortisolLevel = 0.0;
            } else {
                cortisolLevel = Math.pow((yValue - 482.9265) / (-4.2579), 1.0 / 0.5553);
            }
        } else if (maxValue > 0) {
            double yValue = maxValue * 1000.0;

            if (yValue < 428.0 || yValue > 478.6686) {
                cortisolLevel = 0.0;
            } else {
                cortisolLevel = Math.pow((yValue - 482.9265) / (-4.2579), 1.0 / 0.5553);
            }
        } else {
            cortisolLevel = 0.0;
        }
    }

    private void displayResults() {
        if (cortisolLevel > 0) {
            tvCortisol.setText(
                    String.format(Locale.getDefault(), "Cortisol: %.1f ng/mL", cortisolLevel)
            );
        } else {
            tvCortisol.setText("Cortisol: Invalid (no valid samples)");
        }
    }

    private long getStartOfDay(long timestamp) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestamp);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private long getEndOfDay(long timestamp) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestamp);
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 999);
        return cal.getTimeInMillis();
    }

    private double calculateCortisol(double voltage) {
        double yValue = voltage * 1000.0;
        if (yValue >= 428.0 && yValue <= 478.6686) {
            return Math.pow((yValue - 482.9265) / (-4.2579), 1.0 / 0.5553);
        }
        return -1.0;
    }

    // Helper method to correct timestamp (detects if in seconds)
    private long correctTimestamp(long timestamp) {
        if (timestamp < 100000000000L) {
            timestamp = timestamp * 1000L;
            Log.d(TAG, "Corrected seconds to ms: " + timestamp);
        }
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestamp);
        int hourOfDay = cal.get(Calendar.HOUR_OF_DAY);
        Calendar now = Calendar.getInstance();
        int currentHour = now.get(Calendar.HOUR_OF_DAY);
        Log.d(TAG, "Timestamp hour: " + hourOfDay + ", Current hour: " + currentHour);
        return timestamp;
    }

    private void loadAndShowHistoryFromDb() {
        if (tvPreviousResults == null || tablePreviousResults == null || dbHelper == null) {
            return;
        }
        Log.d(TAG, "=== loadAndShowHistoryFromDb ===");
        Log.d(TAG, "Current userEmail: [" + userEmail + "]");

        int childCount = tablePreviousResults.getChildCount();
        if (childCount > 1) {
            tablePreviousResults.removeViews(1, childCount - 1);
        }

        List<DatabaseHelper.BiomarkerExperimentSummary> history =
                dbHelper.getLatestBiomarkerExperiments(userEmail, Integer.MAX_VALUE);

        if (history == null || history.isEmpty()) {
            tvPreviousResults.setText("No previous results available.");
            tvPreviousResults.setVisibility(TextView.VISIBLE);
            graphViewResults.setVisibility(View.GONE);
            return;
        } else {
            tvPreviousResults.setVisibility(TextView.GONE);
        }

        Map<String, List<DatabaseHelper.BiomarkerExperimentSummary>> dailyData = new HashMap<>();
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

        dateFormat.setTimeZone(TimeZone.getDefault());
        timeFormat.setTimeZone(TimeZone.getDefault());

        for (DatabaseHelper.BiomarkerExperimentSummary exp : history) {
            long correctedTimestamp = correctTimestamp(exp.timestamp);

            String dayKey = dateFormat.format(new Date(correctedTimestamp));

            Log.d(TAG, "Original: " + exp.timestamp + " -> Corrected: " + correctedTimestamp + " -> " + timeFormat.format(new Date(correctedTimestamp)));

            if (!dailyData.containsKey(dayKey)) {
                dailyData.put(dayKey, new ArrayList<>());
            }
            dailyData.get(dayKey).add(exp);
        }

        List<String> sortedDays = new ArrayList<>(dailyData.keySet());
        sortedDays.sort((a, b) -> {
            try {
                Date dateA = dateFormat.parse(a);
                Date dateB = dateFormat.parse(b);
                return dateB.compareTo(dateA);
            } catch (Exception e) {
                return 0;
            }
        });

        for (String day : sortedDays) {
            List<DatabaseHelper.BiomarkerExperimentSummary> dayTests = dailyData.get(day);
            dayTests.sort((a, b) -> Long.compare(
                    correctTimestamp(b.timestamp),
                    correctTimestamp(a.timestamp)
            ));

            TableRow headerRow = new TableRow(this);
            headerRow.setBackgroundColor(Color.parseColor("#E8F5E9"));
            headerRow.setPadding(0, 16, 0, 8);
            headerRow.setClickable(true);
            headerRow.setFocusable(true);

            final long dayTimestamp = correctTimestamp(dayTests.get(0).timestamp);

            headerRow.setOnClickListener(v -> {
                selectedDateTimestamp = dayTimestamp;
                setupCortisolGraph(dayTimestamp);
                Toast.makeText(this, "Showing graph for " + day, Toast.LENGTH_SHORT).show();
            });

            TextView tvDayHeader = new TextView(this);
            tvDayHeader.setText(day + " (" + dayTests.size() + " tests) - Tap to view graph");
            tvDayHeader.setPadding(12, 8, 12, 8);
            tvDayHeader.setTextSize(16);
            tvDayHeader.setTypeface(null, android.graphics.Typeface.BOLD);
            tvDayHeader.setTextColor(Color.parseColor("#2E7D32"));

            TableRow.LayoutParams headerParams = new TableRow.LayoutParams();
            headerParams.span = 2;
            tvDayHeader.setLayoutParams(headerParams);

            headerRow.addView(tvDayHeader);
            tablePreviousResults.addView(headerRow);

            for (DatabaseHelper.BiomarkerExperimentSummary exp : dayTests) {
                TableRow row = new TableRow(this);
                row.setPadding(0, 4, 0, 4);

                long displayTimestamp = correctTimestamp(exp.timestamp);

                TextView tvTime = new TextView(this);
                tvTime.setText("  " + timeFormat.format(new Date(displayTimestamp)));
                tvTime.setPadding(16, 8, 8, 8);
                tvTime.setTextSize(14);
                tvTime.setTextColor(Color.parseColor("#424242"));

                double cortisol = calculateCortisol(exp.maxValue);

                TextView tvCort = new TextView(this);
                if (cortisol <= 0) {
                    tvCort.setText("Invalid");
                    tvCort.setTextColor(Color.parseColor("#D32F2F"));
                } else {
                    tvCort.setText(String.format(Locale.getDefault(), "%.2f ng/mL", cortisol));
                    tvCort.setTextColor(Color.parseColor("#4CAF50"));
                }
                tvCort.setPadding(8, 8, 16, 8);
                tvCort.setGravity(Gravity.END);
                tvCort.setTextSize(14);

                row.addView(tvTime);
                row.addView(tvCort);

                tablePreviousResults.addView(row);
            }

            View separator = new View(this);
            separator.setLayoutParams(new TableLayout.LayoutParams(
                    TableLayout.LayoutParams.MATCH_PARENT, 2));
            separator.setBackgroundColor(Color.parseColor("#BDBDBD"));
            tablePreviousResults.addView(separator);
        }
    }

    private void setupCortisolGraph(long dateTimestamp) {

        if (graphViewResults == null || dbHelper == null) {
            return;
        }

        long correctedDateTimestamp = correctTimestamp(dateTimestamp);
        long dayStart = getStartOfDay(correctedDateTimestamp);
        long dayEnd = getEndOfDay(correctedDateTimestamp);

        List<DatabaseHelper.BiomarkerExperimentSummary> allHistory =
                dbHelper.getLatestBiomarkerExperiments(userEmail, Integer.MAX_VALUE);

        if (allHistory == null || allHistory.isEmpty()) {
            graphViewResults.setVisibility(View.GONE);
            return;
        }

        List<DatabaseHelper.BiomarkerExperimentSummary> dayHistory = new ArrayList<>();
        for (DatabaseHelper.BiomarkerExperimentSummary exp : allHistory) {
            long expTimestamp = correctTimestamp(exp.timestamp);
            if (expTimestamp >= dayStart && expTimestamp <= dayEnd) {
                dayHistory.add(exp);
            }
        }

        if (dayHistory.isEmpty()) {
            graphViewResults.setVisibility(View.GONE);
            Toast.makeText(this, "No data available for this date", Toast.LENGTH_SHORT).show();
            return;
        }

        graphViewResults.setVisibility(View.VISIBLE);
        graphViewResults.removeAllSeries();

        dayHistory.sort((a, b) ->
                Long.compare(correctTimestamp(a.timestamp), correctTimestamp(b.timestamp)));

        List<DataPoint> validPoints = new ArrayList<>();
        List<DataPoint> invalidPoints = new ArrayList<>();

        for (DatabaseHelper.BiomarkerExperimentSummary exp : dayHistory) {

            long expTimestamp = correctTimestamp(exp.timestamp);
            double hoursFromMidnight =
                    (expTimestamp - dayStart) / (1000.0 * 60.0 * 60.0);

            // IMPORTANT: use correct stored voltage
            double voltage = exp.maxValue;
            double cortisol = calculateCortisol(voltage);

            if (cortisol > 0) {
                validPoints.add(new DataPoint(hoursFromMidnight, cortisol));
            } else {
                invalidPoints.add(new DataPoint(hoursFromMidnight, 0));
            }
        }

        // ===== VALID SERIES =====
        if (!validPoints.isEmpty()) {
            PointsGraphSeries<DataPoint> validSeries =
                    new PointsGraphSeries<>(validPoints.toArray(new DataPoint[0]));
            validSeries.setColor(Color.parseColor("#4CAF50"));
            validSeries.setSize(18);
            validSeries.setShape(PointsGraphSeries.Shape.POINT);
            validSeries.setTitle("Yours");
            graphViewResults.addSeries(validSeries);
        }

        // ===== INVALID SERIES =====
        if (!invalidPoints.isEmpty()) {
            PointsGraphSeries<DataPoint> invalidSeries =
                    new PointsGraphSeries<>(invalidPoints.toArray(new DataPoint[0]));
            invalidSeries.setColor(Color.parseColor("#D32F2F"));
            invalidSeries.setSize(18);
            invalidSeries.setShape(PointsGraphSeries.Shape.POINT);
            invalidSeries.setTitle("Invalid");
            graphViewResults.addSeries(invalidSeries);
        }

        // ===== GRAPH STYLING =====
        SimpleDateFormat titleFormat =
                new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
        titleFormat.setTimeZone(TimeZone.getDefault());

        graphViewResults.setTitle(
                "Cortisol Trend - " +
                        titleFormat.format(new Date(correctedDateTimestamp))
        );
        graphViewResults.setTitleColor(Color.BLACK);
        graphViewResults.setTitleTextSize(40);

        graphViewResults.setBackgroundColor(Color.WHITE);
        graphViewResults.getGridLabelRenderer()
                .setGridColor(Color.parseColor("#E0E0E0"));

        graphViewResults.getViewport().setYAxisBoundsManual(true);
        graphViewResults.getViewport().setMinY(0);
        graphViewResults.getViewport().setMaxY(12);

        graphViewResults.getViewport().setXAxisBoundsManual(true);
        graphViewResults.getViewport().setMinX(0);
        graphViewResults.getViewport().setMaxX(24);

        graphViewResults.getViewport().setScrollable(true);
        graphViewResults.getViewport().setScalable(true);
        graphViewResults.getViewport().setScalableY(true);

        graphViewResults.getGridLabelRenderer()
                .setHorizontalAxisTitle("Time of the Day");
        graphViewResults.getGridLabelRenderer()
                .setVerticalAxisTitle("Cortisol (ng/mL)");

        graphViewResults.getGridLabelRenderer().setNumHorizontalLabels(7);
        graphViewResults.getGridLabelRenderer().setNumVerticalLabels(7);
        graphViewResults.getGridLabelRenderer().setPadding(50);
        graphViewResults.getGridLabelRenderer().setTextSize(22f);

        graphViewResults.getGridLabelRenderer().setLabelFormatter(
                new com.jjoe64.graphview.DefaultLabelFormatter() {
                    @Override
                    public String formatLabel(double value, boolean isValueX) {
                        if (isValueX) {
                            int hours = (int) Math.round(value);
                            if (hours % 4 != 0) return "";
                            if (hours == 24) hours = 0;
                            return String.format(Locale.getDefault(), "%02d:00", hours);
                        } else {
                            return super.formatLabel(value, isValueX);
                        }
                    }
                }
        );

        // ===== CLEAN LEGEND =====
        graphViewResults.getLegendRenderer().setVisible(true);
        graphViewResults.getLegendRenderer()
                .setBackgroundColor(Color.parseColor("#F5F5F5"));
        graphViewResults.getLegendRenderer()
                .setAlign(com.jjoe64.graphview.LegendRenderer.LegendAlign.TOP);
        graphViewResults.getLegendRenderer().setTextSize(24);
        graphViewResults.getLegendRenderer().setTextColor(Color.BLACK);



        graphViewResults.setOnClickListener(new View.OnClickListener() {
            private long lastTap = 0;

            @Override
            public void onClick(View v) {
                long now = System.currentTimeMillis();
                if (now - lastTap < 300) {
                    graphViewResults.getViewport().setMinX(0);
                    graphViewResults.getViewport().setMaxX(24);
                    graphViewResults.getViewport().setMinY(0);
                    graphViewResults.getViewport().setMaxY(12);
                }
                lastTap = now;
            }
        });
    }

}
