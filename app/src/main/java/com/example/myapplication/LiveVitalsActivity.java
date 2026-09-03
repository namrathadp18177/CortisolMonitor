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

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Locale;

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

    // True once we know the watch is actively streaming — set optimistically on a manual
    // Start tap, and also inferred from any incoming vitals broadcast (covers reopening this
    // screen while the watch was already streaming from a previous session). There's no
    // explicit "stopped" signal from the watch, so Stop is always a manual/optimistic action.
    private boolean isStreaming = false;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            tvSubtitle.setText("Receiving live data from your Galaxy Watch8");

            if (intent.hasExtra("heartRate")) {
                float hr = intent.getFloatExtra("heartRate", -1);
                tvHeartRate.setText(String.format(Locale.US, "%.0f bpm", hr));
            }
            if (intent.hasExtra("edaConductance")) {
                float eda = intent.getFloatExtra("edaConductance", -1);
                tvEda.setText(String.format(Locale.US, "%.2f µS", eda));
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
                sendControlCommand("stop");
                VitalsIngestService.stop(this);
                setStreaming(false);
            } else {
                ensureNotificationPermissionThenStart();
            }
        });
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

    private void startWatchStreaming() {
        VitalsIngestService.start(this);
        sendControlCommand("start");
        setStreaming(true);
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
     *  watch's own notification / the live values updating (or not) is the real confirmation. */
    private void sendControlCommand(String command) {
        MessageClient messageClient = Wearable.getMessageClient(this);
        byte[] payload = command.getBytes(StandardCharsets.UTF_8);
        Wearable.getNodeClient(this).getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    if (nodes.isEmpty()) {
                        Toast.makeText(this, "No paired watch found", Toast.LENGTH_SHORT).show();
                        if ("start".equals(command)) {
                            VitalsIngestService.stop(this);
                            setStreaming(false);
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
