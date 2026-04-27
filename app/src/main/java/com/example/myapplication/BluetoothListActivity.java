package com.example.myapplication;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * BLE device scanner activity.
 *
 * Scans for BLE devices advertising the Optical Detector service UUID
 * (19b10000-e8f2-537e-4f6c-d104768a1214).  Devices named
 * "Optical_Detector_1" are shown first.  Tapping a device returns its
 * MAC address to the calling activity via the "device_address" Intent extra.
 */
public class BluetoothListActivity extends AppCompatActivity {

    private static final int    REQUEST_PERMISSIONS  = 2;
    private static final long   SCAN_TIMEOUT_MS      = 15_000; // 15 s scan window

    private ListView             listViewDevices;
    private ProgressBar          progressScanning;
    private TextView             tvStatus;
    private BluetoothAdapter     bluetoothAdapter;
    private BluetoothLeScanner   leScanner;
    private ArrayAdapter<String> deviceArrayAdapter;
    private ArrayList<BluetoothDevice> deviceList;

    private boolean  isScanning = false;
    private final Handler stopHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bluetooth_list);
        initializeViews();
        setupBluetooth();
    }

    // -------------------------------------------------------------------------
    private void initializeViews() {
        listViewDevices  = findViewById(R.id.listViewDevices);
        progressScanning = findViewById(R.id.progressScanning);
        tvStatus         = findViewById(R.id.tvStatus);

        deviceList          = new ArrayList<>();
        deviceArrayAdapter  = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, new ArrayList<>());
        listViewDevices.setAdapter(deviceArrayAdapter);

        listViewDevices.setOnItemClickListener((parent, view, position, id) -> {
            if (position < deviceList.size()) {
                connectToDevice(deviceList.get(position));
            }
        });
    }

    // -------------------------------------------------------------------------
    private void setupBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available on this device",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        checkAndRequestPermissions();
    }

    // -------------------------------------------------------------------------
    private void checkAndRequestPermissions() {
        ArrayList<String> missing = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                    != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
                    != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_ADMIN);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);

        android.util.Log.d("BLEScan", "Missing permissions: " + missing.toString());
        if (!missing.isEmpty()) {
            android.util.Log.d("BLEScan", "Requesting permissions...");
            ActivityCompat.requestPermissions(this,
                    missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        } else {
            android.util.Log.d("BLEScan", "All permissions granted, proceeding...");
            proceedWithBluetoothSetup();
        }
    }

    // -------------------------------------------------------------------------
    private void proceedWithBluetoothSetup() {
        try {
            if (!bluetoothAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                if (ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED) {
                    startActivityForResult(enableBtIntent, 1);
                }
            } else {
                startBleScan();
            }
        } catch (SecurityException e) {
            Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    // -------------------------------------------------------------------------
    private void startBleScan() {
        android.util.Log.d("BLEScan", "startBleScan() called, isScanning=" + isScanning);
        if (isScanning) return;

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Toast.makeText(this, "BLUETOOTH_SCAN permission required", Toast.LENGTH_SHORT).show();
            return;
        }

        leScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (leScanner == null) {
            Toast.makeText(this, "BLE not supported on this device", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        deviceArrayAdapter.clear();
        deviceList.clear();

        // Scan with no filter — ESP32 often doesn't advertise service UUID
        // until after connection, so filtering by UUID hides it from the list.
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        tvStatus.setText("Scanning for devices…");
        progressScanning.setVisibility(View.VISIBLE);
        isScanning = true;

        android.util.Log.d("BLEScan", "Calling leScanner.startScan(), leScanner=" + leScanner);
        leScanner.startScan(null, settings, scanCallback);
        android.util.Log.d("BLEScan", "leScanner.startScan() returned");

        // Auto-stop after SCAN_TIMEOUT_MS
        stopHandler.postDelayed(this::stopBleScan, SCAN_TIMEOUT_MS);
    }

    // -------------------------------------------------------------------------
    private void stopBleScan() {
        if (!isScanning) return;
        isScanning = false;

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (leScanner != null) leScanner.stopScan(scanCallback);
        }

        progressScanning.setVisibility(View.GONE);
        tvStatus.setText(deviceList.isEmpty()
                ? "No devices found. Ensure device is powered on."
                : "Scan complete – tap a device to connect.");
    }

    // -------------------------------------------------------------------------
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) return;

            // Avoid duplicates
            for (BluetoothDevice d : deviceList) {
                if (d.getAddress().equals(device.getAddress())) return;
            }

            String name = "<unknown>";
            try {
                if (ActivityCompat.checkSelfPermission(BluetoothListActivity.this,
                        Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED) {
                    String n = device.getName();
                    if (n != null && !n.isEmpty()) name = n;
                }
            } catch (SecurityException ignored) {}

            android.util.Log.d("BLEScan", "Found device: " + name + " | " + device.getAddress());

            String displayName = name + "\n" + device.getAddress()
                    + "  RSSI: " + result.getRssi() + " dBm";

            // Always show Optical_Detector_1 at the top
            if (name.equals("Optical_Detector_1")) {
                deviceList.add(0, device);
                deviceArrayAdapter.insert(displayName, 0);
            } else {
                deviceList.add(device);
                deviceArrayAdapter.add(displayName);
            }
            deviceArrayAdapter.notifyDataSetChanged();
        }

        @Override
        public void onScanFailed(int errorCode) {
            Toast.makeText(BluetoothListActivity.this,
                    "BLE scan failed (code " + errorCode + ")", Toast.LENGTH_LONG).show();
            progressScanning.setVisibility(View.GONE);
            tvStatus.setText("Scan failed");
            isScanning = false;
        }
    };

    // -------------------------------------------------------------------------
    private void connectToDevice(BluetoothDevice device) {
        stopBleScan();
        try {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED) {
                Intent resultIntent = new Intent();
                resultIntent.putExtra("device_address", device.getAddress());
                resultIntent.putExtra("device_name",
                        device.getName() != null ? device.getName() : "Optical_Detector_1");
                setResult(RESULT_OK, resultIntent);
                finish();
            }
        } catch (SecurityException e) {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
        }
    }

    // -------------------------------------------------------------------------
    @Override
    public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) { allGranted = false; break; }
            }
            if (allGranted) proceedWithBluetoothSetup();
            else {
                Toast.makeText(this, "Required permissions not granted",
                        Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK) startBleScan();
        else if (requestCode == 1) {
            Toast.makeText(this, "Bluetooth is required", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopHandler.removeCallbacksAndMessages(null);
        stopBleScan();
    }
}
