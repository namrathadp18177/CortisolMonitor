package com.example.myapplication;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.GridLabelRenderer;
import com.jjoe64.graphview.LegendRenderer;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class ResultActivity extends AppCompatActivity {

    private static final String TAG = "ResultActivity";

    private TextView tvCortisol, tvTimestamp, tvPreviousResults;
    private Button btnFinalResults;
    private ImageButton btnBack;
    private TableLayout tablePreviousResults;
    private GraphView graphViewResults;

    private double maxValue = -1.0;
    private double maxValuePort0 = -1.0;
    private double maxValuePort1 = -1.0;
    private String userEmail = "";
    private long timestamp;

    private double cortisolLevel = 0.0;

    private DatabaseHelper dbHelper;

    private long selectedDateTimestamp = 0;

    private TextView  tvPeakCortisol, tvMinCortisol;
    private Button btnExportCsv, btnExportPdf;

    // cache for selected day (for stats; exports use full history)
    private List<DataPoint> todayPoints = new ArrayList<>();

    private long experimentId = -1; // -1 means show all history (default)


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);

        timestamp = System.currentTimeMillis();
        selectedDateTimestamp = timestamp;

        dbHelper = DatabaseHelper.getInstance(this);

        initializeViews();

        Intent intent = getIntent();
        if (intent != null) {
            processIntentData(intent);
        }

        calculateBiomarkerValues();
        displayResults();
        loadAndShowHistoryFromDb();
        setupCortisolGraph(selectedDateTimestamp);
        calculateDailyStatsAndCachePoints(selectedDateTimestamp);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadAndShowHistoryFromDb();   // ensures previous results refresh
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


        tvPeakCortisol = findViewById(R.id.tvPeakCortisol);
        tvMinCortisol  = findViewById(R.id.tvMinCortisol);

        btnExportCsv = findViewById(R.id.btnExportCsv);
        btnExportPdf = findViewById(R.id.btnExportPdf);

        btnExportCsv.setOnClickListener(v -> exportCsvForAllHistory());
        btnExportPdf.setOnClickListener(v -> exportPdfForAllHistory());

        SimpleDateFormat sdf =
                new SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault());
        sdf.setTimeZone(TimeZone.getDefault());

        String formattedTime = sdf.format(new Date(timestamp));
        tvTimestamp.setText("Measured on: " + formattedTime);

        btnFinalResults = findViewById(R.id.btnBiomarker);
        btnFinalResults.setOnClickListener(v -> navigateToFinalResults());
    }

    private void navigateToFinalResults() {
        String role = getIntent().getStringExtra("user_role");
        Intent intent;
        if ("CARE_PROVIDER".equals(role)) {
            intent = new Intent(this, CareProviderDashboardActivity.class);
        } else {
            intent = new Intent(this, PatientMainActivity.class);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void processIntentData(Intent intent) {

        if (intent.hasExtra("user_email")) {
            userEmail = intent.getStringExtra("user_email");
        } else {
            SharedPreferences sessionPrefs =
                    getSharedPreferences("session", MODE_PRIVATE);
            userEmail = sessionPrefs.getString("current_user_email", "");
        }

        if (intent.hasExtra("final_cortisol")) {
            cortisolLevel = intent.getDoubleExtra("final_cortisol", 0.0);
        }

        if (intent.hasExtra("max_value_port1")) {
            maxValuePort1 = intent.getDoubleExtra("max_value_port1", -1.0);
        }

        // NEW — from CortisolGraphActivity tap
        if (intent.hasExtra("experiment_id")) {
            experimentId = intent.getLongExtra("experiment_id", -1);
        }
        // Read selected date from CortisolGraphActivity
        if (intent.hasExtra("selected_date")) {
            selectedDateTimestamp = intent.getLongExtra("selected_date", System.currentTimeMillis());
        }

    }


    private void calculateBiomarkerValues() {

        if (cortisolLevel > 0) return;  // already averaged from OutputBluetooth

        if (maxValuePort1 > 0) {
            double yValue = maxValuePort1 * 1000.0;
            if (yValue >= 428.0 && yValue <= 478.6686) {
                cortisolLevel =
                        Math.pow((yValue - 482.9265) / (-4.2579), 1.0 / 0.5553);
            }
        }
    }

    private void displayResults() {
        if (cortisolLevel > 0) {
            tvCortisol.setText(
                    String.format(Locale.getDefault(),
                            "Cortisol: %.1f ng/mL", cortisolLevel));
            tvCortisol.setTextColor(Color.parseColor("#2E7D32"));
        } else {
            tvCortisol.setText("Cortisol: Invalid (no valid samples)");
            tvCortisol.setTextColor(Color.parseColor("#D32F2F"));
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

    private void loadAndShowHistoryFromDb() {

        int childCount = tablePreviousResults.getChildCount();
        if (childCount > 1) {
            tablePreviousResults.removeViews(1, childCount - 1);
        }

        List<DatabaseHelper.BiomarkerExperimentSummary> history;
        if (experimentId != -1) {
            history = dbHelper.getExperimentById(userEmail, experimentId);
        } else {
            history = dbHelper.getLatestBiomarkerExperiments(userEmail, Integer.MAX_VALUE);
        }


        if (history == null || history.isEmpty()) {
            tvPreviousResults.setText("No previous results available.");
            tvPreviousResults.setVisibility(View.VISIBLE);
            graphViewResults.setVisibility(View.GONE);
            return;
        } else {
            tvPreviousResults.setVisibility(View.GONE);
            graphViewResults.setVisibility(View.VISIBLE);
        }

        SimpleDateFormat dayFormat  =
                new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
        SimpleDateFormat timeFormat =
                new SimpleDateFormat("HH:mm", Locale.getDefault());

        // Group readings by day label, preserving order
        LinkedHashMap<String, List<DatabaseHelper.BiomarkerExperimentSummary>> byDay =
                new LinkedHashMap<>();

        for (DatabaseHelper.BiomarkerExperimentSummary exp : history) {
            String dayKey = dayFormat.format(new Date(exp.timestamp));
            if (!byDay.containsKey(dayKey)) {
                byDay.put(dayKey, new ArrayList<>());
            }
            byDay.get(dayKey).add(exp);
        }

        for (Map.Entry<String, List<DatabaseHelper.BiomarkerExperimentSummary>> entry
                : byDay.entrySet()) {

            // compute this day's start timestamp from the first experiment
            long firstTs = entry.getValue().get(0).timestamp;
            long dayStartTs = getStartOfDay(firstTs);

            // ── Day header row with "tap to view" ──
            TableRow headerRow = new TableRow(this);
            headerRow.setClickable(true);

            TextView tvDayHeader = new TextView(this);
            tvDayHeader.setText(entry.getKey() + "   (tap to view)");
            tvDayHeader.setTypeface(null, Typeface.BOLD);
            tvDayHeader.setTextColor(Color.parseColor("#1565C0"));
            tvDayHeader.setTextSize(14f);
            tvDayHeader.setPadding(16, 16, 8, 4);

            TableRow.LayoutParams spanParams = new TableRow.LayoutParams();
            spanParams.span = 2;
            tvDayHeader.setLayoutParams(spanParams);
            headerRow.addView(tvDayHeader);

            headerRow.setOnClickListener(v -> {
                selectedDateTimestamp = dayStartTs;
                setupCortisolGraph(selectedDateTimestamp);
                calculateDailyStatsAndCachePoints(selectedDateTimestamp);
            });

            tablePreviousResults.addView(headerRow);

            // ── Reading rows under this day ──
            for (DatabaseHelper.BiomarkerExperimentSummary exp : entry.getValue()) {

                TableRow row = new TableRow(this);

                TextView tvTime = new TextView(this);
                tvTime.setText("  " + timeFormat.format(new Date(exp.timestamp)));
                tvTime.setPadding(32, 6, 8, 6);   // extra left indent under day header

                double cortisol = exp.maxValue;
                TextView tvCort = new TextView(this);
                if (cortisol <= 0) {
                    tvCort.setText("Invalid");
                    tvCort.setTextColor(Color.parseColor("#D32F2F"));
                } else {
                    tvCort.setText(String.format(Locale.getDefault(),
                            "%.2f ng/mL", cortisol));
                    tvCort.setTextColor(Color.parseColor("#4CAF50"));
                }

                tvCort.setPadding(8, 6, 16, 6);
                tvCort.setGravity(Gravity.END);

                row.addView(tvTime);
                row.addView(tvCort);

                tablePreviousResults.addView(row);
            }
        }
    }

    // Daily stats (for selectedDateTimestamp) + cache points for that day
    private void calculateDailyStatsAndCachePoints(long dateTimestamp) {
        todayPoints.clear();

        List<DatabaseHelper.BiomarkerExperimentSummary> history =
                dbHelper.getLatestBiomarkerExperiments(userEmail, Integer.MAX_VALUE);

        if (history == null || history.isEmpty()) {
            tvPeakCortisol.setText("Peak: --");
            tvMinCortisol.setText("Lowest: --");
            return;
        }

        long dayStart = getStartOfDay(dateTimestamp);
        long dayEnd   = getEndOfDay(dateTimestamp);

        int count = 0;
        double peak = -Double.MAX_VALUE;
        double min = Double.MAX_VALUE;

        for (DatabaseHelper.BiomarkerExperimentSummary exp : history) {
            if (exp.timestamp < dayStart || exp.timestamp > dayEnd) continue;

            double cortisol = exp.maxValue;
            if (cortisol <= 0) continue;

            double hours = (exp.timestamp - dayStart) / (1000.0 * 60.0 * 60.0);
            todayPoints.add(new DataPoint(hours, cortisol));

            count++;
            if (cortisol > peak) peak = cortisol;
            if (cortisol < min) min = cortisol;
        }

        if (count > 0) {
            tvPeakCortisol.setText(String.format(Locale.getDefault(),
                    "Peak: %.2f ng/mL", peak));
            tvMinCortisol.setText(String.format(Locale.getDefault(),
                    "Lowest: %.2f ng/mL", min));
        } else {
            tvPeakCortisol.setText("Peak: --");
            tvMinCortisol.setText("Lowest: --");
        }
    }


    // ---------- EXPORT: CSV (ALL HISTORY) ----------

    private void exportCsvForAllHistory() {
        List<DatabaseHelper.BiomarkerExperimentSummary> history =
                dbHelper.getLatestBiomarkerExperiments(userEmail, Integer.MAX_VALUE);

        if (history == null || history.isEmpty()) {
            Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String fileName = "cortisol_history_" + System.currentTimeMillis() + ".csv";
            File dir = getExternalFilesDir(null);
            File csvFile = new File(dir, fileName);

            FileWriter writer = new FileWriter(csvFile);
            writer.append("DateTime,Cortisol_ng_ml\n");

            SimpleDateFormat fullFormat =
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

            for (DatabaseHelper.BiomarkerExperimentSummary exp : history) {
                double cortisol = exp.maxValue;
                if (cortisol <= 0) continue;

                String time = fullFormat.format(new Date(exp.timestamp));
                writer.append(time)
                        .append(",")
                        .append(String.format(Locale.getDefault(), "%.2f", cortisol))
                        .append("\n");
            }

            writer.flush();
            writer.close();

            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    csvFile
            );

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/csv");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(shareIntent, "Share cortisol CSV (all history)"));
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Failed to export CSV", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- EXPORT: PDF (ALL HISTORY + CURRENT VIEW) ----------

    private void exportPdfForAllHistory() {
        View root = findViewById(R.id.resultRootLayout);

        if (root == null) {
            Toast.makeText(this, "Layout not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        // Hide graph to avoid hardware-accel crash
        int oldGraphVisibility = graphViewResults.getVisibility();
        graphViewResults.setVisibility(View.GONE);

        root.measure(
                View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());

        Bitmap bitmap = Bitmap.createBitmap(
                root.getMeasuredWidth(),
                root.getMeasuredHeight(),
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(bitmap);
        root.draw(canvas);

        // Restore graph visibility
        graphViewResults.setVisibility(oldGraphVisibility);

        PdfDocument document = new PdfDocument();
        PdfDocument.PageInfo pageInfo =
                new PdfDocument.PageInfo.Builder(
                        bitmap.getWidth(),
                        bitmap.getHeight(),
                        1).create();

        PdfDocument.Page page = document.startPage(pageInfo);
        Canvas pdfCanvas = page.getCanvas();
        pdfCanvas.drawBitmap(bitmap, 0, 0, null);
        document.finishPage(page);

        try {
            String fileName = "cortisol_history_" + System.currentTimeMillis() + ".pdf";

            // Save to Downloads folder (Android 10+)
            File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            File pdfFile = new File(downloadsDir, fileName);

            FileOutputStream fos = new FileOutputStream(pdfFile);
            document.writeTo(fos);
            fos.close();
            document.close();

            // Notify media scanner so file appears immediately in Downloads
            android.media.MediaScannerConnection.scanFile(
                    this,
                    new String[]{pdfFile.getAbsolutePath()},
                    new String[]{"application/pdf"},
                    null
            );

            Toast.makeText(this,
                    "PDF saved to Downloads: " + fileName,
                    Toast.LENGTH_LONG).show();

            // Still offer share option
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    pdfFile
            );

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/pdf");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(shareIntent, "Share cortisol PDF"));

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Failed to export PDF: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- GRAPH ----------

    private void setupCortisolGraph(long dateTimestamp) {

        graphViewResults.removeAllSeries();

        List<DatabaseHelper.BiomarkerExperimentSummary> history =
                dbHelper.getLatestBiomarkerExperiments(userEmail, Integer.MAX_VALUE);

        if (history == null || history.isEmpty()) return;

        List<DataPoint> validPoints = new ArrayList<>();

        long dayStart = getStartOfDay(dateTimestamp);
        long dayEnd = getEndOfDay(dateTimestamp);

        for (DatabaseHelper.BiomarkerExperimentSummary exp : history) {

            // only this day
            if (exp.timestamp < dayStart || exp.timestamp > dayEnd) continue;

            double hours =
                    (exp.timestamp - dayStart) / (1000.0 * 60.0 * 60.0);

            double cortisol = exp.maxValue;

            if (cortisol > 0) {
                validPoints.add(new DataPoint(hours, cortisol));
            }
        }

        // shaded normal band 3–10 ng/mL
        addNormalRangeBand(0, 24, 3.0, 10.0);

        if (!validPoints.isEmpty()) {

            // sort points by time (X)
            java.util.Collections.sort(validPoints, (p1, p2) ->
                    Double.compare(p1.getX(), p2.getX()));

            DataPoint[] pointsArray = validPoints.toArray(new DataPoint[0]);

            LineGraphSeries<DataPoint> line =
                    new LineGraphSeries<>(pointsArray);
            line.setColor(Color.parseColor("#4CAF50"));
            line.setThickness(6);
            line.setTitle("Yours");
            line.setDrawDataPoints(true);
            line.setDataPointsRadius(10f);
            line.setDrawAsPath(false);

            graphViewResults.addSeries(line);
        }

        graphViewResults.getViewport().setMinY(0);
        graphViewResults.getViewport().setMaxY(12);
        graphViewResults.getViewport().setMinX(0);
        graphViewResults.getViewport().setMaxX(24);
        graphViewResults.getViewport().setScrollable(true);
        graphViewResults.getViewport().setScalable(true);

        GridLabelRenderer gridLabel = graphViewResults.getGridLabelRenderer();
        gridLabel.setHorizontalAxisTitle("Time of day (hours)");
        gridLabel.setVerticalAxisTitle("Cortisol (ng/mL)");
        gridLabel.setNumHorizontalLabels(5);
        gridLabel.setNumVerticalLabels(7);
        gridLabel.setTextSize(28f);
        gridLabel.setPadding(16);
        gridLabel.setGridColor(Color.parseColor("#DDDDDD"));
        gridLabel.setHorizontalLabelsColor(Color.parseColor("#666666"));
        gridLabel.setVerticalLabelsColor(Color.parseColor("#666666"));

        LegendRenderer legend = graphViewResults.getLegendRenderer();
        legend.setVisible(true);
        legend.setAlign(LegendRenderer.LegendAlign.TOP);
        legend.setTextSize(26f);
    }

    /**
     * Draw a shaded “normal range” band on the graph using a background line series.
     * Normal range here is 3–10 ng/mL.
     */
    private void addNormalRangeBand(double minX, double maxX,
                                    double normalLow, double normalHigh) {

        DataPoint[] lowLine = new DataPoint[]{
                new DataPoint(minX, normalLow),
                new DataPoint(maxX, normalLow)
        };
        DataPoint[] highLine = new DataPoint[]{
                new DataPoint(minX, normalHigh),
                new DataPoint(maxX, normalHigh)
        };

        LineGraphSeries<DataPoint> lowSeries = new LineGraphSeries<>(lowLine);
        lowSeries.setColor(Color.TRANSPARENT);
        lowSeries.setThickness(1);

        LineGraphSeries<DataPoint> highSeries = new LineGraphSeries<>(highLine);
        highSeries.setColor(Color.TRANSPARENT);
        highSeries.setThickness(1);
        highSeries.setDrawBackground(true);
        highSeries.setBackgroundColor(Color.argb(60, 76, 175, 80));  // #4CAF50 with alpha

        graphViewResults.addSeries(lowSeries);
        graphViewResults.addSeries(highSeries);
    }


}
