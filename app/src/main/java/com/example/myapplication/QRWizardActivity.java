package com.example.myapplication;

import android.app.TimePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class QRWizardActivity extends AppCompatActivity {

    private static final int REQUEST_SCAN_TUBE   = 201;
    private static final int STEP_SCAN_TUBE      = 1;
    private static final int STEP_COLLECT_SALIVA = 2;
    private static final int STEP_MOVE_SALIVA    = 3;
    private static final int STEP_SAMPLE_TIME    = 4;
    private static final int STEP_CARTRIDGE_SCAN = 5;
    private static final int STEP_WAIT           = 6;

    private int currentStep = STEP_SCAN_TUBE;

    private TextView tvStepIndicator, tvTitle, tvSubtitle, tvQrValue, tvTimer;
    private ImageView ivStepImage;
    private Button btnBack, btnPrimary, btnSecondary, btnSampleTime;

    private String tubeId;
    private String cartridgeId;
    private boolean isSampleTimeSet = false;

    private CountDownTimer countDownTimer;
    private boolean timerRunning = false;
    private long timeRemainingMs = 5 * 60 * 1000L;

    private FirebaseFirestore db;
    private String sampleDocId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_wizard);

        db = FirebaseFirestore.getInstance();

        setupToolbar();
        initViews();
        setupListeners();

        // Read start_step passed from care provider step selector dialog
        int startStep = getIntent().getIntExtra("start_step", 0);
        jumpToStep(startStep);

        btnSecondary.setEnabled(true);
    }

    // ── Jumps directly to the step the care provider selected ──
    private void jumpToStep(int startStep) {
        android.util.Log.d("STEP_DEBUG", "QRWizard jumpToStep called with: " + startStep);
        switch (startStep) {
            case 1: // Collect saliva
                showStepCollectSaliva();
                updateStepProgress(2);
                break;
            case 2: // Scan cartridge and add sample
                showStepCartridgeScan();
                updateStepProgress(5);
                break;
            case 3: // Insert cartridge to analyser (strip is ready)
                showStepInsertAnalyser();
                updateStepProgress(7);
                break;
            case 4: // Measure cortisol — wait for results
                showStepWaitForResults();
                updateStepProgress(6);
                break;
            default: // Step 0: Full flow from beginning
                showStepScanTube();
                updateStepProgress(1);
                break;
        }
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Sample Collection");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    private void initViews() {
        tvStepIndicator = findViewById(R.id.tvStepIndicator);
        tvTitle         = findViewById(R.id.tvTitle);
        tvSubtitle      = findViewById(R.id.tvSubtitle);
        tvQrValue       = findViewById(R.id.tvQrValue);
        tvTimer         = findViewById(R.id.tvTimer);
        ivStepImage     = findViewById(R.id.ivStepImage);
        btnBack         = findViewById(R.id.btnBack);
        btnPrimary      = findViewById(R.id.btnPrimary);
        btnSecondary    = findViewById(R.id.btnSecondary);
        btnSampleTime   = findViewById(R.id.btnSampleTime);
    }

    private void setupListeners() {

        btnBack.setOnClickListener(v -> {
            if (currentStep == STEP_SAMPLE_TIME || currentStep == STEP_CARTRIDGE_SCAN
                    || currentStep == STEP_WAIT) {
                goToDashboard();
            } else if (currentStep == STEP_MOVE_SALIVA) {
                showStepCollectSaliva();
            } else if (currentStep == STEP_COLLECT_SALIVA) {
                showStepScanTube();
            } else {
                finish();
            }
        });

        btnPrimary.setOnClickListener(v -> {
            if (currentStep == STEP_SCAN_TUBE || currentStep == STEP_CARTRIDGE_SCAN) {
                startTubeScan();
            } else if (currentStep == STEP_COLLECT_SALIVA || currentStep == STEP_WAIT) {
                toggleTimer();
            }
        });

        btnSecondary.setOnClickListener(v -> {
            if (currentStep == STEP_SCAN_TUBE) {
                if (tubeId == null || tubeId.isEmpty()) {
                    tubeId = "DEMO_TUBE_001";
                }
                createSampleInCloud(tubeId);
                showStepCollectSaliva();
            } else if (currentStep == STEP_COLLECT_SALIVA) {
                showStepMoveSaliva();
            } else if (currentStep == STEP_MOVE_SALIVA) {
                showStepSampleTime();
            } else if (currentStep == STEP_SAMPLE_TIME) {
                if (!isSampleTimeSet) {
                    Toast.makeText(this, "Please set sample time first",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                showStepCartridgeScan();
            } else if (currentStep == STEP_CARTRIDGE_SCAN) {
                showStepWaitForResults();
            } else if (currentStep == STEP_WAIT) {
                showStepInsertAnalyser();
            } else if (currentStep == 7) {
                Intent intent = new Intent(this, OutputBluetooth.class);
                intent.putExtra("tube_id", tubeId);
                intent.putExtra("cartridge_id", cartridgeId);
                intent.putExtra("sample_time", btnSampleTime.getText().toString());
                startActivity(intent);
            }
        });

        btnSampleTime.setOnClickListener(v -> openTimePicker());
    }

    private void resetPrimaryButtonPosition() {
        LinearLayout parent = findViewById(R.id.contentLayout);
        parent.removeView(btnPrimary);
        parent.addView(btnPrimary);
        btnSecondary.setText("Next");
    }

    private void showStepScanTube() {
        resetPrimaryButtonPosition();
        currentStep = STEP_SCAN_TUBE;
        tvStepIndicator.setText("Step 1 of 7");
        tvTitle.setText("Scan Tube QR");
        tvSubtitle.setText("Scan the QR code printed on the tube.");
        ivStepImage.setVisibility(View.GONE);
        tvTimer.setVisibility(View.GONE);
        tvQrValue.setVisibility(View.GONE);
        btnSampleTime.setVisibility(View.GONE);
        btnPrimary.setVisibility(View.VISIBLE);
        btnPrimary.setText("Scan QR Code");
        btnBack.setText("Back");
        btnSecondary.setEnabled(true);
        updateStepProgress(1);
    }

    private void startTubeScan() {
        Intent intent = new Intent(this, QRScanActivity.class);
        if (currentStep == STEP_SCAN_TUBE) {
            intent.putExtra("mode", "TUBE");
        } else if (currentStep == STEP_CARTRIDGE_SCAN) {
            intent.putExtra("mode", "CARTRIDGE");
        }
        startActivityForResult(intent, REQUEST_SCAN_TUBE);
    }

    private void showStepCollectSaliva() {
        resetPrimaryButtonPosition();
        currentStep = STEP_COLLECT_SALIVA;
        tvStepIndicator.setText("Step 2 of 7");
        tvTitle.setText("Collect Saliva");
        tvSubtitle.setText("Put the soft swab under your tongue. Keep it there for 5 full minutes until it becomes very wet with saliva. Try not to talk during this time.");
        ivStepImage.setVisibility(View.VISIBLE);
        ivStepImage.setImageResource(R.drawable.ic_step_collection);
        tvTimer.setVisibility(View.VISIBLE);
        tvTimer.setText("05:00");
        btnPrimary.setVisibility(View.VISIBLE);
        btnPrimary.setText("Start Timer");
        updateStepProgress(2);
    }

    private void showStepMoveSaliva() {
        resetPrimaryButtonPosition();
        currentStep = STEP_MOVE_SALIVA;
        tvStepIndicator.setText("Step 3 of 7");
        tvTitle.setText("Move Saliva to Tube");
        tvSubtitle.setText("Place the swab into the tube. Push it down slowly and firmly as shown to release the saliva into the tube.");
        ivStepImage.setVisibility(View.VISIBLE);
        ivStepImage.setImageResource(R.drawable.ic_step_extraction);
        tvTimer.setVisibility(View.GONE);
        if (countDownTimer != null) countDownTimer.cancel();
        timerRunning = false;
        btnPrimary.setVisibility(View.GONE);
        updateStepProgress(3);
    }

    private void showStepSampleTime() {
        resetPrimaryButtonPosition();
        currentStep = STEP_SAMPLE_TIME;
        tvStepIndicator.setText("Step 4 of 7");
        tvTitle.setText("Enter Sample Time");
        tvSubtitle.setText("Before proceeding, please enter the sample collection time.");
        btnSampleTime.setVisibility(View.VISIBLE);
        btnSampleTime.setText("Set Sample Collection Time");
        LinearLayout parent = findViewById(R.id.contentLayout);
        parent.removeView(btnSampleTime);
        int index = parent.indexOfChild(tvSubtitle);
        parent.addView(btnSampleTime, index + 1);
        tvQrValue.setVisibility(View.VISIBLE);
        tvQrValue.setText("Store Sample (Optional)\n\n" +
                "• Remove the tube carefully\n" +
                "• Close it tightly with the cap\n" +
                "• Store in freezer at -20°C");
        ivStepImage.setVisibility(View.VISIBLE);
        ivStepImage.setImageResource(R.drawable.ic_step_storage);
        tvTimer.setVisibility(View.GONE);
        btnPrimary.setVisibility(View.GONE);
        btnBack.setText("Store It");
        isSampleTimeSet = false;
        updateStepProgress(4);
    }

    private void showStepCartridgeScan() {
        currentStep = STEP_CARTRIDGE_SCAN;
        tvStepIndicator.setText("Step 5 of 7");
        tvTitle.setText("Scan Cartridge QR & Add Your Sample");
        tvSubtitle.setText("Scan the QR code printed on the cartridge.");
        tvQrValue.setVisibility(View.VISIBLE);
        tvQrValue.setText("Add Drops to Test Cartridge as shown:\n" +
                "Turn the tube upside down and gently squeeze 4 to 5 drops onto the Cartridge.");
        ivStepImage.setVisibility(View.VISIBLE);
        ivStepImage.setImageResource(R.drawable.ic_step_loading);
        tvTimer.setVisibility(View.GONE);
        btnSampleTime.setVisibility(View.GONE);
        btnPrimary.setVisibility(View.VISIBLE);
        btnPrimary.setText("Scan Cartridge QR");
        btnBack.setText("Back");
        LinearLayout parent = findViewById(R.id.contentLayout);
        parent.removeView(btnPrimary);
        int index = parent.indexOfChild(tvSubtitle);
        parent.addView(btnPrimary, index + 1);
        updateStepProgress(5);
    }

    private void showStepWaitForResults() {
        resetPrimaryButtonPosition();
        currentStep = STEP_WAIT;
        tvStepIndicator.setText("Step 6 of 7");
        tvTitle.setText("Wait for Results");
        tvSubtitle.setText("Place the test strip on a flat surface and do not move it.\n\nWait for 20 minutes while the test processes.");
        ivStepImage.setVisibility(View.VISIBLE);
        ivStepImage.setImageResource(R.drawable.ic_step_wait);
        tvTimer.setVisibility(View.VISIBLE);
        timeRemainingMs = 20 * 60 * 1000L;
        tvTimer.setText("20:00");
        if (countDownTimer != null) countDownTimer.cancel();
        timerRunning = false;
        btnPrimary.setVisibility(View.VISIBLE);
        btnPrimary.setText("Start Timer");
        btnSecondary.setText("Skip");
        btnSampleTime.setVisibility(View.GONE);
        tvQrValue.setVisibility(View.GONE);
        btnBack.setText("Back");
        updateStepProgress(6);
    }

    private void showStepInsertAnalyser() {
        resetPrimaryButtonPosition();
        currentStep = 7;
        tvStepIndicator.setText("Step 7 of 7");
        tvTitle.setText("Insert into Analyser");
        tvSubtitle.setText("Insert the test strip into the analyser to begin reading.");
        ivStepImage.setVisibility(View.VISIBLE);
        ivStepImage.setImageResource(R.drawable.ic_step_analyser);
        tvTimer.setVisibility(View.GONE);
        btnSampleTime.setVisibility(View.GONE);
        tvQrValue.setVisibility(View.GONE);
        btnPrimary.setVisibility(View.GONE);
        btnSecondary.setText("Next");
        btnSecondary.setEnabled(true);
        btnBack.setText("Back");
        updateStepProgress(7);
    }

    private void createSampleInCloud(String tubeId) {
        String uid = FirebaseAuthHelper.getCurrentUid();
        if (uid == null) return;
        Map<String, Object> sample = new HashMap<>();
        sample.put("tube_id", tubeId);
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

    private void openTimePicker() {
        Calendar c = Calendar.getInstance();
        new TimePickerDialog(this, (view, h, m) -> {
            String time = String.format(Locale.getDefault(), "%02d:%02d", h, m);
            btnSampleTime.setText("Time: " + time);
            updateSampleTimeInCloud(time);
            isSampleTimeSet = true;
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show();
    }

    private void toggleTimer() {
        if (!timerRunning) {
            countDownTimer = new CountDownTimer(timeRemainingMs, 1000) {
                public void onTick(long ms) {
                    timeRemainingMs = ms;
                    long sec = ms / 1000;
                    tvTimer.setText(String.format(Locale.getDefault(),
                            "%02d:%02d", sec / 60, sec % 60));
                }
                public void onFinish() {
                    tvTimer.setText("00:00");
                    btnPrimary.setText("Completed");
                }
            }.start();
            timerRunning = true;
            btnPrimary.setText("Pause");
        } else {
            countDownTimer.cancel();
            timerRunning = false;
            btnPrimary.setText("Resume");
        }
    }

    private void goToDashboard() {
        startActivity(new Intent(this, DashboardActivity.class));
        finish();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQUEST_SCAN_TUBE) {
            String value = data.getStringExtra("qr_value");
            if (value != null) {
                if (currentStep == STEP_SCAN_TUBE) {
                    tubeId = value;
                    tvQrValue.setVisibility(View.VISIBLE);
                    tvQrValue.setText("Tube ID: " + tubeId);
                    createSampleInCloud(tubeId);
                } else if (currentStep == STEP_CARTRIDGE_SCAN) {
                    cartridgeId = value;
                    tvQrValue.setText("Cartridge ID: " + cartridgeId);
                    saveCartridgeToCloud(cartridgeId);
                }
            }
        }
    }

    private void updateStepProgress(int step) {
        int active   = getResources().getColor(android.R.color.holo_green_dark);
        int inactive = getResources().getColor(android.R.color.darker_gray);
        View[] steps = {
                findViewById(R.id.step1),
                findViewById(R.id.step2),
                findViewById(R.id.step3),
                findViewById(R.id.step4),
                findViewById(R.id.step5),
                findViewById(R.id.step6),
                findViewById(R.id.step7)
        };
        for (int i = 0; i < steps.length; i++) {
            steps[i].setBackgroundColor(i < step ? active : inactive);
        }
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
