package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Lets the phone app remotely start/stop [VitalsStreamingService] over the Data Layer, so
 * recording can be controlled from the Live Vitals screen on the phone instead of requiring
 * a tap on the watch itself. Listens at [CONTROL_PATH] for a plain "start" / "stop" payload.
 *
 * Permissions (BODY_SENSORS, ACTIVITY_RECOGNITION) must already be granted on the watch —
 * this is a background listener service with no Activity to show a permission dialog from.
 * If they're missing, a remote "start" is a safe no-op (logged) rather than starting a
 * foreground service that would immediately fail; open the watch app once locally first to
 * grant permissions, after which remote start/stop works normally.
 */
class VitalsControlListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != CONTROL_PATH) return
        when (String(event.data, Charsets.UTF_8)) {
            "start" -> startStreaming()
            "stop" -> stopService(Intent(this, VitalsStreamingService::class.java))
            else -> Log.w(TAG, "Unknown vitals control command received")
        }
    }

    private fun startStreaming() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "Cannot start remotely — missing permissions $missing. " +
                    "Open the watch app once locally to grant them, then remote start will work.")
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, VitalsStreamingService::class.java))
    }

    companion object {
        private const val TAG = "VitalsControlListener"
        const val CONTROL_PATH = "/vitals_control"
        private val REQUIRED_PERMISSIONS = listOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION
        )
    }
}
