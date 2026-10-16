package com.example.myapplication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Foreground service that owns the [WatchHealthManager] trackers, batches incoming
 * EDA / heart-rate+IBI / skin-temperature / accelerometer samples, and pushes each batch to
 * the paired phone over the Wear OS Data Layer (MessageClient), roughly every
 * [SEND_INTERVAL_MS].
 *
 * Each batch carries a [sessionId] generated once per Start tap, so the phone can group
 * everything from one recording together even across multiple sessions or app restarts.
 *
 * Buffers are only cleared once a batch is confirmed sent — if the watch briefly loses its
 * connection to the phone, samples accumulate and go out on the next successful attempt
 * instead of being silently dropped. Buffers are capped (see [MAX_BUFFERED_SAMPLES] /
 * [MAX_BUFFERED_ACCEL_SAMPLES]) so a very long disconnect drops the oldest data instead of
 * growing memory unboundedly.
 *
 * minSdk for this module is 30 (Android 11), so FOREGROUND_SERVICE_TYPE_HEALTH is always
 * available — no need to branch on SDK version for the startForeground() call.
 */
class VitalsStreamingService : Service(), WatchHealthManager.VitalsListener {

    private lateinit var healthManager: WatchHealthManager
    private lateinit var messageClient: MessageClient
    private val handler = Handler(Looper.getMainLooper())

    private val edaBuffer = CopyOnWriteArrayList<JSONObject>()
    private val hrBuffer = CopyOnWriteArrayList<JSONObject>()
    private val skinTempBuffer = CopyOnWriteArrayList<JSONObject>()
    private val accelBuffer = CopyOnWriteArrayList<JSONObject>()

    private var running = false
    private var sendInFlight = false
    private var sessionId: String = ""

