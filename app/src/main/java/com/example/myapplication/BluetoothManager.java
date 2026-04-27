package com.example.myapplication;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.ActivityCompat;

import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

/**
 * Singleton BLE GATT manager for the Optical Detector (new firmware).
 *
 * Device name  : Optical_Detector_1
 * Service UUID : 19b10000-e8f2-537e-4f6c-d104768a1214
 *
 * Characteristics:
 *   Char 1 – Photodiode Data  (Write + Notify)  19b10001…
 *   Char 2 – LED Control      (Read  + Write)   19b10002…
 *   Char 3 – Gain Control     (Read  + Write)   19b10003…
 *   Char 4 – Device Status    (Read  + Write + Notify) 19b10004…
 *
 * Default values (from integration guide):
 *   LED brightness : 200  (90-100% recommended for fluorescence)
 *   Gain wiper     : 128  (mid-scale, balanced sensitivity)
 */
public class BluetoothManager {

    private static final String TAG = "BluetoothManager";

    // ---- BLE UUIDs ------------------------------------------------------------
    public static final UUID OPTICAL_SERVICE_UUID =
            UUID.fromString("19b10000-e8f2-537e-4f6c-d104768a1214");
    public static final UUID CHAR_DATA_UUID =
            UUID.fromString("19b10001-e8f2-537e-4f6c-d104768a1214");
    public static final UUID CHAR_LED_UUID =
            UUID.fromString("19b10002-e8f2-537e-4f6c-d104768a1214");
    public static final UUID CHAR_GAIN_UUID =
            UUID.fromString("19b10003-e8f2-537e-4f6c-d104768a1214");
    public static final UUID CHAR_STATUS_UUID =
            UUID.fromString("19b10004-e8f2-537e-4f6c-d104768a1214");

    // CCCD descriptor UUID (standard BLE descriptor for enabling notifications)
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // ---- Packet framing constants ---------------------------------------------
    public static final byte START_BIT     = (byte) 0xFF;
    public static final byte SEPARATOR_BIT = (byte) 0xFE;
    public static final byte END_BIT       = (byte) 0xFD;

    // ---- Default hardware parameters (per integration guide) -----------------
    public static final byte DEFAULT_LED_BRIGHTNESS = (byte) 200; // 90% for fluorescence
    public static final byte DEFAULT_GAIN_WIPER     = (byte) 128; // mid-scale

    // ---- Commands for Char 1 -------------------------------------------------
    public static final byte CMD_DARK   = (byte) 0x00;
    public static final byte CMD_SIGNAL = (byte) 0x01;

    // ---- Singleton -----------------------------------------------------------
    private static BluetoothManager instance;

    public static synchronized BluetoothManager getInstance(Context context) {
        if (instance == null) {
            instance = new BluetoothManager(context.getApplicationContext());
        }
        return instance;
    }

    // ---- Callback interface --------------------------------------------------
    public interface BleCallback {
        void onConnected();
        void onDisconnected();
        /** Called when a 13-byte optical data notification arrives on Char 1. */
        void onOpticalData(byte[] packet);
        /** Called when a 6-byte status notification arrives on Char 4. */
        void onStatusData(byte[] status);
    }

    // ---- Internal state ------------------------------------------------------
    private final Context    context;
    private final Handler    mainHandler = new Handler(Looper.getMainLooper());
    private BluetoothGatt    gatt;
    private boolean          connected   = false;
    private BleCallback      callback;

    // Write queue: BLE GATT only allows one write at a time
    private final Queue<Runnable> writeQueue    = new LinkedList<>();
    private volatile boolean      writeInFlight = false;

    // ---- Constructor (private – use getInstance) ------------------------------
    private BluetoothManager(Context context) {
        this.context = context;
    }

    // ---- Public API ----------------------------------------------------------

    public void setCallback(BleCallback cb) {
        this.callback = cb;
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * Connect to the BLE device with the given MAC address.
     * Safe to call from any thread.
     */
    public void connect(String address) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.e(TAG, "Bluetooth adapter not available or not enabled");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted");
            return;
        }

        // If already connected to the same device, do nothing
        if (connected && gatt != null &&
                gatt.getDevice().getAddress().equalsIgnoreCase(address)) {
            Log.i(TAG, "Already connected to " + address);
            mainHandler.post(() -> { if (callback != null) callback.onConnected(); });
            return;
        }

        // Disconnect any existing connection first
        disconnectInternal();

        BluetoothDevice device = adapter.getRemoteDevice(address);
        Log.i(TAG, "Connecting to BLE device: " + address);

