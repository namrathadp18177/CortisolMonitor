package com.example.myapplication;

import android.Manifest;
import java.lang.reflect.Method;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.LegendRenderer;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;
import com.jjoe64.graphview.GridLabelRenderer;
import com.jjoe64.graphview.Viewport;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class OutputBluetooth extends AppCompatActivity {
    private static final String TAG = "OutputBluetooth";
    private static final int REQUEST_BLUETOOTH_PERMISSION = 1;
    private static final int REQUEST_LOCATION_PERMISSION = 2;
    private static final int REQUEST_STORAGE_PERMISSION = 3;
    private boolean autoReconnectEnabled = true;
    private boolean isReconnecting = false;
    private final int REFRESH_THRESHOLD = 1500;
    private boolean isRefreshing = false;
    private static final int AUTO_RECONNECT_THRESHOLD = 1400; // Reconnect well before problems
    private final Object reconnectLock = new Object();
    private volatile boolean reconnecting = false;

    // Connection state management
    private volatile boolean isConnecting = false;
    private volatile boolean connectionEstablished = false;
    private final Object connectionLock = new Object();

    private long sampleTimestamp = -1;

    // UI Elements
    private Button startButton, stopButton, saveButton, settingsButton, connectButton, showButton;
    private TextView textViewAppend;
    private static final int BATCH_SIZE = 1000;
    private int currentBatch = 1;
    private GraphView graphView;

    // Graph data storage
    private ArrayList<LineGraphSeries<DataPoint>> graphData;
    private final ArrayList<Double> dataPoints0 = new ArrayList<>();
    private final ArrayList<Double> dataPoints1 = new ArrayList<>();
    private final ArrayList<Double> dataPoints2 = new ArrayList<>();
    private final ArrayList<Double> dataPointsavgd = new ArrayList<>();
    private static final int RECONNECT_THRESHOLD = 1300; // Lower threshold to avoid issues

    private long lastReconnectTime = 0;
    private static final long RECONNECT_COOLDOWN = 30000; // 30 seconds between reconnection attempts
    private final ArrayList<Double> xaxis = new ArrayList<>();
    private final ArrayList<Double> dataPoints0Unclean = new ArrayList<>();
    private final ArrayList<Double> dataPoints1Unclean = new ArrayList<>();
    private final ArrayList<Double> dataPoints2Unclean = new ArrayList<>();
    private final ArrayList<Double> dataPointsavgdUnclean = new ArrayList<>();

    // Bluetooth fields
    private final BluetoothAdapter mBA = BluetoothAdapter.getDefaultAdapter();
    private Set<BluetoothDevice> pairedDevices;
    private BluetoothDevice myDevice;
    private BluetoothSocket mySocket = null;
    private InputStream is;
    private OutputStream os;

    // Experiment parameters
    private double analogRef = 5.0;
    private double durationOfExp = 600.0;
    private int samplesPerSecond = 5;
    private int movingAvgValue = 4;
    private int ADCbits = 15;
    private boolean[] analogPort = {true, true, true, true};

    // State
    private int backCount = 0;
    private int graphCount = 0;
    private int dataSize = 0;
    private double time = 0.0;
    private double max = 0.0;
    private String userEmail;
    private boolean started = false;

    // Buffer for partial packets
    private StringBuilder dataBuffer = new StringBuilder(256);

    // Executor for scheduled polling
    private ScheduledExecutorService scheduler;
    private int errorCount = 0;
    private static final int MAX_ERRORS = 5;

    // Handler to pass data back to UI thread
    private final Handler myHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            String stream = msg.getData().getString("data");
            if (stream == null) {
                Log.e(TAG, "Received null data stream");
                return;
            }
            dataBuffer.append(stream);
            processBufferedData();
        }
    };

    // Database helper
    private DatabaseHelper dbHelper;
    private long currentExperimentId = -1;

    //————————————————————————————
    // Activity Lifecycle
    //————————————————————————————

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_output_bluetooth);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sampleTimestamp = getIntent().getLongExtra("sample_timestamp", -1);

        initializeViews();
        checkPermissions();
        loadSettings();
        GraphView graph = findViewById(R.id.graphViewBL);
        Viewport vp = graph.getViewport();

// Ensure the whole graph is visible and scrollable/zoomable as needed
        vp.setScrollable(true);
        vp.setScalable(true);

// Avoid x‑axis labels overlapping
        GridLabelRenderer gridLabel = graph.getGridLabelRenderer();
        gridLabel.setNumHorizontalLabels(4);   // fewer X labels
        gridLabel.setNumVerticalLabels(5);     // cleaner Y labels

// Add some padding around labels so they are not cut off
        gridLabel.setPadding(16);
        gridLabel.setHorizontalLabelsAngle(0); // keep them horizontal; use 45 if still crowded