    private val sendRunnable = object : Runnable {
        override fun run() {
            flushAndSend()
            if (running) handler.postDelayed(this, SEND_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        messageClient = Wearable.getMessageClient(this)
        healthManager = WatchHealthManager(this, this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            // Prefer the app_session_id the phone sent (see VitalsControlListenerService),
            // so this recording shares one id with whatever questionnaire/biomarker capture
            // it's part of, instead of each side tracking its own. Falls back to minting a
            // fresh one if it's missing (e.g. started without going through the phone's
            // control path, or an old phone build) so recording still works either way.
            val fromPhone = intent?.getStringExtra("appSessionId")
            sessionId = if (!fromPhone.isNullOrEmpty()) fromPhone else "session_" + System.currentTimeMillis()
            Log.i(TAG, "Starting new recording session: $sessionId" +
                    (if (fromPhone.isNullOrEmpty()) " (locally generated)" else " (from phone)"))
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Starting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            )
            healthManager.connect()
            handler.postDelayed(sendRunnable, SEND_INTERVAL_MS)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(sendRunnable)
        healthManager.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- WatchHealthManager.VitalsListener ----

    override fun onServiceReady() {
        updateNotification("Streaming HRV · EDA · skin temp")
        broadcastStatus(true, null)
    }

    override fun onServiceEnded() {
        updateNotification("Disconnected — will retry")
        broadcastStatus(false, "Health Tracking Service disconnected")
    }

    override fun onTrackerUnavailable(trackerType: String, reason: String) {
        Log.w(TAG, "$trackerType unavailable: $reason")
        broadcastStatus(true, "$trackerType unavailable: $reason")
    }

    override fun onTrackerError(trackerType: String, message: String) {
        Log.e(TAG, "$trackerType error: $message")
        broadcastStatus(true, "$trackerType error: $message")
    }

    override fun onEdaSample(timestampMs: Long, skinConductance: Float, status: Int) {
        edaBuffer.add(JSONObject().apply {
            put("t", timestampMs)
            put("conductance", skinConductance)
            put("status", status)
        })
    }

    override fun onHeartRateSample(
        timestampMs: Long,
        heartRate: Int,
        hrStatus: Int,
        ibiListMs: IntArray,
        ibiStatusList: IntArray
    ) {
        hrBuffer.add(JSONObject().apply {
            put("t", timestampMs)
            put("hr", heartRate)
            put("hrStatus", hrStatus)
            put("ibi", JSONArray(ibiListMs.toList()))
            put("ibiStatus", JSONArray(ibiStatusList.toList()))
        })
    }

    override fun onSkinTempSample(timestampMs: Long, ambientC: Float, skinC: Float, status: Int) {
        skinTempBuffer.add(JSONObject().apply {
            put("t", timestampMs)
            put("ambientC", ambientC)
            put("skinC", skinC)
            put("status", status)
        })
    }

    override fun onAccelSample(timestampMs: Long, x: Float, y: Float, z: Float, status: Int) {
        accelBuffer.add(JSONObject().apply {
            put("t", timestampMs)
            put("x", x)
            put("y", y)
            put("z", z)
            put("status", status)
        })
    }

    // ---- Batch send over the Data Layer ----

    private fun flushAndSend() {
        healthManager.flushAll()
        // Small delay so flush()-triggered onDataReceived callbacks land in the buffers
        // before we snapshot and send them.
        handler.postDelayed({ sendBatch() }, FLUSH_SETTLE_MS)
    }

    private fun sendBatch() {
        if (sendInFlight) return // previous attempt still resolving; this tick's data just waits in the buffers
        if (edaBuffer.isEmpty() && hrBuffer.isEmpty() && skinTempBuffer.isEmpty() && accelBuffer.isEmpty()) return

        // Snapshot now, but don't remove from the live buffers yet — only once send is
        // confirmed successful (below). A disconnect or send failure just means "try again
        // next tick with everything accumulated so far" instead of silently losing the batch.
        val edaSnapshot = edaBuffer.toList()
        val hrSnapshot = hrBuffer.toList()
        val skinTempSnapshot = skinTempBuffer.toList()
        val accelSnapshot = accelBuffer.toList()

        val payload = JSONObject().apply {
            put("sessionId", sessionId)
            put("eda", JSONArray(edaSnapshot))
            put("hr", JSONArray(hrSnapshot))
            put("skinTemp", JSONArray(skinTempSnapshot))
            put("accel", JSONArray(accelSnapshot))
        }

        sendInFlight = true
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    Log.w(TAG, "No connected phone node — batch held for retry (${payload.toString().length} bytes buffered so far)")
                    sendInFlight = false
                    enforceBufferCaps()
                    return@addOnSuccessListener
                }
                val bytes = payload.toString().toByteArray(Charsets.UTF_8)
                var pending = nodes.size
                var anySuccess = false
                for (node in nodes) {
                    messageClient.sendMessage(node.id, VITALS_PATH, bytes)
                        .addOnSuccessListener {
                            anySuccess = true
                            pending--
                            if (pending == 0) {
                                onBatchSendSettled(anySuccess, edaSnapshot, hrSnapshot, skinTempSnapshot, accelSnapshot)
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "send failed to ${node.id}", e)
                            pending--
                            if (pending == 0) {
                                onBatchSendSettled(anySuccess, edaSnapshot, hrSnapshot, skinTempSnapshot, accelSnapshot)
                            }
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "connectedNodes lookup failed", e)
                sendInFlight = false
                enforceBufferCaps()
            }
    }

    private fun onBatchSendSettled(
        success: Boolean,
        edaSnapshot: List<JSONObject>,
        hrSnapshot: List<JSONObject>,
        skinTempSnapshot: List<JSONObject>,
        accelSnapshot: List<JSONObject>
    ) {
        sendInFlight = false
        if (success) {
            // Reference-equality removal (org.json.JSONObject doesn't override equals()), so
            // this only removes the exact entries that were actually sent, not anything
            // appended to the buffers while the send was in flight.
            edaBuffer.removeAll(edaSnapshot)
            hrBuffer.removeAll(hrSnapshot)
            skinTempBuffer.removeAll(skinTempSnapshot)
            accelBuffer.removeAll(accelSnapshot)
        }
        // On failure: leave buffers as-is, next tick retries with everything accumulated since.
        enforceBufferCaps()
    }

    /** Bounds memory if the watch is disconnected from the phone for a long time — drops the
     *  oldest samples past the cap rather than growing forever. See Watch8_Data_Collection_Guide.md
     *  §5 for DataClient/ChannelClient alternatives if long disconnects turn out to be common. */
    private fun enforceBufferCaps() {
        trimBuffer(edaBuffer, MAX_BUFFERED_SAMPLES)
        trimBuffer(hrBuffer, MAX_BUFFERED_SAMPLES)
        trimBuffer(skinTempBuffer, MAX_BUFFERED_SAMPLES)
        trimBuffer(accelBuffer, MAX_BUFFERED_ACCEL_SAMPLES)
    }

    private fun trimBuffer(buffer: CopyOnWriteArrayList<JSONObject>, cap: Int) {
        val overflow = buffer.size - cap
        if (overflow > 0) {
            Log.w(TAG, "Buffer exceeded $cap samples while disconnected — dropping oldest $overflow")
            repeat(overflow) {
                if (buffer.isNotEmpty()) buffer.removeAt(0)
            }
        }
    }

    // ---- Notification plumbing ----

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Vitals streaming", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CortisolMonitor")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun broadcastStatus(connected: Boolean, error: String?) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            putExtra("connected", connected)
            putExtra("error", error)
            setPackage(packageName)
        })
    }

    companion object {
        private const val TAG = "VitalsStreamingService"
        private const val CHANNEL_ID = "vitals_streaming"
        private const val NOTIFICATION_ID = 42
        const val VITALS_PATH = "/vitals"
        const val ACTION_STATUS = "com.example.myapplication.VITALS_STATUS"
        private const val SEND_INTERVAL_MS = 10_000L
        private const val FLUSH_SETTLE_MS = 300L
        private const val MAX_BUFFERED_SAMPLES = 3600       // ~1 hour at the 1 Hz EDA/HR/skin-temp rate
        private const val MAX_BUFFERED_ACCEL_SAMPLES = 90_000 // ~1 hour at the 25 Hz accelerometer rate
    }
}