        // connectGatt must be called on the main thread on some Android versions
        mainHandler.post(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            gatt = device.connectGatt(context, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
        });
    }

    /** Gracefully disconnect from the BLE device. */
    public void disconnect() {
        disconnectInternal();
    }

    /** Write a single byte to the LED Control characteristic (Char 2). */
    public void setLedBrightness(byte value) {
        enqueueWrite(CHAR_LED_UUID, new byte[]{value});
    }

    /** Write a single byte to the Gain Control characteristic (Char 3). */
    public void setGain(byte value) {
        enqueueWrite(CHAR_GAIN_UUID, new byte[]{value});
    }

    /** Write 0x00 to Char 1 → triggers a dark (baseline) reading. */
    public void triggerDarkRead() {
        enqueueWrite(CHAR_DATA_UUID, new byte[]{CMD_DARK});
    }

    /** Write 0x01 to Char 1 → triggers a signal reading (LEDs ON). */
    public void triggerSignalRead() {
        enqueueWrite(CHAR_DATA_UUID, new byte[]{CMD_SIGNAL});
    }

    /** Write 0x00 to Char 4 → requests a 6-byte status notification. */
    public void requestStatus() {
        enqueueWrite(CHAR_STATUS_UUID, new byte[]{(byte) 0x00});
    }

    // ---- Write queue helpers -------------------------------------------------

    private void enqueueWrite(UUID charUuid, byte[] value) {
        writeQueue.add(() -> executeWrite(charUuid, value));
        drainWriteQueue();
    }

    private synchronized void drainWriteQueue() {
        if (writeInFlight || writeQueue.isEmpty() || !connected || gatt == null) return;
        Runnable next = writeQueue.poll();
        if (next != null) {
            writeInFlight = true;
            next.run();
        }
    }

    private void executeWrite(UUID charUuid, byte[] value) {
        if (gatt == null || !connected) {
            writeInFlight = false;
            drainWriteQueue();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            writeInFlight = false;
            return;
        }

        BluetoothGattService service = gatt.getService(OPTICAL_SERVICE_UUID);
        if (service == null) {
            Log.e(TAG, "Optical service not found");
            writeInFlight = false;
            drainWriteQueue();
            return;
        }

        BluetoothGattCharacteristic characteristic = service.getCharacteristic(charUuid);
        if (characteristic == null) {
            Log.e(TAG, "Characteristic not found: " + charUuid);
            writeInFlight = false;
            drainWriteQueue();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: new writeCharacteristic API
            int writeType = (characteristic.getProperties() &
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            gatt.writeCharacteristic(characteristic, value, writeType);
        } else {
            characteristic.setValue(value);
            characteristic.setWriteType(
                    (characteristic.getProperties() &
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                            ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            gatt.writeCharacteristic(characteristic);
        }
    }

    // ---- Enable notifications on a characteristic ----------------------------

    private void enableNotifications(UUID charUuid) {
        if (gatt == null || !connected) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) return;

        BluetoothGattService service = gatt.getService(OPTICAL_SERVICE_UUID);
        if (service == null) return;

        BluetoothGattCharacteristic characteristic = service.getCharacteristic(charUuid);
        if (characteristic == null) return;

        gatt.setCharacteristicNotification(characteristic, true);

        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
        if (descriptor != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            } else {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(descriptor);
            }
        }
    }

    // ---- Disconnect (internal) -----------------------------------------------

    private void disconnectInternal() {
        connected = false;
        writeQueue.clear();
        writeInFlight = false;
        if (gatt != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED) {
                gatt = null;
                return;
            }
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
    }

    // ---- GATT Callback -------------------------------------------------------

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT connected. Discovering services…");
                connected = true;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                                != PackageManager.PERMISSION_GRANTED) return;
                g.discoverServices();

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT disconnected (status=" + status + ")");
                connected = false;
                writeQueue.clear();
                writeInFlight = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                                != PackageManager.PERMISSION_GRANTED) {
                    gatt = null;
                    return;
                }
                g.close();
                gatt = null;
                mainHandler.post(() -> { if (callback != null) callback.onDisconnected(); });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: status=" + status);
                return;
            }
            Log.i(TAG, "Services discovered. Enabling notifications…");

            // Enable notifications on Char 1 (optical data) and Char 4 (status)
            // Must be sequential (each requires a descriptor write to complete first).
            // We use a short delay between them to avoid GATT congestion.
            mainHandler.post(() -> enableNotifications(CHAR_DATA_UUID));
            mainHandler.postDelayed(() -> enableNotifications(CHAR_STATUS_UUID), 400);

            // Notify connected after notifications are set up
            mainHandler.postDelayed(() -> {
                if (callback != null) callback.onConnected();
            }, 800);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g,
                BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Write failed on " + characteristic.getUuid() + " status=" + status);
            }
            // Release the write lock so the next queued write can proceed
            writeInFlight = false;
            drainWriteQueue();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g,
                BluetoothGattDescriptor descriptor, int status) {
            // Descriptor writes (CCCD) are also serialised writes; release lock
            writeInFlight = false;
            drainWriteQueue();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g,
                BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data == null) return;

            UUID uuid = characteristic.getUuid();
            if (CHAR_DATA_UUID.equals(uuid) && data.length == 13) {
                // Validate framing: byte[0]=0xFF, byte[12]=0xFD
                if ((data[0] & 0xFF) == 0xFF && (data[12] & 0xFF) == 0xFD) {
                    mainHandler.post(() -> { if (callback != null) callback.onOpticalData(data); });
                } else {
                    Log.w(TAG, "Optical packet framing invalid – discarding");
                }
            } else if (CHAR_STATUS_UUID.equals(uuid) && data.length == 6) {
                mainHandler.post(() -> { if (callback != null) callback.onStatusData(data); });
            }
        }

        // API 33+ notification callback (overrides the deprecated one above)
        @Override
        public void onCharacteristicChanged(BluetoothGatt g,
                BluetoothGattCharacteristic characteristic, byte[] data) {
            if (data == null) return;

            UUID uuid = characteristic.getUuid();
            if (CHAR_DATA_UUID.equals(uuid) && data.length == 13) {
                if ((data[0] & 0xFF) == 0xFF && (data[12] & 0xFF) == 0xFD) {
                    mainHandler.post(() -> { if (callback != null) callback.onOpticalData(data); });
                } else {
                    Log.w(TAG, "Optical packet framing invalid – discarding");
                }
            } else if (CHAR_STATUS_UUID.equals(uuid) && data.length == 6) {
                mainHandler.post(() -> { if (callback != null) callback.onStatusData(data); });
            }
        }
    };
}
