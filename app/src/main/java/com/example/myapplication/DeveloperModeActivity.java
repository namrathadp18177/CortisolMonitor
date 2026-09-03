package com.example.myapplication;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.LegendRenderer;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

public class DeveloperModeActivity extends AppCompatActivity {

    private static final String TAG = "DevMode";

    private GraphView graph;
    private LineGraphSeries<DataPoint> seriesPort1;
    private LineGraphSeries<DataPoint> seriesAvg;

    private TextView tvConnectionStatus;
    private TextView tvBatteryLevel;
    private TextView tvBatteryLevelCtrl;
    private TextView tvBatteryStatusCtrl;

    private final Handler handler = new Handler();

    // Tracks last plotted index so we only append NEW points
    private int lastPlottedIndex = 0;

    // Poll Char 4 (device status) every 2 seconds when connected
    private final Runnable pollStatus = new Runnable() {
        @Override
        public void run() {
            BluetoothManager mgr = BluetoothManager.getInstance(getApplicationContext());
            if (mgr.isConnected()) {
                mgr.requestStatus();
            }
            handler.postDelayed(this, 2000);
        }
    };

    // Update graph every 200ms
    private final Runnable updateGraph = new Runnable() {
        @Override
        public void run() {

            int len = OutputBluetooth.devModeXAxis.size();

            // Append only new data
            while (lastPlottedIndex < len) {

                double t = OutputBluetooth.devModeXAxis.get(lastPlottedIndex);
                double y1 = OutputBluetooth.devModeYAxis.get(lastPlottedIndex);
                double yav = (OutputBluetooth.devModeAvgY.size() > lastPlottedIndex)
                        ? OutputBluetooth.devModeAvgY.get(lastPlottedIndex)
                        : y1;

                seriesPort1.appendData(
                        new DataPoint(t, y1),
                        true,      // scroll to end
                        5000       // max points kept in memory
                );

                seriesAvg.appendData(
                        new DataPoint(t, yav),
                        true,
                        5000
                );

                lastPlottedIndex++;
            }

            // Update connection status and battery level
            updateDeviceStatus();

            handler.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_developer_mode);

        // IMPORTANT: Activate developer mode data feed
        OutputBluetooth.devModeActive = true;

        Toolbar toolbar = findViewById(R.id.devToolbar);
        setSupportActionBar(toolbar);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Developer Mode");
        }

