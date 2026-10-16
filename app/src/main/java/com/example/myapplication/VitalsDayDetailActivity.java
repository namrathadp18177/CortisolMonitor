package com.example.myapplication;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.jjoe64.graphview.DefaultLabelFormatter;
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One calendar day's graph for one vital signal (heart rate, EDA, or skin temperature),
 * reached from {@link VitalsDayListActivity}. For heart rate, also computes and shows
 * day-level HRV metrics (RMSSD/SDNN/mean HR/pNN50) via {@link HrvCalculator}, and offers a
 * CSV export of that day's data (+ HRV summary, for heart rate).
 *
 * X axis is clock time since local midnight (00:00–24:00), labeled every 4 hours
 * (00:00/04:00/08:00/.../24:00). The plotted line itself is averaged into
 * {@link #BUCKET_HOURS}-wide windows — hourly by default — which is finer than the axis
 * labels, so pinch/scroll-zooming into the chart (already enabled) reveals real hour-to-hour
 * shape rather than straight lines between 4-hour label ticks. This is a display
 * simplification only — the CSV export and the HRV computation both still run on every raw
 * 1 Hz sample, untouched by this.
 */
public class VitalsDayDetailActivity extends AppCompatActivity {

    public static final String EXTRA_VITAL_TYPE = "vital_type";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_DAY_START_MS = "day_start_ms";
    public static final String EXTRA_DAY_END_MS = "day_end_ms";

    /** Width, in hours, of each averaged window on the day graph — 1h gives up to 24 points
     *  across a day, fine enough that zooming into any hour still shows real shape, not a
     *  straight line. Purely a display simplification — every raw sample is still in the
     *  database and in the CSV export regardless of this value. */
    private static final int BUCKET_HOURS = 1;

    private String vitalType;
    private String title;
    private long dayStartMs;
    private long dayEndMs;

    private GraphView graph;
    private TextView tvDayDetailDate;
    private TextView tvDayDetailNoData;
    private View hrvCard;
    private TextView tvHrvSummary;
    private ExecutorService executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vitals_day_detail);

        vitalType = getIntent().getStringExtra(EXTRA_VITAL_TYPE);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        dayStartMs = getIntent().getLongExtra(EXTRA_DAY_START_MS, 0);
        dayEndMs = getIntent().getLongExtra(EXTRA_DAY_END_MS, 0);
        if (vitalType == null) vitalType = VitalsDatabaseHelper.VITAL_TYPE_HR;
        if (title == null) title = "Vitals";

        Toolbar toolbar = findViewById(R.id.vitalsDayDetailToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(title);
        }

        graph = findViewById(R.id.graphVitalsDay);
        tvDayDetailDate = findViewById(R.id.tvVitalsDayDetailDate);
        tvDayDetailNoData = findViewById(R.id.tvVitalsDayDetailNoData);
        hrvCard = findViewById(R.id.cardHrvSummary);
        tvHrvSummary = findViewById(R.id.tvHrvSummary);

        SimpleDateFormat dayFmt = new SimpleDateFormat("EEEE, MMM dd, yyyy", Locale.getDefault());
        tvDayDetailDate.setText(dayStartMs > 0 ? dayFmt.format(new Date(dayStartMs)) : "");

        findViewById(R.id.btnExportVitalsDay).setOnClickListener(v ->
                VitalsCsvExporter.exportVitalsDayAndShare(this, vitalType, dayStartMs, dayEndMs));

        findViewById(R.id.btnExportAllVitalsDay).setOnClickListener(v ->
                VitalsCsvExporter.exportAllVitalsDayAndShare(this, dayStartMs, dayEndMs));

        hrvCard.setVisibility(VitalsDatabaseHelper.VITAL_TYPE_HR.equals(vitalType) ? View.VISIBLE : View.GONE);

        executor = Executors.newSingleThreadExecutor();
        loadAndPlot();
    }

    private void loadAndPlot() {
        String userEmail = DatabaseHelper.getCurrentUserEmail();
        String type = vitalType;
        long start = dayStartMs;
        long end = dayEndMs;
        Handler main = new Handler(Looper.getMainLooper());

        executor.execute(() -> {
            VitalsDatabaseHelper db = VitalsDatabaseHelper.getInstance(this);
            List<VitalsDatabaseHelper.VitalPoint> points =
                    db.getVitalSeriesForRange(userEmail, type, start, end);

            HrvCalculator.HrvMetrics hrv = null;
            if (VitalsDatabaseHelper.VITAL_TYPE_HR.equals(type)) {
                List<Integer> ibi = db.getValidIbiSeriesForRange(userEmail, start, end);
                hrv = HrvCalculator.compute(ibi);
            }

            HrvCalculator.HrvMetrics finalHrv = hrv;
            main.post(() -> {
                plotPoints(points);
                if (VitalsDatabaseHelper.VITAL_TYPE_HR.equals(type)) {
                    renderHrvSummary(finalHrv);
                }
            });
        });
    }

    private void plotPoints(List<VitalsDatabaseHelper.VitalPoint> points) {
        if (points == null || points.isEmpty()) {
            tvDayDetailNoData.setVisibility(View.VISIBLE);
            graph.setVisibility(View.GONE);
            return;
        }
        tvDayDetailNoData.setVisibility(View.GONE);
        graph.setVisibility(View.VISIBLE);

        List<VitalsDatabaseHelper.VitalPoint> plotPoints = bucketByFixedInterval(points, BUCKET_HOURS);

        DataPoint[] data = new DataPoint[plotPoints.size()];
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int i = 0; i < plotPoints.size(); i++) {
            VitalsDatabaseHelper.VitalPoint p = plotPoints.get(i);
            double hourOfDay = (p.timestampMs - dayStartMs) / 3_600_000.0;
            data[i] = new DataPoint(hourOfDay, p.value);
            if (p.value < minY) minY = p.value;
            if (p.value > maxY) maxY = p.value;
        }

        LineGraphSeries<DataPoint> series = new LineGraphSeries<>(data);
        series.setColor(Color.parseColor("#1565C0"));
        series.setThickness(4);
        series.setDrawDataPoints(plotPoints.size() < 200);
        series.setDataPointsRadius(6);

        graph.removeAllSeries();
        graph.addSeries(series);
        graph.setTitle(axisTitle());
        graph.getViewport().setXAxisBoundsManual(true);
        graph.getViewport().setMinX(0);
        graph.getViewport().setMaxX(24);
        // 7 evenly-spaced labels across a 0–24 range lands exactly on 00:00, 04:00, 08:00,
        // 12:00, 16:00, 20:00, 24:00 — a clean 4-hour-spaced axis regardless of how many
        // points are actually plotted underneath it.
        graph.getGridLabelRenderer().setNumHorizontalLabels(7);
        graph.getGridLabelRenderer().setVerticalAxisTitle(axisTitle());
        // No separate x-axis title here on purpose — GraphView draws the label row and the
        // axis title at (nearly) the same reserved height when both use the default text
        // size, which reads as the title sitting on top of the labels. The "HH:00" labels
        // are self-explanatory as clock time, so we just drop the redundant title instead of
        // fighting that spacing. Shrinking + angling the labels fixes the other complaint —
        // 7 "HH:00" strings at full size and 0° collide with their neighbors; angled labels
        // take less horizontal room per tick.
        graph.getGridLabelRenderer().setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 11f, getResources().getDisplayMetrics()));
        graph.getGridLabelRenderer().setHorizontalLabelsAngle(30);
        graph.getGridLabelRenderer().setLabelFormatter(new DefaultLabelFormatter() {
            @Override
            public String formatLabel(double value, boolean isValueX) {
                if (!isValueX) return super.formatLabel(value, false);
                int hour = ((int) Math.round(value)) % 24;
                if (hour < 0) hour += 24;
                return String.format(Locale.US, "%02d:00", hour);
            }
        });

        // A little headroom above/below so the line isn't glued to the chart edges.
        double pad = Math.max(1.0, (maxY - minY) * 0.15);
        graph.getViewport().setYAxisBoundsManual(true);
        graph.getViewport().setMinY(minY - pad);
        graph.getViewport().setMaxY(maxY + pad);
        graph.getViewport().setScrollable(true);
        graph.getViewport().setScalable(true);
    }

    private String axisTitle() {
        if (VitalsDatabaseHelper.VITAL_TYPE_HR.equals(vitalType)) return "Heart rate (bpm)";
        if (VitalsDatabaseHelper.VITAL_TYPE_EDA.equals(vitalType)) return "EDA (µS)";
        return "Skin temperature (°C)";
    }

    /** Averages the day's raw samples into fixed windows {@code bucketHours} wide, aligned to
     *  local midnight (e.g. 4h windows → up to 6 points across a 24h day), so the on-screen
     *  chart reads as a handful of clear points instead of a dense per-second trace. A window
     *  with no samples is skipped rather than plotted as zero/gap. Purely a display
     *  simplification — the CSV export and the HRV computation don't go through this, they
     *  both use every raw sample directly from the database. */
    private List<VitalsDatabaseHelper.VitalPoint> bucketByFixedInterval(
            List<VitalsDatabaseHelper.VitalPoint> points, int bucketHours) {
        long bucketMs = bucketHours * 3_600_000L;
        List<VitalsDatabaseHelper.VitalPoint> result = new ArrayList<>();

        long bucketStart = dayStartMs;
        long tsSum = 0;
        double valSum = 0;
        int count = 0;

        for (VitalsDatabaseHelper.VitalPoint p : points) {
            while (p.timestampMs >= bucketStart + bucketMs) {
                if (count > 0) {
                    result.add(new VitalsDatabaseHelper.VitalPoint(tsSum / count, valSum / count));
                    tsSum = 0;
                    valSum = 0;
                    count = 0;
                }
                bucketStart += bucketMs;
            }
            tsSum += p.timestampMs;
            valSum += p.value;
            count++;
        }
        if (count > 0) {
            result.add(new VitalsDatabaseHelper.VitalPoint(tsSum / count, valSum / count));
        }
        return result;
    }

    private void renderHrvSummary(HrvCalculator.HrvMetrics hrv) {
        if (hrv == null) {
            tvHrvSummary.setText("Not enough valid IBI data on this day to compute HRV.");
            return;
        }
        String confidence = hrv.hasEnoughData() ? "" :
                "\n(Fewer than 30 valid beats — treat these as low-confidence.)";
        tvHrvSummary.setText(String.format(Locale.US,
                "RMSSD: %.1f ms\nSDNN: %.1f ms\npNN50: %.1f%%\nMean HR: %.0f bpm\nMean IBI: %.0f ms\nValid beats used: %d%s",
                hrv.rmssdMs, hrv.sdnnMs, hrv.pnn50Percent, hrv.meanHrBpm, hrv.meanIbiMs, hrv.beatCount, confidence));
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
