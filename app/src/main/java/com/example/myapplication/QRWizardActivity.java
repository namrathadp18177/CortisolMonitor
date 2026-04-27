package com.example.myapplication;

import android.app.TimePickerDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.MenuItem;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class QRWizardActivity extends AppCompatActivity {

    private static final int REQUEST_SCAN_TUBE = 201;
    private static final int REQUEST_SCAN_DEVICE_QR = 888;
    private static final int REQUEST_CONNECT_ANALYSER = 889;
    private static final int REQUEST_BLUETOOTH_DEVICE = 302;
    private static final int REQUEST_BLUETOOTH_PERMISSION = 301;

    private static final int STEP_SCAN_TUBE = 1;
    private static final int STEP_COLLECT_SALIVA = 2;
    private static final int STEP_MOVE_SALIVA = 3;
    private static final int STEP_SAMPLE_TIME = 4;
    private static final int STEP_CARTRIDGE_SCAN = 5;
    private static final int STEP_WAIT = 6;
    private static final int STEP_CONNECT_ANALYSER = 7;

    private int currentStep = STEP_SCAN_TUBE;

    private TextView tvStepIndicator, tvTitle, tvSubtitle, tvQrValue, tvTimer;
    private ImageView ivStepImage; // kept in Java for any legacy reference but no longer used in XML
    private android.widget.Button btnBack, btnPrimary, btnSecondary, btnSampleTime, btnScanQR, btnPreviousDevice;
    private android.widget.ProgressBar stepProgressBar;

    private String tubeId;
    private String cartridgeId;
    private boolean isSampleTimeSet = false;
    private CountDownTimer countDownTimer;
    private boolean timerRunning = false;
    private long timeRemainingMs = 5 * 60 * 1000L;
    private long totalTimerMs = 5 * 60 * 1000L;

    private FirebaseFirestore db;
    private String sampleDocId;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothDevice selectedBluetoothDevice;
    private String selectedDeviceAddress;
    private String selectedDeviceName;

    // Video background
    private TextureView videoBackground;
    private View videoScrim;

    // Image background (steps 2, 3, 4, 5)
    private ImageView imageBackground;

    // Step 6 animated hourglass
    private AnimatedHourglassView hourglassView;

    private MediaPlayer mediaPlayer;

    // ─────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_wizard);

        // Reset to firmware defaults at the start of every test.
        // Any user-customised values from a previous test are discarded.
        OutputBluetooth.devLedBrightness = 255;
        OutputBluetooth.devGainWiper     = 128;
        getSharedPreferences("dev_hw_prefs", MODE_PRIVATE)
                .edit().putBoolean("user_customised", false).apply();

        db = FirebaseFirestore.getInstance();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        setupToolbar();
        initViews();
        setupListeners();

        int startStep = getIntent().getIntExtra("start_step", 0);
        jumpToStep(startStep);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mediaPlayer != null) mediaPlayer.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseMediaPlayer();
        if (countDownTimer != null) countDownTimer.cancel();

        // Return LED and Gain to firmware defaults after the test ends.
        // This ensures any per-test customisation doesn't carry over.
        OutputBluetooth.devLedBrightness = 255;
        OutputBluetooth.devGainWiper     = 128;
        getSharedPreferences("dev_hw_prefs", MODE_PRIVATE)
                .edit().putBoolean("user_customised", false).apply();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ─────────────────────────────────────────────
    // Setup
    // ─────────────────────────────────────────────

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Sample Collection");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    private void initViews() {
        stepProgressBar   = findViewById(R.id.stepProgressBar);
        tvStepIndicator   = findViewById(R.id.tvStepIndicator);
        tvTitle           = findViewById(R.id.tvTitle);
        tvSubtitle        = findViewById(R.id.tvSubtitle);
        tvQrValue         = findViewById(R.id.tvQrValue);
        tvTimer           = findViewById(R.id.tvTimer);
        btnBack           = findViewById(R.id.btnBack);
        btnPrimary        = findViewById(R.id.btnPrimary);
        btnSecondary      = findViewById(R.id.btnSecondary);
        btnSampleTime     = findViewById(R.id.btnSampleTime);
        btnScanQR         = findViewById(R.id.btnScanQR);
        btnPreviousDevice = findViewById(R.id.btnPreviousDevice);
        videoBackground   = findViewById(R.id.videoBackground);
        videoScrim        = findViewById(R.id.videoScrim);
        imageBackground   = findViewById(R.id.imageBackground);
        // ivStepImage removed from XML; set to null safely
        ivStepImage       = null;
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> {
            if      (currentStep == STEP_SCAN_TUBE)        finish();
            else if (currentStep == STEP_COLLECT_SALIVA)   showStepScanTube();
            else if (currentStep == STEP_MOVE_SALIVA)      showStepCollectSaliva();
            else if (currentStep == STEP_SAMPLE_TIME)      showStepMoveSaliva();
            else if (currentStep == STEP_CARTRIDGE_SCAN)   showStepSampleTime();
            else if (currentStep == STEP_WAIT)             showStepCartridgeScan();
            else if (currentStep == STEP_CONNECT_ANALYSER) showStepWaitForResults();
            else finish();
        });

        btnPrimary.setOnClickListener(v -> {
            if (currentStep == STEP_SCAN_TUBE || currentStep == STEP_CARTRIDGE_SCAN) {
                startTubeScan();
            } else if (currentStep == STEP_COLLECT_SALIVA || currentStep == STEP_WAIT) {
                toggleTimer();
            } else if (currentStep == STEP_CONNECT_ANALYSER) {
                if (selectedDeviceAddress != null && !selectedDeviceAddress.trim().isEmpty()) {
                    if (selectedBluetoothDevice == null && bluetoothAdapter != null) {
                        try {
                            selectedBluetoothDevice = bluetoothAdapter.getRemoteDevice(selectedDeviceAddress);
                        } catch (Exception e) {
                            Toast.makeText(this, "Invalid device address", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    connectInBackground(selectedBluetoothDevice);
                } else {
                    showBluetoothDevicePicker();
                }
            }
        });

        btnSecondary.setOnClickListener(v -> {
            if      (currentStep == STEP_SCAN_TUBE)        { if (tubeId == null || tubeId.isEmpty()) tubeId = "DEMO-TUBE-001"; createSampleInCloud(tubeId); showStepCollectSaliva(); }
            else if (currentStep == STEP_COLLECT_SALIVA)   showStepMoveSaliva();
            else if (currentStep == STEP_MOVE_SALIVA)      showStepSampleTime();
            else if (currentStep == STEP_SAMPLE_TIME)      { if (!isSampleTimeSet) { Toast.makeText(this, "Please set sample time first", Toast.LENGTH_SHORT).show(); return; } showStepCartridgeScan(); }
            else if (currentStep == STEP_CARTRIDGE_SCAN)   showStepWaitForResults();
            else if (currentStep == STEP_WAIT)             showStepInsertAnalyser();
            else if (currentStep == STEP_CONNECT_ANALYSER) {
                if (selectedDeviceAddress == null || selectedDeviceAddress.trim().isEmpty()) {
                    Toast.makeText(this, "Please connect to the analyzer first", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent intent = new Intent(this, OutputBluetooth.class);
                intent.putExtra("tube_id",       tubeId);
                intent.putExtra("cartridge_id",  cartridgeId);
                intent.putExtra("sample_time",   btnSampleTime.getText().toString());
                intent.putExtra("user_role",     getIntent().getStringExtra("user_role"));
                intent.putExtra("user_email",    getIntent().getStringExtra("user_email"));
                intent.putExtra("patient_email", getIntent().getStringExtra("user_email"));
                intent.putExtra("device_address",selectedDeviceAddress);
                intent.putExtra("device_name",   selectedDeviceName);
                intent.putExtra("connect_only",  false);
                startActivity(intent);
            }
        });

        btnScanQR.setOnClickListener(v -> {
            if (currentStep == STEP_CONNECT_ANALYSER) {
                Intent intent = new Intent(this, QRScanActivity.class);
                intent.putExtra("mode", "DEVICE");
                startActivityForResult(intent, REQUEST_SCAN_DEVICE_QR);
            }
        });

        btnSampleTime.setOnClickListener(v -> openTimePicker());

        btnPreviousDevice.setOnClickListener(v -> {
            android.content.SharedPreferences blePrefs = getSharedPreferences("ble_prefs", MODE_PRIVATE);
            String savedAddress = blePrefs.getString("last_device_address", null);
            String savedName    = blePrefs.getString("last_device_name",    "OpticalDetector1");
            if (savedAddress == null || savedAddress.trim().isEmpty()) {
                Toast.makeText(this, "No previously connected device found", Toast.LENGTH_SHORT).show();
                return;
            }
            selectedDeviceAddress = savedAddress;
            selectedDeviceName    = savedName;
            try {
                selectedBluetoothDevice = bluetoothAdapter.getRemoteDevice(savedAddress);
                connectInBackground(selectedBluetoothDevice);
            } catch (Exception e) {
                Toast.makeText(this, "Could not reach saved device, try scanning again", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ─────────────────────────────────────────────
    // Jump-to-step deep-link entry
    // ─────────────────────────────────────────────

    private void jumpToStep(int startStep) {
        android.util.Log.d("STEP_DEBUG", "QRWizard jumpToStep called with startStep=" + startStep);
        switch (startStep) {
            case 1: showStepCollectSaliva();  updateStepProgress(2); break;
            case 2: showStepCartridgeScan();  updateStepProgress(5); break;
            case 3: showStepInsertAnalyser(); updateStepProgress(7); break;
            case 4: showStepWaitForResults(); updateStepProgress(6); break;
            default: showStepScanTube();      updateStepProgress(1); break;
        }
    }

    // ─────────────────────────────────────────────
    // Step screens
    // ─────────────────────────────────────────────

    private void showStepScanTube() {
        hideVideoBackground();
        resetPrimaryButtonPosition();
        currentStep = STEP_SCAN_TUBE;
        tvStepIndicator.setText("Step 1 of 7");
        tvTitle.setText("Scan Tube QR");
        tvSubtitle.setText("Scan the QR code printed on the tube.");
        tvTimer.setVisibility(View.GONE);
        tvQrValue.setVisibility(View.GONE);
        btnSampleTime.setVisibility(View.GONE);
        btnPrimary.setVisibility(View.VISIBLE);
        btnPrimary.setText("Scan QR Code");
        btnPrimary.setEnabled(true);
        btnScanQR.setVisibility(View.GONE);
        btnPreviousDevice.setVisibility(View.GONE);
        btnBack.setText("Back");
        btnSecondary.setText("Next");
        btnSecondary.setEnabled(true);
        updateStepProgress(1);
    }

    private void showStepCollectSaliva() {
        resetPrimaryButtonPosition();
        currentStep = STEP_COLLECT_SALIVA;
        tvStepIndicator.setText("Step 2 of 7");
        tvTitle.setText("Collect Saliva");
        tvSubtitle.setText("Put the swab inside your mouth. Keep it there for 5 full minutes until it becomes very wet with saliva. Try not to talk during this time.");

        // ✅ KEY CHANGE: full-screen background image instead of inline ivStepImage
        showImageBackground(R.drawable.ic_step_collection);

        tvTimer.setVisibility(View.VISIBLE);
        tvTimer.setText("05:00");
        timeRemainingMs = 5 * 60 * 1000L;
        totalTimerMs    = 5 * 60 * 1000L;
        timerRunning    = false;
        tvQrValue.setVisibility(View.GONE);
        btnSampleTime.setVisibility(View.GONE);
        btnPrimary.setVisibility(View.VISIBLE);
        btnPrimary.setEnabled(true);
        btnPrimary.setText("Start Timer");
        btnScanQR.setVisibility(View.GONE);
        btnPreviousDevice.setVisibility(View.GONE);
        btnBack.setText("Back");
        btnSecondary.setText("Next");
        btnSecondary.setEnabled(true);
        updateStepProgress(2);
    }

    private void showStepMoveSaliva() {
        resetPrimaryButtonPosition();
        currentStep = STEP_MOVE_SALIVA;
        tvStepIndicator.setText("Step 3 of 7");
        tvTitle.setText("Move Saliva to Tube");
        tvSubtitle.setText("Place the swab into the tube. Push it down slowly and firmly as shown to release the saliva into the tube.");
        showImageBackground(R.drawable.ic_step_extraction);
        tvTimer.setVisibility(View.GONE);
        tvQrValue.setVisibility(View.GONE);
        btnSampleTime.setVisibility(View.GONE);
        if (countDownTimer != null) countDownTimer.cancel();
        timerRunning = false;
        btnPrimary.setVisibility(View.GONE);
        btnScanQR.setVisibility(View.GONE);
        btnPreviousDevice.setVisibility(View.GONE);
        btnBack.setText("Back");
        btnSecondary.setText("Next");
        btnSecondary.setEnabled(true);
        updateStepProgress(3);
    }

    private void showStepSampleTime() {
        hideVideoBackground();
        resetPrimaryButtonPosition();
        currentStep = STEP_SAMPLE_TIME;
        tvStepIndicator.setText("Step 4 of 7");
        tvTitle.setText("Enter Sample Time");
        tvSubtitle.setText("Before proceeding, please enter the sample collection time.");
        showImageBackground(R.drawable.ic_step_storage);
        btnSampleTime.setVisibility(View.VISIBLE);
        btnSampleTime.setText("Set Sample Collection Time");
        LinearLayout parent = findViewById(R.id.contentLayout);
        parent.removeView(btnSampleTime);
        int index = parent.indexOfChild(tvSubtitle);
        parent.addView(btnSampleTime, index + 1);
        tvQrValue.setVisibility(View.VISIBLE);
        tvQrValue.setText("Store Sample (Optional)\nRemove the tube carefully.\nClose it tightly with the cap.\nStore in freezer at -20°C.");
        tvQrValue.setTextColor(Color.WHITE);
        tvTimer.setVisibility(View.GONE);
        btnPrimary.setVisibility(View.GONE);
        btnScanQR.setVisibility(View.GONE);
        btnPreviousDevice.setVisibility(View.GONE);
        btnBack.setText("Store It");
        btnSecondary.setText("Next");
        isSampleTimeSet = false;
        updateStepProgress(4);
    }

    private void showStepCartridgeScan() {
        hideVideoBackground();
        resetPrimaryButtonPosition();
        currentStep = STEP_CARTRIDGE_SCAN;
        tvStepIndicator.setText("Step 5 of 7");
        tvTitle.setText("Scan Cartridge QR & Add Your Sample");
        tvSubtitle.setText("Scan the QR code printed on the cartridge.");
        showImageBackground(R.drawable.ic_step_loading);
        tvQrValue.setVisibility(View.VISIBLE);
        tvQrValue.setText("Add Drops to Test Cartridge as shown.\nTurn the tube upside down and gently squeeze 4 to 5 drops onto the Cartridge.");
        tvQrValue.setTextColor(Color.WHITE);
        tvQrValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tvSubtitle.getTextSize());
        tvQrValue.setTypeface(tvSubtitle.getTypeface());
        tvTimer.setVisibility(View.GONE);
        btnSampleTime.setVisibility(View.GONE);
        btnPrimary.setVisibility(View.VISIBLE);
        btnPrimary.setEnabled(true);
        btnPrimary.setText("Scan Cartridge QR");
        LinearLayout parent = findViewById(R.id.contentLayout);
        parent.removeView(btnPrimary);
        int index = parent.indexOfChild(tvSubtitle);
        parent.addView(btnPrimary, index + 1);
        btnScanQR.setVisibility(View.GONE);
        btnPreviousDevice.setVisibility(View.GONE);
        btnBack.setText("Back");
        btnSecondary.setText("Next");
        btnSecondary.setEnabled(true);
        updateStepProgress(5);
    }

    private void showStepWaitForResults() {
        resetPrimaryButtonPosition();
        currentStep = STEP_WAIT;
        tvStepIndicator.setText("Step 6 of 7");
        tvTitle.setText("Wait for Results");
        tvSubtitle.setText("Place the test strip on a flat surface and do not move it for 20 minutes while the test processes.");

        if (videoBackground != null) videoBackground.setVisibility(View.GONE);
        if (videoScrim != null)      videoScrim.setVisibility(View.GONE);
        if (imageBackground != null) imageBackground.setVisibility(View.GONE);
        releaseMediaPlayer();

        LinearLayout contentLayout = findViewById(R.id.contentLayout);
        hourglassView = new AnimatedHourglassView(this);
        int sizePx = (int) (200 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams hgParams = new LinearLayout.LayoutParams(sizePx, sizePx);
        hgParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        hgParams.topMargin    = (int) (20 * getResources().getDisplayMetrics().density);
        hgParams.bottomMargin = (int) (8  * getResources().getDisplayMetrics().density);
        hourglassView.setLayoutParams(hgParams);
        hourglassView.setFillLevel(1f);
        int insertIdx = contentLayout.indexOfChild(tvSubtitle) + 1;
        contentLayout.addView(hourglassView, insertIdx);

        // Restore default text colors — no scrim active
        tvStepIndicator.setTextColor(getResources().getColor(R.color.text_secondary, null));
        tvTitle.setTextColor(getResources().getColor(R.color.primary, null));
        tvSubtitle.setTextColor(getResources().getColor(R.color.text_secondary, null));
        LinearLayout bottomButtons = findViewById(R.id.bottomButtons);
        if (bottomButtons != null) bottomButtons.setBackgroundColor(getResources().getColor(R.color.background, null));

        tvTimer.setVisibility(View.VISIBLE);
        timeRemainingMs = 20 * 60 * 1000L;
        totalTimerMs    = 20 * 60 * 1000L;
        tvTimer.setText("20:00");
        if (countDownTimer != null) countDownTimer.cancel();
        timerRunning = false;
        tvQrValue.setVisibility(View.GONE);
        btnSampleTime.setVisibility(View.GONE);
        btnPrimary.setVisibility(View.VISIBLE);
        btnPrimary.setEnabled(true);
        btnPrimary.setText("Start Timer");
        btnSecondary.setText("Skip");
        btnSecondary.setEnabled(true);
        btnScanQR.setVisibility(View.GONE);
        btnPreviousDevice.setVisibility(View.GONE);
        btnBack.setText("Back");
        updateStepProgress(6);
    }

    private void showStepInsertAnalyser() {
        resetPrimaryButtonPosition();
        currentStep = STEP_CONNECT_ANALYSER;
        tvStepIndicator.setText("Step 7 of 7");
        tvTitle.setText("Connect to Analyzer");
        tvSubtitle.setText("Please turn on your analyzer and insert your strip.\nPress connect button to connect your analyzer, or scan QR if you have a QR ID.");
        showVideoBackground(R.raw.video_step7_analyser);
        tvTimer.setVisibility(View.GONE);
        tvQrValue.setVisibility(View.GONE);
        btnSampleTime.setVisibility(View.GONE);
        btnPrimary.setVisibility(View.VISIBLE);
        btnPrimary.setEnabled(true);
        btnPrimary.setText("Connect");
        btnScanQR.setVisibility(View.VISIBLE);
        btnScanQR.setEnabled(true);
        btnSecondary.setVisibility(View.VISIBLE);
        btnSecondary.setText("Next");
        btnSecondary.setEnabled(false);
        selectedBluetoothDevice  = null;
        selectedDeviceAddress    = null;
        selectedDeviceName       = null;
        android.content.SharedPreferences blePrefs = getSharedPreferences("ble_prefs", MODE_PRIVATE);
        String lastAddress = blePrefs.getString("last_device_address", null);
        String lastName    = blePrefs.getString("last_device_name",    "OpticalDetector1");
        if (lastAddress != null && !lastAddress.trim().isEmpty()) {
            btnPreviousDevice.setVisibility(View.VISIBLE);
            btnPreviousDevice.setText("Quick-connect: " + lastName);
        } else {
            btnPreviousDevice.setVisibility(View.GONE);
        }
        btnBack.setText("Back");
        updateStepProgress(7);
    }

    // ─────────────────────────────────────────────
    // Video background helpers
    // ─────────────────────────────────────────────

    private void showVideoBackground(int rawResId) {
        if (videoBackground == null || videoScrim == null) return;
        releaseMediaPlayer();
        if (imageBackground != null) imageBackground.setVisibility(View.GONE);
        videoBackground.setVisibility(View.VISIBLE);
        videoScrim.setVisibility(View.VISIBLE);
        tvStepIndicator.setTextColor(Color.WHITE);
        tvTitle.setTextColor(Color.WHITE);
        tvSubtitle.setTextColor(Color.WHITE);
        LinearLayout bottomButtons = findViewById(R.id.bottomButtons);
        if (bottomButtons != null) bottomButtons.setBackgroundColor(Color.TRANSPARENT);
        if (videoBackground.isAvailable()) {
            startVideo(videoBackground, rawResId);
        } else {
            videoBackground.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int w, int h) { startVideo(videoBackground, rawResId); }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {}
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { releaseMediaPlayer(); return true; }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}
            });
        }
    }

    private void hideVideoBackground() {
        if (videoBackground != null) videoBackground.setVisibility(View.GONE);
        if (videoScrim != null)      videoScrim.setVisibility(View.GONE);
        if (imageBackground != null) imageBackground.setVisibility(View.GONE);
        releaseMediaPlayer();
        LinearLayout bottomButtons = findViewById(R.id.bottomButtons);
        if (bottomButtons != null) bottomButtons.setBackgroundColor(getResources().getColor(R.color.background, null));
        if (tvStepIndicator != null) tvStepIndicator.setTextColor(getResources().getColor(R.color.text_secondary, null));
        if (tvTitle != null)         tvTitle.setTextColor(getResources().getColor(R.color.primary, null));
        if (tvSubtitle != null)      tvSubtitle.setTextColor(getResources().getColor(R.color.text_secondary, null));
    }

    // ─────────────────────────────────────────────
    // Image background helpers (steps 2, 3, 4, 5)
    // ─────────────────────────────────────────────

    private void showImageBackground(int drawableResId) {
        if (imageBackground == null) return;
        if (videoBackground != null) videoBackground.setVisibility(View.GONE);
        releaseMediaPlayer();
        imageBackground.setImageResource(drawableResId);
        imageBackground.setVisibility(View.VISIBLE);
        if (videoScrim != null) videoScrim.setVisibility(View.VISIBLE);
        tvStepIndicator.setTextColor(Color.WHITE);
        tvTitle.setTextColor(Color.WHITE);
        tvSubtitle.setTextColor(Color.WHITE);
        LinearLayout bottomButtons = findViewById(R.id.bottomButtons);
        if (bottomButtons != null) bottomButtons.setBackgroundColor(Color.TRANSPARENT);
    }

    private void startVideo(TextureView tv, int rawResId) {
        releaseMediaPlayer();
        try {
            AssetFileDescriptor afd = getResources().openRawResourceFd(rawResId);
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mediaPlayer.setSurface(new Surface(tv.getSurfaceTexture()));
            mediaPlayer.setLooping(true);
            mediaPlayer.setVolume(0f, 0f);
            mediaPlayer.setOnPreparedListener(mp -> {
                int videoWidth  = mp.getVideoWidth();
                int videoHeight = mp.getVideoHeight();
                int viewWidth   = tv.getWidth();
                int viewHeight  = tv.getHeight();
                float scaleX = 1f, scaleY = 1f;
                if (videoWidth > 0 && videoHeight > 0) {
                    float videoAspect = (float) videoWidth / videoHeight;
                    float viewAspect  = (float) viewWidth  / viewHeight;
                    if (videoAspect > viewAspect) scaleX = videoAspect / viewAspect;
                    else                          scaleY = viewAspect  / videoAspect;
                }
                Matrix matrix = new Matrix();
                matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f);
                tv.setTransform(matrix);
                mp.start();
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            android.util.Log.e("VideoBackground", "Error starting video: " + e.getMessage());
        }
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try { if (mediaPlayer.isPlaying()) mediaPlayer.stop(); } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    // ─────────────────────────────────────────────
    // Bluetooth helpers
    // ─────────────────────────────────────────────

    private void showBluetoothDevicePicker() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth first", Toast.LENGTH_SHORT).show();
            return;
        }
        android.app.ProgressDialog scanningDialog = new android.app.ProgressDialog(this);
        scanningDialog.setMessage("Scanning for devices...");
        scanningDialog.setCancelable(true);
        scanningDialog.show();

        final ArrayList<BluetoothDevice> foundDevices = new ArrayList<>();
        final ArrayList<String>          foundNames   = new ArrayList<>();

        android.bluetooth.le.BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        android.bluetooth.le.ScanSettings scanSettings = new android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build();

        android.bluetooth.le.ScanCallback scanCb = new android.bluetooth.le.ScanCallback() {
            @Override
            public void onScanResult(int callbackType, android.bluetooth.le.ScanResult result) {
                BluetoothDevice device = result.getDevice();
                if (device == null) return;
                for (BluetoothDevice d : foundDevices) if (d.getAddress().equals(device.getAddress())) return;
                String name = null;
                try { name = device.getName(); } catch (SecurityException ignored) {}
                if (name == null || !name.toLowerCase().contains("opticaldetector")) return;
                foundDevices.add(device);
                foundNames.add(name);
            }
        };

        try { scanner.startScan(null, scanSettings, scanCb); }
        catch (SecurityException e) {
            scanningDialog.dismiss();
            Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_SHORT).show();
            return;
        }

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try { scanner.stopScan(scanCb); } catch (Exception ignored) {}
            scanningDialog.dismiss();
            if (foundDevices.isEmpty()) {
                Toast.makeText(this, "No OpticalDetector devices found nearby. Make sure the analyzer is on.", Toast.LENGTH_LONG).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Select Device")
                    .setItems(foundNames.toArray(new String[0]), (dialog, which) -> {
                        BluetoothDevice picked = foundDevices.get(which);
                        selectedDeviceAddress  = picked.getAddress();
                        selectedDeviceName     = foundNames.get(which);
                        connectInBackground(picked);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }, 4000);
    }

    private void connectInBackground(BluetoothDevice device) {
        if (device == null) {
            Toast.makeText(this, "Device not found. Please try again.", Toast.LENGTH_SHORT).show();
            updateStep7FailedState();
            return;
        }
        tvSubtitle.setText("Connecting to " + selectedDeviceName + "... wait.");
        btnPrimary.setVisibility(View.GONE);
        btnScanQR.setVisibility(View.GONE);
        btnPreviousDevice.setVisibility(View.GONE);
        btnPrimary.setEnabled(false);
        btnScanQR.setEnabled(false);
        btnSecondary.setEnabled(false);

        BluetoothManager bleManager = BluetoothManager.getInstance(this);
        bleManager.setCallback(new BluetoothManager.BleCallback() {
            @Override public void onConnected() {
                android.util.Log.i("STEP7", "BLE connected to " + device.getAddress());
                runOnUiThread(() -> updateStep7ConnectedState(selectedDeviceName, selectedDeviceAddress));
            }
            @Override public void onDisconnected() {
                android.util.Log.w("STEP7", "BLE disconnected during wizard");
                runOnUiThread(() -> updateStep7FailedState());
            }
            @Override public void onOpticalData(byte[] packet) {}
            @Override public void onStatusData(byte[] status) {}
        });
        bleManager.connect(device.getAddress());
    }

    private void updateStep7ConnectedState(String deviceName, String deviceAddress) {
        getSharedPreferences("ble_prefs", MODE_PRIVATE).edit()
                .putString("last_device_address", deviceAddress)
                .putString("last_device_name",    deviceName)
                .apply();
        tvTitle.setText("Insert Cartridge");
        tvSubtitle.setText("Connected to " + deviceName + ".\nInsert the test strip cartridge to the analyzer as shown in the picture below.");
        btnPrimary.setVisibility(View.GONE);
        btnScanQR.setVisibility(View.GONE);
        btnPreviousDevice.setVisibility(View.GONE);
        btnSecondary.setEnabled(true);
        btnSecondary.setText("Next");
        Toast.makeText(this, "Connected to " + deviceName, Toast.LENGTH_SHORT).show();
    }

    private void updateStep7FailedState() {
        tvSubtitle.setText("Connection failed.\nPlease make sure the analyzer is turned on and paired, then try again.");
        btnPrimary.setVisibility(View.VISIBLE);
        btnPrimary.setEnabled(true);
        btnPrimary.setText("Connect");
        btnScanQR.setVisibility(View.VISIBLE);
        btnScanQR.setEnabled(true);
        android.content.SharedPreferences blePrefs = getSharedPreferences("ble_prefs", MODE_PRIVATE);
        String lastAddress = blePrefs.getString("last_device_address", null);
        if (lastAddress != null && !lastAddress.trim().isEmpty()) btnPreviousDevice.setVisibility(View.VISIBLE);
        btnSecondary.setEnabled(false);
        Toast.makeText(this, "Failed to connect to analyzer", Toast.LENGTH_SHORT).show();
    }

    // ─────────────────────────────────────────────
    // QR scan helpers
    // ─────────────────────────────────────────────

    private void startTubeScan() {
        Intent intent = new Intent(this, QRScanActivity.class);
        intent.putExtra("mode", currentStep == STEP_SCAN_TUBE ? "TUBE" : "CARTRIDGE");
        startActivityForResult(intent, REQUEST_SCAN_TUBE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQUEST_SCAN_TUBE) {
            String scanned = data.getStringExtra("scanned_value");
            if (scanned == null || scanned.isEmpty()) return;
            if (currentStep == STEP_SCAN_TUBE) {
                tubeId = scanned;
                tvQrValue.setVisibility(View.VISIBLE);
                tvQrValue.setText("Tube ID: " + tubeId);
                createSampleInCloud(tubeId);
                showStepCollectSaliva();
            } else if (currentStep == STEP_CARTRIDGE_SCAN) {
                cartridgeId = scanned;
                tvQrValue.setVisibility(View.VISIBLE);
                tvQrValue.setText("Cartridge ID: " + cartridgeId);
                saveCartridgeToCloud(cartridgeId);
            }
        } else if (requestCode == REQUEST_SCAN_DEVICE_QR) {
            String scanned = data.getStringExtra("scanned_value");
            if (scanned == null || scanned.isEmpty()) return;
            selectedDeviceAddress = scanned;
            selectedDeviceName    = "Scanned Device";
            try {
                selectedBluetoothDevice = bluetoothAdapter.getRemoteDevice(scanned);
                connectInBackground(selectedBluetoothDevice);
            } catch (Exception e) {
                Toast.makeText(this, "Invalid device QR code", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ─────────────────────────────────────────────
    // Timer
    // ─────────────────────────────────────────────

    private void toggleTimer() {
        if (!timerRunning) {
            countDownTimer = new CountDownTimer(timeRemainingMs, 1000) {
                @Override
                public void onTick(long ms) {
                    timeRemainingMs = ms;
                    long sec = ms / 1000;
                    tvTimer.setText(String.format(Locale.getDefault(), "%02d:%02d", sec / 60, sec % 60));
                    if (currentStep == STEP_WAIT && hourglassView != null)
                        hourglassView.setFillLevel((float) ms / totalTimerMs);
                }
                @Override
                public void onFinish() {
                    timeRemainingMs = 0;
                    timerRunning    = false;
                    tvTimer.setText("00:00");
                    btnPrimary.setText("Completed");
                    if (currentStep == STEP_WAIT && hourglassView != null) {
                        hourglassView.setFillLevel(0f);
                    }
                }
            }.start();
            timerRunning = true;
            btnPrimary.setText("Pause");
        } else {
            if (countDownTimer != null) countDownTimer.cancel();
            timerRunning = false;
            btnPrimary.setText("Resume");
        }
    }

    // ─────────────────────────────────────────────
    // Cloud helpers
    // ─────────────────────────────────────────────

    private void createSampleInCloud(String tubeId) {
        String uid = FirebaseAuthHelper.getCurrentUid();
        if (uid == null) return;
        Map<String, Object> sample = new HashMap<>();
        sample.put("tube_id",    tubeId);
        sample.put("created_at", Timestamp.now());
        db.collection("users").document(uid).collection("samples")
                .add(sample)
                .addOnSuccessListener(doc -> sampleDocId = doc.getId());
    }

    private void saveCartridgeToCloud(String cartridgeId) {
        String uid = FirebaseAuthHelper.getCurrentUid();
        if (uid == null || sampleDocId == null) return;
        Map<String, Object> update = new HashMap<>();
        update.put("cartridge_id", cartridgeId);
        db.collection("users").document(uid).collection("samples")
                .document(sampleDocId).update(update);
    }

    private void updateSampleTimeInCloud(String time) {
        String uid = FirebaseAuthHelper.getCurrentUid();
        if (uid == null || sampleDocId == null) return;
        db.collection("users").document(uid).collection("samples")
                .document(sampleDocId).update("sample_time", time);
    }

    // ─────────────────────────────────────────────
    // Misc helpers
    // ─────────────────────────────────────────────

    private void openTimePicker() {
        Calendar c = Calendar.getInstance();
        new TimePickerDialog(this, (view, h, m) -> {
            String time = String.format(Locale.getDefault(), "%02d:%02d", h, m);
            btnSampleTime.setText("Time: " + time);
            updateSampleTimeInCloud(time);
            isSampleTimeSet = true;
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show();
    }

    private void resetPrimaryButtonPosition() {
        LinearLayout parent = findViewById(R.id.contentLayout);
        if (hourglassView != null) {
            parent.removeView(hourglassView);
            hourglassView = null;
        }
        parent.removeView(btnPrimary);
        parent.addView(btnPrimary);
        btnSecondary.setText("Next");
    }

    private void updateStepProgress(int step) {
        if (stepProgressBar != null) stepProgressBar.setProgress(step);
        if (tvStepIndicator != null) tvStepIndicator.setText("Step " + step + " of 7");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Nested view: circular progress ring used by Step 6 (20-minute wait).
    // Drains a green arc clockwise from the top as time passes.
    // Previously lived in its own AnimatedHourglassView.java; inlined here
    // because it is only used by this activity.
    // ─────────────────────────────────────────────────────────────────────────
    private static class AnimatedHourglassView extends View {

        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arcPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arcRect    = new RectF();

        private float fillLevel = 1f;   // 1.0 = full, 0.0 = empty

        AnimatedHourglassView(Context context) {
            super(context);
            setBackgroundColor(Color.TRANSPARENT);

            trackPaint.setStyle(Paint.Style.STROKE);
            trackPaint.setColor(Color.parseColor("#C8E6C9"));   // light green track
            trackPaint.setStrokeCap(Paint.Cap.ROUND);

            arcPaint.setStyle(Paint.Style.STROKE);
            arcPaint.setColor(Color.parseColor("#27AE60"));     // solid green arc
            arcPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        /** Call every second from onTick: level = msRemaining / totalMs */
        void setFillLevel(float level) {
            fillLevel = Math.max(0f, Math.min(1f, level));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) return;

            float cx = w / 2f;
            float cy = h / 2f;
            float r  = Math.min(cx, cy) * 0.85f;
            float sw = r * 0.14f;

            trackPaint.setStrokeWidth(sw);
            arcPaint.setStrokeWidth(sw);

            // Full 360° background track
            canvas.drawCircle(cx, cy, r, trackPaint);

            // Green arc draining clockwise from 12 o'clock
            float sweep = 360f * fillLevel;
            if (sweep > 0f) {
                arcRect.set(cx - r, cy - r, cx + r, cy + r);
                canvas.drawArc(arcRect, -90f, sweep, false, arcPaint);
            }
        }
    }
}
