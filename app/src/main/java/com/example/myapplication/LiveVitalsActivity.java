package com.example.myapplication;

import android.Manifest;
import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;

import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import android.graphics.Color;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

/**
 * Minimal live view of the most recent HR / EDA / skin-temperature samples streamed from
 * the Galaxy Watch8, so the pipeline (watch → Data Layer → WatchDataListenerService →
 * this screen) can be visually confirmed end to end. Raw samples are stored by
 * {@link WatchDataListenerService} into {@link VitalsDatabaseHelper} regardless of whether
 * this screen is open.
 *
 * The Heart Rate / EDA / Skin Temperature cards are tappable and open
 * {@link VitalsDayListActivity} for day-wise history + a graph of that signal. The
 * accelerometer readout is hidden by default — it only shows up once "Developer Mode" is
 * switched on (see {@link #swDeveloperMode}); the raw signal is still recorded either way.
 */
public class LiveVitalsActivity extends AppCompatActivity {

    private static final String DEV_PREFS = "live_vitals_prefs";
    private static final String KEY_DEVELOPER_MODE = "developer_mode_enabled";

    private TextView tvHeartRate;
    private TextView tvEda;
    private TextView tvSkinTemp;
    private TextView tvAccel;
    private TextView tvLastUpdated;
    private TextView tvSubtitle;
    private MaterialButton btnStreamToggle;
    private SwitchMaterial swDeveloperMode;
    private MaterialCardView cardAccel;
    private LineChart chartHeartRate;
    private LineChart chartEda;
    private LineChart chartAccel;

    // Rolling live-signal buffers feeding the 3 charts above. Capped to LIVE_CHART_MAX_POINTS
    // so the chart scrolls forward like a monitor readout instead of growing forever; the
    // x-axis is just "sample index since this screen opened", not wall-clock time.
    private static final int LIVE_CHART_MAX_POINTS = 60;
    private final List<Entry> hrEntries = new ArrayList<>();
    private final List<Entry> edaEntries = new ArrayList<>();
    private final List<Entry> accelEntries = new ArrayList<>();
    private float hrSampleIndex = 0f;
    private float edaSampleIndex = 0f;
    private float accelSampleIndex = 0f;

    // True once we know the watch is actively streaming — set optimistically on a manual
    // Start tap, and also inferred from any incoming vitals broadcast (covers reopening this
    // screen while the watch was already streaming from a previous session). There's no
    // explicit "stopped" signal from the watch, so Stop is always a manual/optimistic action.
    private boolean isStreaming = false;

    // The app_session_id sent to the watch with the current/most recent "start" command (see
    // resolveAppSessionId()). Not yet read back anywhere on the phone -- the watch echoes it
    // back as the session_id on every /vitals batch (see VitalsStreamingService.onStartCommand
    // in the watch module), so it already ends up on the stored vitals rows either way.
    private String currentAppSessionId;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            tvSubtitle.setText("Receiving live data from your Galaxy Watch8");

            if (intent.hasExtra("heartRate")) {
                float hr = intent.getFloatExtra("heartRate", -1);
                tvHeartRate.setText(String.format(Locale.US, "%.0f bpm", hr));
                if (hr >= 0) {
                    pushLivePoint(chartHeartRate, hrEntries, hrSampleIndex++, hr,
                            "Heart rate (bpm)", "#E53935");
                }
            }
            if (intent.hasExtra("edaConductance")) {
                float eda = intent.getFloatExtra("edaConductance", -1);
                tvEda.setText(String.format(Locale.US, "%.2f µS", eda));
                if (eda >= 0) {
                    pushLivePoint(chartEda, edaEntries, edaSampleIndex++, eda,
                            "EDA (µS)", "#1976D2");
                }
            }
            if (intent.hasExtra("skinTempC")) {
                float temp = intent.getFloatExtra("skinTempC", -1);
                tvSkinTemp.setText(String.format(Locale.US, "%.1f °C", temp));
            }
            // Any vitals arriving at all means the watch is actively streaming — reflect that
            // even if this screen was opened after streaming was already started elsewhere.
            if (!isStreaming) setStreaming(true);

