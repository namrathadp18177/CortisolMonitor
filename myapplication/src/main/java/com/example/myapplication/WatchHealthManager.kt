package com.example.myapplication

import android.content.Context
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey

/**
 * Wraps the Samsung Health Sensor SDK: connects to HealthTrackingService, checks which
 * trackers this specific watch/firmware supports, and starts EDA_CONTINUOUS,
 * HEART_RATE_CONTINUOUS (which also carries IBI, i.e. beat-to-beat intervals for HRV),
 * SKIN_TEMPERATURE_CONTINUOUS, and ACCELEROMETER_CONTINUOUS simultaneously — all four are
 * "continuous" tracker types so they don't conflict the way an on-demand tracker (e.g.
 * ECG_ON_DEMAND) would. Accelerometer is included as a motion/activity-context signal: for
 * a cortisol-correlation goal, physical movement independently raises HR, lowers HRV, and
 * increases EDA — without a motion signal there's no way to separate "stress" from
 * "walking" in the other three streams.
 *
 * NOTE: this file references classes from samsung-health-sensor-api.aar, which is not
 * bundled in this repo (see myapplication/libs/README.txt). It will not compile until that
 * .aar is dropped into myapplication/libs/.
 *
 * NOTE on field names: Samsung's public docs (see Watch8_Data_Collection_Guide.md §B5) don't
 * spell out every ValueKey field name. AMBIENT_TEMPERATURE / OBJECT_TEMPERATURE / STATUS
 * (SkinTemperatureSet) were guessed from Samsung's naming convention and confirmed correct
 * once the real .aar compiled. X / Y / Z / STATUS (AccelerometerSet) below are the same kind
 * of guess, not yet confirmed against real data — check Android Studio's autocomplete or the
 * first real CSV export if this doesn't compile or the values look wrong.
 */
