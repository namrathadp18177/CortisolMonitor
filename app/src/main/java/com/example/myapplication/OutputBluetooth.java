package com.example.myapplication;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.GridLabelRenderer;
import com.jjoe64.graphview.LegendRenderer;
import com.jjoe64.graphview.Viewport;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.StringTokenizer;
import java.util.concurrent.ScheduledExecutorService;

public class OutputBluetooth extends AppCompatActivity {
    private static final String TAG = "OutputBluetooth";
    private static final int REQUEST_BLUETOOTH_PERMISSION = 1;
    private static final int REQUEST_LOCATION_PERMISSION = 2;
    private static final int REQUEST_STORAGE_PERMISSION = 3;
    private static final int REQUEST_QR_SCAN = 11;

    private boolean autoReconnectEnabled = true;

    private boolean connectOnly = false;


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
    private android.widget.ProgressBar collectionProgressBar;
    private TextView tvProgressLabel;
    private TextView tvResultsInstruction;
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
    private final ArrayList<Double> validCortisolList = new ArrayList<>();

    // BLE manager (singleton)
    private BluetoothManager bleManager;

    // Measurement state machine for dark → signal cycling
    private enum MeasState { IDLE, DARK_PENDING, SIGNAL_PENDING }
    private volatile MeasState measState = MeasState.IDLE;
    private int darkPD1 = 0, darkPD2 = 0, darkPD3 = 0; // stored dark baseline

    // Handler-based measurement loop (replaces ScheduledExecutorService)
    private final Handler measHandler = new Handler(Looper.getMainLooper());
    private Runnable measurementRunnable;

    // Experiment parameters
    private double analogRef = 2.048; // ADS1118 FSR ±2.048 V (new firmware)
    private double durationOfExp = 600.0;
    private int samplesPerSecond = 5;
    private int movingAvgValue = 4;
    private int ADCbits = 15;
    private boolean[] analogPort = {true, true, true, true};
    private boolean navigatingToResults = false;

    // ---- Static developer-mode hardware controls (defaults per integration guide) ----
    // These can be adjusted from DeveloperModeActivity via sliders.
    public static volatile int devLedBrightness = 255; // Firmware default: full brightness
    public static volatile int devGainWiper     = 128; // Firmware default: mid-scale


    // State
    private int backCount = 0;
    private int graphCount = 0;
    private int dataSize = 0;
    private double time = 0.0;
    private double max = 0.0;
    private String userEmail;
    private boolean started = false;

    // Executor for scheduled polling (kept for compatibility; BLE uses measHandler)
    private ScheduledExecutorService scheduler;
    private int errorCount = 0;
    private static final int MAX_ERRORS = 5;


    private static final long DEV_PATTERN_TIMEOUT_MS = 6000L;

    // 0=TL, 1=TR, 2=BR, 3=BL
    private final int[] devPattern = {0, 1, 2, 3};
    private int devIndex = 0;
    private long patternStartTime = 0L;

    // Shared live data for Developer Mode
    public static final ArrayList<Double> devModeXAxis = new ArrayList<>();
    public static final ArrayList<Double> devModeYAxis = new ArrayList<>();
    public static volatile boolean devModeActive = false;
    public static volatile int devSamplesPerSecond = 5;
    public static volatile int devMovingAvgValue = 4;
    public static volatile double devDurationOfExp = 600.0;
    public static volatile boolean[] devAnalogPort = {true, true, true, true};
    private String tubeId;
    private String cartridgeId;
    private String sampleTime;
    private String deviceAddress;

    private String userRole;



    public static final ArrayList<Double> devModeAvgY = new ArrayList<>();  // add this

    // Device status (populated from BLE status characteristic, Char 4)
    /** Raw VBAT ADC value from status packet (16-bit big-endian). Interpreted as mV. */
    public static volatile int devBatteryVbat = 0;
    /** Raw VBUS ADC value from status packet. */
    public static volatile int devBatteryVbus = 0;
    /** Name of the currently connected BLE device (empty if not connected). */
    public static volatile String devConnectedDeviceName = "";

    // replace
    private static final int BATCHSIZE = 1000;
    // with
    private static final int MAX_SAMPLES = 100;




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
        FrameLayout root = findViewById(R.id.rootFrameLayout);
        View devTapOverlay = findViewById(R.id.devTapOverlay);
        devTapOverlay.setOnTouchListener((v, event) -> {
            Log.d(TAG, "onTouch action=" + event.getAction());
            if (event.getAction() == MotionEvent.ACTION_DOWN) {  // change UP to DOWN
                handleDeveloperPattern(event, v.getWidth(), v.getHeight());
            }
            return false;
        });
        Intent intent = getIntent();
        tubeId = intent.getStringExtra("tube_id");
        cartridgeId = intent.getStringExtra("cartridge_id");
        sampleTime = intent.getStringExtra("sample_time");
        deviceAddress = intent.getStringExtra("device_address");
        userRole = intent.getStringExtra("user_role");
        connectOnly = getIntent().getBooleanExtra("connect_only", false);

// If care provider, save experiment under PATIENT's email, not CP's email
        if ("CARE_PROVIDER".equals(userRole)) {
            userEmail = intent.getStringExtra("patient_email");
            if (userEmail == null || userEmail.isEmpty()) {
                userEmail = intent.getStringExtra("user_email");
            }
        } else {
            userEmail = intent.getStringExtra("user_email");
        }

        android.util.Log.d("HISTORY_DEBUG", "OutputBluetooth saving experiment");




        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sampleTimestamp = getIntent().getLongExtra("sample_timestamp", -1);

