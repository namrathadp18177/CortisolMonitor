package com.example.myapplication;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QRScanActivity extends AppCompatActivity {

    private static final String TAG = "QRScanActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 100;
    private static final String VALID_DEVICE_NAME = "CortisolAnalyser";

    private PreviewView previewView;
    private TextView tvScanStatus;
    private ExecutorService cameraExecutor;
    private boolean qrDetected = false;
    private ProcessCameraProvider cameraProvider;

    // NEW: scan mode – "ANALYSER" (default), "TUBE", "CARTRIDGE"
    private String mode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_scan);

        previewView = findViewById(R.id.previewView);
        tvScanStatus = findViewById(R.id.tvScanStatus);

        Button btnCancel = findViewById(R.id.btnCancelScan);
        btnCancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        // Read mode from caller, default to ANALYSER
        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "ANALYSER";

        cameraExecutor = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION);
        } else {
            startCamera();
        }
    }

    private void startCamera() {
        try {
            com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider> future =
                    ProcessCameraProvider.getInstance(this);

            future.addListener(() -> {
                try {
                    ProcessCameraProvider provider = future.get();
                    cameraProvider = provider;

                    Preview preview = new Preview.Builder().build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());

                    ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build();

                    imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                    provider.unbindAll();
                    provider.bindToLifecycle(
                            this,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                    );

                } catch (Exception e) {
                    Log.e(TAG, "Camera bind error: " + e.getMessage());
                }
            }, ContextCompat.getMainExecutor(this));

        } catch (Exception e) {
            Log.e(TAG, "Camera start error: " + e.getMessage());
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void analyzeImage(ImageProxy imageProxy) {
        if (qrDetected || imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(),
                imageProxy.getImageInfo().getRotationDegrees()
        );

        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();

        BarcodeScanner scanner = BarcodeScanning.getClient(options);

        scanner.process(image)
                .addOnSuccessListener((List<Barcode> barcodes) -> {
                    for (Barcode barcode : barcodes) {
                        String rawValue = barcode.getRawValue();
                        if (rawValue != null) {
                            handleScannedQR(rawValue);
                        }
                    }
                })
                .addOnFailureListener(e -> Log.e(TAG, "Scan error: " + e.getMessage()))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    @SuppressLint("SetTextI18n")
    private void handleScannedQR(String rawValue) {
        Log.d(TAG, "Scanned: " + rawValue);

        // Tube or cartridge mode: accept any QR text, return directly
        if ("TUBE".equals(mode) || "CARTRIDGE".equals(mode)) {
            qrDetected = true;
            runOnUiThread(() -> {
                tvScanStatus.setText("QR scanned");
                Intent result = new Intent();
                result.putExtra("qr_value", rawValue);
                setResult(RESULT_OK, result);
                finish();
            });
            return;
        }

        // Default: ANALYSER mode
        // Accept either a full formatted QR (containing device name + MAC: prefix)
        // or a plain MAC address (e.g. "14:33:5C:9C:49:EA")
        String macAddress = parseMacAddress(rawValue);
        if (macAddress == null) {
            runOnUiThread(() -> {
                tvScanStatus.setText("Invalid QR. Please scan your Analyser.");
                Toast.makeText(this, "Not a valid Analyser QR code",
                        Toast.LENGTH_SHORT).show();
            });
            return;
        }

        qrDetected = true;
        runOnUiThread(() -> {
            tvScanStatus.setText("Analyser found! Connecting...");
            Intent result = new Intent();
            result.putExtra("device_address", macAddress);
            setResult(RESULT_OK, result);
            finish();
        });
    }

    private String parseMacAddress(String qrValue) {
        try {
            String trimmed = qrValue.trim();
            // Format 1: plain MAC address (e.g. "14:33:5C:9C:49:EA")
            if (trimmed.matches("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) {
                return trimmed.toUpperCase();
            }
            // Format 2: prefixed (e.g. "CortisolAnalyser;MAC:14:33:5C:9C:49:EA;")
            if (trimmed.contains("MAC:")) {
                int start = trimmed.indexOf("MAC:") + 4;
                int end = trimmed.indexOf(";", start);
                if (end == -1) end = trimmed.length();
                String mac = trimmed.substring(start, end).trim();
                if (mac.matches("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) {
                    return mac.toUpperCase();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "MAC parse error: " + e.getMessage());
        }
        return null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this,
                        "Camera permission required to scan QR",
                        Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}