        Button settingsBtn = findViewById(R.id.devSettingsButton);
        settingsBtn.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(this, SettingsActivity.class);
                intent.putExtra("sps", OutputBluetooth.devSamplesPerSecond);
                intent.putExtra("avgpoints", OutputBluetooth.devMovingAvgValue);
                intent.putExtra("duration", OutputBluetooth.devDurationOfExp);
                intent.putExtra("analog_port", OutputBluetooth.devAnalogPort);
                startActivityForResult(intent, 1);
            } catch (Exception e) {
                Log.e(TAG, "Error starting Dev Settings", e);
            }
        });

        tvConnectionStatus  = findViewById(R.id.devConnectionStatus);
        tvBatteryLevel      = findViewById(R.id.devBatteryLevel);
        tvBatteryLevelCtrl  = findViewById(R.id.devBatteryLevelCtrl);
        tvBatteryStatusCtrl = findViewById(R.id.devBatteryStatusCtrl);

        setupHardwareControls();
        setupGraph();

        handler.post(updateGraph);
        handler.postDelayed(pollStatus, 500); // first status request after 500ms
    }

    /**
     * Wire up the LED brightness and Gain SeekBars.
     * Changes are written live to OutputBluetooth static fields and, if BLE is
     * connected, forwarded to the hardware via BluetoothManager.
     */
    private void setupHardwareControls() {
        SeekBar ledSeekBar   = findViewById(R.id.devLedSeekBar);
        TextView ledValText  = findViewById(R.id.devLedValueText);
        SeekBar gainSeekBar  = findViewById(R.id.devGainSeekBar);
        TextView gainValText = findViewById(R.id.devGainValueText);

        // Load persisted values if the user previously customised them; otherwise keep defaults
        android.content.SharedPreferences hwPrefs =
                getSharedPreferences("dev_hw_prefs", MODE_PRIVATE);
        if (hwPrefs.getBoolean("user_customised", false)) {
            OutputBluetooth.devLedBrightness = hwPrefs.getInt("led_brightness",
                    OutputBluetooth.devLedBrightness);
            OutputBluetooth.devGainWiper     = hwPrefs.getInt("gain_wiper",
                    OutputBluetooth.devGainWiper);
        }

        // Initialise SeekBars from current (possibly restored) values
        ledSeekBar.setProgress(OutputBluetooth.devLedBrightness);
        ledValText.setText(String.valueOf(OutputBluetooth.devLedBrightness));
        gainSeekBar.setProgress(OutputBluetooth.devGainWiper);
        gainValText.setText(String.valueOf(OutputBluetooth.devGainWiper));

        ledSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                ledValText.setText(String.valueOf(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int value = seekBar.getProgress();
                OutputBluetooth.devLedBrightness = value;
                // Persist so subsequent test sessions use the same value
                getSharedPreferences("dev_hw_prefs", MODE_PRIVATE).edit()
                        .putInt("led_brightness", value)
                        .putBoolean("user_customised", true)
                        .apply();
                BluetoothManager mgr = BluetoothManager.getInstance(
                        getApplicationContext());
                if (mgr.isConnected()) {
                    mgr.setLedBrightness((byte) value);
                    Log.i(TAG, "LED brightness set to " + value);
                }
            }
        });

        gainSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                gainValText.setText(String.valueOf(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int value = seekBar.getProgress();
                OutputBluetooth.devGainWiper = value;
                // Persist so subsequent test sessions use the same value
                getSharedPreferences("dev_hw_prefs", MODE_PRIVATE).edit()
                        .putInt("gain_wiper", value)
                        .putBoolean("user_customised", true)
                        .apply();
                BluetoothManager mgr = BluetoothManager.getInstance(
                        getApplicationContext());
                if (mgr.isConnected()) {
                    mgr.setGain((byte) value);
                    Log.i(TAG, "Gain wiper set to " + value);
                }
            }
        });
    }

    /**
     * Refreshes the connection-status and battery TextViews.
     * Called every 200 ms from the updateGraph runnable.
     *
     * Battery decode (per hardware docs, Char 4):
     *   vbatRaw  = 12-bit ADC count (0–4095) from GPIO26 (VBATSNS)
     *   vbatVolts = vbatRaw * (3.3 / 4095)          [ESP32 analogRead scale]
     *   batteryPct = ((vbatVolts - 3.3) / (4.2 - 3.3)) * 100  [LiPo 3.3–4.2V]
     *
     *   Battery Status: Active if BLE is connected and a valid battery reading exists.
     *                   Inactive only when disconnected or no data received yet.
     */
    private void updateDeviceStatus() {
        if (tvConnectionStatus == null || tvBatteryLevel == null) return;

        BluetoothManager mgr = BluetoothManager.getInstance(getApplicationContext());
        boolean connected = mgr.isConnected();

        if (connected) {
            String name = OutputBluetooth.devConnectedDeviceName;
            if (name == null || name.isEmpty()) name = "Optical Detector";
            tvConnectionStatus.setText("● " + name);
            tvConnectionStatus.setTextColor(Color.parseColor("#388E3C")); // green
        } else {
            tvConnectionStatus.setText("● Disconnected");
            tvConnectionStatus.setTextColor(Color.parseColor("#B00020")); // red
        }

        int vbatRaw = OutputBluetooth.devBatteryVbat;

        int vbusRaw = OutputBluetooth.devBatteryVbus;

        // Charging status: VBUS > 50 means USB plugged in → TP4065 is charging
        boolean isCharging = vbusRaw > 50;

        if (vbatRaw > 0) {
            // V_BAT = (vbatRaw / 4095) × 3.3 / 0.2658   (divider: R9=442kΩ, R12=160kΩ)
            // pct   = ((V_BAT - 3.0) / 1.2) × 100        (LiPo usable range: 3.0V–4.2V)
            float vBat = (vbatRaw / 4095f) * 3.3f / 0.2658f;
            int pct = (int) Math.max(0, Math.min(100, ((vBat - 3.0f) / 1.2f) * 100f));
            String pctStr = pct + "%";

            tvBatteryLevel.setText("Battery: " + pctStr);
            if (tvBatteryLevelCtrl != null) tvBatteryLevelCtrl.setText(pctStr);
        } else {
            tvBatteryLevel.setText("Battery: —");
            if (tvBatteryLevelCtrl != null) tvBatteryLevelCtrl.setText("—");
        }

        // Battery status
        if (tvBatteryStatusCtrl != null) {
            if (isCharging) {
                tvBatteryStatusCtrl.setText("Charging");
                tvBatteryStatusCtrl.setTextColor(Color.parseColor("#1565C0")); // blue
            } else if (connected) {
                tvBatteryStatusCtrl.setText("Active");
                tvBatteryStatusCtrl.setTextColor(Color.parseColor("#388E3C")); // green
            } else {
                tvBatteryStatusCtrl.setText("Inactive");
                tvBatteryStatusCtrl.setTextColor(Color.parseColor("#B00020")); // red
            }
        }
    }

    private void setupGraph() {

        graph = findViewById(R.id.graphViewDev);
        graph.setTitle("Voltage (V) vs Time (s)");

        // X axis
        graph.getViewport().setXAxisBoundsManual(true);
        graph.getViewport().setMinX(0);
        graph.getViewport().setMaxX(OutputBluetooth.devDurationOfExp);

        // Y axis fixed 0–5V
        graph.getViewport().setYAxisBoundsManual(true);
        graph.getViewport().setMinY(0.0);
        graph.getViewport().setMaxY(5.0);

        graph.getViewport().setScrollable(true);
        graph.getViewport().setScalable(true);

        graph.getGridLabelRenderer().setNumVerticalLabels(6);
        graph.getGridLabelRenderer().setNumHorizontalLabels(6);

        // Create series once
        seriesPort1 = new LineGraphSeries<>();
        seriesPort1.setTitle("Port 1");
        seriesPort1.setColor(Color.MAGENTA);
        seriesPort1.setThickness(2);

        seriesAvg = new LineGraphSeries<>();
        seriesAvg.setTitle("Filtered");
        seriesAvg.setColor(Color.BLACK);
        seriesAvg.setThickness(3);

        graph.addSeries(seriesPort1);
        graph.addSeries(seriesAvg);

        graph.getLegendRenderer().setVisible(true);
        graph.getLegendRenderer().setAlign(LegendRenderer.LegendAlign.TOP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {

            OutputBluetooth.devSamplesPerSecond =
                    data.getIntExtra("sps", OutputBluetooth.devSamplesPerSecond);

            OutputBluetooth.devMovingAvgValue =
                    data.getIntExtra("avgpoints", OutputBluetooth.devMovingAvgValue);

            OutputBluetooth.devDurationOfExp =
                    data.getDoubleExtra("duration", OutputBluetooth.devDurationOfExp);

            boolean[] newPorts = data.getBooleanArrayExtra("analog_port");
            if (newPorts != null) {
                OutputBluetooth.devAnalogPort = newPorts;
            }

            // Clear buffers for fresh plotting
            OutputBluetooth.devModeXAxis.clear();
            OutputBluetooth.devModeYAxis.clear();
            OutputBluetooth.devModeAvgY.clear();

            lastPlottedIndex = 0;

            seriesPort1.resetData(new DataPoint[]{});
            seriesAvg.resetData(new DataPoint[]{});

            graph.getViewport().setMaxX(OutputBluetooth.devDurationOfExp);

            Log.i(TAG, "Developer settings updated");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        handler.removeCallbacks(updateGraph);
        handler.removeCallbacks(pollStatus);

        OutputBluetooth.devModeActive = false;

        OutputBluetooth.devModeXAxis.clear();
        OutputBluetooth.devModeYAxis.clear();
        OutputBluetooth.devModeAvgY.clear();
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