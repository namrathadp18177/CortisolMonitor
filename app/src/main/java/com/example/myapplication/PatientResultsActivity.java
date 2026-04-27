package com.example.myapplication;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PatientResultsActivity extends AppCompatActivity {

    private TextView tvPatientEmail, tvDate, tvCortisol, tvCortisolBadge,
            tvStressCategory, tvStressDescription, tvNoData,
            tvPeakCortisol, tvLowestCortisol;
    private ProgressBar progressStressLevel;
    private CardView resultCategoryCard;
    private LineChart lineChart;
    private TableLayout tableTests;
    private DatabaseHelper dbHelper;

    private String patientEmail;
    private long selectedDate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_patient_results);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Patient Results");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        patientEmail = getIntent().getStringExtra("useremail");
        selectedDate = getIntent().getLongExtra("selected_date", 0);

        dbHelper = DatabaseHelper.getInstance(this);

        tvPatientEmail      = findViewById(R.id.tvPatientEmail);
        tvDate              = findViewById(R.id.tvDate);
        tvCortisol          = findViewById(R.id.tvCortisol);
        tvCortisolBadge     = findViewById(R.id.tvCortisolBadge);
        tvStressCategory    = findViewById(R.id.tvStressCategory);
        tvStressDescription = findViewById(R.id.tvStressDescription);
        tvNoData            = findViewById(R.id.tvNoData);
        progressStressLevel = findViewById(R.id.progressStressLevel);
        resultCategoryCard  = findViewById(R.id.resultCategoryCard);
        lineChart           = findViewById(R.id.lineChart);
        tableTests          = findViewById(R.id.tableTests);
        tvPeakCortisol      = findViewById(R.id.tvPeakCortisol);
        tvLowestCortisol    = findViewById(R.id.tvLowestCortisol);

        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
        tvPatientEmail.setText("Patient: " + patientEmail);
        tvDate.setText("Date: " + sdf.format(new Date(selectedDate)));

        loadResults();
        loadChart();

        findViewById(R.id.btnExportCsv).setOnClickListener(v -> exportCsv());
        findViewById(R.id.btnHome).setOnClickListener(v -> finish());
    }

    private void loadResults() {
        List<DatabaseHelper.BiomarkerExperimentSummary> history =
                dbHelper.getLatestBiomarkerExperiments(patientEmail, Integer.MAX_VALUE);

        if (history == null || history.isEmpty()) {
            tvNoData.setVisibility(View.VISIBLE);
            return;
        }

        long dayStart = getStartOfDay(selectedDate);
        long dayEnd   = getEndOfDay(selectedDate);

        // Collect ALL tests for the selected day
        List<DatabaseHelper.BiomarkerExperimentSummary> dayTests = new ArrayList<>();
        for (DatabaseHelper.BiomarkerExperimentSummary exp : history) {
            if (exp.timestamp >= dayStart && exp.timestamp <= dayEnd && exp.maxValue > 0) {
                dayTests.add(exp);
            }
        }

        if (dayTests.isEmpty()) {
            tvNoData.setVisibility(View.VISIBLE);
            return;
        }

        // Sort ascending by time
        dayTests.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));

        // Calculate peak and lowest
        double peak   = dayTests.stream().mapToDouble(e -> e.maxValue).max().orElse(0);
        double lowest = dayTests.stream().mapToDouble(e -> e.maxValue).min().orElse(0);

        tvCortisol.setText(String.format(Locale.US, "Peak: %.2f ng/mL", peak));
        tvPeakCortisol.setText(String.format(Locale.US, "Peak: %.2f ng/mL", peak));
        tvLowestCortisol.setText(String.format(Locale.US, "Lowest: %.2f ng/mL", lowest));
        applyCortisolBadge(peak);
        applyStressLevel(peak);

        // Build the tests table
        tableTests.removeAllViews();

        // Header row
        TableRow header = new TableRow(this);
        header.setBackgroundColor(Color.parseColor("#F0F0F0"));
        header.addView(makeCell("#", true, "#555555"));
        header.addView(makeCell("Time", true, "#555555"));
        header.addView(makeCell("Cortisol (ng/mL)", true, "#555555"));
        tableTests.addView(header);
        tableTests.addView(makeDivider());

        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        boolean alternate = false;
        int index = 1;

        for (DatabaseHelper.BiomarkerExperimentSummary exp : dayTests) {
            TableRow row = new TableRow(this);
            row.setBackgroundColor(alternate ? Color.parseColor("#FAFAFA") : Color.WHITE);

            TextView tvIndex = makeCell(String.valueOf(index++), false, "#999999");
            TextView tvTime  = makeCell(timeFmt.format(new Date(exp.timestamp)), false, "#333333");
            TextView tvVal   = makeCell(String.format(Locale.US, "%.2f", exp.maxValue), false,
                    exp.maxValue >= 5 && exp.maxValue <= 25
                            ? "#4CAF50"   // normal - green
                            : exp.maxValue < 5
                            ? "#2196F3"   // low - blue
                            : "#F44336"); // high - red

            row.addView(tvIndex);
            row.addView(tvTime);
            row.addView(tvVal);
            tableTests.addView(row);
            tableTests.addView(makeDivider());

            alternate = !alternate;
        }
    }

    private void loadChart() {
        List<DatabaseHelper.BiomarkerExperimentSummary> history =
                dbHelper.getLatestBiomarkerExperiments(patientEmail, Integer.MAX_VALUE);

        if (history == null || history.isEmpty()) {
            lineChart.setVisibility(View.GONE);
            return;
        }

        // ✅ Filter to selected day only — same as loadResults()
        long dayStart = getStartOfDay(selectedDate);
        long dayEnd   = getEndOfDay(selectedDate);

        List<DatabaseHelper.BiomarkerExperimentSummary> dayHistory = new ArrayList<>();
        for (DatabaseHelper.BiomarkerExperimentSummary exp : history) {
            if (exp.timestamp >= dayStart && exp.timestamp <= dayEnd && exp.maxValue > 0) {
                dayHistory.add(exp);
            }
        }

        if (dayHistory.isEmpty()) {
            lineChart.setVisibility(View.GONE);
            return;
        }

        dayHistory.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));

        List<Entry> entries = new ArrayList<>();
        List<String> dateLabels = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault()); // time only, same day

        for (int i = 0; i < dayHistory.size(); i++) {
            DatabaseHelper.BiomarkerExperimentSummary exp = dayHistory.get(i);
            entries.add(new Entry(i, (float) exp.maxValue));
            dateLabels.add(sdf.format(new Date(exp.timestamp)));
        }

        LineDataSet dataSet = new LineDataSet(entries, "Cortisol (ng/mL)");
        dataSet.setColor(Color.parseColor("#5C6BC0"));
        dataSet.setCircleColor(Color.parseColor("#5C6BC0"));
        dataSet.setLineWidth(2f);
        dataSet.setCircleRadius(4f);
        dataSet.setDrawValues(false);
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        dataSet.setDrawFilled(true);
        dataSet.setFillColor(Color.parseColor("#C5CAE9"));
        dataSet.setFillAlpha(80);

        lineChart.setData(new LineData(dataSet));
        lineChart.getDescription().setEnabled(false);
        lineChart.getLegend().setEnabled(false);
        lineChart.setTouchEnabled(false);

        XAxis xAxis = lineChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setValueFormatter(new IndexAxisValueFormatter(dateLabels));
        xAxis.setTextColor(Color.parseColor("#777777"));
        xAxis.setTextSize(10f);
        xAxis.setLabelRotationAngle(-30f);

        lineChart.getAxisLeft().setTextColor(Color.parseColor("#777777"));
        lineChart.getAxisRight().setEnabled(false);
        lineChart.animateX(600);
        lineChart.invalidate();
    }

    private void exportCsv() {
        List<DatabaseHelper.BiomarkerExperimentSummary> history =
                dbHelper.getLatestBiomarkerExperiments(patientEmail, Integer.MAX_VALUE);

        if (history == null || history.isEmpty()) {
            Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String fileName = "cortisol_" + patientEmail + "_" + System.currentTimeMillis() + ".csv";
            File csvFile = new File(getExternalFilesDir(null), fileName);
            FileWriter writer = new FileWriter(csvFile);
            writer.append("DateTime,Cortisol_ng_ml\n");

            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            for (DatabaseHelper.BiomarkerExperimentSummary exp : history) {
                if (exp.maxValue <= 0) continue;
                writer.append(fmt.format(new Date(exp.timestamp)))
                        .append(",")
                        .append(String.format(Locale.US, "%.2f", exp.maxValue))
                        .append("\n");
            }
            writer.flush();
            writer.close();

            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", csvFile);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/csv");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Export Patient CSV"));

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void applyCortisolBadge(double cortisol) {
        if (cortisol < 5.0) {
            tvCortisolBadge.setText("Low");
            tvCortisolBadge.getBackground().setTint(Color.parseColor("#2196F3"));
        } else if (cortisol <= 25.0) {
            tvCortisolBadge.setText("Normal");
            tvCortisolBadge.getBackground().setTint(Color.parseColor("#4CAF50"));
        } else {
            tvCortisolBadge.setText("High");
            tvCortisolBadge.getBackground().setTint(Color.parseColor("#F44336"));
        }
    }

    private void applyStressLevel(double cortisol) {
        String category, description;
        int progress, cardColor;

        if (cortisol < 5.0) {
            category    = "Low Stress";
            description = "Cortisol levels are below normal range. May indicate adrenal fatigue or under-stress.";
            progress    = 20;
            cardColor   = Color.parseColor("#2196F3");
        } else if (cortisol <= 15.0) {
            category    = "Normal";
            description = "Cortisol levels are within the healthy range. No immediate stress concerns.";
            progress    = 45;
            cardColor   = Color.parseColor("#4CAF50");
        } else if (cortisol <= 25.0) {
            category    = "Elevated Stress";
            description = "Cortisol is slightly elevated. Consider monitoring and stress-reduction activities.";
            progress    = 70;
            cardColor   = Color.parseColor("#FF9800");
        } else {
            category    = "High Stress";
            description = "Cortisol levels are significantly elevated. Clinical follow-up is recommended.";
            progress    = 95;
            cardColor   = Color.parseColor("#F44336");
        }

        tvStressCategory.setText(category);
        tvStressDescription.setText(description);
        progressStressLevel.setProgress(progress);
        resultCategoryCard.setCardBackgroundColor(cardColor);
    }

    private TextView makeCell(String text, boolean bold, String hexColor) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setPadding(16, 14, 16, 14);
        tv.setTextSize(14f);
        tv.setTextColor(Color.parseColor(hexColor));
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    private View makeDivider() {
        View divider = new View(this);
        divider.setLayoutParams(new TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(Color.parseColor("#E0E0E0"));
        return divider;
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

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