// Optional: set label text size a bit smaller
        gridLabel.setTextSize(28f); // default is ~30f, reduce if still clipped



        // Initialize database helper using the singleton pattern
        dbHelper = DatabaseHelper.getInstance(this);

        // Set current user email in DatabaseHelper if coming from login
        if (userEmail != null && !userEmail.isEmpty()) {
            DatabaseHelper.setCurrentUserEmail(userEmail);
        }

        userEmail = getIntent().getStringExtra("user_email");
        graphData = new ArrayList<>();
        resetGraph();
    }

    @Override
    protected void onResume() {
        super.onResume();
        initializeBluetooth();
    }

    @Override
    protected void onStop() {
        super.onStop();
        shutdownScheduler();
        if (started) sendTimer(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        shutdownScheduler();
        if (started) sendTimer(false);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            // Settings activity result
            try {
                int newSamplesPerSecond = data.getIntExtra("sps", samplesPerSecond);
                int newMovingAvgValue = data.getIntExtra("avgpoints", movingAvgValue);
                double newDurationOfExp = data.getDoubleExtra("duration", durationOfExp);
                boolean[] newAnalogPort = data.getBooleanArrayExtra("analog_port");
                if (newAnalogPort == null) {
                    newAnalogPort = new boolean[]{true, true, true, true};
                }

                // Check if settings changed and we have existing data
                boolean settingsChanged = (newSamplesPerSecond != samplesPerSecond) ||
                        (newMovingAvgValue != movingAvgValue) ||
                        (newDurationOfExp != durationOfExp) ||
                        !java.util.Arrays.equals(newAnalogPort, analogPort);

                if (settingsChanged && hasExistingData()) {
                    // Show warning about existing data
                    showSettingsChangeWarning(newSamplesPerSecond, newMovingAvgValue, newDurationOfExp, newAnalogPort);
                } else {
                    // No existing data or no changes, apply settings directly
                    applyNewSettings(newSamplesPerSecond, newMovingAvgValue, newDurationOfExp, newAnalogPort);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error processing settings result", e);
                Toast.makeText(this, "Error updating settings", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == 0) {
            // Bluetooth enable request result
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show();
                // Wait a bit for Bluetooth to fully initialize
                new Handler().postDelayed(() -> {
                    initializeBluetooth();
                }, 1000);
            } else {
                Toast.makeText(this, "Bluetooth is required for this app", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showSettingsChangeWarning(int newSps, int newAvg, double newDuration, boolean[] newPorts) {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("Settings Changed")
                .setMessage("You have " + dataSize + " existing data points.\n\nChanging settings may affect data consistency.\n\nWhat would you like to do?")
                .setIcon(android.R.drawable.ic_dialog_info)
                .setPositiveButton("Apply & Keep Data", (dialog, which) -> {
                    Log.i(TAG, "User chose to apply settings and keep existing data");
                    applyNewSettings(newSps, newAvg, newDuration, newPorts);
                    Toast.makeText(this, "Settings applied. Note: Data consistency may be affected.", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Apply & Clear Data", (dialog, which) -> {
                    Log.i(TAG, "User chose to apply settings and clear existing data");
                    applyNewSettings(newSps, newAvg, newDuration, newPorts);
                    resetDataArrays();
                    Toast.makeText(this, "Settings applied and data cleared", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Cancel Changes", (dialog, which) -> {
                    Log.i(TAG, "User cancelled settings changes");
                    Toast.makeText(this, "Settings changes cancelled", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                })
                .setCancelable(true)
                .show();
    }

    private void applyNewSettings(int newSps, int newAvg, double newDuration, boolean[] newPorts) {
        samplesPerSecond = newSps;
        movingAvgValue = newAvg;
        durationOfExp = newDuration;
        analogPort = newPorts;

        saveSettings();
        resetGraph();

        Log.i(TAG, "Settings updated - SPS: " + samplesPerSecond +
                ", MovingAvg: " + movingAvgValue + ", Duration: " + durationOfExp);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        boolean allGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        switch (requestCode) {
            case REQUEST_BLUETOOTH_PERMISSION:
                if (allGranted) {
                    // Remove verbose success toast - just proceed
                    Log.i(TAG, "Bluetooth permissions granted");
                    initializeBluetooth();
                } else {
                    Toast.makeText(this, "Bluetooth permissions are required for this app", Toast.LENGTH_LONG).show();
                }
                break;

            case REQUEST_LOCATION_PERMISSION:
                if (allGranted) {
                    // Remove verbose success toast
                    Log.i(TAG, "Location permission granted");
                } else {
                    // Keep this as it's important for user understanding
                    Toast.makeText(this, "Location permission may be required for Bluetooth scanning", Toast.LENGTH_LONG).show();
                }
                break;

            case REQUEST_STORAGE_PERMISSION:
                if (allGranted) {
                    // Remove verbose success toast
                    Log.i(TAG, "Storage permission granted");
                    try {
                        saveToFile(); // Retry the save operation
                    } catch (IOException e) {
                        Log.e(TAG, "Error saving file after permission granted", e);
                        Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(this, "Storage permission denied. Cannot save files.", Toast.LENGTH_LONG).show();
                }
                break;

            default:
                Log.w(TAG, "Unknown permission request code: " + requestCode);
                break;
        }
    }

    //————————————————————————————
    // UI Callbacks
    //————————————————————————————

    public void onClickStart(View v) {
        synchronized (connectionLock) {
            // 1) Stop any existing job & ensure clean state
            sendTimer(false);

            // 2) Check if we have existing data and ask user what to do
            if (hasExistingData()) {
                showDataContinuationDialog();
                return;
            }

            // 3) No existing data, proceed with fresh start
            startDataCollection(true); // true = reset data arrays
        }
    }

    private boolean hasExistingData() {
        return dataSize > 0 && (!dataPointsavgd.isEmpty() || !xaxis.isEmpty());
    }

    private void showDataContinuationDialog() {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("Existing Data Found")
                .setMessage("You have " + dataSize + " data points from a previous session.\n\nWhat would you like to do?")
                .setIcon(android.R.drawable.ic_dialog_info)
                .setPositiveButton("Continue Collection", (dialog, which) -> {
                    Log.i(TAG, "User chose to continue from existing data");
                    startDataCollection(false); // false = don't reset data arrays
                })
                .setNegativeButton("Start Fresh", (dialog, which) -> {
                    Log.i(TAG, "User chose to start fresh collection");
                    startDataCollection(true); // true = reset data arrays
                })
                .setNeutralButton("Cancel", (dialog, which) -> {
                    Log.i(TAG, "User cancelled data collection start");
                    dialog.dismiss();
                })
                .setCancelable(true)
                .show();
    }

    private void startDataCollection(boolean resetData) {
        synchronized (connectionLock) {
            // 1) Reset data if requested
            if (resetData) {
                resetDataArrays();
                Log.i(TAG, "Starting fresh data collection");
            } else {
                Log.i(TAG, "Continuing data collection from sample #" + dataSize + " (time: " + String.format("%.2f", time) + "s)");
            }

            // 2) Check runtime BLUETOOTH_CONNECT permission (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                        REQUEST_BLUETOOTH_PERMISSION);
                return;
            }

            // 3) Check if we already have a valid connection
            if (hasValidConnection()) {
                // We have a valid connection, just start data collection
                Log.i(TAG, "Using existing connection for data collection");
                started = true;
                sendTimer(true);

                // Update UI for data collection mode
                startButton.setEnabled(false);
                stopButton.setEnabled(true);
                connectButton.setEnabled(false);
                settingsButton.setEnabled(true);
                saveButton.setEnabled(false);
                showButton.setEnabled(false);

                // Log button states for debugging
                logButtonStates("data collection started");
                return;
            }

            // 4) Need to establish new connection
            // Prevent multiple connection attempts
            if (isConnecting) {
                Toast.makeText(this, "Connection already in progress...", Toast.LENGTH_SHORT).show();
                return;
            }

            // 5) Set connecting state and spawn connect thread
            isConnecting = true;
            connectionEstablished = false;

            if (myDevice != null) {
                new ConnectThread(myDevice).start();
            } else {
                // No device selected, try to find one
                isConnecting = false; // Reset since we're going to searchForDevice
                initializeBluetooth();
            }

            // 6) UI state will be enabled in ConnectThread on success.
            //    For now disable Start to prevent double-tap:
            startButton.setEnabled(false);
            stopButton.setEnabled(false);
        }
    }

    public void onClickStop(View v) {
        Log.i(TAG, "Stop button clicked - stopping data collection only");

        synchronized (connectionLock) {
            // 1) Stop polling and reset data collection state only
            sendTimer(false);
            started = false;
            // DON'T reset connectionEstablished or isConnecting - keep Bluetooth connected
            isReconnecting = false;
        }

        // 2) DON'T close the socket - keep Bluetooth connection alive
        // This prevents the disconnection/reconnection cycle

        // 3) Update UI for stopped state but keep connection
        stopButton.setEnabled(false);
        startButton.setEnabled(true);
        connectButton.setEnabled(true);
        settingsButton.setEnabled(true);

        // Update save/show buttons based on data availability
        updateDataDependentButtons();

        // Log button states for debugging
        logButtonStates("after stop (connection maintained)");

        Toast.makeText(this, "Data collection stopped", Toast.LENGTH_SHORT).show();
        Log.i(TAG, "Data collection stopped - Bluetooth connection maintained");
    }

    public void onClickConnect(View v) {
        Log.i(TAG, "Connect button clicked");

        synchronized (connectionLock) {
            // Prevent multiple connection attempts
            if (isConnecting) {
                Log.w(TAG, "Connection already in progress");
                Toast.makeText(this, "Connection already in progress...", Toast.LENGTH_SHORT).show();
                return;
            }

            // manual reconnect
            Log.i(TAG, "Starting manual reconnection");
            shutdownScheduler();
            connectionEstablished = false;
            isReconnecting = false;

            if (mySocket != null) {
                try {
                    mySocket.close();
                    Log.d(TAG, "Closed existing socket for reconnection");
                } catch (IOException ignored) {
                }
                mySocket = null;
            }
            is = null;
            os = null;
        }

        Toast.makeText(this, "Reconnecting...", Toast.LENGTH_SHORT).show();
        initializeBluetooth();
    }
    public void onClickShow(View v) {
        Log.i(TAG, "Show button clicked");
        try {
            if (dataPointsavgd.isEmpty()) {
                // No new data, just open ResultActivity to show history
                Intent intent = new Intent(this, ResultActivity.class);
                intent.putExtra("user_email", userEmail);
                startActivity(intent);
                return;
            }

            // Calculate max values
            double max = findMaxbyAvg(dataPointsavgd);
            double max0 = findMaxbyAvg(dataPoints0);
            double max1 = findMaxbyAvg(dataPoints1);
            double max2 = findMaxbyAvg(dataPoints2);

            Log.i(TAG, "Calculated max values: port0=" + max0 + ", port1=" + max1 + ", port2=" + max2);

            // SAVE TO DATABASE FIRST
            
            if (currentExperimentId == -1) {
                // Create new experiment
                long tsToSave = (sampleTimestamp > 0) ? sampleTimestamp : System.currentTimeMillis();
                currentExperimentId = dbHelper.createBiomarkerExperiment(
                        userEmail, "Bluetooth", samplesPerSecond,
                        movingAvgValue, durationOfExp, max1, dataSize, tsToSave);
                dbHelper.addBiomarkerDataBatch(currentExperimentId, xaxis,
                        dataPoints0, dataPoints1, dataPoints2, dataPointsavgd);
                Log.i(TAG, "New experiment saved with ID: " + currentExperimentId);
            } else {
                // Update existing experiment
                dbHelper.updateBiomarkerExperimentMaxValue(currentExperimentId, max1);  // ✅ Also change this
                Log.i(TAG, "Updated experiment " + currentExperimentId + " with max value: " + max1);
            }


            // Then open ResultActivity
            Intent resultActivity = new Intent(this, ResultActivity.class);
            resultActivity.putExtra("max_value_port0", max0);
            resultActivity.putExtra("max_value_port1", max1);
            resultActivity.putExtra("max_value_port2", max2);
            resultActivity.putExtra("user_email", userEmail);
            startActivity(resultActivity);

            Log.i(TAG, "Started ResultActivity with max values");
        } catch (Exception e) {
            Log.e(TAG, "Error showing results", e);
            Toast.makeText(this, "Error showing results: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }



//    public void onClickShow(View v) {
//        Log.i(TAG, "Show button clicked");
//
//        // Check if data collection is currently active
//        if (started) {
//            Log.w(TAG, "Show requested during active data collection");
//            showShowBlockedDialog();
//            return;
//        }
//
//        // Check if button is actually enabled
////        if (!showButton.isEnabled()) {
////            Log.w(TAG, "Show button clicked but is disabled");
////            Toast.makeText(this, "Show not available - no data collected yet", Toast.LENGTH_SHORT).show();
////            return;
////        }
//
////        if (dataPointsavgd.isEmpty()) {
////            Log.w(TAG, "No data to show - dataPointsavgd is empty");
////            Toast.makeText(this, "No data to show", Toast.LENGTH_SHORT).show();
////            return;
////        }
//
//        try {
//            if (!dataPointsavgd.isEmpty()) {
//              ;
//                Log.i(TAG, "Calculated max value: " + max);
//
//                // Calculate max values for port 1 and port 2
//                double max0= findMaxbyAvg(dataPoints0);
//                double max1 = findMaxbyAvg(dataPoints1);
//                double max2 = findMaxbyAvg(dataPoints2);
//                Log.i(TAG, "Calculated max values: port1=" + max1 + ", port2=" + max2);
//
//                // If we have an experiment ID, update its max value
//                if (currentExperimentId != -1) {
//                    dbHelper.updateBiomarkerExperimentMaxValue(currentExperimentId, max);
//                    Log.i(TAG, "Updated experiment " + currentExperimentId + " with max value: " + max);
//                }
//            }
//
//            Intent resultActivity = new Intent(this, ResultActivity.class);
//            // Add port-specific max values
//            resultActivity.putExtra("max_value_port0", findMaxbyAvg(dataPoints0));
//            resultActivity.putExtra("max_value_port1", findMaxbyAvg(dataPoints1));
//            resultActivity.putExtra("max_value_port2", findMaxbyAvg(dataPoints2));
//            resultActivity.putExtra("user_email", userEmail);
//            startActivity(resultActivity);
//            Log.i(TAG, "Started ResultActivity with max_value: " + max);
//
//        } catch (Exception e) {
//            Log.e(TAG, "Error showing results", e);
//            Toast.makeText(this, "Error showing results: " + e.getMessage(), Toast.LENGTH_SHORT).show();
//        }
//    }

    private void showShowBlockedDialog() {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("Show Results Unavailable")
                .setMessage("Results cannot be shown while data collection is active.\n\nPlease stop data collection first, then view results.")
                .setIcon(android.R.drawable.ic_dialog_info)
                .setPositiveButton("Stop & Show Results", (dialog, which) -> {
                    Log.i(TAG, "User chose to stop data collection and show results");

                    // Stop data collection
                    onClickStop(null);

                    // Wait a moment for stop to complete, then show results
                    new android.os.Handler().postDelayed(() -> {
                        // Call show again now that collection is stopped
                        onClickShow(null);
                    }, 500); // 500ms delay to ensure stop completes
                })
                .setNegativeButton("Continue Collection", (dialog, which) -> {
                    Log.i(TAG, "User chose to continue data collection");
                    dialog.dismiss();
                })
                .setCancelable(true)
                .show();
    }

    public void onClickSetting(View v) {
        Log.i(TAG, "Settings button clicked");

        // Check if button is actually enabled
        if (!settingsButton.isEnabled()) {
            Log.w(TAG, "Settings button clicked but is disabled");
            Toast.makeText(this, "Settings not available during data collection", Toast.LENGTH_SHORT).show();
            return;
        }

        // Check if data collection is currently active
        if (started) {
            Log.w(TAG, "Settings requested during active data collection");
            showSettingsBlockedDialog();
            return;
        }

        try {
            Intent settingsIntent = new Intent(OutputBluetooth.this, SettingsActivity.class);
            settingsIntent.putExtra("sps", samplesPerSecond);
            settingsIntent.putExtra("avgpoints", movingAvgValue);
            settingsIntent.putExtra("duration", durationOfExp);
            settingsIntent.putExtra("analog_port", analogPort);
            startActivityForResult(settingsIntent, 1);
            Log.i(TAG, "Settings activity started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error starting settings activity", e);
            Toast.makeText(this, "Error opening settings: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showSettingsBlockedDialog() {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("Settings Unavailable")
                .setMessage("Settings cannot be changed while data collection is active.\n\nPlease stop data collection first, then change settings.")
                .setIcon(android.R.drawable.ic_dialog_info)
                .setPositiveButton("Stop & Open Settings", (dialog, which) -> {
                    Log.i(TAG, "User chose to stop data collection and open settings");

                    // Stop data collection
                    onClickStop(null);

                    // Wait a moment for stop to complete, then open settings
                    new android.os.Handler().postDelayed(() -> {
                        try {
                            Intent settingsIntent = new Intent(OutputBluetooth.this, SettingsActivity.class);
                            settingsIntent.putExtra("sps", samplesPerSecond);
                            settingsIntent.putExtra("avgpoints", movingAvgValue);
                            settingsIntent.putExtra("duration", durationOfExp);
                            settingsIntent.putExtra("analog_port", analogPort);
                            startActivityForResult(settingsIntent, 1);
                            Log.i(TAG, "Settings activity started after stopping data collection");
                        } catch (Exception e) {
                            Log.e(TAG, "Error starting settings activity after stop", e);
                            Toast.makeText(OutputBluetooth.this, "Error opening settings: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }, 500); // 500ms delay to ensure stop completes
                })
                .setNegativeButton("Continue Collection", (dialog, which) -> {
                    Log.i(TAG, "User chose to continue data collection");
                    dialog.dismiss();
                })
                .setCancelable(true)
                .show();
    }

    public void onClickSave(View v) {
        Log.i(TAG, "Save button clicked");

        // Check if data collection is currently active
        if (started) {
            Log.w(TAG, "Save requested during active data collection");
            showSaveBlockedDialog();
            return;
        }

        // Check if button is actually enabled
        if (!saveButton.isEnabled()) {
            Log.w(TAG, "Save button clicked but is disabled");
            Toast.makeText(this, "Save not available - no data collected yet", Toast.LENGTH_SHORT).show();
            return;
        }

        if (dataSize == 0) {
            Log.w(TAG, "No data to save - dataSize is 0");
            Toast.makeText(this, "No data to save", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.i(TAG, "Saving " + dataSize + " data points");

        // Check permissions based on Android version
        if (needsStoragePermission()) {
            Log.w(TAG, "Storage permission not granted, requesting...");
            requestStoragePermission();
            return;
        }

        try {
            // Save to file and get file information
            SaveResult fileResult = saveToFileWithResult();

            // Save to database
            saveToDatabaseHelper();

            // Show detailed success dialog with file path and options
            showSaveSuccessDialog(fileResult);

            Log.i(TAG, "Save operation completed successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error during save operation", e);
            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // Helper class to store save result information
    private static class SaveResult {
        final File file;
        final String displayPath;
        final String storageType;
        final boolean canOpenFolder;

        SaveResult(File file, String displayPath, String storageType, boolean canOpenFolder) {
            this.file = file;
            this.displayPath = displayPath;
            this.storageType = storageType;
            this.canOpenFolder = canOpenFolder;
        }
    }

    // Enhanced save method that returns detailed result information
    private SaveResult saveToFileWithResult() throws IOException {
        SimpleDateFormat sdf = new SimpleDateFormat("MM_dd__HH_mm", Locale.getDefault());
        String dateNtime = sdf.format(new Date());
        String fileName = "BL_" + dateNtime + ".csv";
        String headers = "Data point,Time(sec),Port0(V),Port1(V),Port2(V)";

        try {
            SaveResult result = null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ - Use MediaStore for public Documents directory
                result = saveToMediaStoreWithResult(fileName, headers);
            } else {
                // Android 9 and below - Use traditional external storage
                result = saveToExternalStorageWithResult(fileName, headers);
            }

            if (result != null && result.file != null) {
                Log.i(TAG, "File saved successfully: " + result.file.getPath());
                return result;
            } else {
                throw new IOException("Failed to create file location");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error saving file: " + e.getMessage(), e);

            // Fallback: try saving to app-specific external storage
            try {
                Log.i(TAG, "Attempting fallback save to app-specific storage...");
                SaveResult fallbackResult = saveToAppSpecificStorageWithResult(fileName, headers);
                if (fallbackResult != null && fallbackResult.file != null) {
                    Log.i(TAG, "Fallback save successful: " + fallbackResult.file.getPath());
                    return fallbackResult;
                } else {
                    throw new IOException("Fallback save also failed");
                }
            } catch (Exception fallbackError) {
                Log.e(TAG, "Fallback save failed: " + fallbackError.getMessage(), fallbackError);
                throw new IOException("All save attempts failed: " + e.getMessage());
            }
        }
    }

    private SaveResult saveToMediaStoreWithResult(String fileName, String headers) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use MediaStore for Android 10+
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOCUMENTS + "/BluetoothData");

            android.net.Uri uri = getContentResolver().insert(
                    android.provider.MediaStore.Files.getContentUri("external"), values);

            if (uri != null) {
                try (java.io.OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                    writeDataToStream(outputStream, headers);

                    // Create result with MediaStore information
                    File virtualFile = new File(android.os.Environment.DIRECTORY_DOCUMENTS + "/BluetoothData/" + fileName);
                    String displayPath = "Documents/BluetoothData/" + fileName;
                    return new SaveResult(virtualFile, displayPath, "Public Documents", true);
                }
            } else {
                throw new IOException("Failed to create MediaStore entry");
            }
        }
        return null;
    }

    private SaveResult saveToExternalStorageWithResult(String fileName, String headers) throws IOException {
        // Traditional external storage for Android 9 and below
        File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        File bluetoothDir = new File(documentsDir, "BluetoothData");

        if (!bluetoothDir.exists() && !bluetoothDir.mkdirs()) {
            throw new IOException("Failed to create directory: " + bluetoothDir.getPath());
        }

        File fileLocation = new File(bluetoothDir, fileName);

        try (FileOutputStream dataOutput = new FileOutputStream(fileLocation)) {
            writeDataToStream(dataOutput, headers);
        }

        String displayPath = fileLocation.getAbsolutePath();
        return new SaveResult(fileLocation, displayPath, "External Storage", true);
    }

    private SaveResult saveToAppSpecificStorageWithResult(String fileName, String headers) throws IOException {
        // App-specific external storage (always available, no permissions needed)
        File appExternalDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (appExternalDir == null) {
            appExternalDir = getExternalFilesDir(null);
        }

        if (appExternalDir == null) {
            throw new IOException("External storage not available");
        }

        File bluetoothDir = new File(appExternalDir, "BluetoothData");
        if (!bluetoothDir.exists() && !bluetoothDir.mkdirs()) {
            throw new IOException("Failed to create app-specific directory");
        }

        File fileLocation = new File(bluetoothDir, fileName);

        try (FileOutputStream dataOutput = new FileOutputStream(fileLocation)) {
            writeDataToStream(dataOutput, headers);
        }

        String displayPath = fileLocation.getAbsolutePath();
        return new SaveResult(fileLocation, displayPath, "App Storage", true);
    }

    private void showSaveSuccessDialog(SaveResult result) {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);

        String message = "Data saved successfully!\n\n" +
                "📁 Location: " + result.storageType + "\n" +
                "📄 File: " + result.file.getName() + "\n" +
                "📍 Path: " + result.displayPath + "\n\n" +
                "💾 " + dataSize + " data points saved";

        builder.setTitle("Save Successful")
                .setMessage(message)
                .setIcon(android.R.drawable.ic_dialog_info);

        // Add "Open Folder" button if possible
        if (result.canOpenFolder) {
            builder.setPositiveButton("Open Folder", (dialog, which) -> {
                openFileLocation(result);
            });
        }

        // Add "Share File" button
        builder.setNeutralButton("Share File", (dialog, which) -> {
            shareFile(result.file);
        });

        // Add "OK" button
        builder.setNegativeButton("OK", (dialog, which) -> {
            dialog.dismiss();
        });

        builder.setCancelable(true)
                .show();
    }

    private void openFileLocation(SaveResult result) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // For Android 10+, try to open the Documents folder
                try {
                    // Try to open the specific folder
                    intent.setDataAndType(android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments%2FBluetoothData"),
                            "resource/folder");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "Failed to open specific folder, trying general approach", e);
                }

                // Fallback: Open Documents folder
                intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments"),
                        "resource/folder");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);

            } else {
                // For older Android versions, try to open file manager to the folder
                File parentDir = result.file.getParentFile();
                if (parentDir != null && parentDir.exists()) {
                    intent.setDataAndType(android.net.Uri.fromFile(parentDir), "resource/folder");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } else {
                    throw new Exception("Parent directory not accessible");
                }
            }

        } catch (Exception e) {
            Log.w(TAG, "Failed to open folder, trying alternative methods", e);

            // Alternative: Try to open a file manager app
            try {
                Intent fileManagerIntent = new Intent(Intent.ACTION_VIEW);
                fileManagerIntent.setType("resource/folder");
                fileManagerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                if (fileManagerIntent.resolveActivity(getPackageManager()) != null) {
                    startActivity(fileManagerIntent);
                    Toast.makeText(this, "File saved to: " + result.displayPath, Toast.LENGTH_LONG).show();
                } else {
                    throw new Exception("No file manager available");
                }

            } catch (Exception e2) {
                Log.w(TAG, "No file manager available", e2);

                // Final fallback: Show path in a copyable dialog
                showPathCopyDialog(result);
            }
        }
    }

    private void showPathCopyDialog(SaveResult result) {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);

        // Create a text view with selectable text
        android.widget.TextView textView = new android.widget.TextView(this);
        textView.setText(result.displayPath);
        textView.setTextIsSelectable(true);
        textView.setPadding(50, 30, 50, 30);
        textView.setTextSize(14);

        builder.setTitle("File Location")
                .setMessage("File saved successfully! You can copy the path below:")
                .setView(textView)
                .setPositiveButton("Copy Path", (dialog, which) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("File Path", result.displayPath);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "Path copied to clipboard", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("OK", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void shareFile(File file) {
        try {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/csv");

            // Use FileProvider for Android 7+ compatibility
            android.net.Uri fileUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                fileUri = androidx.core.content.FileProvider.getUriForFile(
                        this,
                        getPackageName() + ".fileprovider",
                        file
                );
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                fileUri = android.net.Uri.fromFile(file);
            }

            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Bluetooth Data Export");
            shareIntent.putExtra(Intent.EXTRA_TEXT, "Biomarker data collected via Bluetooth (" + dataSize + " samples)");

            Intent chooser = Intent.createChooser(shareIntent, "Share data file");
            if (shareIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(chooser);
            } else {
                Toast.makeText(this, "No apps available to share the file", Toast.LENGTH_SHORT).show();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error sharing file", e);
            Toast.makeText(this, "Error sharing file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // Keep the original saveToFile method for backward compatibility (now calls the enhanced version)
    private void saveToFile() throws IOException {
        SaveResult result = saveToFileWithResult();
        if (result == null || result.file == null) {
            throw new IOException("Save operation failed");
        }
    }

    private boolean needsStoragePermission() {
        // For Android 10 and above, we don't need WRITE_EXTERNAL_STORAGE for app-specific directories
        // For Android 6-9, we still need the permission for external storage
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ uses scoped storage, no permission needed for app-specific directories
            return false;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-9 needs WRITE_EXTERNAL_STORAGE permission
            return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED;
        }
        // Below Android 6, no runtime permissions needed
        return false;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_PERMISSION);
        }
    }

    private void showSaveBlockedDialog() {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("Save Unavailable")
                .setMessage("Data cannot be saved while data collection is active.\n\nPlease stop data collection first, then save the data.")
                .setIcon(android.R.drawable.ic_dialog_info)
                .setPositiveButton("Stop & Save", (dialog, which) -> {
                    Log.i(TAG, "User chose to stop data collection and save");

                    // Stop data collection
                    onClickStop(null);

                    // Wait a moment for stop to complete, then save
                    new android.os.Handler().postDelayed(() -> {
                        // Call save again now that collection is stopped
                        onClickSave(null);
                    }, 500); // 500ms delay to ensure stop completes
                })
                .setNegativeButton("Continue Collection", (dialog, which) -> {
                    Log.i(TAG, "User chose to continue data collection");
                    dialog.dismiss();
                })
                .setCancelable(true)
                .show();
    }

    // Extract the file saving logic to a separate method (keep existing code)
    private void saveToFileOld() {
        SimpleDateFormat sdf = new SimpleDateFormat("MM_dd__HH_mm", Locale.getDefault());
        String dateNtime = sdf.format(new Date());
        String fileName = "BL_" + dateNtime + ".csv";
        String headers = "Data point,Time(sec),Port0(V),Port1(V),Port2(V)";

        try {
            File fileLocation = null;
            FileOutputStream dataOutput = null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ - Use MediaStore for public Documents directory
                fileLocation = saveToMediaStore(fileName, headers);
            } else {
                // Android 9 and below - Use traditional external storage
                fileLocation = saveToExternalStorage(fileName, headers);
            }

            if (fileLocation != null) {
                Log.i(TAG, "File saved successfully: " + fileLocation.getPath());
            } else {
                throw new IOException("Failed to create file location");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error saving file: " + e.getMessage(), e);

            // Fallback: try saving to app-specific external storage
            try {
                Log.i(TAG, "Attempting fallback save to app-specific storage...");
                File fallbackLocation = saveToAppSpecificStorage(fileName, headers);
                if (fallbackLocation != null) {
                    Log.i(TAG, "Fallback save successful: " + fallbackLocation.getPath());
                    Toast.makeText(this, "File saved to app folder: " + fallbackLocation.getName(), Toast.LENGTH_LONG).show();
                } else {
                    throw new IOException("Fallback save also failed");
                }
            } catch (Exception fallbackError) {
                Log.e(TAG, "Fallback save failed: " + fallbackError.getMessage(), fallbackError);
                Toast.makeText(this, "Failed to save file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private File saveToMediaStore(String fileName, String headers) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use MediaStore for Android 10+
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOCUMENTS + "/BluetoothData");

            android.net.Uri uri = getContentResolver().insert(
                    android.provider.MediaStore.Files.getContentUri("external"), values);

            if (uri != null) {
                try (java.io.OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                    writeDataToStream(outputStream, headers);

                    // Return a File object for logging (path may not be accessible)
                    return new File(android.os.Environment.DIRECTORY_DOCUMENTS + "/BluetoothData/" + fileName);
                }
            } else {
                throw new IOException("Failed to create MediaStore entry");
            }
        }
        return null;
    }

    private File saveToExternalStorage(String fileName, String headers) throws IOException {
        // Traditional external storage for Android 9 and below
        File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        File bluetoothDir = new File(documentsDir, "BluetoothData");

        if (!bluetoothDir.exists() && !bluetoothDir.mkdirs()) {
            throw new IOException("Failed to create directory: " + bluetoothDir.getPath());
        }

        File fileLocation = new File(bluetoothDir, fileName);

        try (FileOutputStream dataOutput = new FileOutputStream(fileLocation)) {
            writeDataToStream(dataOutput, headers);
        }

        return fileLocation;
    }

    private File saveToAppSpecificStorage(String fileName, String headers) throws IOException {
        // App-specific external storage (always available, no permissions needed)
        File appExternalDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (appExternalDir == null) {
            appExternalDir = getExternalFilesDir(null);
        }

        if (appExternalDir == null) {
            throw new IOException("External storage not available");
        }

        File bluetoothDir = new File(appExternalDir, "BluetoothData");
        if (!bluetoothDir.exists() && !bluetoothDir.mkdirs()) {
            throw new IOException("Failed to create app-specific directory");
        }

        File fileLocation = new File(bluetoothDir, fileName);

        try (FileOutputStream dataOutput = new FileOutputStream(fileLocation)) {
            writeDataToStream(dataOutput, headers);
        }

        return fileLocation;
    }

    private void writeDataToStream(java.io.OutputStream outputStream, String headers) throws IOException {
        // Calculate max values for each port
        double max1 = findMaxbyAvg(dataPoints1);
        double max2 = findMaxbyAvg(dataPoints2);

        outputStream.write(("Sampling rate= " + samplesPerSecond + " samples/sec\n").getBytes());
        outputStream.write(("Moving Avg value= " + movingAvgValue + " points\n").getBytes());
        outputStream.write(("Data size= " + dataSize + "\n").getBytes());
        outputStream.write(("Max value (avg)= " + max + "\n").getBytes());
        outputStream.write(("Max value port1= " + max1 + "\n").getBytes());
        outputStream.write(("Max value port2= " + max2 + "\n\n").getBytes());
        outputStream.write(headers.getBytes());

        int maxIndex = Math.min(dataSize,
                Math.min(dataPoints0.size(),
                        Math.min(dataPoints1.size(), dataPoints2.size())));

        for (int i = 0; i < maxIndex; i++) {
            outputStream.write(("\n" + (i + 1) + "," +
                    xaxis.get(i) + "," +
                    dataPoints0.get(i) + "," +
                    dataPoints1.get(i) + "," +
                    dataPoints2.get(i)).getBytes());
        }

        outputStream.flush();
    }

    // Add new method to save data to the database
    private void saveToDatabaseHelper() {
        try {
            long tsToSave;
            if (sampleTimestamp > 0) {
                tsToSave = sampleTimestamp;
            } else {
                tsToSave = System.currentTimeMillis();
            }
            // Create a new experiment entry
            currentExperimentId = dbHelper.createBiomarkerExperiment(
                    userEmail,
                    "Bluetooth",
                    samplesPerSecond,
                    movingAvgValue,
                    durationOfExp,
                    max,
                    dataSize,
                    tsToSave
            );

            // Add all data points
            dbHelper.addBiomarkerDataBatch(
                    currentExperimentId,
                    xaxis,
                    dataPoints0,
                    dataPoints1,
                    dataPoints2,
                    dataPointsavgd
            );

            // Remove individual success toast - consolidated message shown in onClickSave
            Log.i(TAG, "Data saved to database with experiment ID: " + currentExperimentId);

        } catch (Exception e) {
            Toast.makeText(this, "Database save error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Database save error: " + e.getMessage(), e);
        }
    }

    //————————————————————————————
    // Permissions & Initialization
    //————————————————————————————

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ArrayList<String> perms = new ArrayList<>();

            // Bluetooth permissions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                    != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
                            != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH);
                perms.add(Manifest.permission.BLUETOOTH_ADMIN);
            }

            // Location permission (needed for Bluetooth scanning)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }

            // Storage permission (only for Android 6-9, not needed for Android 10+)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }

            if (!perms.isEmpty()) {
                ActivityCompat.requestPermissions(this,
                        perms.toArray(new String[0]),
                        REQUEST_BLUETOOTH_PERMISSION);
            }
        }
    }

    private void initializeViews() {
        graphView = findViewById(R.id.graphViewBL);

        startButton = findViewById(R.id.buttonStartBL);
        stopButton = findViewById(R.id.buttonStopBL);
        saveButton = findViewById(R.id.buttonSaveBL);
        settingsButton = findViewById(R.id.buttonSettingBL);
        connectButton = findViewById(R.id.buttonConnectBL);
        showButton = findViewById(R.id.buttonShowBL);
        textViewAppend = findViewById(R.id.textAppendBL);

        // Set initial button states
        startButton.setEnabled(false);
        stopButton.setEnabled(false);
        settingsButton.setEnabled(true); // Always allow settings
        connectButton.setEnabled(false); // Will be enabled when Bluetooth is ready
        saveButton.setEnabled(false);
        showButton.setEnabled(true);
        showButton.setAlpha(1.0f);

        // Add long-press listener to start button for clearing data
        startButton.setOnLongClickListener(v -> {
            if (hasExistingData()) {
                showClearDataDialog();
                return true; // Consume the long click
            } else {
                Toast.makeText(this, "No data to clear", Toast.LENGTH_SHORT).show();
                return true;
            }
        });
    }

    private void showClearDataDialog() {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("Clear Data")
                .setMessage("Are you sure you want to clear all " + dataSize + " collected data points?\n\nThis action cannot be undone.")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton("Clear Data", (dialog, which) -> {
                    Log.i(TAG, "User chose to clear all data");
                    resetDataArrays();
                    Toast.makeText(this, "All data cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    Log.i(TAG, "User cancelled data clearing");
                    dialog.dismiss();
                })
                .setCancelable(true)
                .show();
    }

    private void loadSettings() {
        File settingFile = new File(this.getFilesDir(), "settings");
        if (settingFile.isFile()) {
            try (FileInputStream inputStream = openFileInput("settings")) {
                byte[] buf = new byte[inputStream.available()];
                inputStream.read(buf);
                StringTokenizer st = new StringTokenizer(new String(buf), "/");
                if (st.countTokens() >= 8) {
                    samplesPerSecond = Integer.parseInt(st.nextToken());
                    movingAvgValue = Integer.parseInt(st.nextToken());
                    durationOfExp = Double.parseDouble(st.nextToken());
                    analogPort[0] = st.nextToken().equals("T");
                    analogPort[1] = st.nextToken().equals("T");
                    analogPort[2] = st.nextToken().equals("T");
                    analogPort[3] = st.nextToken().equals("T");
                    // last token is movingAvgValue again if you like
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading settings: " + e.getMessage(), e);
                Toast.makeText(this, "Can't load file, using defaults", Toast.LENGTH_SHORT).show();
            }
        } else {
            saveSettings();
        }
    }

    private void saveSettings() {
        try (FileOutputStream outputStream = openFileOutput("settings", MODE_PRIVATE)) {
            String data = samplesPerSecond + "/" +
                    movingAvgValue + "/" +
                    durationOfExp + "/" +
                    (analogPort[0] ? "T" : "F") + "/" +
                    (analogPort[1] ? "T" : "F") + "/" +
                    (analogPort[2] ? "T" : "F") + "/" +
                    (analogPort[3] ? "T" : "F") + "/" +
                    movingAvgValue;
            outputStream.write(data.getBytes());
        } catch (Exception e) {
            Log.e(TAG, "Error saving settings: " + e.getMessage(), e);
            Toast.makeText(this, "Failed to save settings", Toast.LENGTH_SHORT).show();
        }
    }

    private void initializeBluetooth() {
        try {
            Log.i(TAG, "Initializing Bluetooth...");

            if (mBA == null) {
                Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_LONG).show();
                Log.e(TAG, "Bluetooth adapter is null - device doesn't support Bluetooth");
                return;
            }

            // Check if Bluetooth is enabled
            if (!mBA.isEnabled()) {
                Log.i(TAG, "Bluetooth is disabled, requesting to enable...");
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);

                // Check permission for Android 12+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED) {
                        Log.w(TAG, "BLUETOOTH_CONNECT permission not granted, requesting...");
                        ActivityCompat.requestPermissions(this,
                                new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                                REQUEST_BLUETOOTH_PERMISSION);
                        return;
                    }
                }

                startActivityForResult(enableBtIntent, 0);
                Toast.makeText(this, "Please enable Bluetooth to continue", Toast.LENGTH_LONG).show();
                return;
            }

            Log.i(TAG, "Bluetooth is enabled, searching for devices...");

            // Enable UI elements now that Bluetooth is ready
            connectButton.setEnabled(true);
            settingsButton.setEnabled(true);
            startButton.setEnabled(false); // Will be enabled after connection

            // Search for and connect to device
            searchForDevice();

        } catch (SecurityException e) {
            Log.e(TAG, "Security exception in initializeBluetooth", e);
            Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in initializeBluetooth", e);
            Toast.makeText(this, "Error initializing Bluetooth: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void searchForDevice() {
        try {
            // Check permissions first
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "BLUETOOTH_CONNECT permission not granted");
                    return;
                }
            }

            // Get all paired devices and show a selection dialog
            pairedDevices = mBA.getBondedDevices();
            if (pairedDevices == null || pairedDevices.isEmpty()) {
                Toast.makeText(this, "No paired Bluetooth devices found. Please pair your device first.", Toast.LENGTH_LONG).show();
                return;
            }

            Log.i(TAG, "Found " + pairedDevices.size() + " paired devices");

            // Create list of devices for selection dialog
            final ArrayList<BluetoothDevice> deviceList = new ArrayList<>(pairedDevices);
            final String[] deviceNames = new String[deviceList.size()];

            for (int i = 0; i < deviceList.size(); i++) {
                BluetoothDevice device = deviceList.get(i);
                String name = device.getName();
                if (name == null) name = "Unknown Device";
                deviceNames[i] = name + " (" + device.getAddress() + ")";
            }

            // Show device selection dialog
            runOnUiThread(() -> {
                new androidx.appcompat.app.AlertDialog.Builder(OutputBluetooth.this)
                        .setTitle("Select Bluetooth Device")
                        .setItems(deviceNames, (dialog, which) -> {
                            // Get selected device
                            myDevice = deviceList.get(which);

                            Toast.makeText(OutputBluetooth.this,
                                    "Connecting to: " + myDevice.getName(), Toast.LENGTH_SHORT).show();

                            // Set connection state
                            synchronized (connectionLock) {
                                if (isConnecting) {
                                    Log.w(TAG, "Connection already in progress");
                                    Toast.makeText(OutputBluetooth.this,
                                            "Connection already in progress...", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                isConnecting = true;
                                connectionEstablished = false;
                            }

                            // Start connection
                            ConnectThread ct = new ConnectThread(myDevice);
                            ct.start();
                        })
                        .setNegativeButton("Cancel", null)
                        .setCancelable(true)
                        .show();
            });

        } catch (SecurityException e) {
            Log.e(TAG, "Security exception in searchForDevice", e);
            Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in searchForDevice", e);
            Toast.makeText(this, "Error searching for devices: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    //————————————————————————————
    // Scheduled send/read loop
    //————————————————————————————

    private void sendTimer(boolean start) {
        long intervalMs = 1000L / samplesPerSecond;
        shutdownScheduler();  // always call first

        if (start) {
            // Only start if we have a valid connection
            if (!hasValidConnection()) {
                Log.w(TAG, "Cannot start timer - connection not established");
                return;
            }

            Log.i(TAG, "Starting data collection timer with interval: " + intervalMs + "ms");
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleWithFixedDelay(
                    this::pollOnce,
                    500, // Initial delay to ensure connection is stable
                    intervalMs,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    // Create a complete reconnection handler that addresses the timeout issues
    private void smartReconnect() {
        // Only proceed if not already reconnecting and cooldown period has passed
        long currentTime = System.currentTimeMillis();
        if (dataSize >= RECONNECT_THRESHOLD && !isReconnecting &&
                (currentTime - lastReconnectTime > RECONNECT_COOLDOWN)) {

            isReconnecting = true;
            lastReconnectTime = currentTime;

            Log.i(TAG, "Starting controlled reconnection at sample #" + dataSize);

            // Save current data
            saveCurrentProgress();

            // Create a completely independent thread for reconnection
            Thread reconnectThread = new Thread(() -> {
                try {
                    // Pause the scheduler but don't change started state
                    boolean wasStarted = started;
                    shutdownScheduler();

                    // Close existing connection
                    synchronized (OutputBluetooth.this) {
                        if (mySocket != null) {
                            try {
                                mySocket.close();
                            } catch (IOException e) {
                                Log.e(TAG, "Error closing socket", e);
                            }
                            mySocket = null;
                        }
                        is = null;
                        os = null;
                    }

                    // Wait a significant time for resources to be released
                    Thread.sleep(3000);

                    // Force Bluetooth reset - try disabling and re-enabling
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                ContextCompat.checkSelfPermission(OutputBluetooth.this,
                                        Manifest.permission.BLUETOOTH_CONNECT)
                                        == PackageManager.PERMISSION_GRANTED) {
                            mBA.disable();
                            Thread.sleep(2000);
                            mBA.enable();
                            Thread.sleep(5000);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Bluetooth toggle failed", e);
                    }

                    // Start a completely fresh connection on UI thread
                    runOnUiThread(() -> {
                        try {
                            // Show status
                            Toast.makeText(OutputBluetooth.this,
                                    "Creating new connection...", Toast.LENGTH_SHORT).show();

                            // Cancel any ongoing discovery
                            if (mBA != null) {
                                mBA.cancelDiscovery();
                            }

                            // Create connection on UI thread to avoid BlockedOnMainThread errors
                            // Use a separate class, not an anonymous inner class
                            FinalConnectThread finalConnect = new FinalConnectThread(myDevice, wasStarted);
                            finalConnect.start();
                        } catch (Exception e) {
                            Log.e(TAG, "Error in UI reconnect", e);
                            isReconnecting = false;
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error in reconnect thread", e);
                    isReconnecting = false;
                }
            });

            reconnectThread.setDaemon(true); // Allow JVM to exit if this thread is hanging
            reconnectThread.start();
        }
    }

    // New connection thread class with clear completion handling
    private class FinalConnectThread extends Thread {
        private final BluetoothDevice device;
        private final boolean restartCollection;
        private final UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

        FinalConnectThread(BluetoothDevice device, boolean restartCollection) {
            this.device = device;
            this.restartCollection = restartCollection;
        }

        @Override
        public void run() {
            try {
                // Cancel discovery
                mBA.cancelDiscovery();

                // Try standard connection
                mySocket = device.createRfcommSocketToServiceRecord(uuid);

                try {
                    mySocket.connect();
                } catch (IOException standardEx) {
                    Log.w(TAG, "Standard connect failed, trying fallback", standardEx);

                    // Close failed socket
                    try {
                        mySocket.close();
                    } catch (IOException ignored) {}

                    // Fallback via reflection
                    try {
                        Method fallback = device.getClass().getMethod("createRfcommSocket", int.class);
                        mySocket = (BluetoothSocket) fallback.invoke(device, 1);
                        mySocket.connect();
                    } catch (Exception fallbackEx) {
                        Log.e(TAG, "Fallback connect also failed", fallbackEx);
                        runOnUiThread(() ->
                                Toast.makeText(OutputBluetooth.this, "Connection failed", Toast.LENGTH_SHORT).show());

                        // IMPORTANT: Reset the reconnection flag
                        isReconnecting = false;
                        return;
                    }
                }

                // Initialize streams
                is = mySocket.getInputStream();
                os = mySocket.getOutputStream();

                // Send a command to reset the device and clear buffers
                os.write("R\r".getBytes());
                os.flush();

                // Clear input buffer
                while (is.available() > 0) {
                    is.skip(is.available());
                }

                // Update UI on success
                runOnUiThread(() -> {
                    Toast.makeText(OutputBluetooth.this, "Connection re-established", Toast.LENGTH_SHORT).show();

                    // Update buttons based on whether we're restarting collection or not
                    if (restartCollection) {
                        // Data collection will restart - keep settings enabled to show dialog
                        stopButton.setEnabled(true);
                        connectButton.setEnabled(false);
                        settingsButton.setEnabled(true); // Keep enabled to show dialog
                        saveButton.setEnabled(false);
                        showButton.setEnabled(false);
                        startButton.setEnabled(false);
                    } else {
                        // Just reconnected - allow settings since not collecting
                        startButton.setEnabled(true);
                        stopButton.setEnabled(false);
                        connectButton.setEnabled(true);
                        settingsButton.setEnabled(true);
                        saveButton.setEnabled(dataSize > 0);   // Only if there's data
                        showButton.setEnabled(dataSize > 0);   // Only if there's data
                    }

                    // Restart data collection if needed
                    if (restartCollection) {
                        started = true;
                        sendTimer(true);
                    }

                    // Log button states for debugging
                    logButtonStates("after connection success");

                    // Reset reconnection flags
                    OutputBluetooth.this.reconnecting = false;
                    isReconnecting = false;
                    errorCount = 0;

                    // Don't automatically start data collection - wait for user to press Start
                    Log.i(TAG, "Connection established. Ready for data collection.");
                });
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error in FinalConnectThread", e);

                // IMPORTANT: Reset the flag even on failure
                runOnUiThread(() -> {
                    Toast.makeText(OutputBluetooth.this,
                            "Connection error - please reconnect manually", Toast.LENGTH_LONG).show();
                    isReconnecting = false;
                });
            }
        }
    }

    // Modified pollOnce() method with transparent reconnection
    private void pollOnce() {
        // Check if we should still be running
        if (!started || !connectionEstablished) {
            Log.v(TAG, "Polling stopped - started: " + started + ", connected: " + connectionEstablished);
            return;
        }

        try {
            // Check socket and stream validity
            if (mySocket == null || !mySocket.isConnected() || os == null || is == null) {
                Log.w(TAG, "Socket or streams not ready - Socket: " + (mySocket != null) +
                        ", Connected: " + (mySocket != null && mySocket.isConnected()) +
                        ", OutputStream: " + (os != null) + ", InputStream: " + (is != null));
                throw new IOException("Socket or streams not ready");
            }

            // ---- WRITE command to MCU ----
            synchronized (OutputBluetooth.this) {
                try {
                    os.write("0\r".getBytes());
                    os.flush();
                    Log.v(TAG, "Command sent successfully");
                } catch (IOException writeEx) {
                    Log.e(TAG, "Write failed (broken pipe): " + writeEx.getMessage(), writeEx);
                    if (!isReconnecting && started) {
                        // Remove frequent reconnection toast - just log and reconnect silently
                        Log.w(TAG, "Connection lost during write, attempting reconnection");
                        reconnectBluetooth();
                    }
                    return;
                }
            }

            // ---- WAIT FOR DATA with timeout ----
            byte[] buffer = new byte[512];
            int totalRead = 0;
            long startTime = System.currentTimeMillis();
            long timeout = startTime + 1000; // Increased timeout to 1000ms

            while (System.currentTimeMillis() < timeout && totalRead < buffer.length) {
                synchronized (OutputBluetooth.this) {
                    if (is != null && is.available() > 0) {
                        int readBytes = is.read(buffer, totalRead, buffer.length - totalRead);
                        if (readBytes > 0) {
                            totalRead += readBytes;
                            Log.v(TAG, "Read " + readBytes + " bytes, total: " + totalRead);
                            // Check for packet terminator
                            if (buffer[totalRead - 1] == 'p') {
                                Log.v(TAG, "Found packet terminator");
                                break;
                            }
                        }
                    }
                }
                Thread.sleep(10); // avoid CPU spinning
            }

            // ---- HANDLE THE DATA ----
            if (totalRead > 0) {
                errorCount = 0;
                String pkt = new String(buffer, 0, totalRead).trim();


                Log.v(TAG, "Sample #" + dataSize + " received (" + totalRead + " bytes): " + pkt);

                Message msg = myHandler.obtainMessage();
                Bundle b = new Bundle();
                b.putString("data", pkt);
                msg.setData(b);
                myHandler.sendMessage(msg);
            } else {
                errorCount++;
                long readTime = System.currentTimeMillis() - startTime;
                Log.w(TAG, "Poll missed: no data read in " + readTime + "ms (consecutive errors=" + errorCount + ")");

                if (errorCount >= MAX_ERRORS && !isReconnecting && started) {
                    // Remove frequent error toast - just log and reconnect silently
                    Log.w(TAG, "Multiple unexpected errors, attempting reconnection");
                    reconnectBluetooth();
                    errorCount = 0;
                }
            }

            // ✅ Check if we hit batch limit
            checkBatchBoundary();

            // only redraw every 3 samples
            if (graphCount == 3) {
                graphCount = 0;
                drawGraph();
            }

            // Update Save and Show button states when data is available
            runOnUiThread(this::updateDataDependentButtons);

        } catch (IOException ioEx) {
            Log.e(TAG, "IO error in pollOnce(): " + ioEx.getMessage(), ioEx);
            if (!isReconnecting && started) {
                // Remove frequent IO error toast - just log and reconnect silently
                Log.w(TAG, "IO error during polling, attempting reconnection");
                reconnectBluetooth();
            }
        } catch (InterruptedException intEx) {
            Log.w(TAG, "pollOnce() interrupted", intEx);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in pollOnce(): " + e.getMessage(), e);
            errorCount++;
            if (errorCount >= MAX_ERRORS && !isReconnecting && started) {
                // Remove frequent error toast - just log and reconnect silently
                Log.w(TAG, "Multiple unexpected errors, attempting reconnection");
                reconnectBluetooth();
                errorCount = 0;
            }
        }
    }

    // Helper method to save current progress
    private void saveCurrentProgress() {
        // Only save if we have meaningful data
        if (dataSize < 10) return;

        // Create a backup file with current timestamp
        SimpleDateFormat sdf = new SimpleDateFormat("MM_dd_HH_mm_ss", Locale.getDefault());
        String backupTime = sdf.format(new Date());
        File backupFile = new File(
                getFilesDir(),
                "BL_backup_" + backupTime + ".csv");

        try (FileOutputStream backupOutput = new FileOutputStream(backupFile)) {
            backupOutput.write(("Sampling rate= " + samplesPerSecond + " samples/sec\n").getBytes());
            backupOutput.write(("Moving Avg value= " + movingAvgValue + " points\n").getBytes());
            backupOutput.write(("Data size= " + dataSize + "\n\n").getBytes());
            backupOutput.write(("Data point,Time(sec),Port0(V),Port1(V),Port2(V)\n").getBytes());

            int maxIndex = Math.min(dataSize,
                    Math.min(dataPoints0.size(),
                            Math.min(dataPoints1.size(), dataPoints2.size())));

            for (int i = 0; i < maxIndex; i++) {
                backupOutput.write(("\n" + (i + 1) + "," +
                        xaxis.get(i) + "," +
                        dataPoints0.get(i) + "," +
                        dataPoints1.get(i) + "," +
                        dataPoints2.get(i)).getBytes());
            }
            Log.i(TAG, "Backup saved: " + backupFile.getPath());
        } catch (Exception e) {
            Log.e(TAG, "Error saving backup: " + e.getMessage(), e);
        }
    }
    private void shutdownScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void reconnectBluetooth() {
        synchronized (connectionLock) {
            // Prevent multiple reconnection attempts
            if (isReconnecting || isConnecting) {
                Log.w(TAG, "Reconnection already in progress, skipping");
                return;
            }

            isReconnecting = true;
            connectionEstablished = false;
        }

        Log.i(TAG, "Starting Bluetooth reconnection...");
        shutdownScheduler();

        // Close existing connection in background thread
        new Thread(() -> {
            try {
                if (mySocket != null) {
                    mySocket.close();
                    mySocket = null;
                }
                is = null;
                os = null;

                // Wait before attempting reconnection
                Thread.sleep(2000);

                runOnUiThread(() -> {
                    synchronized (connectionLock) {
                        isReconnecting = false;
                        isConnecting = false;
                    }

                    // Only attempt reconnection if we're still supposed to be started
                    if (started) {
                        initializeBluetooth();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error in reconnectBluetooth", e);
                runOnUiThread(() -> {
                    synchronized (connectionLock) {
                        isReconnecting = false;
                        isConnecting = false;
                    }
                    Toast.makeText(OutputBluetooth.this,
                            "Reconnection failed. Please try manually.", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    //————————————————————————————
    // Packet buffering & parsing
    //————————————————————————————

    private void processBufferedData() {
        String bufferStr = dataBuffer.toString();

        // keep pulling complete packets   #…..p
        int startIdx, endIdx;
        while ((endIdx = bufferStr.indexOf('p')) >= 0) {
            startIdx = bufferStr.lastIndexOf('#', endIdx);
            if (startIdx < 0) startIdx = 0;

            String pkt = bufferStr.substring(startIdx, endIdx + 1);
            processBluetoothData(pkt);

            // trim processed part
            bufferStr = (endIdx + 1 < bufferStr.length())
                    ? bufferStr.substring(endIdx + 1)
                    : "";
        }
        dataBuffer = new StringBuilder(bufferStr);       // keep remainder

        if (dataBuffer.length() > 1024) {                // emergency drain
            Log.w(TAG, "Data buffer overflow, resetting");
            dataBuffer.setLength(0);
        }
    }

    private void processBluetoothData(String packet) {
        try {
            // strip markers
            if (packet.startsWith("#")) packet = packet.substring(1);
            if (packet.endsWith("p")) packet = packet.substring(0, packet.length() - 1);

            StringTokenizer tokens = new StringTokenizer(packet, ":");
            if (tokens.countTokens() != 3) return;

            // parse & mask off whatever prefix you used
            int raw0 = Integer.parseInt(tokens.nextToken()) % 100000;
            int raw1 = Integer.parseInt(tokens.nextToken()) % 100000;
            int raw2 = Integer.parseInt(tokens.nextToken()) % 100000;

            double val0 = raw0 * analogRef / (Math.pow(2, ADCbits) - 1);
            double val1 = raw1 * analogRef / (Math.pow(2, ADCbits) - 1);
            double val2 = raw2 * analogRef / (Math.pow(2, ADCbits) - 1);

            // update text
            dataSize++;
            time += 1.0 / samplesPerSecond;
            // Calculate cortisol from last 100 samples (or all if less than 100)
            double currentCortisol = calculateRollingAverageCortisol();

            textViewAppend.setText(String.format(Locale.getDefault(),
                    "Samples %d | %.2fs\n0: %.4f V\n1: %.4f V\n2: %.4f V\nCortisol: %.1f ng/mL",
                    dataSize, time, val0, val1, val2, currentCortisol));


            // collect for plotting
            xaxis.add(time);
            graphCount++;
            processPortData(0, val0);
            processPortData(1, val1);
            processPortData(2, val2);

            // average of all three
            double avg = (val0 + val1 + val2) / 3.0;
            dataPointsavgdUnclean.add(avg);
            dataPointsavgd.add(movingAvgValue == 0
                    ? avg
                    : MVFilter(dataPointsavgdUnclean.size(), movingAvgValue, dataPointsavgdUnclean));
            cleanGraph(dataPointsavgd);

            // only redraw every 3 samples
            if (graphCount == 3) {
                graphCount = 0;
                drawGraph();
            }

            // Update Save and Show button states when data is available
            runOnUiThread(this::updateDataDependentButtons);

        } catch (Exception e) {
            Log.e(TAG, "Error in processBluetoothData", e);
        }
    }

    private void processPortData(int portIndex, double value) {
        try {
            switch (portIndex) {
                case 0:
                    dataPoints0Unclean.add(value);
                    dataPoints0.add(movingAvgValue == 0
                            ? value
                            : MVFilter(dataPoints0Unclean.size(), movingAvgValue, dataPoints0Unclean));
                    break;
                case 1:
                    dataPoints1Unclean.add(value);
                    dataPoints1.add(movingAvgValue == 0
                            ? value
                            : MVFilter(dataPoints1Unclean.size(), movingAvgValue, dataPoints1Unclean));
                    break;
                case 2:
                    dataPoints2Unclean.add(value);
                    dataPoints2.add(movingAvgValue == 0
                            ? value
                            : MVFilter(dataPoints2Unclean.size(), movingAvgValue, dataPoints2Unclean));
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing port " + portIndex + ": " + e.getMessage(), e);
        }
    }
    private double calculateRollingAverageCortisol() {
        if (dataPoints1.isEmpty()) {
            return 0.0;
        }

        int totalSamples = dataPoints1.size();
        int samplesToAverage = Math.min(100, totalSamples);
        int startIndex = totalSamples - samplesToAverage;

        double sum = 0.0;
        for (int i = startIndex; i < totalSamples; i++) {
            sum += dataPoints1.get(i);
        }
        double avgVoltage = sum / samplesToAverage;
        double yValue = avgVoltage * 1000.0; // mV

        // Single range check + log
        if (yValue < 428.0 || yValue > 478.6686) {
            Log.d(TAG, "ROLLING INVALID totalSamples=" + totalSamples
                    + " windowSize=" + samplesToAverage
                    + " avgVoltage=" + avgVoltage
                    + " yValue=" + yValue);
            return 0.0;
        }

        double cortisol =
                Math.pow((yValue - 482.9265) / (-4.2579), 1.0 / 0.5553);

        Log.d(TAG, "ROLLING totalSamples=" + totalSamples
                + " windowSize=" + samplesToAverage
                + " avgVoltage=" + avgVoltage
                + " yValue=" + yValue
                + " cortisol=" + cortisol);

        return cortisol;
    }




    private void drawGraph() {
        int len = Math.min(dataPoints0.size(), xaxis.size());
        if (len == 0) return;

        // Build arrays once – reuse for all series that need them
        DataPoint[] p0  = new DataPoint[len];
        DataPoint[] p1  = new DataPoint[len];
        DataPoint[] p2  = new DataPoint[len];
        DataPoint[] pav = new DataPoint[len];
        for (int i = 0; i < len; i++) {
            double t = xaxis.get(i);
            p0[i]  = new DataPoint(t, dataPoints0.get(i));
            p1[i]  = new DataPoint(t, dataPoints1.get(i));
            p2[i]  = new DataPoint(t, dataPoints2.get(i));
            pav[i] = new DataPoint(t, dataPointsavgd.get(i));
        }

        graphView.removeAllSeries();

        if (analogPort[0]) {              // ‑‑ noisy 0
            LineGraphSeries<DataPoint> s = new LineGraphSeries<>(p0);
            s.setTitle("Noisy Port0");
            s.setColor(Color.LTGRAY);
            s.setThickness(1);
            graphView.addSeries(s);
        }
        if (analogPort[1]) {              // ‑‑ noisy 1
            LineGraphSeries<DataPoint> s = new LineGraphSeries<>(p1);
            s.setTitle("Noisy Port1");
            s.setColor(Color.LTGRAY);
            s.setThickness(1);
            graphView.addSeries(s);
        }
        if (analogPort[2]) {              // ‑‑ noisy 2
            LineGraphSeries<DataPoint> s = new LineGraphSeries<>(p2);
            s.setTitle("Noisy Port2");
            s.setColor(Color.LTGRAY);
            s.setThickness(1);
            graphView.addSeries(s);
        }

        if (analogPort[0]) {              // ‑‑ filtered 0
            LineGraphSeries<DataPoint> s = new LineGraphSeries<>(p0);
            s.setTitle("Port0");
            s.setColor(Color.BLUE);
            s.setThickness(2);
            graphView.addSeries(s);
        }
        if (analogPort[1]) {              // ‑‑ filtered 1
            LineGraphSeries<DataPoint> s = new LineGraphSeries<>(p1);
            s.setTitle("Port1");
            s.setColor(Color.MAGENTA);
            s.setThickness(2);
            graphView.addSeries(s);
        }
        if (analogPort[2]) {              // ‑‑ filtered 2
            LineGraphSeries<DataPoint> s = new LineGraphSeries<>(p2);
            s.setTitle("Port2");
            s.setColor(Color.RED);
            s.setThickness(2);
            graphView.addSeries(s);
        }
        if (analogPort[3]) {              // ‑‑ overall average
            LineGraphSeries<DataPoint> s = new LineGraphSeries<>(pav);
            s.setTitle("Filtered output");
            s.setColor(Color.BLACK);
            s.setThickness(3);
            graphView.addSeries(s);
        }

        // scroll window
        if (!


                xaxis.isEmpty()) {
            double last = xaxis.get(xaxis.size() - 1);
            if (last > durationOfExp) {
                graphView.getViewport().setMinX(last - durationOfExp);
                graphView.getViewport().setMaxX(last);
            }
        }
        graphView.getLegendRenderer().setVisible(true);
        graphView.getLegendRenderer().setAlign(LegendRenderer.LegendAlign.TOP);
    }

    private void resetGraph() {
        try {
            graphView.removeAllSeries();
            graphView.setTitle("Voltage (V) vs Time (s)");
            graphView.getViewport().setYAxisBoundsManual(false);
            graphView.getViewport().setXAxisBoundsManual(true);
            graphView.getViewport().setMinX(0);
            graphView.getViewport().setMaxX(durationOfExp);
            graphView.getViewport().setScrollable(true);
            graphView.getViewport().setScalable(true);
            graphView.getGridLabelRenderer().setNumVerticalLabels(13);
            graphView.getGridLabelRenderer().setNumHorizontalLabels(9);
        } catch (Exception e) {
            Log.e(TAG, "Error in resetGraph: " + e.getMessage(), e);
        }
    }

    private double MVFilter(int arrSize, int x, ArrayList<Double> arr) {
        if (arr == null || arr.isEmpty() || x <= 0) return 0.0;
        double sum = 0.0;
        int start = Math.max(0, arrSize - x), end = arrSize;
        for (int i = start; i < end; i++) sum += arr.get(i);
        int count = end - start;
        return count > 0 ? sum / count : 0.0;
    }

    public void cleanGraph(ArrayList<Double> arl) {
        if (arl == null || arl.size() < 3) return;
        int size = arl.size();
        for (int a = 1; a < size - 1; a++) {
            // Single outlier
            if ((arl.get(a) - arl.get(a - 1) > 0.01 && arl.get(a) - arl.get(a + 1) > 0.01) ||
                    (arl.get(a - 1) - arl.get(a) > 0.01 && arl.get(a + 1) - arl.get(a) > 0.01)) {
                arl.set(a, (arl.get(a - 1) + arl.get(a + 1)) / 2);
            }
            // Two-point outlier
            if (a < size - 2) {
                double avg2 = (arl.get(a) + arl.get(a + 1)) / 2;
                if ((avg2 - arl.get(a - 1) > 0.01 && avg2 - arl.get(a + 2) > 0.01) ||
                        (arl.get(a - 1) - avg2 > 0.01 && arl.get(a + 2) - avg2 > 0.01)) {
                    arl.set(a, (arl.get(a - 1) + arl.get(a + 2)) / 2);
                    arl.set(a + 1, (arl.get(a - 1) + arl.get(a + 2)) / 2);
                }
            }
            // Three-point outlier
            if (a < size - 3) {
                double avg3 = (arl.get(a) + arl.get(a + 1) + arl.get(a + 2)) / 3;
                if ((avg3 - arl.get(a - 1) > 0.01 && avg3 - arl.get(a + 3) > 0.01) ||
                        (arl.get(a - 1) - avg3 > 0.01 && arl.get(a + 3) - avg3 > 0.01)) {
                    arl.set(a, (arl.get(a - 1) + arl.get(a + 3)) / 2);
                    arl.set(a + 1, (arl.get(a - 1) + arl.get(a + 3)) / 2);
                    arl.set(a + 2, (arl.get(a - 1) + arl.get(a + 3)) / 2);
                }
            }
        }
    }

    private void resetDataArrays() {
        dataPoints0.clear();
        dataPoints1.clear();
        dataPoints2.clear();
        dataPointsavgd.clear();
        xaxis.clear();
        dataPoints0Unclean.clear();
        dataPoints1Unclean.clear();
        dataPoints2Unclean.clear();
        dataPointsavgdUnclean.clear();
        time = 0.0;
        dataSize = 0;
        graphCount = 0;

        // Update Save and Show button states when data is cleared
        updateDataDependentButtons();
    }

    public double findMaxbyAvg(ArrayList<Double> arl) {
        if (arl == null || arl.isEmpty()) return 0.0;
        double maxVal = 0;
        int idx = 0;
        for (int i = 0; i < arl.size(); i++) {
            if (arl.get(i) > maxVal) {
                maxVal = arl.get(i);
                idx = i;
            }
        }
        int start = Math.max(0, idx - 19), end = Math.min(arl.size() - 1, idx + 20);
        double sum = 0.0;
        for (int i = start; i <= end; i++) sum += arl.get(i);
        return (end - start + 1) > 0 ? sum / (end - start + 1) : maxVal;
    }

    @Override
    public void onBackPressed() {
        backCount++;
        if (backCount == 1) {
            Toast.makeText(this, "Press BACK again to exit", Toast.LENGTH_SHORT).show();
            new Handler().postDelayed(() -> backCount = 0, 2000);
            return;
        }
        super.onBackPressed();
    }

    //————————————————————————————
    // Thread to connect Bluetooth
    //————————————————————————————

    private void checkBatchBoundary() {
        if (dataSize > 0 && dataSize % BATCH_SIZE == 0) {
            Log.i(TAG, "Reached batch boundary at sample #" + dataSize);

            // Save current batch data
            saveCurrentProgress();

            // Force a complete connection reset
            runOnUiThread(() -> {
                Toast.makeText(OutputBluetooth.this,
                        "Starting batch " + (currentBatch + 1), Toast.LENGTH_SHORT).show();
            });

            // Shutdown current collection
            shutdownScheduler();

            // Close and reset connection in a separate thread
            new Thread(() -> {
                try {
                    if (mySocket != null) {
                        mySocket.close();
                        mySocket = null;
                    }
                    is = null;
                    os = null;

                    // Wait before reconnection
                    Thread.sleep(1000);

                    // Start fresh connection
                    runOnUiThread(() -> {
                        // Increment batch counter
                        currentBatch++;

                        // ✅ FIXED: Set connection state properly
                        synchronized (connectionLock) {
                            isConnecting = true;
                            connectionEstablished = false;
                        }

                        // Start with fresh connection
                        new ConnectThread(myDevice).start();

                        // ❌ REMOVE THIS - Let ConnectThread handle timer restart
                        // The ConnectThread will automatically start the timer in notifyConnectionSuccess()
                        // new Handler().postDelayed(() -> {
                        //     sendTimer(true);
                        // }, 2000);
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error during batch transition", e);

                    // ✅ ADDED: Reset connection state on error
                    runOnUiThread(() -> {
                        synchronized (connectionLock) {
                            isConnecting = false;
                            connectionEstablished = false;
                        }

                        Toast.makeText(OutputBluetooth.this,
                                "Batch transition failed. Please reconnect manually.",
                                Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        }
    }

    // Inner class for connecting to HC-05, with fallback and improved error handling
    private class ConnectThread extends Thread {
        private final UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
        private final BluetoothDevice device;
        private static final int MAX_RETRY_ATTEMPTS = 3;
        private static final long RETRY_DELAY_MS = 2000;
        private static final long CONNECTION_TIMEOUT_MS = 15000;

        ConnectThread(BluetoothDevice device) {
            this.device = device;
        }

        @Override
        public void run() {
            boolean connectionSuccessful = false;

            // Ensure we're in connecting state
            synchronized (connectionLock) {
                if (!isConnecting) {
                    Log.w(TAG, "Connection attempt cancelled - not in connecting state");
                    return;
                }
            }

            for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS && !connectionSuccessful; attempt++) {
                // Check if we should still be connecting
                synchronized (connectionLock) {
                    if (!isConnecting) {
                        Log.i(TAG, "Connection attempt cancelled during retry " + attempt);
                        return;
                    }
                }

                Log.i(TAG, "Connection attempt " + attempt + "/" + MAX_RETRY_ATTEMPTS);

                try {
                    // Ensure Bluetooth is enabled and ready
                    if (!ensureBluetoothReady()) {
                        Log.e(TAG, "Bluetooth not ready for connection");
                        continue;
                    }

                    // Cancel any ongoing discovery to free up resources
                    if (mBA.isDiscovering()) {
                        mBA.cancelDiscovery();
                        Thread.sleep(1000); // Wait for discovery to stop
                    }

                    // Close any existing socket
                    closeExistingSocket();

                    // Wait between attempts (except first attempt)
                    if (attempt > 1) {
                        Log.i(TAG, "Waiting " + RETRY_DELAY_MS + "ms before retry...");
                        Thread.sleep(RETRY_DELAY_MS);
                    }

                    // Try standard SPP connection first
                    connectionSuccessful = attemptStandardConnection();

                    if (!connectionSuccessful) {
                        // Try fallback connection method
                        connectionSuccessful = attemptFallbackConnection();
                    }

                    if (connectionSuccessful) {
                        // Initialize streams and test connection
                        if (initializeStreamsAndTest()) {
                            Log.i(TAG, "Connection successful on attempt " + attempt);
                            notifyConnectionSuccess();
                            return;
                        } else {
                            connectionSuccessful = false;
                            closeExistingSocket();
                        }
                    }

                } catch (InterruptedException e) {
                    Log.e(TAG, "Connection thread interrupted", e);
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Unexpected error in connection attempt " + attempt, e);
                    closeExistingSocket();
                }
            }

            // All attempts failed - reset connection state
            synchronized (connectionLock) {
                isConnecting = false;
                connectionEstablished = false;
            }

            Log.e(TAG, "All connection attempts failed");
            runOnUiThread(() -> {
                Toast.makeText(OutputBluetooth.this,
                        "Connection failed after " + MAX_RETRY_ATTEMPTS + " attempts. Please check device and try again.",
                        Toast.LENGTH_LONG).show();

                // Reset UI state
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
                connectButton.setEnabled(true);
                settingsButton.setEnabled(true);
            });
        }

        private boolean ensureBluetoothReady() {
            try {
                if (mBA == null || !mBA.isEnabled()) {
                    Log.e(TAG, "Bluetooth adapter not available or not enabled");
                    return false;
                }

                // Check permissions for Android 12+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(OutputBluetooth.this,
                            Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        Log.e(TAG, "BLUETOOTH_CONNECT permission not granted");
                        return false;
                    }
                }

                return true;
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception checking Bluetooth state", e);
                return false;
            }
        }

        private void closeExistingSocket() {
            if (mySocket != null) {
                try {
                    mySocket.close();
                    Log.d(TAG, "Existing socket closed");
                } catch (IOException e) {
                    Log.w(TAG, "Error closing existing socket", e);
                }
                mySocket = null;
            }
        }

        private boolean attemptStandardConnection() {
            try {
                Log.d(TAG, "Attempting standard RFCOMM connection...");

                // Add a delay before connection attempt to stabilize
                Thread.sleep(1000);

                // Create the socket
                mySocket = device.createRfcommSocketToServiceRecord(uuid);

                // Set connection timeout if possible (on some Android versions)
                try {
                    // Use reflection to set socket timeout property
                    Method m = mySocket.getClass().getMethod("setConnectionTimeout", int.class);
                    m.invoke(mySocket, 15000); // 15 seconds timeout
                    Log.d(TAG, "Connection timeout set to 15 seconds");
                } catch (Exception e) {
                    // Method not available, continue without setting timeout
                    Log.d(TAG, "Could not set connection timeout: " + e.getMessage());
                }

                // Try connecting with improved error handling
                try {
                    // Cancel discovery to avoid interference
                    if (mBA.isDiscovering()) {
                        mBA.cancelDiscovery();
                        Thread.sleep(500);
                    }

                    // Actual connection
                    mySocket.connect();
                    Log.d(TAG, "Standard connection successful");
                    return true;
                } catch (IOException e) {
                    String errorMsg = e.getMessage();
                    Log.w(TAG, "Standard connection failed: " + errorMsg);

                    // More detailed error logging
                    if (errorMsg != null && errorMsg.contains("timeout")) {
                        Log.e(TAG, "Connection timed out - device may be out of range or powered off");
                    } else if (errorMsg != null && errorMsg.contains("closed")) {
                        Log.e(TAG, "Socket was closed during connection");
                    }

                    closeExistingSocket();
                    return false;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in standard connection attempt: " + e.getClass().getName() + ": " + e.getMessage());
                closeExistingSocket();
                return false;
            }
        }

        private boolean attemptFallbackConnection() {
            try {
                Log.d(TAG, "Attempting fallback connection via reflection...");

                // Use reflection to create socket with channel 1
                Method fallbackMethod = device.getClass().getMethod("createRfcommSocket", int.class);
                mySocket = (BluetoothSocket) fallbackMethod.invoke(device, 1);

                if (mySocket != null) {
                    mySocket.connect();
                    Log.d(TAG, "Fallback connection successful");
                    return true;
                }
            } catch (Exception e) {
                Log.w(TAG, "Fallback connection failed: " + e.getMessage());
                closeExistingSocket();
            }
            return false;
        }

        private boolean initializeStreamsAndTest() {
            try {
                Log.d(TAG, "Initializing input/output streams...");

                // Get streams
                is = mySocket.getInputStream();
                os = mySocket.getOutputStream();

                if (is == null || os == null) {
                    Log.e(TAG, "Failed to get input/output streams");
                    return false;
                }

                // Test the connection by sending a simple command
                Log.d(TAG, "Testing connection with ping command...");
                os.write("R\r".getBytes()); // Reset command
                os.flush();

                // Wait a bit and clear any response
                Thread.sleep(500);
                while (is.available() > 0) {
                    is.skip(is.available());
                }

                Log.d(TAG, "Connection test successful");
                return true;

            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize streams or test connection", e);
                return false;
            }
        }


        private void notifyConnectionSuccess() {
            runOnUiThread(() -> {
                try {
                    String deviceName = device.getName();
                    if (deviceName == null) deviceName = "Unknown Device";

                    Toast.makeText(OutputBluetooth.this,
                            "Connected to " + deviceName, Toast.LENGTH_SHORT).show();

                    // Set connection state flags
                    synchronized (connectionLock) {
                        isConnecting = false;
                        connectionEstablished = true;
                    }

                    // ✅ CHECK: If this is a batch transition and data collection was active
                    boolean shouldResumeCollection = started && currentBatch > 1;

                    if (shouldResumeCollection) {
                        // ✅ BATCH TRANSITION: Automatically resume data collection
                        Log.i(TAG, "Batch transition complete - resuming data collection");

                        // Update UI for resumed collection
                        startButton.setEnabled(false);
                        stopButton.setEnabled(true);
                        connectButton.setEnabled(false);
                        settingsButton.setEnabled(true);
                        saveButton.setEnabled(false);
                        showButton.setEnabled(false);

                        // Restart data collection
                        sendTimer(true);

                        Toast.makeText(OutputBluetooth.this,
                                "Batch " + currentBatch + " resumed", Toast.LENGTH_SHORT).show();
                    } else {
                        // ✅ NORMAL CONNECTION: Update UI for connected but not started
                        startButton.setEnabled(true);  // Enable start button
                        stopButton.setEnabled(false);  // Not started yet
                        connectButton.setEnabled(true); // Allow manual reconnect
                        settingsButton.setEnabled(true); // Allow settings
                        saveButton.setEnabled(dataSize > 0);   // Only if there's data
                        showButton.setEnabled(dataSize > 0);   // Only if there's data

                        Log.i(TAG, "Connection established. Ready for data collection.");
                    }

                    // Log button states for debugging
                    logButtonStates("after connection success");

                    // Reset reconnection flags
                    OutputBluetooth.this.reconnecting = false;
                    isReconnecting = false;
                    errorCount = 0;

                } catch (SecurityException e) {
                    Log.e(TAG, "Security exception in success notification", e);
                    // Reset connection state on error
                    synchronized (connectionLock) {
                        isConnecting = false;
                        connectionEstablished = false;
                    }
                }
            });
        }
    }

    // Helper method to check if we have a valid Bluetooth connection
    private boolean hasValidConnection() {
        return connectionEstablished && mySocket != null && mySocket.isConnected() &&
                os != null && is != null;
    }

    // Helper method to log current button states for debugging
    private void logButtonStates(String context) {
        Log.d(TAG, "Button states (" + context + "): " +
                "Start=" + startButton.isEnabled() +
                ", Stop=" + stopButton.isEnabled() +
                ", Connect=" + connectButton.isEnabled() +
                ", Settings=" + settingsButton.isEnabled() +
                ", Save=" + saveButton.isEnabled() +
                ", Show=" + showButton.isEnabled());
    }

    // Helper method to update Save and Show button states based on data availability
    private void updateDataDependentButtons() {
        boolean hasData = dataSize > 0 && !dataPointsavgd.isEmpty();
        // Only enable save/show buttons if we have data AND data collection is not active
        boolean shouldEnable = hasData && !started;

        // Only update if the state would change to avoid unnecessary UI updates
        if (saveButton.isEnabled() != shouldEnable) {
            saveButton.setEnabled(shouldEnable);
            Log.d(TAG, "Save button " + (shouldEnable ? "enabled" : "disabled") +
                    " (dataSize=" + dataSize + ", started=" + started + ")");
        }

        if (showButton.isEnabled() != shouldEnable) {
            showButton.setEnabled(shouldEnable);
            Log.d(TAG, "Show button " + (shouldEnable ? "enabled" : "disabled") +
                    " (dataSize=" + dataSize + ", started=" + started + ")");
        }
    }
}