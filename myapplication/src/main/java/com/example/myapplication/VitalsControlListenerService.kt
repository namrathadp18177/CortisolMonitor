package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

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
        val raw = String(event.data, Charsets.UTF_8)
        val (command, appSessionId) = parseControlPayload(raw)
        when (command) {
            "start" -> startStreaming(appSessionId)
            "stop" -> stopService(Intent(this, VitalsStreamingService::class.java))
            else -> Log.w(TAG, "Unknown vitals control command received: $raw")
        }
    }

    /** Payload is normally a small JSON object, e.g. {"command":"start","appSessionId":"..."},
     *  sent by LiveVitalsActivity on the phone so this recording can share the phone's
     *  app_session_id (see VitalsStreamingService.onStartCommand). Falls back to treating the
     *  whole payload as a bare "start"/"stop" string if it isn't valid JSON, so an old phone
     *  build (or a manual test message) still works, just without a shared session id. */
    private fun parseControlPayload(raw: String): Pair<String, String?> {
        return try {
            val json = JSONObject(raw)
            val command = if (json.has("command")) json.getString("command") else raw
            val appSessionId = if (json.has("appSessionId")) json.getString("appSessionId") else null
            Pair(command, appSessionId)
        } catch (e: Exception) {
            Pair(raw, null)
        }
    }

    private fun startStreaming(appSessionId: String?) {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "Cannot start remotely — missing permissions $missing. " +
                    "Open the watch app once locally to grant them, then remote start will work.")
            return
        }
        val intent = Intent(this, VitalsStreamingService::class.java)
        if (!appSessionId.isNullOrEmpty()) {
            intent.putExtra("appSessionId", appSessionId)
        }
        ContextCompat.startForegroundService(this, intent)
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
