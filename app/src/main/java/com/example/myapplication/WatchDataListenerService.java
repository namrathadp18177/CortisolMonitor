package com.example.myapplication;

import android.content.Intent;
import android.util.Log;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Receives JSON batches of EDA / heart-rate+IBI / skin-temperature / accelerometer samples
 * pushed from the Wear OS watch module (:myapplication → VitalsStreamingService) over the
 * Data Layer MessageClient at path "/vitals", stores them in {@link VitalsDatabaseHelper},
 * and broadcasts the latest values locally so {@link LiveVitalsActivity} can update live.
 *
 * Each row is tagged with the batch's session_id (one per Start tap on the watch) and with
 * whichever user is currently logged into the phone app ({@link DatabaseHelper#getCurrentUserEmail()}),
 * so multiple recordings — or multiple participants sharing this phone — can be told apart later.
 *
 * Payload shape sent by the watch:
 * {
 *   "sessionId": "session_1735999999999",
 *   "eda":      [ { "t": <ms>, "conductance": <float>, "status": <int> }, ... ],
 *   "hr":       [ { "t": <ms>, "hr": <int>, "hrStatus": <int>, "ibi": [..], "ibiStatus": [..] }, ... ],
 *   "skinTemp": [ { "t": <ms>, "ambientC": <float>, "skinC": <float>, "status": <int> }, ... ],
 *   "accel":    [ { "t": <ms>, "x": <float>, "y": <float>, "z": <float>, "status": <int> }, ... ]
 * }
 */
public class WatchDataListenerService extends WearableListenerService {

    private static final String TAG = "WatchDataListener";
    public static final String VITALS_PATH = "/vitals";
    public static final String ACTION_VITALS_UPDATED = "com.example.myapplication.VITALS_UPDATED";

    @Override
    public void onMessageReceived(MessageEvent event) {
        if (!VITALS_PATH.equals(event.getPath())) return;

        String json = new String(event.getData(), StandardCharsets.UTF_8);
        try {
            JSONObject payload = new JSONObject(json);
            VitalsDatabaseHelper db = VitalsDatabaseHelper.getInstance(this);

            String sessionId = payload.optString("sessionId", "unknown_session");
            String participantEmail = resolveParticipantEmail();

            Float lastHr = null;
            Float lastEda = null;
            Float lastSkinTemp = null;
            Float lastAccelX = null;
            Float lastAccelY = null;
            Float lastAccelZ = null;

            JSONArray edaArr = payload.optJSONArray("eda");
            if (edaArr != null) {
                for (int i = 0; i < edaArr.length(); i++) {
                    JSONObject s = edaArr.getJSONObject(i);
                    long t = s.getLong("t");
                    double conductance = s.getDouble("conductance");
                    int status = s.optInt("status", -1);
                    db.insertEda(sessionId, participantEmail, t, conductance, status);
                    lastEda = (float) conductance;
                }
            }

            JSONArray hrArr = payload.optJSONArray("hr");
            if (hrArr != null) {
                for (int i = 0; i < hrArr.length(); i++) {
                    JSONObject s = hrArr.getJSONObject(i);
                    long t = s.getLong("t");
                    int hr = s.optInt("hr", -1);
                    int hrStatus = s.optInt("hrStatus", -1);
                    JSONArray ibiArr = s.optJSONArray("ibi");
                    JSONArray ibiStatusArr = s.optJSONArray("ibiStatus");
                    String ibi = ibiArr != null ? ibiArr.toString() : "[]";
                    String ibiStatus = ibiStatusArr != null ? ibiStatusArr.toString() : "[]";
                    db.insertHeartRate(sessionId, participantEmail, t, hr, hrStatus, ibi, ibiStatus);
                    // status == 1 means a valid HR reading per Watch8_Data_Collection_Guide.md §B5
                    if (hrStatus == 1) lastHr = (float) hr;
                }
            }

            JSONArray tempArr = payload.optJSONArray("skinTemp");
            if (tempArr != null) {
                for (int i = 0; i < tempArr.length(); i++) {
                    JSONObject s = tempArr.getJSONObject(i);
                    long t = s.getLong("t");
                    double ambient = s.optDouble("ambientC", Double.NaN);
                    double skin = s.optDouble("skinC", Double.NaN);
                    int status = s.optInt("status", -1);
                    db.insertSkinTemp(sessionId, participantEmail, t, ambient, skin, status);
                    lastSkinTemp = (float) skin;
                }
            }

            JSONArray accelArr = payload.optJSONArray("accel");
            if (accelArr != null) {
                for (int i = 0; i < accelArr.length(); i++) {
                    JSONObject s = accelArr.getJSONObject(i);
                    long t = s.getLong("t");
                    double x = s.optDouble("x", Double.NaN);
                    double y = s.optDouble("y", Double.NaN);
                    double z = s.optDouble("z", Double.NaN);
                    int status = s.optInt("status", -1);
                    db.insertAccel(sessionId, participantEmail, t, x, y, z, status);
                    lastAccelX = (float) x;
                    lastAccelY = (float) y;
                    lastAccelZ = (float) z;
                }
            }

            Intent update = new Intent(ACTION_VITALS_UPDATED);
            update.setPackage(getPackageName());
            if (lastHr != null) update.putExtra("heartRate", lastHr);
            if (lastEda != null) update.putExtra("edaConductance", lastEda);
            if (lastSkinTemp != null) update.putExtra("skinTempC", lastSkinTemp);
            if (lastAccelX != null) {
                update.putExtra("accelX", lastAccelX);
                update.putExtra("accelY", lastAccelY);
                update.putExtra("accelZ", lastAccelZ);
            }
            update.putExtra("receivedAt", System.currentTimeMillis());
            sendBroadcast(update);
            VitalsIngestService.start(this);

            Log.d(TAG, "Stored batch [" + sessionId + "]: eda=" + (edaArr == null ? 0 : edaArr.length())
                    + " hr=" + (hrArr == null ? 0 : hrArr.length())
                    + " skinTemp=" + (tempArr == null ? 0 : tempArr.length())
                    + " accel=" + (accelArr == null ? 0 : accelArr.length()));
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse vitals payload", e);
        }
    }

    /**
     * Never attribute a watch session to a care-provider account. Prefer the
     * in-process email, then the persisted session email from login.
     */
    private String resolveParticipantEmail() {
        if ("CARE_PROVIDER".equals(FirebaseAuthHelper.getSavedRole(this))) {
            return "unassigned";
        }
        String email = DatabaseHelper.getCurrentUserEmail();
        if (email != null && !email.isEmpty()) return email;
        email = FirebaseAuthHelper.getCurrentEmail();
        if (email != null && !email.isEmpty()) return email;
        email = FirebaseAuthHelper.getSavedEmail(this);
        return email != null ? email : "";
    }
}