            if (intent.hasExtra("accelX")) {
                float x = intent.getFloatExtra("accelX", 0);
                float y = intent.getFloatExtra("accelY", 0);
                float z = intent.getFloatExtra("accelZ", 0);
                tvAccel.setText(String.format(Locale.US, "x: %.0f  y: %.0f  z: %.0f", x, y, z));
                // Same raw-counts -> g -> vector-magnitude conversion as accel_windowing.py
                // (COUNTS_PER_G = 4096), so this matches what the offline pipeline sees.
                float magG = (float) Math.sqrt(
                        Math.pow(x / 4096.0, 2) + Math.pow(y / 4096.0, 2) + Math.pow(z / 4096.0, 2));
                pushLivePoint(chartAccel, accelEntries, accelSampleIndex++, magG,
                        "Accel. magnitude (g)", "#8E24AA");
            }

            long receivedAt = intent.getLongExtra("receivedAt", System.currentTimeMillis());
            tvLastUpdated.setText("Last update: " + timeFormat.format(receivedAt));
        }
    };

    private static final String TAG = "LiveVitalsActivity";
    // Must match VitalsControlListenerService.CONTROL_PATH in the watch module.
    private static final String CONTROL_PATH = "/vitals_control";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_vitals);

        tvHeartRate = findViewById(R.id.tvHeartRate);
        tvEda = findViewById(R.id.tvEda);
        tvSkinTemp = findViewById(R.id.tvSkinTemp);
        tvAccel = findViewById(R.id.tvAccel);
        tvLastUpdated = findViewById(R.id.tvLastUpdated);
        tvSubtitle = findViewById(R.id.tvVitalsSubtitle);
        btnStreamToggle = findViewById(R.id.btnStreamToggle);
        swDeveloperMode = findViewById(R.id.swDeveloperMode);
        cardAccel = findViewById(R.id.cardAccel);
        chartHeartRate = findViewById(R.id.chartHeartRate);
        chartEda = findViewById(R.id.chartEda);
        chartAccel = findViewById(R.id.chartAccel);

        setupCharts();
        setupDeveloperMode();

        findViewById(R.id.btnExportCsv).setOnClickListener(v -> exportCsv());
        findViewById(R.id.btnClearData).setOnClickListener(v -> confirmClearData());
        findViewById(R.id.btnViewSessionHistory).setOnClickListener(v ->
                startActivity(new Intent(this, VitalsSessionHistoryActivity.class)));

        findViewById(R.id.cardHeartRate).setOnClickListener(v ->
                openDayList(VitalsDayListActivity.VITAL_TYPE_HR, "Heart Rate / HRV"));
        findViewById(R.id.cardEda).setOnClickListener(v ->
                openDayList(VitalsDayListActivity.VITAL_TYPE_EDA, "EDA — Skin Conductance"));
        findViewById(R.id.cardSkinTemp).setOnClickListener(v ->
                openDayList(VitalsDayListActivity.VITAL_TYPE_SKIN_TEMP, "Skin Temperature"));

        btnStreamToggle.setOnClickListener(v -> {
            if (isStreaming) {
                confirmStopStreaming();
            } else {
                ensureNotificationPermissionThenStart();
            }
        });
    }

    /** One-time styling for the 3 live line charts (heart rate, EDA, accelerometer
     *  magnitude) -- same MPAndroidChart library PatientResultsActivity already uses for the
     *  cortisol graph, just configured for a scrolling live feed instead of a fixed history. */
    private void setupCharts() {
        styleLiveChart(chartHeartRate, "No heart rate data yet");
        styleLiveChart(chartEda, "No EDA data yet");
        styleLiveChart(chartAccel, "No accelerometer data yet");
    }

    private void styleLiveChart(LineChart chart, String noDataText) {
        if (chart == null) return;
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.setTouchEnabled(false);
        chart.setNoDataText(noDataText);
        chart.setNoDataTextColor(ContextCompat.getColor(this, R.color.text_hint));

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setDrawLabels(false);
        xAxis.setDrawAxisLine(true);

        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setTextColor(Color.parseColor("#777777"));
        leftAxis.setTextSize(10f);
        leftAxis.setDrawGridLines(true);
        chart.getAxisRight().setEnabled(false);
    }

    /** Appends one live sample to a chart's rolling buffer (capped at LIVE_CHART_MAX_POINTS,
     *  dropping the oldest point once full so the chart scrolls forward) and redraws it. Same
     *  pattern for all 3 charts -- only the buffer/index and line color differ. */
    private void pushLivePoint(LineChart chart, List<Entry> buffer, float sampleIndex, float value,
                                String label, String hexColor) {
        if (chart == null) return;
        buffer.add(new Entry(sampleIndex, value));
        if (buffer.size() > LIVE_CHART_MAX_POINTS) {
            buffer.remove(0);
        }

        LineDataSet dataSet = new LineDataSet(new ArrayList<>(buffer), label);
        dataSet.setColor(Color.parseColor(hexColor));
        dataSet.setLineWidth(2f);
        dataSet.setDrawCircles(false);
        dataSet.setDrawValues(false);
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        dataSet.setDrawFilled(true);
        dataSet.setFillColor(Color.parseColor(hexColor));
        dataSet.setFillAlpha(40);

        chart.setData(new LineData(dataSet));
        chart.getXAxis().setAxisMinimum(buffer.get(0).getX());
        chart.notifyDataSetChanged();
        chart.invalidate();
    }

    /** Reads/writes the "Developer Mode" toggle from SharedPreferences so it stays in the
     *  state the user last left it in, and shows/hides the raw accelerometer card to match.
     *  This only controls visibility — accelerometer samples keep being received and stored
     *  by {@link WatchDataListenerService} regardless of the toggle. */
    private void setupDeveloperMode() {
        SharedPreferences prefs = getSharedPreferences(DEV_PREFS, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_DEVELOPER_MODE, false);
        swDeveloperMode.setChecked(enabled);
        cardAccel.setVisibility(enabled ? android.view.View.VISIBLE : android.view.View.GONE);

        swDeveloperMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            cardAccel.setVisibility(isChecked ? android.view.View.VISIBLE : android.view.View.GONE);
            prefs.edit().putBoolean(KEY_DEVELOPER_MODE, isChecked).apply();
        });
    }

    private void openDayList(String vitalType, String title) {
        Intent intent = new Intent(this, VitalsDayListActivity.class);
        intent.putExtra(VitalsDayListActivity.EXTRA_VITAL_TYPE, vitalType);
        intent.putExtra(VitalsDayListActivity.EXTRA_TITLE, title);
        startActivity(intent);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private static final int REQ_POST_NOTIFICATIONS = 7101;

    private void ensureNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_POST_NOTIFICATIONS);
            return;
        }
        startWatchStreaming();
    }

    /** Confirms before actually stopping a recording. The live numbers on this screen only
     *  update once every ~10s batch from the watch, so reopening this screen mid-recording can
     *  show blank/stale values for a few seconds even though the button has already correctly
     *  flipped to "Stop watch streaming" -- easy to misread as "not started yet" and tap Stop
     *  by mistake, silently ending a real recording. This dialog is the safety net for that. */
    private void confirmStopStreaming() {
        new AlertDialog.Builder(this)
                .setTitle("Stop watch streaming?")
                .setMessage("This ends the current recording session. Data already recorded stays saved.")
                .setPositiveButton("Stop", (dialog, which) -> {
                    sendControlCommand("stop", null);
                    VitalsIngestService.stop(this);
                    setStreaming(false);
                    currentAppSessionId = null;
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startWatchStreaming() {
        currentAppSessionId = resolveAppSessionId();
        VitalsIngestService.start(this);
        sendControlCommand("start", currentAppSessionId);
        setStreaming(true);
    }

    /** The app_session_id to tag this watch recording with -- reuses one passed in via the
     *  "app_session_id" Intent extra (for a future flow that ties a watch recording +
     *  questionnaire + biomarker test together under one id), or mints a fresh session scoped
     *  to just this recording if none was supplied (e.g. opened directly from Home). */
    private String resolveAppSessionId() {
        String fromIntent = getIntent() != null ? getIntent().getStringExtra("app_session_id") : null;
        if (fromIntent != null && !fromIntent.isEmpty()) {
            return fromIntent;
        }
        String patientEmail = DatabaseHelper.getCurrentUserEmail();
        return DatabaseHelper.getInstance(this).createSession(patientEmail, patientEmail, null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_POST_NOTIFICATIONS) {
            startWatchStreaming();
        }
    }

    /** Updates isStreaming plus the toggle button's label/color to match — green "Start" when
     *  idle, red "Stop" when streaming, so the current state is obvious at a glance. */
    private void setStreaming(boolean streaming) {
        isStreaming = streaming;
        btnStreamToggle.setText(streaming ? "Stop watch streaming" : "Start watch streaming");
        int colorRes = streaming ? R.color.stress_high : R.color.primary;
        btnStreamToggle.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(this, colorRes)));
    }

    /** Sends a "start"/"stop" command to every connected watch node over the Data Layer at
     *  {@link #CONTROL_PATH}, received by VitalsControlListenerService on the watch. This is
     *  fire-and-forget from the phone's side — there's no ack shown beyond the toast; the
     *  watch's own notification / the live values updating (or not) is the real confirmation.
     *
     *  Payload is a small JSON object, e.g. {"command":"start","appSessionId":"asess_..."} —
     *  not a bare string anymore, so the watch can start its recording under the same
     *  app_session_id the phone is tracking (see VitalsControlListenerService /
     *  VitalsStreamingService.onStartCommand in the watch module, which still falls back to
     *  minting its own id if appSessionId is missing, e.g. from an old build on either side).
     *  appSessionId may be null for "stop" (and for "start" if resolveAppSessionId() somehow
     *  returned null, though that shouldn't happen in practice). */
    private void sendControlCommand(String command, String appSessionId) {
        MessageClient messageClient = Wearable.getMessageClient(this);
        JSONObject json = new JSONObject();
        try {
            json.put("command", command);
            if (appSessionId != null && !appSessionId.isEmpty()) {
                json.put("appSessionId", appSessionId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to build control payload for '" + command + "'", e);
        }
        byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
        Wearable.getNodeClient(this).getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    if (nodes.isEmpty()) {
                        Toast.makeText(this, "No paired watch found", Toast.LENGTH_SHORT).show();
                        if ("start".equals(command)) {
                            VitalsIngestService.stop(this);
                            setStreaming(false);
                            currentAppSessionId = null;
                        }
                        return;
                    }
                    for (Node node : nodes) {
                        messageClient.sendMessage(node.getId(), CONTROL_PATH, payload)
                                .addOnFailureListener(e ->
                                        Log.e(TAG, "Failed to send '" + command + "' to " + node.getId(), e));
                    }
                    Toast.makeText(this,
                            command.equals("start") ? "Starting watch streaming…" : "Stopping watch streaming…",
                            Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to look up connected nodes", e);
                    Toast.makeText(this, "Couldn't reach watch", Toast.LENGTH_SHORT).show();
                });
    }

    /** Exports only this screen's own data (vitals CSVs) and opens the share sheet. Combined
     *  Results is the screen for exporting everything at once. */
    private void exportCsv() {
        VitalsCsvExporter.exportVitalsAndShare(this);
    }

    private void confirmClearData() {
        new AlertDialog.Builder(this)
                .setTitle("Clear session data?")
                .setMessage("Deletes all stored EDA / heart-rate / skin-temperature samples from this phone. " +
                        "Export a CSV first if you still need them — this can't be undone.")
                .setPositiveButton("Clear", (dialog, which) -> clearData())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearData() {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            VitalsDatabaseHelper.getInstance(getApplicationContext()).clearAll();
            main.post(() -> Toast.makeText(this, "Session data cleared", Toast.LENGTH_SHORT).show());
        }).start();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(WatchDataListenerService.ACTION_VITALS_UPDATED);
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(receiver);
    }
}