class WatchHealthManager(
    private val context: Context,
    private val listener: VitalsListener
) {

    interface VitalsListener {
        fun onEdaSample(timestampMs: Long, skinConductance: Float, status: Int)
        fun onHeartRateSample(
            timestampMs: Long,
            heartRate: Int,
            hrStatus: Int,
            ibiListMs: IntArray,
            ibiStatusList: IntArray
        )
        fun onSkinTempSample(timestampMs: Long, ambientC: Float, skinC: Float, status: Int)
        fun onAccelSample(timestampMs: Long, x: Float, y: Float, z: Float, status: Int)
        fun onTrackerUnavailable(trackerType: String, reason: String)
        fun onTrackerError(trackerType: String, message: String)
        fun onServiceReady()
        fun onServiceEnded()
    }

    private var healthTrackingService: HealthTrackingService? = null
    private var edaTracker: HealthTracker? = null
    private var hrTracker: HealthTracker? = null
    private var skinTempTracker: HealthTracker? = null
    private var accelTracker: HealthTracker? = null

    // One-time diagnostic latch for the IBI_LIST empty-array investigation below (see
    // hrListener) — logs at most once per WatchHealthManager instance instead of every second.

    @Volatile
    var isConnected: Boolean = false
        private set

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            isConnected = true
            Log.d(TAG, "HealthTrackingService connected")
            startAvailableTrackers()
            listener.onServiceReady()
        }

        override fun onConnectionEnded() {
            isConnected = false
            Log.d(TAG, "HealthTrackingService connection ended")
            listener.onServiceEnded()
        }

        override fun onConnectionFailed(e: HealthTrackerException) {
            isConnected = false
            Log.e(TAG, "HealthTrackingService connection failed", e)
            listener.onTrackerError("connection", e.message ?: "connection failed (code ${e.errorCode})")
        }
    }

    fun connect() {
        if (healthTrackingService != null) return
        val service = HealthTrackingService(connectionListener, context)
        healthTrackingService = service
        service.connectService()
    }

    fun disconnect() {
        stopAllTrackers()
        healthTrackingService?.disconnectService()
        healthTrackingService = null
        isConnected = false
    }

    private fun startAvailableTrackers() {
        val svc = healthTrackingService ?: return
        val available = svc.trackingCapability.supportHealthTrackerTypes

        startOneTracker(svc, available, HealthTrackerType.EDA_CONTINUOUS,
            "not supported on this watch/firmware (needs Watch8-series+)") {
            edaTracker = it.also { t -> t.setEventListener(edaListener) }
        }

        startOneTracker(svc, available, HealthTrackerType.HEART_RATE_CONTINUOUS,
            "not supported on this watch/firmware") {
            hrTracker = it.also { t -> t.setEventListener(hrListener) }
        }

        startOneTracker(svc, available, HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS,
            "not supported on this watch/firmware (needs Watch5+)") {
            skinTempTracker = it.also { t -> t.setEventListener(skinTempListener) }
        }

        startOneTracker(svc, available, HealthTrackerType.ACCELEROMETER_CONTINUOUS,
            "not supported on this watch/firmware") {
            accelTracker = it.also { t -> t.setEventListener(accelListener) }
        }
    }

    /** Starting any one tracker (permission not actually granted despite the runtime check,
     *  SDK policy not yet downloaded, firmware quirk, etc.) must never be allowed to crash
     *  the whole service — that would take down the other three trackers with it. Every
     *  failure here is reported through the listener instead of thrown. */
    private fun startOneTracker(
        svc: HealthTrackingService,
        available: Collection<HealthTrackerType>,
        type: HealthTrackerType,
        unavailableReason: String,
        onStarted: (HealthTracker) -> Unit
    ) {
        if (!available.contains(type)) {
            listener.onTrackerUnavailable(type.name, unavailableReason)
            return
        }
        try {
            onStarted(svc.getHealthTracker(type))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start $type", e)
            listener.onTrackerError(type.name, "failed to start: ${e.message}")
        }
    }

    private fun stopAllTrackers() {
        edaTracker?.unsetEventListener()
        hrTracker?.unsetEventListener()
        skinTempTracker?.unsetEventListener()
        accelTracker?.unsetEventListener()
        edaTracker = null
        hrTracker = null
        skinTempTracker = null
        accelTracker = null
    }

    /** Forces delivery of anything batched while the screen was off. Call right before sending a batch to the phone. */
    fun flushAll() {
        edaTracker?.flush()
        hrTracker?.flush()
        skinTempTracker?.flush()
        accelTracker?.flush()
    }

    private val edaListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(list: MutableList<DataPoint>) {
            for (dp in list) {
                try {
                    val conductance = dp.getValue(ValueKey.EdaSet.SKIN_CONDUCTANCE)
                    val status = dp.getValue(ValueKey.EdaSet.STATUS)
                    listener.onEdaSample(dp.timestamp, conductance, status)
                } catch (e: Exception) {
                    // A single malformed sample must never crash the service — log and skip it
                    // rather than take down every tracker with it.
                    Log.e(TAG, "Failed to parse EDA sample", e)
                    listener.onTrackerError("EDA_CONTINUOUS", "sample parse error: ${e.message}")
                }
            }
        }

        override fun onFlushCompleted() {}

        override fun onError(error: HealthTracker.TrackerError) {
            listener.onTrackerError("EDA_CONTINUOUS", error.name)
        }
    }

    private val hrListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(list: MutableList<DataPoint>) {
            for (dp in list) {
                try {
                    val hr = dp.getValue(ValueKey.HeartRateSet.HEART_RATE)
                    val hrStatus = dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)
                    // CONFIRMED 2026-08-25 via on-device logging (real IBI values, e.g.
                    // [1005, 610, 1740, 792, 1161] ms): ValueKey.HeartRateSet.IBI_LIST /
                    // IBI_STATUS_LIST are declared as raw types in the Samsung aar, so Kotlin
                    // sees DataPoint.getValue(...) as returning `Any` here, and the ACTUAL
                    // runtime type is java.util.ArrayList<Integer> — NOT IntArray. The old
                    // `as? IntArray` safe cast always failed against that and silently fell
                    // back to an empty array, which is why every exported CSV had
                    // ibi_list_ms = [] despite valid heart-rate readings. toIntArray() below
                    // normalizes the real ArrayList<Integer> shape (and still tolerates a
                    // genuine IntArray, in case a future SDK/firmware build changes it back).
                    val ibiList = toIntArray(dp.getValue(ValueKey.HeartRateSet.IBI_LIST))
                    val ibiStatusList = toIntArray(dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST))

                    listener.onHeartRateSample(dp.timestamp, hr, hrStatus, ibiList, ibiStatusList)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse HR sample", e)
                    listener.onTrackerError("HEART_RATE_CONTINUOUS", "sample parse error: ${e.message}")
                }
            }
        }

        override fun onFlushCompleted() {}

        override fun onError(error: HealthTracker.TrackerError) {
            listener.onTrackerError("HEART_RATE_CONTINUOUS", error.name)
        }
    }

    private val skinTempListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(list: MutableList<DataPoint>) {
            for (dp in list) {
                try {
                    val ambient = dp.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)
                    val skin = dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
                    val status = dp.getValue(ValueKey.SkinTemperatureSet.STATUS)
                    listener.onSkinTempSample(dp.timestamp, ambient, skin, status)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse skin temp sample", e)
                    listener.onTrackerError("SKIN_TEMPERATURE_CONTINUOUS", "sample parse error: ${e.message}")
                }
            }
        }

        override fun onFlushCompleted() {}

        override fun onError(error: HealthTracker.TrackerError) {
            listener.onTrackerError("SKIN_TEMPERATURE_CONTINUOUS", error.name)
        }
    }

    private val accelListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(list: MutableList<DataPoint>) {
            for (dp in list) {
                try {
                    // ACCELEROMETER_X/Y/Z are raw accelerometer counts, Int-typed under the
                    // hood (confirmed by the compiler), not m/s². See Samsung's "Understanding
                    // and Converting Galaxy Watch Accelerometer Data" blog: divide by 4096 for
                    // g-force, or by 4096 then multiply by 9.80665 for m/s². Converted to Float
                    // here since that's what the rest of this pipeline expects.
                    // STATUS is declared with a Java wildcard (unlike EdaSet.STATUS /
                    // SkinTemperatureSet.STATUS, which are plain ValueKey<Integer>) — Kotlin
                    // can't unify a wildcard-typed ValueKey against getValue<T>()'s invariant T
                    // even with an explicit witness, so the key itself needs an unchecked cast.
                    val x = dp.getValue<Int>(ValueKey.AccelerometerSet.ACCELEROMETER_X).toFloat()
                    val y = dp.getValue<Int>(ValueKey.AccelerometerSet.ACCELEROMETER_Y).toFloat()
                    val z = dp.getValue<Int>(ValueKey.AccelerometerSet.ACCELEROMETER_Z).toFloat()
                    // Neither STATUS nor ACCELEROMETER_STATUS resolves against the actual
                    // shipped aar (1.4.1), despite Samsung's release notes documenting
                    // AccelerometerSet.STATUS as added in 1.2.0 — docs vs. shipped library
                    // mismatch. X/Y/Z (the signal that actually matters here) work fine;
                    // status is dropped rather than guessed a third time. -1 = "not available".
                    val status = -1
                    listener.onAccelSample(dp.timestamp, x, y, z, status)
                } catch (e: Exception) {
                    // This tracker runs at 25 Hz — if any single field read throws (a still-wrong
                    // guessed type, an unchecked cast that doesn't actually hold, etc.) this is
                    // what would otherwise crash the whole service 25 times a second. Log once
                    // per bad sample and move on instead.
                    Log.e(TAG, "Failed to parse accelerometer sample", e)
                    listener.onTrackerError("ACCELEROMETER_CONTINUOUS", "sample parse error: ${e.message}")
                }
            }
        }

        override fun onFlushCompleted() {}

        override fun onError(error: HealthTracker.TrackerError) {
            listener.onTrackerError("ACCELEROMETER_CONTINUOUS", error.name)
        }
    }

    /** The Samsung Health Sensor SDK's actual runtime type for IBI_LIST / IBI_STATUS_LIST is
     *  java.util.ArrayList<Integer> (confirmed via on-device logging of real data on
     *  2026-08-25), not IntArray as the raw-typed ValueKey<T> signature suggested. This
     *  normalizes either representation instead of assuming one, so a future SDK/firmware
     *  change that switches representations again degrades to an empty list (logged once)
     *  instead of silently dropping data or crashing. */
    private fun toIntArray(raw: Any?): IntArray {
        return when (raw) {
            null -> IntArray(0)
            is IntArray -> raw
            is List<*> -> IntArray(raw.size) { i -> (raw[i] as? Number)?.toInt() ?: 0 }
            is Array<*> -> IntArray(raw.size) { i -> (raw[i] as? Number)?.toInt() ?: 0 }
            else -> {
                Log.w(TAG, "Unexpected IBI list runtime type: ${raw.javaClass.name}, value = $raw")
                IntArray(0)
            }
        }
    }

    companion object {
        private const val TAG = "WatchHealthManager"
    }
}
