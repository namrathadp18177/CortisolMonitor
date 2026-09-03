package com.example.myapplication;

import android.app.TimePickerDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Matrix;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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

    private static final int REQUEST_SCAN_TUBE      = 201;
    private static final int REQUEST_SCAN_DEVICE_QR = 888;

    private static final int STEP_SCAN_TUBE        = 1;
    private static final int STEP_COLLECT_SALIVA   = 2;
    private static final int STEP_MOVE_SALIVA      = 3;
    private static final int STEP_SAMPLE_TIME      = 4;
    private static final int STEP_CARTRIDGE_SCAN   = 5;
    private static final int STEP_WAIT             = 6;
    private static final int STEP_CONNECT_ANALYSER = 7;

    private int currentStep = STEP_SCAN_TUBE;

    // ── UI refs ──────────────────────────────────────────────────────────────
    private TextView tvStepIndicator, tvTitle, tvSubtitle, tvQrValue, tvTimer;
    private android.widget.Button btnBack, btnPrimary, btnSecondary,
            btnSampleTime, btnScanQR, btnPreviousDevice, btnScanDevices;
    private android.widget.ProgressBar stepProgressBar;

    // ── Carousel ─────────────────────────────────────────────────────────────
    private HorizontalScrollView carouselScroll;
    private LinearLayout         carouselTrack;
    private LinearLayout         carouselDots;
    private int   carouselCurrentIndex = 0;

    // ── Video ────────────────────────────────────────────────────────────────
    private TextureView videoBackground;
    private View        videoScrim;
    private MediaPlayer mediaPlayer;

    // ── Data ─────────────────────────────────────────────────────────────────
    private String  tubeId, cartridgeId, sampleDocId;
    private boolean isSampleTimeSet = false;
    private CountDownTimer countDownTimer;
    private boolean timerRunning    = false;
    private long    timeRemainingMs = 5 * 60 * 1000L;
    private long    totalTimerMs    = 5 * 60 * 1000L;

    private FirebaseFirestore db;
    private BluetoothAdapter  bluetoothAdapter;
    private BluetoothDevice   selectedBluetoothDevice;
    private String            selectedDeviceAddress, selectedDeviceName;

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_wizard);

        OutputBluetooth.devLedBrightness = 255;
        OutputBluetooth.devGainWiper     = 128;
        getSharedPreferences("dev_hw_prefs", MODE_PRIVATE)
                .edit().putBoolean("user_customised", false).apply();

        db               = FirebaseFirestore.getInstance();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        setupToolbar();
        initViews();
        setupListeners();

        int startStep = getIntent().getIntExtra("start_step", 0);
        jumpToStep(startStep);
    }

    @Override protected void onPause()  { super.onPause();  if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause(); }
    @Override protected void onResume() { super.onResume(); if (mediaPlayer != null) mediaPlayer.start(); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseMediaPlayer();
        if (countDownTimer != null) countDownTimer.cancel();
        OutputBluetooth.devLedBrightness = 255;
        OutputBluetooth.devGainWiper     = 128;
        getSharedPreferences("dev_hw_prefs", MODE_PRIVATE)
                .edit().putBoolean("user_customised", false).apply();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup
    // ─────────────────────────────────────────────────────────────────────────

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
        btnScanDevices    = findViewById(R.id.btnScanDevices);
        videoBackground   = findViewById(R.id.videoBackground);
        videoScrim        = findViewById(R.id.videoScrim);
        carouselScroll    = findViewById(R.id.carouselScroll);
        carouselTrack     = findViewById(R.id.carouselTrack);
        carouselDots      = findViewById(R.id.carouselDots);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Listeners
    // ─────────────────────────────────────────────────────────────────────────

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
                        try { selectedBluetoothDevice = bluetoothAdapter.getRemoteDevice(selectedDeviceAddress); }
                        catch (Exception e) { Toast.makeText(this, "Invalid device address", Toast.LENGTH_SHORT).show(); return; }
                    }
                    connectInBackground(selectedBluetoothDevice);
                } else {
                    showBluetoothDevicePicker();
                }
            }
        });

        btnSecondary.setOnClickListener(v -> {
            if (currentStep == STEP_SCAN_TUBE) {
                if (tubeId == null || tubeId.isEmpty()) tubeId = "DEMO-TUBE-001";
                createSampleInCloud(tubeId);
                showStepCollectSaliva();
            } else if (currentStep == STEP_COLLECT_SALIVA)  { showStepMoveSaliva(); }
            else if (currentStep == STEP_MOVE_SALIVA)        { showStepSampleTime(); }
            else if (currentStep == STEP_SAMPLE_TIME) {
                if (!isSampleTimeSet) { Toast.makeText(this, "Please set sample time first", Toast.LENGTH_SHORT).show(); return; }
                showStepCartridgeScan();
            } else if (currentStep == STEP_CARTRIDGE_SCAN)   { showStepWaitForResults(); }
            else if (currentStep == STEP_WAIT)               { showStepInsertAnalyser(); }
            else if (currentStep == STEP_CONNECT_ANALYSER) {
                if (selectedDeviceAddress == null || selectedDeviceAddress.trim().isEmpty()) {
                    Toast.makeText(this, "Please connect to the analyzer first", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent intent = new Intent(this, OutputBluetooth.class);
                intent.putExtra("tube_id",        tubeId);
                intent.putExtra("cartridge_id",   cartridgeId);
                intent.putExtra("sample_time",    btnSampleTime.getText().toString());
                intent.putExtra("user_role",      getIntent().getStringExtra("user_role"));
                intent.putExtra("user_email",     getIntent().getStringExtra("user_email"));
                intent.putExtra("patient_email",  getIntent().getStringExtra("user_email"));
                intent.putExtra("device_address", selectedDeviceAddress);
                intent.putExtra("device_name",    selectedDeviceName);
                intent.putExtra("connect_only",   false);
                startActivity(intent);
            }
        });

        btnScanDevices.setOnClickListener(v -> {
            if (currentStep == STEP_CONNECT_ANALYSER) {
                showBluetoothDevicePicker();
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
            String savedName    = blePrefs.getString("last_device_name", "OpticalDetector1");
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

    // ─────────────────────────────────────────────────────────────────────────
    // Deep-link jump
    // ─────────────────────────────────────────────────────────────────────────

    private void jumpToStep(int startStep) {
        switch (startStep) {
            case 1:  showStepCollectSaliva();  break;
            case 2:  showStepCartridgeScan();  break;
            case 3:  showStepInsertAnalyser(); break;
            case 4:  showStepWaitForResults(); break;
            default: showStepScanTube();       break;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step screens
    // ─────────────────────────────────────────────────────────────────────────

    private void showStepScanTube() {
        resetPrimaryButtonPosition();
        currentStep = STEP_SCAN_TUBE;
        tvTitle.setText("Scan Tube QR");
        tvSubtitle.setText("Scan the QR code printed on the tube.");
        showCarouselImages(R.drawable.ic_step1);
        tvTimer.setVisibility(View.GONE);
        tvQrValue.setVisibility(View.GONE);
        btnSampleTime.setVisibility(View.GONE);
        btnPrimary.setVisibility(View.VISIBLE);
        btnPrimary.setText("Scan QR Code");
        btnPrimary.setEnabled(true);
        btnScanQR.setVisibility(View.GONE);
        btnPreviousDevice.setVisibility(View.GONE);
        btnScanDevices.setVisibility(View.GONE);
        btnBack.setText("Back");
        btnSecondary.setText("Next");
        btnSecondary.setEnabled(true);
        updateStepProgress(1);
    }

    private void showStepCollectSaliva() {
        resetPrimaryButtonPosition();
        currentStep = STEP_COLLECT_SALIVA;
        tvTitle.setText("Collect Saliva");
        tvSubtitle.setText("Put the swab inside your mouth. Keep it there for 5 full minutes until it becomes very wet with saliva. Try not to talk during this time.");
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
        btnScanDevices.setVisibility(View.GONE);
        btnBack.setText("Back");
        btnSecondary.setText("Next");
        btnSecondary.setEnabled(true);
        showCarouselImages(R.drawable.ic_step2_a, R.drawable.ic_step2_b);
        updateStepProgress(2);
    }

    private void showStepMoveSaliva() {
        resetPrimaryButtonPosition();
        currentStep = STEP_MOVE_SALIVA;
        tvTitle.setText("Move Saliva to Tube");
        tvSubtitle.setText("Place the swab into the tube. Push it down slowly and firmly as shown to release the saliva into the tube.");
        tvTimer.setVisibility(View.GONE);
        tvQrValue.setVisibility(View.GONE);
        btnSampleTime.setVisibility(View.GONE);
        if (countDownTimer != null) countDownTimer.cancel();
        timerRunning = false;
        btnPrimary.setVisibility(View.GONE);
        btnScanQR.setVisibility(View.GONE);
        btnPreviousDevice.setVisibility(View.GONE);
        btnScanDevices.setVisibility(View.GONE);
        btnBack.setText("Back");
        btnSecondary.setText("Next");
        btnSecondary.setEnabled(true);
        showCarouselImages(R.drawable.ic_step3_a, R.drawable.ic_step3_b);
        updateStepProgress(3);
    }

    private void showStepSampleTime() {
        resetPrimaryButtonPosition();
        currentStep = STEP_SAMPLE_TIME;
        tvTitle.setText("Enter Sample Time");
        tvSubtitle.setText("Before proceeding, please enter the sample collection time.");
        btnSampleTime.setVisibility(View.VISIBLE);
        btnSampleTime.setText("Set Sample Collection Time");
        LinearLayout parent = findViewById(R.id.contentLayout);
        parent.removeView(btnSampleTime);
        parent.addView(btnSampleTime, parent.indexOfChild(tvSubtitle) + 1);
        tvQrValue.setVisibility(View.VISIBLE);
        tvQrValue.setText("Store Sample (Optional)\nRemove the tube carefully.\nClose it tightly with the cap.\nStore in freezer at -20°C.");
        tvQrValue.setTextColor(getResources().getColor(R.color.text_primary, null));
        tvTimer.setVisibility(View.GONE);
        btnPrimary.setVisibility(View.GONE);
        btnScanQR.setVisibility(View.GONE);
        btnPreviousDevice.setVisibility(View.GONE);
        btnScanDevices.setVisibility(View.GONE);
        btnBack.setText("Store It");
        btnSecondary.setText("Next");
        isSampleTimeSet = false;
        showCarouselImages(R.drawable.ic_step4);
        updateStepProgress(4);
    }

    private void showStepCartridgeScan() {
        resetPrimaryButtonPosition();
        currentStep = STEP_CARTRIDGE_SCAN;
        tvTitle.setText("Scan Cartridge QR & Add Your Sample");
        tvSubtitle.setText("Scan the QR code printed on the cartridge.");
        tvQrValue.setVisibility(View.VISIBLE);
        tvQrValue.setText("Add Drops to Test Cartridge as shown.\nTurn the tube upside down and gently squeeze 4 to 5 drops onto the Cartridge.");
        tvQrValue.setTextColor(getResources().getColor(R.color.text_primary, null));
        tvQrValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tvSubtitle.getTextSize());
        tvQrValue.setTypeface(tvSubtitle.getTypeface());
        tvTimer.setVisibility(View.GONE);
        btnSampleTime.setVisibility(View.GONE);
        btnPrimary.setVisibility(View.VISIBLE);
        btnPrimary.setEnabled(true);
        btnPrimary.setText("Scan Cartridge QR");
        LinearLayout parent = findViewById(R.id.contentLayout);
        parent.removeView(btnPrimary);
        parent.addView(btnPrimary, parent.indexOfChild(tvSubtitle) + 1);
        btnScanQR.setVisibility(View.GONE);
        btnPreviousDevice.setVisibility(View.GONE);
        btnScanDevices.setVisibility(View.GONE);
        btnBack.setText("Back");
        btnSecondary.setText("Next");
        btnSecondary.setEnabled(true);
        showCarouselImages(R.drawable.ic_step5_a, R.drawable.ic_step5_b);
        updateStepProgress(5);
    }

    private void showStepWaitForResults() {
        resetPrimaryButtonPosition();
        currentStep = STEP_WAIT;
        tvTitle.setText("Wait for Results");
        tvSubtitle.setText("Place the test strip on a flat surface and do not move it for 20 minutes while the test processes.");
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
        btnScanDevices.setVisibility(View.GONE);
        btnBack.setText("Back");
        restoreDefaultTextColors();
        showCarouselImages(R.drawable.ic_step6);
        updateStepProgress(6);
    }

    private void showStepInsertAnalyser() {
        resetPrimaryButtonPosition();
        currentStep = STEP_CONNECT_ANALYSER;
        tvTitle.setText("Connect to Analyzer");
        tvSubtitle.setText("Please turn on your analyzer and insert your strip.\nPress connect button to connect your analyzer, or scan QR if you have a QR ID.");
        tvTimer.setVisibility(View.GONE);
        tvQrValue.setVisibility(View.GONE);
        btnSampleTime.setVisibility(View.GONE);
        btnPrimary.setVisibility(View.VISIBLE);
        btnPrimary.setEnabled(true);
        btnPrimary.setText("Connect");
        btnScanQR.setVisibility(View.VISIBLE);
        btnScanQR.setEnabled(true);
        btnScanDevices.setVisibility(View.VISIBLE);
        btnScanDevices.setEnabled(true);
        btnSecondary.setVisibility(View.VISIBLE);
        btnSecondary.setText("Next");
        btnSecondary.setEnabled(false);
        selectedBluetoothDevice = null;
        selectedDeviceAddress   = null;
        selectedDeviceName      = null;
        android.content.SharedPreferences blePrefs = getSharedPreferences("ble_prefs", MODE_PRIVATE);
        String lastAddress = blePrefs.getString("last_device_address", null);
        String lastName    = blePrefs.getString("last_device_name", "OpticalDetector1");
        if (lastAddress != null && !lastAddress.trim().isEmpty()) {
            btnPreviousDevice.setVisibility(View.VISIBLE);
            btnPreviousDevice.setText("Quick-connect: " + lastName);
        } else {
            btnPreviousDevice.setVisibility(View.GONE);
        }
        btnBack.setText("Back");
        showCarouselImages(R.drawable.ic_step7_a, R.drawable.ic_step7_b);
        updateStepProgress(7);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Carousel
    // ─────────────────────────────────────────────────────────────────────────

    private void showCarouselImages(int... drawableResIds) {
        if (carouselScroll == null) return;

        releaseMediaPlayer();
        if (videoBackground != null) videoBackground.setVisibility(View.GONE);
        if (videoScrim != null)      videoScrim.setVisibility(View.GONE);

        carouselCurrentIndex = 0;
        carouselTrack.removeAllViews();
        carouselDots.removeAllViews();
        carouselScroll.setVisibility(View.VISIBLE);

        carouselScroll.post(() -> {
            int w = carouselScroll.getWidth();
            if (w == 0) w = getResources().getDisplayMetrics().widthPixels;

            carouselTrack.removeAllViews();
            final int pageWidth = w;

            for (int resId : drawableResIds) {
                ImageView iv = new ImageView(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        pageWidth, LinearLayout.LayoutParams.WRAP_CONTENT);
                iv.setLayoutParams(lp);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setAdjustViewBounds(true);
                iv.setImageResource(resId);
                carouselTrack.addView(iv);
            }

            FrameLayout.LayoutParams trackLp = new FrameLayout.LayoutParams(
                    pageWidth * drawableResIds.length,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            carouselTrack.setLayoutParams(trackLp);

            carouselScroll.scrollTo(0, 0);
        });

        // Dots
        if (drawableResIds.length > 1) {
            carouselDots.setVisibility(View.VISIBLE);
            int dp8 = (int)(8 * getResources().getDisplayMetrics().density);
            int dp5 = (int)(5 * getResources().getDisplayMetrics().density);
            for (int i = 0; i < drawableResIds.length; i++) {
                View dot = new View(this);
                LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp8, dp8);
                dotLp.setMargins(dp5, 0, dp5, 0);
                dot.setLayoutParams(dotLp);
                android.graphics.drawable.GradientDrawable shape =
                        new android.graphics.drawable.GradientDrawable();
                shape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                shape.setColor(i == 0 ? 0xFF1B6CA8 : 0xFFCCCCCC);
                dot.setBackground(shape);
                carouselDots.addView(dot);
            }
        } else {
            carouselDots.setVisibility(View.GONE);
        }

        // ── Smooth swipe — ACTION_MOVE drags live, ACTION_UP snaps to page ──
        final int[] ids = drawableResIds;
        carouselScroll.setOnTouchListener(new View.OnTouchListener() {
            private float startX;
            private int   startScrollX;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX       = e.getRawX();
                        startScrollX = carouselScroll.getScrollX();
                        // Tell parent ScrollView not to steal this touch
                        carouselScroll.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = startX - e.getRawX();
                        carouselScroll.scrollTo((int)(startScrollX + dx), 0);
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        carouselScroll.getParent().requestDisallowInterceptTouchEvent(false);
                        float totalDiff = startX - e.getRawX();
                        int   pageWidth = carouselScroll.getWidth();
                        int   next      = carouselCurrentIndex;
                        if      (totalDiff >  pageWidth * 0.2f) next = Math.min(ids.length - 1, carouselCurrentIndex + 1);
                        else if (totalDiff < -pageWidth * 0.2f) next = Math.max(0, carouselCurrentIndex - 1);
                        carouselCurrentIndex = next;
                        carouselScroll.smoothScrollTo(next * pageWidth, 0);
                        updateCarouselDots();
                        return true;
                }
                return false;
            }
        });

        restoreDefaultTextColors();
    }

    private void updateCarouselDots() {
        for (int i = 0; i < carouselDots.getChildCount(); i++) {
            android.graphics.drawable.GradientDrawable d =
                    new android.graphics.drawable.GradientDrawable();
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            d.setColor(i == carouselCurrentIndex ? 0xFF1B6CA8 : 0xFFCCCCCC);
            carouselDots.getChildAt(i).setBackground(d);
        }
    }

    private void hideCarousel() {
        if (carouselScroll != null) {
            carouselScroll.setVisibility(View.GONE);
            carouselScroll.setOnTouchListener(null);
        }
        if (carouselDots  != null) { carouselDots.setVisibility(View.GONE); carouselDots.removeAllViews(); }
        if (carouselTrack != null) carouselTrack.removeAllViews();
        carouselCurrentIndex = 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Video helpers
    // ─────────────────────────────────────────────────────────────────────────

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
                int vw = mp.getVideoWidth(), vh = mp.getVideoHeight();
                int tw = tv.getWidth(),      th = tv.getHeight();
                float sx = 1f, sy = 1f;
                if (vw > 0 && vh > 0) {
                    float va = (float) vw / vh, ta = (float) tw / th;
                    if (va > ta) sx = va / ta; else sy = ta / va;
                }
                Matrix m = new Matrix();
                m.setScale(sx, sy, tw / 2f, th / 2f);
                tv.setTransform(m);
                mp.start();
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            android.util.Log.e("Video", "Error: " + e.getMessage());
        }
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try { if (mediaPlayer.isPlaying()) mediaPlayer.stop(); } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bluetooth
    // ─────────────────────────────────────────────────────────────────────────

    private void showBluetoothDevicePicker() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth first", Toast.LENGTH_SHORT).show();
            return;
        }
        android.app.ProgressDialog dlg = new android.app.ProgressDialog(this);
        dlg.setMessage("Scanning for devices...");
        dlg.setCancelable(true);
        dlg.show();

        final ArrayList<BluetoothDevice> found      = new ArrayList<>();
        final ArrayList<String>          foundNames = new ArrayList<>();

        android.bluetooth.le.BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        android.bluetooth.le.ScanSettings settings = new android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build();

        android.bluetooth.le.ScanCallback cb = new android.bluetooth.le.ScanCallback() {
            @Override public void onScanResult(int t, android.bluetooth.le.ScanResult r) {
                BluetoothDevice dev = r.getDevice();
                if (dev == null) return;
                for (BluetoothDevice d : found) if (d.getAddress().equals(dev.getAddress())) return;

                String name = null;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        try { name = dev.getName(); } catch (Exception ignored) {}
                    }
                } else {
                    try { name = dev.getName(); } catch (Exception ignored) {}
                }
//                if (name == null || !name.toLowerCase().contains("opticaldetector")) return;
                found.add(dev);
                foundNames.add(name);
            }
        };

        try { scanner.startScan(null, settings, cb); }
        catch (SecurityException e) {
            dlg.dismiss();
            Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_SHORT).show();
            return;
        }

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try { scanner.stopScan(cb); } catch (Exception ignored) {}
            dlg.dismiss();
            if (found.isEmpty()) {
                Toast.makeText(this, "No OpticalDetector devices found. Make sure the analyzer is on.", Toast.LENGTH_LONG).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Select Device")
                    .setItems(foundNames.toArray(new String[0]), (dialog, which) -> {
                        selectedDeviceAddress = found.get(which).getAddress();
                        selectedDeviceName    = foundNames.get(which);
                        connectInBackground(found.get(which));
                    })
                    .setNegativeButton("Cancel", null).show();
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
        btnScanDevices.setVisibility(View.GONE);
        btnPrimary.setEnabled(false);
        btnScanQR.setEnabled(false);
        btnScanDevices.setEnabled(false);
        btnSecondary.setEnabled(false);

        BluetoothManager mgr = BluetoothManager.getInstance(this);
        mgr.setCallback(new BluetoothManager.BleCallback() {
            @Override public void onConnected()           { runOnUiThread(() -> updateStep7ConnectedState(selectedDeviceName, selectedDeviceAddress)); }
            @Override public void onDisconnected()        { runOnUiThread(() -> updateStep7FailedState()); }
            @Override public void onOpticalData(byte[] p) {}
            @Override public void onStatusData(byte[] s)  {}
        });
        mgr.connect(device.getAddress());
    }

    private void updateStep7ConnectedState(String deviceName, String deviceAddress) {
        getSharedPreferences("ble_prefs", MODE_PRIVATE).edit()
                .putString("last_device_address", deviceAddress)
                .putString("last_device_name",    deviceName).apply();
        tvTitle.setText("Insert Cartridge");
        tvSubtitle.setText("Connected to " + deviceName + ".\nInsert the test strip cartridge to the analyzer as shown.");
        btnPrimary.setVisibility(View.GONE);
        btnScanQR.setVisibility(View.GONE);
        btnPreviousDevice.setVisibility(View.GONE);
        btnScanDevices.setVisibility(View.GONE);
        btnSecondary.setEnabled(true);
        btnSecondary.setText("Next");
        showCarouselImages(R.drawable.ic_step7_b);
        Toast.makeText(this, "Connected to " + deviceName, Toast.LENGTH_SHORT).show();
    }

    private void updateStep7FailedState() {
        tvSubtitle.setText("Connection failed.\nPlease make sure the analyzer is turned on and paired, then try again.");
        btnPrimary.setVisibility(View.VISIBLE);
        btnPrimary.setEnabled(true);
        btnPrimary.setText("Connect");
        btnScanQR.setVisibility(View.VISIBLE);
        btnScanQR.setEnabled(true);
        btnScanDevices.setVisibility(View.VISIBLE);
        btnScanDevices.setEnabled(true);
        String last = getSharedPreferences("ble_prefs", MODE_PRIVATE).getString("last_device_address", null);
        if (last != null && !last.trim().isEmpty()) btnPreviousDevice.setVisibility(View.VISIBLE);
        btnSecondary.setEnabled(false);
        Toast.makeText(this, "Failed to connect to analyzer", Toast.LENGTH_SHORT).show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // QR scan
    // ─────────────────────────────────────────────────────────────────────────

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
            String scanned = data.getStringExtra("device_address");
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

    // ─────────────────────────────────────────────────────────────────────────
    // Timer
    // ─────────────────────────────────────────────────────────────────────────

    private void toggleTimer() {
        if (!timerRunning) {
            countDownTimer = new CountDownTimer(timeRemainingMs, 1000) {
                @Override public void onTick(long ms) {
                    timeRemainingMs = ms;
                    long sec = ms / 1000;
                    tvTimer.setText(String.format(Locale.getDefault(), "%02d:%02d", sec / 60, sec % 60));
                }
                @Override public void onFinish() {
                    timeRemainingMs = 0;
                    timerRunning    = false;
                    tvTimer.setText("00:00");
                    btnPrimary.setText("Completed");
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

    // ─────────────────────────────────────────────────────────────────────────
    // Cloud
    // ─────────────────────────────────────────────────────────────────────────

    private void createSampleInCloud(String tubeId) {
        String uid = FirebaseAuthHelper.getCurrentUid();
        if (uid == null) return;
        Map<String, Object> sample = new HashMap<>();
        sample.put("tube_id", tubeId);
        sample.put("created_at", Timestamp.now());
        db.collection("users").document(uid).collection("samples")
                .add(sample).addOnSuccessListener(doc -> sampleDocId = doc.getId());
    }

    private void saveCartridgeToCloud(String cartridgeId) {
        String uid = FirebaseAuthHelper.getCurrentUid();
        if (uid == null || sampleDocId == null) return;
        Map<String, Object> u = new HashMap<>();
        u.put("cartridge_id", cartridgeId);
        db.collection("users").document(uid).collection("samples").document(sampleDocId).update(u);
    }

    private void updateSampleTimeInCloud(String time) {
        String uid = FirebaseAuthHelper.getCurrentUid();
        if (uid == null || sampleDocId == null) return;
        db.collection("users").document(uid).collection("samples")
                .document(sampleDocId).update("sample_time", time);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

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
        parent.removeView(btnSampleTime);
        parent.addView(btnSampleTime);
        parent.removeView(btnPrimary);
        parent.addView(btnPrimary);
        btnSecondary.setText("Next");
    }

    private void restoreDefaultTextColors() {
        if (tvStepIndicator != null) tvStepIndicator.setTextColor(getResources().getColor(R.color.text_secondary, null));
        if (tvTitle != null)         tvTitle.setTextColor(getResources().getColor(R.color.primary, null));
        if (tvSubtitle != null)      tvSubtitle.setTextColor(getResources().getColor(R.color.text_secondary, null));
        LinearLayout bb = findViewById(R.id.bottomButtons);
        if (bb != null) bb.setBackgroundColor(getResources().getColor(R.color.background, null));
    }

    private void updateStepProgress(int step) {
        if (stepProgressBar != null) stepProgressBar.setProgress(step);
        if (tvStepIndicator != null) tvStepIndicator.setText("Step " + step + " of 7");
    }
}