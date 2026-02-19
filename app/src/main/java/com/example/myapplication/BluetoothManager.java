package com.example.myapplication;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class BluetoothManager {
    private static final String TAG = "BluetoothManager";
    private final BluetoothAdapter bluetoothAdapter;
    private final Context context;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;
    private InputStream inputStream;
    private boolean isConnected = false;
    private OnDataReceivedListener dataReceivedListener;
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public interface OnDataReceivedListener {
        void onDataReceived(String data);
    }

    public BluetoothManager(Context context) {
        this.context = context;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public BluetoothAdapter getBluetoothAdapter() {
        return bluetoothAdapter;
    }

    public boolean isBluetoothEnabled() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("BLUETOOTH_CONNECT permission not granted");
                }
            }
            return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied checking Bluetooth state: " + e.getMessage());
            return false;
        }
    }

    public Set<BluetoothDevice> getPairedDevices() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("BLUETOOTH_CONNECT permission not granted");
                }
            }
            if (bluetoothAdapter != null) {
                return bluetoothAdapter.getBondedDevices();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied getting paired devices: " + e.getMessage());
        }
        return new HashSet<>();
    }

    public void setOnDataReceivedListener(OnDataReceivedListener listener) {
        this.dataReceivedListener = listener;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public void connectToDevice(String address) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("BLUETOOTH_CONNECT permission not granted");
                }
            }

            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            bluetoothSocket = device.createRfcommSocketToServiceRecord(MY_UUID);
            bluetoothSocket.connect();
            outputStream = bluetoothSocket.getOutputStream();
            inputStream = bluetoothSocket.getInputStream();
            isConnected = true;
            startListening();
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied connecting to device: " + e.getMessage());
            isConnected = false;
        } catch (IOException e) {
            Log.e(TAG, "Error connecting to device: " + e.getMessage());
            isConnected = false;
            cleanup();
        }
    }

    private void startListening() {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[1024];
            int bytes;

            while (isConnected) {
                try {
                    bytes = inputStream.read(buffer);
                    String data = new String(buffer, 0, bytes);
                    if (dataReceivedListener != null) {
                        dataReceivedListener.onDataReceived(data);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error reading data: " + e.getMessage());
                    isConnected = false;
                    cleanup();
                    break;
                }
            }
        });
        thread.start();
    }

    public void sendData(String data) {
        if (outputStream != null && isConnected) {
            try {
                outputStream.write(data.getBytes());
            } catch (IOException e) {
                Log.e(TAG, "Error sending data: " + e.getMessage());
                isConnected = false;
                cleanup();
            }
        }
    }

    public void disconnect() {
        isConnected = false;
        cleanup();
    }

    private void cleanup() {
        try {
            if (outputStream != null) {
                outputStream.close();
            }
            if (inputStream != null) {
                inputStream.close();
            }
            if (bluetoothSocket != null) {
                bluetoothSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing connection: " + e.getMessage());
        } finally {
            outputStream = null;
            inputStream = null;
            bluetoothSocket = null;
        }
    }
}