        initializeViews();
        checkPermissions();
        loadSettings();
//        GraphView graph = findViewById(R.id.graphViewBL);
//        Viewport vp = graph.getViewport();
//
//// Ensure the whole graph is visible and scrollable/zoomable as needed
//        vp.setScrollable(true);
//        vp.setScalable(true);

// Avoid x‑axis labels overlapping
//        GridLabelRenderer gridLabel = graph.getGridLabelRenderer();
//        gridLabel.setNumHorizontalLabels(4);   // fewer X labels
//        gridLabel.setNumVerticalLabels(5);     // cleaner Y labels

// Add some padding around labels so they are not cut off
//        gridLabel.setPadding(16);
//        gridLabel.setHorizontalLabelsAngle(0); // keep them horizontal; use 45 if still crowded
//
//// Optional: set label text size a bit smaller
//        gridLabel.setTextSize(28f); // default is ~30f, reduce if still clipped



        // Initialize database helper using the singleton pattern
        dbHelper = DatabaseHelper.getInstance(this);



        userEmail = getIntent().getStringExtra("user_email");
        graphData = new ArrayList<>();
        resetGraph();
    }


    @Override
    protected void onResume() {
        super.onResume();
        navigatingToResults = false;

        // Obtain the BLE manager singleton
        bleManager = BluetoothManager.getInstance(this);

        // If the BLE singleton is already connected (e.g. passed from connect-only mode),
        // adopt it immediately without re-connecting.
        if (bleManager.isConnected()) {
            bleManager.setCallback(bleCallback);
            connectionEstablished = true;
            isConnecting = false;
            onBluetoothConnected();
            return;
        }

        // Normal flow — connect to the device whose address was passed by the wizard
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, 0);
        } else {
            if (connectButton != null) connectButton.setEnabled(true);
            if (deviceAddress != null && !deviceAddress.isEmpty()
                    && !connectionEstablished && !isConnecting) {
                synchronized (connectionLock) {
                    isConnecting = true;
                    connectionEstablished = false;
                }
                bleManager.setCallback(bleCallback);
                bleManager.connect(deviceAddress);
            }
        }
    }




    @Override
    protected void onStop() {
        super.onStop();
        if (!devModeActive && !navigatingToResults) { // ← ADD !navigatingToResults
            shutdownScheduler();
            if (started) sendTimer(false);
        }
    }





    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!devModeActive && !navigatingToResults) {
            shutdownScheduler();
            if (started) sendTimer(false);
        }
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
                    showSettingsChangeWarning(newSamplesPerSecond, newMovingAvgValue, newDurationOfExp, newAnalogPort);
                } else {
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
                new Handler().postDelayed(() -> {
                    initializeBluetooth();
                }, 1000);
            } else {
                Toast.makeText(this, "Bluetooth is required for this app", Toast.LENGTH_LONG).show();
            }

        } else if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            // Result from BluetoothListActivity (BLE device scanner)
            String address = data.getStringExtra("device_address");
            if (address != null && !address.isEmpty()) {
                deviceAddress = address;
                Toast.makeText(this, "Connecting to BLE device…", Toast.LENGTH_SHORT).show();
                synchronized (connectionLock) {
                    isConnecting = true;
                    connectionEstablished = false;
                }
                if (startButton != null) startButton.setEnabled(false);
                bleManager = BluetoothManager.getInstance(this);
                bleManager.setCallback(bleCallback);
                bleManager.connect(address);
            }

        } else if (requestCode == REQUEST_QR_SCAN
                && resultCode == RESULT_OK
                && data != null) {
            // QR scan result → treat address as BLE device address
            String address = data.getStringExtra("device_address");
            if (address != null) {
                deviceAddress = address;
                Toast.makeText(this, "QR scanned! Connecting via BLE…", Toast.LENGTH_SHORT).show();
                synchronized (connectionLock) {
                    isConnecting = true;
                    connectionEstablished = false;
                    if (startButton != null) startButton.setEnabled(false);
                }
                bleManager = BluetoothManager.getInstance(this);
                bleManager.setCallback(bleCallback);
                bleManager.connect(address);
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
                Log.i(TAG, "Bluetooth ready - starting data collection directly");

                started = true;
                sendTimer(true);

                startButton.setEnabled(false);
                connectButton.setEnabled(false);
                showButton.setEnabled(false);

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

            if (deviceAddress != null && !deviceAddress.isEmpty()) {
                // Reconnect BLE using known address
                bleManager.connect(deviceAddress);
            } else {
                // No device known — open scanner
                isConnecting = false;
                initializeBluetooth();
            }

            // 6) UI state will be enabled in ConnectThread on success.
            //    For now disable Start to prevent double-tap:
            startButton.setEnabled(false);
//            stopButton.setEnabled(false);
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
        // Dismiss overlay if showing
        Fragment overlay = getSupportFragmentManager().findFragmentByTag("step_overlay");
        if (overlay != null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .remove(overlay)
                    .commit();
            findViewById(R.id.stepOverlayContainer).setVisibility(View.GONE);
        }



        // 2) DON'T close the socket - keep Bluetooth connection alive
        // This prevents the disconnection/reconnection cycle

        // 3) Update UI for stopped state but keep connection
//        stopButton.setEnabled(false);
        startButton.setEnabled(true);
        connectButton.setEnabled(true);
        // settingsButton.setEnabled(true);

        // Update save/show buttons based on data availability
        updateDataDependentButtons();

        // Log button states for debugging
        // logButtonStates("after stop (connection maintained)");

        Toast.makeText(this, "Data collection stopped", Toast.LENGTH_SHORT).show();
        Log.i(TAG, "Data collection stopped - Bluetooth connection maintained");
    }
//    private void showStepOverlay() {
//        View container = findViewById(R.id.stepOverlayContainer);
//        if (container == null) {
//            android.util.Log.e("OVERLAY", "stepOverlayContainer not found in layout!");
//            return;
//        }
//        container.setVisibility(View.VISIBLE);
//        android.util.Log.d("OVERLAY", "Container visibility set to VISIBLE: " + container.getVisibility());
//
//        // Step 1 — 5 minute timer
//        StepModel step1 = new StepModel("STEP 01", "Collect Saliva",
//                "Put the soft swab under your tongue. Keep it there for 5 full minutes until it becomes very wet with saliva. Try not to talk during this time.",
//                null, R.drawable.ic_step_collection, true, false);
//        step1.timerDurationMs = 5 * 60 * 1000;
//
//
//// Step 5 — 20 minute timer
//        StepModel step5 = new StepModel("STEP 05", "Wait 20 Minutes",
//                "Place the test strip on a flat surface. Do not move it. Wait 20 full minutes to process.",
//                null, R.drawable.ic_step_wait, true, false);
//        step5.timerDurationMs = 20 * 60 * 1000;
//        step5.showMotivation = false;
//
//
//// Step 6 — Final step
//        StepModel step6 = new StepModel("STEP 06", "Insert into Analyser",
//                "After waiting 20 minutes, gently insert the test strip into the Analyser to see begin reading.",
//                null, R.drawable.ic_step_analyser, false, false);
//
//
//        List<StepModel> steps = new ArrayList<>(Arrays.asList(
//                step1,
//
//                new StepModel("STEP 02", "Move Saliva to Tube",
//                        "Place the swab into the tube. Push it down slowly and firmly as shown to release the saliva into the tube.",
//                        null, R.drawable.ic_step_extraction, false, false),
//
//                new StepModel("STEP 03", "Store Sample (Optional)",
//                        "If you would like to use the sample later:",
//                        new ArrayList<>(Arrays.asList(
//                                "Remove the tube carefully",
//                                "Close it tightly with the cap",
//                                "Store in a freezer at -20°C if not using immediately"
//                        )), R.drawable.ic_step_storage, false, true),
//
//                new StepModel("STEP 04", "Add Drops to Test Strip",
//                        "Turn the tube upside down. Gently squeeze 4 to 5 drops onto the test strip.",
//                        null, R.drawable.ic_step_loading, false, false),
//
//                step5,
//                step6
//        ));
//        StepOverlayFragment fragment = StepOverlayFragment.newInstance(steps);
//        fragment.setStepOverlayListener(new StepOverlayFragment.StepOverlayListener() {
//            @Override
//            public void onOverlayComplete() {
//                getSupportFragmentManager()
//                        .beginTransaction()
//                        .remove(fragment)
//                        .commit();
//                findViewById(R.id.stepOverlayContainer).setVisibility(View.GONE);
//
//                // NOW start actual sample collection after step 5
//                started = true;
//                sendTimer(true);
//                startButton.setEnabled(false);
////                stopButton.setEnabled(true);
//                connectButton.setEnabled(false);
//                // settingsButton.setEnabled(true);
////                saveButton.setEnabled(false);
//                showButton.setEnabled(false);
//                Log.i(TAG, "Overlay complete - sample collection started");
//            }
//
//
//            @Override
//            public void onStoredForLater() {
//                getSupportFragmentManager()
//                        .beginTransaction()
//                        .remove(fragment)
//                        .commit();
//                findViewById(R.id.stepOverlayContainer).setVisibility(View.GONE);
//                onClickStop(null);
//            }
//        });
//
//        getSupportFragmentManager()
//                .beginTransaction()
//                .add(R.id.stepOverlayContainer, fragment, "step_overlay")
//                .commit();
//    }





    public void onClickConnect(View v) {
        Log.i(TAG, "Connect button clicked");

        synchronized (connectionLock) {
            if (isConnecting) {
                Toast.makeText(this, "Connection already in progress...", Toast.LENGTH_SHORT).show();
                return;
            }

            stopMeasurementCycle();
            connectionEstablished = false;
            isReconnecting = false;

            // Disconnect existing BLE connection
            if (bleManager != null) bleManager.disconnect();
        }

        // Show BLE device scanner
        searchForDevice();
    }

    public void onClickQRConnect(View v) {
        Intent intent = new Intent(this, QRScanActivity.class);
        startActivityForResult(intent, REQUEST_QR_SCAN);
    }


    public void onClickShow(View v) {
        navigatingToResults = true;
        Log.i(TAG, "Show button clicked");
        try {
            if (dataPointsavgd.isEmpty()) {
                // No new data, just open ResultActivity to show history
                navigatingToResults = true;
                Intent intent = new Intent(this, ResultActivity.class);
                String role = getIntent().getStringExtra("user_role");
                intent.putExtra("user_email", userEmail);
                intent.putExtra("user_role", role);
                startActivity(intent);
                return;
            }

            // Calculate max values
            double max1 = findMaxbyAvg(dataPoints1);
            double max = findMaxbyAvg(dataPointsavgd);
            double max0 = findMaxbyAvg(dataPoints0);
            double finalCortisol = calculateFinalExperimentCortisol();
            double max2 = findMaxbyAvg(dataPoints2);

            Log.i(TAG, "Calculated max values: port0=" + max0 + ", port1=" + max1 + ", port2=" + max2);

            // SAVE TO DATABASE FIRST

            if (currentExperimentId == -1) {
                // Create new experiment
                long tsToSave = (sampleTimestamp > 0) ? sampleTimestamp : System.currentTimeMillis();
                currentExperimentId = dbHelper.createBiomarkerExperiment(
                        userEmail, "Bluetooth", samplesPerSecond,
                        movingAvgValue, durationOfExp, finalCortisol, dataSize, tsToSave, tubeId,             // from wizard Intent
                        cartridgeId);
                dbHelper.addBiomarkerDataBatch(currentExperimentId, xaxis,
                        dataPoints0, dataPoints1, dataPoints2, dataPointsavgd);
                Log.i(TAG, "New experiment saved with ID: " + currentExperimentId);
            } else {
                // Update existing experiment
                dbHelper.updateBiomarkerExperimentMaxValue(currentExperimentId, finalCortisol);  // ✅ Also change this
                Log.i(TAG, "Updated experiment " + currentExperimentId + " with max value: " + max1);
            }


            // Then open ResultActivity
            String role = getIntent().getStringExtra("user_role");

            // Save cortisol to SharedPreferences so CombinedResultsActivity
            // can pick it up as maxstr3 regardless of navigation path
            getSharedPreferences("BiomarkerResults", MODE_PRIVATE)
                    .edit()
                    .putFloat("latest_cortisol_ng_ml", (float) finalCortisol)
                    .apply();
            Log.i(TAG, "Saved latest cortisol reading to SharedPreferences");

            Intent resultActivity = new Intent(this, ResultActivity.class);
            resultActivity.putExtra("max_value_port0", max0);
            resultActivity.putExtra("final_cortisol", finalCortisol);
            resultActivity.putExtra("max_value_port2", max2);
            resultActivity.putExtra("user_email", userEmail);
            resultActivity.putExtra("user_role", role);
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
                        navigatingToResults = true;
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
            long tsToSave = (sampleTimestamp > 0)
                    ? sampleTimestamp
                    : System.currentTimeMillis();

            double finalCortisol = calculateFinalExperimentCortisol();

            currentExperimentId = dbHelper.createBiomarkerExperiment(
                    userEmail,
                    "Bluetooth",
                    samplesPerSecond,
                    movingAvgValue,
                    durationOfExp,
                    finalCortisol,   // ← STORE CORTISOL ONLY
                    dataSize,
                    tsToSave,
                    tubeId,             // from wizard Intent
                    cartridgeId
            );

            dbHelper.addBiomarkerDataBatch(
                    currentExperimentId,
                    xaxis,
                    dataPoints0,
                    dataPoints1,
                    dataPoints2,
                    dataPointsavgd
            );

            Log.d("DEBUG_SAVE", "Saved cortisol reading to DB");
            new SyncManager(getApplicationContext()).pushAllDirtyData(null);

        } catch (Exception e) {
            Log.e(TAG, "Database save error", e);
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
        // graphView = findViewById(R.id.graphViewBL);

        startButton = findViewById(R.id.buttonStartBL);
//        stopButton = findViewById(R.id.buttonStopBL);
//        saveButton = findViewById(R.id.buttonSaveBL);
//       //  settingsButton = findViewById(R.id.buttonSettingBL);
        connectButton         = findViewById(R.id.buttonConnectBL);
        showButton            = findViewById(R.id.buttonShowBL);
        textViewAppend        = findViewById(R.id.textAppendBL);
        collectionProgressBar = findViewById(R.id.collectionProgressBar);
        tvProgressLabel       = findViewById(R.id.tvProgressLabel);
        tvResultsInstruction  = findViewById(R.id.tvResultsInstruction);

        // Set initial button states
        startButton.setEnabled(false);
        connectButton.setEnabled(false); // Will be enabled when Bluetooth is ready

        // Results section hidden — only shown when collection completes
        showButton.setVisibility(View.GONE);
        if (tvResultsInstruction != null) tvResultsInstruction.setVisibility(View.GONE);

        // Metrics text is developer-mode only (shown in DeveloperModeActivity, not here)
        View metricsTitle = findViewById(R.id.tvMetricsTitle);
        if (metricsTitle   != null) metricsTitle.setVisibility(View.GONE);
        if (textViewAppend != null) textViewAppend.setVisibility(View.GONE);
        // Progress bar is always shown for all users on this screen
        if (collectionProgressBar != null) collectionProgressBar.setVisibility(View.VISIBLE);
        if (tvProgressLabel       != null) tvProgressLabel.setVisibility(View.VISIBLE);

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
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_LONG).show();
            return;
        }
        if (!adapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, 0);
        } else {
            if (connectButton != null) connectButton.setEnabled(true);
            if (startButton != null) startButton.setEnabled(false);
            searchForDevice();
        }
    }

    /**
     * Launch the BLE scanner activity so the user can pick a device.
     * When they tap one, onActivityResult gets the MAC address and connects.
     */
    private void searchForDevice() {
        Intent intent = new Intent(this, BluetoothListActivity.class);
        startActivityForResult(intent, 100);
    }

    //————————————————————————————
    // BLE measurement loop (dark → signal cycling)
    //————————————————————————————

    /**
     * Start or stop the BLE measurement cycle.
     * Replaces the old RFCOMM scheduler — now Handler-driven and event-based.
     */
    private void sendTimer(boolean start) {
        stopMeasurementCycle(); // always cancel any pending callbacks first

        if (!start) return;

        if (!hasValidConnection()) {
            Log.w(TAG, "Cannot start measurement – BLE not connected");
            return;
        }

        long intervalMs = Math.max(200L, 1000L / samplesPerSecond);
        Log.i(TAG, "Starting BLE measurement cycle, interval=" + intervalMs + "ms");

        measurementRunnable = new Runnable() {
            @Override
            public void run() {
                if (!started || !hasValidConnection()) {
                    Log.v(TAG, "Measurement cycle stopped");
                    return;
                }
                triggerDarkMeasurement();
                // The next trigger is scheduled in the BLE callback after a
                // complete dark+signal pair has been processed.
                // Store interval in a local field so the callback can use it.
                currentMeasIntervalMs = intervalMs;
            }
        };

        // Small initial delay to let the connection settle
        measHandler.postDelayed(measurementRunnable, 500);
    }

    /** Current measurement interval; set by sendTimer() and used by the BLE callback. */
    private long currentMeasIntervalMs = 200;

    /** Stop any pending measurement callbacks without changing the `started` flag. */
    private void stopMeasurementCycle() {
        measHandler.removeCallbacksAndMessages(null);
        measState = MeasState.IDLE;
        // Keep scheduler field null-safe for any code that still checks it
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** Trigger step 1: request a dark (baseline) reading from the device. */
    private void triggerDarkMeasurement() {
        if (!hasValidConnection()) return;
        measState = MeasState.DARK_PENDING;
        bleManager.triggerDarkRead();
        Log.v(TAG, "Dark read triggered");
    }

    // -------------------------------------------------------------------------
    // BLE callback — all dispatched on the main thread by BluetoothManager
    // -------------------------------------------------------------------------
    private final BluetoothManager.BleCallback bleCallback = new BluetoothManager.BleCallback() {

        @Override
        public void onConnected() {
            synchronized (connectionLock) {
                connectionEstablished = true;
                isConnecting         = false;
            }
            isReconnecting = false;
            errorCount     = 0;

            // Send whatever LED/Gain values are currently in the static fields.
            // These are reset to defaults (200/128) each time QRWizardActivity starts,
            // and only change if the user visits Developer Mode before running the test.
            bleManager.setLedBrightness((byte) devLedBrightness);
            bleManager.setGain((byte) devGainWiper);

            Log.i(TAG, "BLE connected. Settings sent: LED=" + devLedBrightness
                    + " Gain=" + devGainWiper);

            // If connect-only mode, return RESULT_OK to the wizard
            if (connectOnly) {
                Intent result = new Intent();
                result.putExtra("deviceaddress", deviceAddress);
                setResult(RESULT_OK, result);
                finish();
                return;
            }

            onBluetoothConnected();

            // Resume collection if it was already running
            if (started) {
                if (startButton  != null) startButton.setEnabled(false);
                if (connectButton!= null) connectButton.setEnabled(false);
                if (showButton   != null) showButton.setEnabled(false);
                sendTimer(true);
            }
        }

        @Override
        public void onDisconnected() {
            synchronized (connectionLock) {
                connectionEstablished = false;
            }
            devConnectedDeviceName = "";
            stopMeasurementCycle();
            Log.w(TAG, "BLE disconnected");

            runOnUiThread(() -> {
                if (startButton  != null) startButton.setEnabled(false);
                if (connectButton!= null) connectButton.setEnabled(true);
                Toast.makeText(OutputBluetooth.this,
                        "Device disconnected. Tap Connect to reconnect.",
                        Toast.LENGTH_LONG).show();
            });
        }

        @Override
        public void onOpticalData(byte[] packet) {
            // Packet: [0xFF][SEQ_H][SEQ_L][0xFE][PD1_H][PD1_L][0xFE][PD2_H][PD2_L][0xFE][PD3_H][PD3_L][0xFD]
            int seq  = ((packet[1] & 0xFF) << 8) | (packet[2]  & 0xFF);
            int pd1  = ((packet[4] & 0xFF) << 8) | (packet[5]  & 0xFF);
            int pd2  = ((packet[7] & 0xFF) << 8) | (packet[8]  & 0xFF);
            int pd3  = ((packet[10]& 0xFF) << 8) | (packet[11] & 0xFF);

            if (measState == MeasState.DARK_PENDING) {
                // Store dark baseline and immediately request the signal reading
                darkPD1  = pd1;
                darkPD2  = pd2;
                darkPD3  = pd3;
                measState = MeasState.SIGNAL_PENDING;
                bleManager.triggerSignalRead();
                Log.v(TAG, "Dark stored: PD1=" + pd1 + " PD2=" + pd2 + " PD3=" + pd3
                        + "  seq=" + seq);

            } else if (measState == MeasState.SIGNAL_PENDING) {
                // We have both dark and signal — compute voltages and process
                measState = MeasState.IDLE;

                // Convert raw 16-bit unsigned counts to voltage:
                // FSR = ±2.048 V (ADS1118), single-ended range 0..32767 → 0..+2.048 V
                double val0 = pd1 * analogRef / (Math.pow(2, ADCbits) - 1);
                double val1 = pd2 * analogRef / (Math.pow(2, ADCbits) - 1);
                double val2 = pd3 * analogRef / (Math.pow(2, ADCbits) - 1);

                Log.v(TAG, "Signal: PD1=" + pd1 + " PD2=" + pd2 + " PD3=" + pd3
                        + "  seq=" + seq);

                processSignalValues(val0, val1, val2);

                // Schedule the next dark→signal cycle after the configured interval
                if (started && hasValidConnection()) {
                    measHandler.postDelayed(() -> {
                        if (started && hasValidConnection()) triggerDarkMeasurement();
                    }, currentMeasIntervalMs);
                }
            } else {
                Log.w(TAG, "Unexpected optical packet in state " + measState);
            }
        }

        @Override
        public void onStatusData(byte[] status) {
            // 6 bytes: [VBAT_H][VBAT_L][VBUS_H][VBUS_L][LED_BRIGHTNESS][GAIN_WIPER]
            int vbat = ((status[0] & 0xFF) << 8) | (status[1] & 0xFF);
            int vbus = ((status[2] & 0xFF) << 8) | (status[3] & 0xFF);
            int led  =   status[4] & 0xFF;
            int gain =   status[5] & 0xFF;
            // Expose to developer mode
            devBatteryVbat = vbat;
            devBatteryVbus = vbus;
            Log.d(TAG, "Status: VBAT=" + vbat + " VBUS=" + vbus
                    + " LED=" + led + " Gain=" + gain);
        }
    };

    // smartReconnect() removed — BLE GATT handles reconnection via the
    // onDisconnected callback; user can tap Connect to re-pair if needed.

    @SuppressWarnings("unused")
    private void smartReconnect() {
        // Only proceed if not already reconnecting and cooldown period has passed
        long currentTime = System.currentTimeMillis();
        if (dataSize >= RECONNECT_THRESHOLD && !isReconnecting &&
                (currentTime - lastReconnectTime > RECONNECT_COOLDOWN)) {

            isReconnecting = true;
            lastReconnectTime = currentTime;
            Log.i(TAG, "BLE reconnect requested at sample #" + dataSize);
            saveCurrentProgress();
            // BLE reconnect: just re-call connect on the manager
            stopMeasurementCycle();
            if (bleManager != null && deviceAddress != null) {
                bleManager.connect(deviceAddress);
            }
            isReconnecting = false;
            /* ---------- legacy RFCOMM code below kept for reference ----------
            Thread reconnectThread = new Thread(() -> {
                try {
                    boolean wasStarted = started;
                    stopMeasurementCycle();
                    synchronized (OutputBluetooth.this) {
                        // no socket to close for BLE
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

            /* --- end of legacy RFCOMM reconnect code --- */
        }
    }

    // FinalConnectThread removed — BLE uses BluetoothManager.connect() instead.
    // pollOnce() removed — BLE uses the bleCallback.onOpticalData() state machine instead.

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
    /** Kept for compatibility with onStop/onDestroy calls; delegates to stopMeasurementCycle(). */
    private void shutdownScheduler() {
        stopMeasurementCycle();
    }

    /** BLE reconnection — re-invoke connect() on the singleton manager. */
    private void reconnectBluetooth() {
        synchronized (connectionLock) {
            if (isReconnecting || isConnecting) return;
            isReconnecting = true;
            connectionEstablished = false;
        }
        Log.i(TAG, "BLE reconnect triggered");
        stopMeasurementCycle();
        if (bleManager != null && deviceAddress != null && !deviceAddress.isEmpty()) {
            bleManager.setCallback(bleCallback);
            bleManager.connect(deviceAddress);
        } else {
            isReconnecting = false;
            runOnUiThread(() ->
                    Toast.makeText(this, "Reconnection failed — no device address", Toast.LENGTH_SHORT).show());
        }
    }

    //————————————————————————————
    // BLE signal processing
    //————————————————————————————

    /**
     * Called by bleCallback.onOpticalData() with pre-computed voltages from the
     * 13-byte BLE optical packet.  Contains all cortisol calculation, UI update,
     * graph feeding, and developer mode data logic.
     */
    private void processSignalValues(double val0, double val1, double val2) {
        try {
            // update counters
            dataSize++;
            time += 1.0 / samplesPerSecond;

            double yValue = val1 * 1000.0;

            if (yValue >= 428.0 && yValue <= 478.6686) {
                double cortisol = Math.pow((yValue - 482.9265) / (-4.2579), 1.0 / 0.5553);
                validCortisolList.add(cortisol);

                double runningAvg = calculateFinalExperimentCortisol();
                Log.d("CORTISOL_ROLLING",
                        "Sample#" + dataSize +
                                " | yValue=" + String.format(Locale.getDefault(), "%.4f", yValue) +
                                " | thisCortisol=" + String.format(Locale.getDefault(), "%.4f", cortisol) +
                                " | validCount=" + validCortisolList.size() +
                                " | runningAvg=" + String.format(Locale.getDefault(), "%.4f", runningAvg));
            } else {
                Log.d("CORTISOL_ROLLING",
                        "Sample#" + dataSize +
                                " | yValue=" + String.format(Locale.getDefault(), "%.4f", yValue) +
                                " | REJECTED (out of range 428-478.6686)" +
                                " | validCount=" + validCortisolList.size());
            }

            Log.d(TAG, "processSignalValues called, devModeActive=" + devModeActive + " val1=" + val1 + " time=" + time);

            if (devModeActive) {
                devModeXAxis.add(time);
                devModeYAxis.add(val1);
                double devAvg = dataPointsavgd.isEmpty() ? val1 : dataPointsavgd.get(dataPointsavgd.size() - 1);
                devModeAvgY.add(devAvg);
                Log.d(TAG, "DevMode fed: xSize=" + devModeXAxis.size() + " ySize=" + devModeYAxis.size());
            }

            // Calculate cortisol from last 100 samples (or all if less than 100)
            double currentCortisol = calculateFinalExperimentCortisol();

            textViewAppend.setText(String.format(Locale.getDefault(),
                    "Samples %d | %.2fs\n0: %.4f V\n1: %.4f V\n2: %.4f V\nCortisol: %.1f ng/mL",
                    dataSize, time, val0, val1, val2, currentCortisol));

            // Update progress bar for regular users
            int progressPct = (int) Math.min(100, (double) dataSize / MAX_SAMPLES * 100);
            if (collectionProgressBar != null) collectionProgressBar.setProgress(progressPct);
            if (tvProgressLabel != null)
                tvProgressLabel.setText("Collecting data... " + progressPct + "%");

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
            runOnUiThread(this::checkSampleLimit);

        } catch (Exception e) {
            Log.e(TAG, "Error in processSignalValues", e);
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
            Log.d("ROLLING_CORTISOL", "No samples yet");
            return 0.0;
        }

        int totalSamples = dataPoints1.size();
        int samplesToCheck = Math.min(100, totalSamples);
        int startIndex = totalSamples - samplesToCheck;

        double sumCortisol = 0.0;
        double sumVoltage = 0.0;
        int validCount = 0;

        for (int i = startIndex; i < totalSamples; i++) {

            double voltage = dataPoints1.get(i);
            double yValue = voltage * 1000.0;

            if (yValue >= 428.0 && yValue <= 478.6686) {

                double cortisol =
                        Math.pow((yValue - 482.9265) / (-4.2579),
                                1.0 / 0.5553);

                sumCortisol += cortisol;
                sumVoltage += voltage;
                validCount++;
            }
        }

        if (validCount == 0) {
            Log.d("ROLLING_CORTISOL",
                    "ValidCount=0 (All samples invalid)");
            return 0.0;
        }

        double avgCortisol = sumCortisol / validCount;
        double avgVoltage = sumVoltage / validCount;
        double avgYValue = avgVoltage * 1000.0;

        Log.d("ROLLING_CORTISOL",
                "ValidCount=" + validCount +
                        " avgVoltage=" + avgVoltage +
                        " avgYValue=" + avgYValue +
                        " Result=" + avgCortisol);

        return avgCortisol;
    }
    private void autoFinalizeAndShow() {
        try {
            if (dataSize == 0 || dataPointsavgd.isEmpty()) {
                Log.w(TAG, "No data to auto-save or show");
                return;
            }

            // calculate max values (same as in onClickShow)
            double max0 = findMaxbyAvg(dataPoints0);
            double max1 = findMaxbyAvg(dataPoints1);
            double finalCortisol = calculateFinalExperimentCortisol();
            double max2 = findMaxbyAvg(dataPoints2);

            // ensure experiment exists and update max
            if (currentExperimentId == -1) {
                long tsToSave = (sampleTimestamp > 0) ?
                        sampleTimestamp : System.currentTimeMillis();
                currentExperimentId = dbHelper.createBiomarkerExperiment(
                        userEmail,
                        "Bluetooth",
                        samplesPerSecond,
                        movingAvgValue,
                        durationOfExp,
                        finalCortisol,
                        dataSize,
                        tsToSave,
                        tubeId,             // from wizard Intent
                        cartridgeId
                );
                dbHelper.addBiomarkerDataBatch(
                        currentExperimentId,
                        xaxis, dataPoints0, dataPoints1, dataPoints2, dataPointsavgd
                );
                new SyncManager(getApplicationContext()).pushAllDirtyData(null);
            } else {
                dbHelper.updateBiomarkerExperimentMaxValue(currentExperimentId, finalCortisol);
            }

            // optional: also save CSV automatically
            try {
                SaveResult fileResult = saveToFileWithResult();
                Log.i(TAG, "Auto-saved CSV to " + fileResult.displayPath);
            } catch (Exception e) {
                Log.e(TAG, "Auto CSV save failed", e);
                // do not block results if file save fails
            }

            // open ResultActivity (based on onClickShow)
            String role = getIntent().getStringExtra("userrole");
            Intent resultActivity = new Intent(this, ResultActivity.class);
            resultActivity.putExtra("max_value_port0", max0);
            resultActivity.putExtra("max_value_port1", max1);
            resultActivity.putExtra("max_value_port2", max2);
            resultActivity.putExtra("final_cortisol", finalCortisol);
            resultActivity.putExtra("user_email", userEmail);
            resultActivity.putExtra("user_role", role);
            startActivity(resultActivity);

        } catch (Exception e) {
            Log.e(TAG, "Error in autoFinalizeAndShow", e);
            Toast.makeText(this, "Error finalizing data: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
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
        validCortisolList.clear();
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
    private void handleDeveloperPattern(MotionEvent event, int width, int height) {
        long now = System.currentTimeMillis();

        if (patternStartTime == 0L || now - patternStartTime > DEV_PATTERN_TIMEOUT_MS) {
            patternStartTime = now;
            devIndex = 0;
        }

        float x = event.getX();
        float y = event.getY();

        int region = getRegionForTap(x, y, width, height);

        Log.d(TAG, "region=" + region + " expected=" + devPattern[devIndex] + " index=" + devIndex);

        if (region == devPattern[devIndex]) {
            devIndex++;
            if (devIndex == devPattern.length) {
                devIndex = 0;
                patternStartTime = 0L;
                Log.d(TAG, "Developer pattern matched!");
                openDeveloperMode();
            }
        } else {
            patternStartTime = now;
            devIndex = (region == devPattern[0]) ? 1 : 0;
        }
    }


    private void openDeveloperMode() {
        Intent intent = new Intent(this, DeveloperModeActivity.class);
        startActivity(intent);
    }
    private void applyNewSettings(int newSps, int newAvg, double newDuration, boolean[] newPorts) {
        samplesPerSecond = newSps;
        movingAvgValue = newAvg;
        durationOfExp = newDuration;
        analogPort = newPorts;

        // Sync to developer mode static fields
        OutputBluetooth.devSamplesPerSecond = newSps;
        OutputBluetooth.devMovingAvgValue = newAvg;
        OutputBluetooth.devDurationOfExp = newDuration;
        OutputBluetooth.devAnalogPort = newPorts;

        saveSettings();
        // resetGraph();  // commented out since graph moved to DevMode
    }




    private int getRegionForTap(float x, float y, int width, int height) {
        boolean left = x < width / 2f;
        boolean top = y < height / 2f;

        if (top && left) return 0;       // TL
        if (top && !left) return 1;      // TR
        if (!top && !left) return 2;     // BR
        return 3;                        // BL
    }



    private void checkSampleLimit() {
        if (dataSize >= MAX_SAMPLES && started) {
            Log.i(TAG, "Reached max sample limit " + MAX_SAMPLES + " at sample " + dataSize);

            // stop collection
            sendTimer(false);
            started = false;

            // Mark progress bar as complete
            if (collectionProgressBar != null) collectionProgressBar.setProgress(100);
            if (tvProgressLabel != null)
                tvProgressLabel.setText("Collection complete!");

            // Reveal the Results button now that collection is done
            if (showButton != null) {
                showButton.setVisibility(View.VISIBLE);
                showButton.setEnabled(true);
                showButton.setAlpha(1.0f);
            }
            if (tvResultsInstruction != null) tvResultsInstruction.setVisibility(View.VISIBLE);

            // Let the user know
            Toast.makeText(this,
                    "Sample collection completed (" + dataSize + " samples). Tap Results to view.",
                    Toast.LENGTH_LONG).show();

            // Update metrics text in dev mode
            if (textViewAppend != null) {
                textViewAppend.setText(
                        "Data Length: " + dataSize +
                                "\nTime passed: " + String.format(Locale.getDefault(), "%.1f s", time) +
                                "\nSample collection completed"
                );
            }

            // Do NOT auto-open ResultActivity here.
            // You can still auto-save to DB if you want:
            saveToDatabaseHelperSafe();
        }
    }
    private void saveToDatabaseHelperSafe() {
        try {
            if (dataPointsavgd.isEmpty()) return;

            double max0 = findMaxbyAvg(dataPoints0);
            double max1 = findMaxbyAvg(dataPoints1);
            double finalCortisol = calculateFinalExperimentCortisol();
            double max2 = findMaxbyAvg(dataPoints2);

            long tsToSave = (sampleTimestamp > 0) ?
                    sampleTimestamp : System.currentTimeMillis();

            if (currentExperimentId == -1) {
                currentExperimentId = dbHelper.createBiomarkerExperiment(
                        userEmail,
                        "Bluetooth",
                        samplesPerSecond,
                        movingAvgValue,
                        durationOfExp,
                        finalCortisol,
                        dataSize,
                        tsToSave,
                        tubeId,             // from wizard Intent
                        cartridgeId
                );
                dbHelper.addBiomarkerDataBatch(
                        currentExperimentId,
                        xaxis, dataPoints0, dataPoints1, dataPoints2, dataPointsavgd
                );
            } else {
                dbHelper.updateBiomarkerExperimentMaxValue(currentExperimentId, finalCortisol);
            }
            new SyncManager(getApplicationContext()).pushAllDirtyData(null);
        } catch (Exception e) {
            Log.e(TAG, "Error saving to DB after completion", e);
        }
    }


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

            // BLE: disconnect, increment batch, then reconnect
            new Thread(() -> {
                try {
                    runOnUiThread(() -> {
                        if (bleManager != null) bleManager.disconnect();
                    });

                    // Wait for GATT to close cleanly
                    Thread.sleep(1000);

                    runOnUiThread(() -> {
                        currentBatch++;
                        synchronized (connectionLock) {
                            isConnecting = true;
                            connectionEstablished = false;
                        }
                        if (bleManager != null && deviceAddress != null && !deviceAddress.isEmpty()) {
                            bleManager.connect(deviceAddress);
                        } else {
                            isConnecting = false;
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error during batch transition", e);
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

    private void onBluetoothConnected() {
        connectionEstablished = true;

        String deviceName = (deviceAddress != null && !deviceAddress.isEmpty())
                ? "Optical_Detector_1 (" + deviceAddress + ")"
                : "Optical_Detector_1";
        devConnectedDeviceName = deviceName;

        if (textViewAppend != null) {
            textViewAppend.setText(
                    "Device connected: " + deviceName +
                            "\nData Length: " + dataSize +
                            "\nTime passed: " + String.format(Locale.getDefault(), "%.1f s", time)
            );
        }

        if (startButton != null) startButton.setEnabled(true);
        if (connectButton != null) connectButton.setEnabled(true);
    }
    private double calculateFinalExperimentCortisol() {
        if (validCortisolList == null || validCortisolList.isEmpty()) {
            Log.d(TAG, "calculateFinalExperimentCortisol: no valid cortisol samples");
            return 0.0;  // or -1.0 if you prefer a sentinel
        }

        double sum = 0.0;
        int count = 0;

        for (double c : validCortisolList) {
            if (!Double.isNaN(c) && !Double.isInfinite(c)) {
                sum += c;
                count++;
            }
        }

        if (count == 0) {
            Log.d(TAG, "calculateFinalExperimentCortisol: all values invalid");
            return 0.0;
        }

        double avg = sum / count;
        Log.d(TAG, "calculateFinalExperimentCortisol: count=" + count + " avg=" + avg);
        return avg;
    }





    // Helper method to check if we have a valid BLE connection
    private boolean hasValidConnection() {
        return connectionEstablished && bleManager != null && bleManager.isConnected();
    }

    // Helper method to log current button states for debugging
//    private void logButtonStates(String context) {
//        Log.d(TAG, "Button states (" + context + "): " +
//                "Start=" + startButton.isEnabled() +
//                ", Stop=" + stopButton.isEnabled() +
//                ", Connect=" + connectButton.isEnabled() +
//                ", Settings=" + settingsButton.isEnabled() +
//                ", Save=" + saveButton.isEnabled() +
//                ", Show=" + showButton.isEnabled());
//    }

    // Helper method to update Save and Show button states based on data availability
    private void updateDataDependentButtons() {
        boolean hasData = dataSize > 0 && !dataPointsavgd.isEmpty();
        // Only enable save/show buttons if we have data AND data collection is not active
        boolean shouldEnable = hasData && !started;

        // Only update if the state would change to avoid unnecessary UI updates
//        if (saveButton.isEnabled() != shouldEnable) {
//            saveButton.setEnabled(shouldEnable);
//            Log.d(TAG, "Save button " + (shouldEnable ? "enabled" : "disabled") +
//                    " (dataSize=" + dataSize + ", started=" + started + ")");
//        }
//
//        if (showButton.isEnabled() != shouldEnable) {
//            showButton.setEnabled(shouldEnable);
//            Log.d(TAG, "Show button " + (shouldEnable ? "enabled" : "disabled") +
//                    " (dataSize=" + dataSize + ", started=" + started + ")");
//        }

        if (stopButton != null) {
            stopButton.setEnabled(false);
        }
        if (saveButton != null) {
            saveButton.setEnabled(hasData);
        }
        // showButton visibility is controlled exclusively by checkSampleLimit()
        // — do not change it here.
    }
}