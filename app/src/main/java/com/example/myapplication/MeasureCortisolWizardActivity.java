package com.example.myapplication;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.util.Locale;

public class MeasureCortisolWizardActivity extends AppCompatActivity {

    private static final int REQUEST_TUBE_QR      = 101;
    private static final int REQUEST_CARTRIDGE_QR = 102;
    private static final int REQUEST_DEVICE_QR    = 103;

    private TextView tvStepIndicator;
    private Button btnBack, btnNext;

    private int currentStep = 1;
    private static final int TOTAL_STEPS = 12;

    // Collected data
    private String tubeId = "";
    private String cartridgeId = "";
    private String sampleTime = "";
    private String deviceAddress = "";

    // Timer state
    private CountDownTimer activeTimer = null;

    private String userEmail;
    private String userRole;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_measure_cortisol_wizard);

        userEmail = getIntent().getStringExtra("user_email");
        userRole  = getIntent().getStringExtra("user_role");

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        tvStepIndicator = findViewById(R.id.tvStepIndicator);
        btnBack         = findViewById(R.id.btnBack);
        btnNext         = findViewById(R.id.btnNext);

        btnBack.setOnClickListener(v -> goBack());
        btnNext.setOnClickListener(v -> goNext());

        showStep(currentStep);
    }

    // ─────────────────────────────────────────────
    // Navigation
    // ─────────────────────────────────────────────

    private void goNext() {
        if (currentStep == 12) {
            launchOutputBluetooth();
            return;
        }

        // Step 5: validate sample time
        if (currentStep == 5) {
            EditText et = findViewById(R.id.etSampleTime);
            if (et != null && TextUtils.isEmpty(et.getText())) {
                Toast.makeText(this, "Please enter the sample time", Toast.LENGTH_SHORT).show();
                return;
            }
            if (et != null) sampleTime = et.getText().toString().trim();
        }

        currentStep++;
        showStep(currentStep);
    }

    private void goBack() {
        if (currentStep == 1) {
            finish();
            return;
        }
        currentStep--;
        showStep(currentStep);
    }

    // ─────────────────────────────────────────────
    // Step Renderer
    // ─────────────────────────────────────────────

    private void showStep(int step) {
        cancelActiveTimer();

        tvStepIndicator.setText("Step " + step + " of " + TOTAL_STEPS);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getStepTitle(step));
        }

        btnBack.setVisibility(step == 1 ? View.GONE : View.VISIBLE);
        btnNext.setText(step == 12 ? "Launch" : (isSkippable(step) ? "Skip" : "Next"));
        btnNext.setEnabled(true);

        FrameLayout container = findViewById(R.id.stepContainer);
        container.removeAllViews();

        View stepView = buildStepView(step);
        container.addView(stepView);
    }

    private String getStepTitle(int step) {
        switch (step) {
            case 1:  return "Pre-Test Instructions";
            case 2:  return "Sample Collection Instructions";
            case 3:  return "Scan Collection Tube";
            case 4:  return "Sample Collection";
            case 5:  return "Enter Sample Time";
            case 6:  return "Move Saliva to Tube";
            case 7:  return "Sample Storage";
            case 8:  return "Add to Test Strip";
            case 9:  return "Wait 20 Minutes";
            case 10: return "Insert into Analyser";
            case 11: return "Connect Analyser";
            case 12: return "Ready to Scan";
            default: return "Measure Cortisol";
        }
    }

    private boolean isSkippable(int step) {
        // Only timers and optional storage conceptually skippable
        return step == 4 || step == 7 || step == 9;
    }

    // ─────────────────────────────────────────────
    // Step View Builders
    // ─────────────────────────────────────────────

    private View buildStepView(int step) {
        switch (step) {
            case 1:
                return buildInfoStep(
                        "Before You Begin",
                        "This test measures cortisol levels in your saliva.\n\n" +
                                "Please ensure you:\n\n" +
                                "• Have not eaten, drunk, or brushed teeth for at least 30 minutes\n" +
                                "• Are in a relaxed state\n" +
                                "• Have the collection tube and test strip ready\n" +
                                "• Have the portable analyser nearby");

            case 2:
                return buildInfoStep(
                        "Sample Collection Overview",
                        "You will now collect a saliva sample.\n\n" +
                                "The process involves:\n\n" +
                                "1. Scanning your collection tube\n" +
                                "2. Holding a swab under your tongue for 5 minutes\n" +
                                "3. Transferring the sample to the tube\n" +
                                "4. Adding drops to the test strip\n" +
                                "5. Waiting 20 minutes for the strip to process\n" +
                                "6. Inserting the strip into the analyser");

            case 3:
                return buildQrStep(
                        "Scan your collection tube QR code to register this sample.",
                        tubeId,
                        REQUEST_TUBE_QR);

            case 4:
                return buildTimerStep(
                        "Hold the swab under your tongue for 5 minutes.\n\n" +
                                "Do not talk or swallow excessively during this time.",
                        5 * 60 * 1000L);

            case 5:
                return buildTimeInputStep();

            case 6:
                return buildInfoStep(
                        "Move Saliva to Tube",
                        "Place the swab into the collection tube.\n\n" +
                                "Push it down slowly and firmly to release the saliva into the tube.\n\n" +
                                "Make sure the tube is held upright.");

            case 7:
                return buildInfoStep(
                        "Sample Storage (Optional)",
                        "If you are not using the sample immediately:\n\n" +
                                "• Close the tube tightly with the cap\n" +
                                "• Store in a freezer at -20°C\n\n" +
                                "If you are continuing now, tap Next to proceed.");

            case 8:
                return buildQrStep(
                        "Add 4-5 drops from the tube onto the test strip.\n\n" +
                                "Then scan the cartridge QR code below.",
                        cartridgeId,
                        REQUEST_CARTRIDGE_QR);

            case 9:
                return buildTimerStep(
                        "Place the test strip on a flat surface.\n\n" +
                                "Do not move it. Wait 20 minutes for the sample to process.",
                        20 * 60 * 1000L);

            case 10:
                return buildInfoStep(
                        "Insert into Analyser",
                        "After the 20 minutes have passed:\n\n" +
                                "• Pick up the test strip carefully\n" +
                                "• Gently insert it into the slot on the analyser\n" +
                                "• Ensure it clicks into place\n\n" +
                                "Then tap Next to connect the analyser.");

            case 11:
                return buildConnectionStep();

            case 12:
                return buildReadyStep();

            default:
                return buildInfoStep("", "");
        }
    }

    // Static info screen
    private View buildInfoStep(String title, String body) {
        ScrollView sv = new ScrollView(this);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(48, 48, 48, 48);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(20f);
        tvTitle.setTextColor(getResources().getColor(R.color.colorPrimary, getTheme()));
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, 32);
        ll.addView(tvTitle);

        TextView tvBody = new TextView(this);
        tvBody.setText(body);
        tvBody.setTextSize(15f);
        tvBody.setLineSpacing(8f, 1f);
        ll.addView(tvBody);

        sv.addView(ll);
        return sv;
    }

    // Timer screen
    private View buildTimerStep(String instruction, long durationMs) {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setGravity(Gravity.CENTER);
        ll.setPadding(48, 64, 48, 48);

        TextView tvInstruction = new TextView(this);
        tvInstruction.setText(instruction);
        tvInstruction.setTextSize(15f);
        tvInstruction.setLineSpacing(8f, 1f);
        tvInstruction.setGravity(Gravity.CENTER);
        tvInstruction.setPadding(0, 0, 0, 48);
        ll.addView(tvInstruction);

        TextView tvTimer = new TextView(this);
        tvTimer.setTextSize(56f);
        tvTimer.setTypeface(null, Typeface.BOLD);
        tvTimer.setTextColor(getResources().getColor(R.color.colorPrimary, getTheme()));
        tvTimer.setGravity(Gravity.CENTER);
        tvTimer.setPadding(0, 0, 0, 48);
        ll.addView(tvTimer);

        Button btnStartTimer = new Button(this);
        btnStartTimer.setText("Start Timer");
        btnStartTimer.setBackgroundTintList(
                ColorStateList.valueOf(
                        getResources().getColor(R.color.colorPrimary, getTheme())));
        btnStartTimer.setTextColor(Color.WHITE);
        ll.addView(btnStartTimer);

        btnNext.setEnabled(false);  // block Next until finished or skip

        final long[] remaining = {durationMs};
        final boolean[] running = {false};

        // initial display
        long minsInit = durationMs / 60000;
        tvTimer.setText(String.format(Locale.getDefault(), "%02d:00", minsInit));

        btnStartTimer.setOnClickListener(v -> {
            if (running[0]) {
                cancelActiveTimer();
                running[0] = false;
                btnStartTimer.setText("Resume Timer");
                // allow skip while paused
                btnNext.setEnabled(true);
            } else {
                running[0] = true;
                btnStartTimer.setText("Pause Timer");
                btnNext.setEnabled(false);

                activeTimer = new CountDownTimer(remaining[0], 1000) {
                    @Override
                    public void onTick(long millisUntilFinished) {
                        remaining[0] = millisUntilFinished;
                        long mins = millisUntilFinished / 60000;
                        long secs = (millisUntilFinished % 60000) / 1000;
                        tvTimer.setText(String.format(Locale.getDefault(),
                                "%02d:%02d", mins, secs));
                    }

                    @Override
                    public void onFinish() {
                        tvTimer.setText("00:00");
                        tvTimer.setTextColor(Color.parseColor("#4CAF50"));
                        btnStartTimer.setEnabled(false);
                        btnNext.setEnabled(true);
                        btnNext.setText("Next");
                        running[0] = false;
                        Toast.makeText(MeasureCortisolWizardActivity.this,
                                "Timer complete!", Toast.LENGTH_SHORT).show();
                    }
                }.start();
            }
        });

        return ll;
    }

    // QR scan screen
    private View buildQrStep(String instruction, String currentValue, int requestCode) {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(48, 64, 48, 48);
        ll.setGravity(Gravity.CENTER);

        TextView tvInstruction = new TextView(this);
        tvInstruction.setText(instruction);
        tvInstruction.setTextSize(15f);
        tvInstruction.setLineSpacing(8f, 1f);
        tvInstruction.setGravity(Gravity.CENTER);
        tvInstruction.setPadding(0, 0, 0, 32);
        ll.addView(tvInstruction);

        TextView tvScanned = new TextView(this);
        tvScanned.setTextSize(13f);
        tvScanned.setGravity(Gravity.CENTER);
        tvScanned.setPadding(0, 0, 0, 24);
        if (!currentValue.isEmpty()) {
            tvScanned.setText("Scanned: " + currentValue);
            tvScanned.setTextColor(Color.parseColor("#4CAF50"));
        } else {
            tvScanned.setText("Not yet scanned");
            tvScanned.setTextColor(Color.parseColor("#999999"));
        }
        ll.addView(tvScanned);

        Button btnScan = new Button(this);
        btnScan.setText("Scan QR Code");
        btnScan.setBackgroundTintList(
                ColorStateList.valueOf(
                        getResources().getColor(R.color.colorPrimary, getTheme())));
        btnScan.setTextColor(Color.WHITE);
        btnScan.setOnClickListener(v -> {
            Intent intent = new Intent(this, QRScanActivity.class);
            startActivityForResult(intent, requestCode);
        });
        ll.addView(btnScan);

        return ll;
    }

    // Sample time input screen
    private View buildTimeInputStep() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(48, 64, 48, 48);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("When was this sample collected?");
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, 16);
        ll.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText("Enter the time you started collecting the saliva sample.");
        tvSub.setTextSize(14f);
        tvSub.setPadding(0, 0, 0, 32);
        ll.addView(tvSub);

        EditText etSampleTime = new EditText(this);
        etSampleTime.setId(R.id.etSampleTime);
        etSampleTime.setHint("e.g. 08:30 AM");
        etSampleTime.setTextSize(16f);
        etSampleTime.setText(sampleTime);
        ll.addView(etSampleTime);

        return ll;
    }

    // Analyser connection screen
    private View buildConnectionStep() {
        ScrollView sv = new ScrollView(this);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(48, 48, 48, 48);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("Connect to Analyser");
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, 8);
        ll.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText("Scan the QR code on the analyser or enter the device ID manually.");
        tvSub.setTextSize(14f);
        tvSub.setPadding(0, 0, 0, 32);
        ll.addView(tvSub);

        // QR connect button
        Button btnQR = new Button(this);
        btnQR.setText("Scan Analyser QR Code");
        btnQR.setBackgroundTintList(
                ColorStateList.valueOf(
                        getResources().getColor(R.color.colorPrimary, getTheme())));
        btnQR.setTextColor(Color.WHITE);
        btnQR.setOnClickListener(v -> {
            Intent intent = new Intent(this, QRScanActivity.class);
            startActivityForResult(intent, REQUEST_DEVICE_QR);
        });
        ll.addView(btnQR);

        TextView tvOr = new TextView(this);
        tvOr.setText("— or enter manually —");
        tvOr.setGravity(Gravity.CENTER);
        tvOr.setPadding(0, 24, 0, 24);
        tvOr.setTextColor(Color.parseColor("#999999"));
        ll.addView(tvOr);

        // Manual device ID input
        EditText etDeviceId = new EditText(this);
        etDeviceId.setId(R.id.etDeviceId);
        etDeviceId.setHint("Enter Device ID (e.g. AA:BB:CC:DD:EE:FF)");
        etDeviceId.setTextSize(14f);
        ll.addView(etDeviceId);

        // Confirm manual button
        Button btnConfirmManual = new Button(this);
        btnConfirmManual.setText("Confirm Device ID");
        btnConfirmManual.setBackgroundTintList(
                ColorStateList.valueOf(Color.parseColor("#607D8B")));
        btnConfirmManual.setTextColor(Color.WHITE);
        btnConfirmManual.setPadding(0, 24, 0, 0);
        btnConfirmManual.setOnClickListener(v -> {
            EditText et = findViewById(R.id.etDeviceId);
            if (et != null && !TextUtils.isEmpty(et.getText())) {
                deviceAddress = et.getText().toString().trim();
                Toast.makeText(this, "Device ID saved: " + deviceAddress,
                        Toast.LENGTH_SHORT).show();
                btnNext.setEnabled(true);
            } else {
                Toast.makeText(this, "Please enter a device ID", Toast.LENGTH_SHORT).show();
            }
        });
        ll.addView(btnConfirmManual);

        // Status text
        TextView tvStatus = new TextView(this);
        tvStatus.setId(R.id.tvConnectionStatus);
        tvStatus.setPadding(0, 24, 0, 0);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setTextSize(13f);
        if (!deviceAddress.isEmpty()) {
            tvStatus.setText("Connected: " + deviceAddress);
            tvStatus.setTextColor(Color.parseColor("#4CAF50"));
        } else {
            tvStatus.setText("Not connected");
            tvStatus.setTextColor(Color.parseColor("#999999"));
            btnNext.setEnabled(false);
        }
        ll.addView(tvStatus);

        sv.addView(ll);
        return sv;
    }

    // Ready to scan screen
    private View buildReadyStep() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setGravity(Gravity.CENTER);
        ll.setPadding(48, 64, 48, 48);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("Ready to Scan");
        tvTitle.setTextSize(24f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(getResources().getColor(R.color.colorPrimary, getTheme()));
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setPadding(0, 0, 0, 24);
        ll.addView(tvTitle);

        TextView tvSummary = new TextView(this);
        tvSummary.setText(
                "All steps complete. Here is a summary:\n\n" +
                        "Tube ID: " + (tubeId.isEmpty() ? "Not scanned" : tubeId) + "\n" +
                        "Cartridge ID: " + (cartridgeId.isEmpty() ? "Not scanned" : cartridgeId) + "\n" +
                        "Sample Time: " + (sampleTime.isEmpty() ? "Not entered" : sampleTime) + "\n" +
                        "Device: " + (deviceAddress.isEmpty() ? "Not set" : deviceAddress) + "\n\n" +
                        "Tap Launch to begin data collection.");
        tvSummary.setTextSize(15f);
        tvSummary.setLineSpacing(8f, 1f);
        tvSummary.setGravity(Gravity.CENTER);
        ll.addView(tvSummary);

        btnNext.setText("Launch");

        return ll;
    }

    // ─────────────────────────────────────────────
    // QR Results
    // ─────────────────────────────────────────────

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null) {
            String scanned = data.getStringExtra("device_address");
            if (scanned == null) scanned = data.getStringExtra("qr_result");
            if (scanned == null) return;

            if (requestCode == REQUEST_TUBE_QR) {
                tubeId = scanned;
                Toast.makeText(this, "Tube scanned: " + tubeId, Toast.LENGTH_SHORT).show();
                showStep(currentStep);
            } else if (requestCode == REQUEST_CARTRIDGE_QR) {
                cartridgeId = scanned;
                Toast.makeText(this, "Cartridge scanned: " + cartridgeId, Toast.LENGTH_SHORT).show();
                showStep(currentStep);
            } else if (requestCode == REQUEST_DEVICE_QR) {
                deviceAddress = scanned;
                TextView tvStatus = findViewById(R.id.tvConnectionStatus);
                if (tvStatus != null) {
                    tvStatus.setText("Connected: " + deviceAddress);
                    tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                }
                btnNext.setEnabled(true);
                Toast.makeText(this, "Analyser connected: " + deviceAddress,
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ─────────────────────────────────────────────
    // Launch OutputBluetooth
    // ─────────────────────────────────────────────

    private void launchOutputBluetooth() {
        Intent intent = new Intent(this, OutputBluetooth.class);
        intent.putExtra("user_email", userEmail);
        intent.putExtra("user_role", userRole);
        intent.putExtra("tube_id", tubeId);
        intent.putExtra("cartridge_id", cartridgeId);
        intent.putExtra("sample_time", sampleTime);
        intent.putExtra("device_address", deviceAddress);
        startActivity(intent);
        finish();
    }

    // ─────────────────────────────────────────────
    // Timer cleanup
    // ─────────────────────────────────────────────

    private void cancelActiveTimer() {
        if (activeTimer != null) {
            activeTimer.cancel();
            activeTimer = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelActiveTimer();
    }

    @Override
    public boolean onSupportNavigateUp() {
        if (currentStep > 1) {
            goBack();
        } else {
            finish();
        }
        return true;
    }
}
