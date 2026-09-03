package com.example.myapplication;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

public class SyncManager {

    private static final String TAG = "SyncManager";

    private final Context context;
    private final FirebaseFirestore db;
    private final UserDatabaseHelper userDb;
    private final DatabaseHelper appDb;
    private final String uid;
    private boolean hadFailure;

    public SyncManager(Context context) {
        this.context = context.getApplicationContext();
        this.db = FirebaseFirestore.getInstance();
        this.userDb = UserDatabaseHelper.getInstance(context);
        this.appDb = DatabaseHelper.getInstance(context);
        this.uid = FirebaseAuthHelper.getCurrentUid();
    }

    private String currentEmail() {
        String email = FirebaseAuthHelper.getCurrentEmail();
        return email == null ? "" : email.trim().toLowerCase();
    }

    private boolean belongsToSignedInUser(String rowEmail) {
        if (rowEmail == null) return false;
        return currentEmail().equals(rowEmail.trim().toLowerCase());
    }

    public void pushAllDirtyData(SyncCallback callback) {
        if (uid == null || uid.isEmpty()) {
            Log.w(TAG, "No UID available, skipping sync");
            if (callback != null) callback.onFailure("User not logged in");
            return;
        }

        hadFailure = false;
        Log.d(TAG, "Starting push sync for UID: " + uid);

        pushUsers(() ->
                pushCareProviders(() ->
                        pushCpPatientInfo(() ->
                                pushBiomarkerExperiments(() ->
                                        pushQuestionnaireResponses(() ->
                                                pushQuestionnaireSummaries(() -> {
                                                    if (hadFailure) {
                                                        Log.e(TAG, "Sync finished with one or more failures; leaving remaining dirty flags");
                                                        if (callback != null) {
                                                            callback.onFailure("One or more records failed to sync");
                                                        }
                                                        return;
                                                    }
                                                    Log.d(TAG, "All dirty data pushed successfully");
                                                    FirebaseAuthHelper.saveLastSyncAt(context, uid,
                                                            new java.text.SimpleDateFormat(
                                                                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                                                                    java.util.Locale.US)
                                                                    .format(new java.util.Date()));
                                                    if (callback != null) callback.onSuccess();
                                                })
                                        )
                                )
                        )
                )
        );
    }

    // ── pushUsers & pushCareProviders & pushCpPatientInfo are UNCHANGED ──
    // (keep your existing implementations — they have correct column names)

    // ====================== BIOMARKER EXPERIMENTS ======================

    private void pushBiomarkerExperiments(Runnable onComplete) {
        Cursor cursor = appDb.getDirtyRows("biomarker_experiments");
        if (cursor == null || cursor.getCount() == 0) {
            if (cursor != null) cursor.close();
            onComplete.run();
            return;
        }

        final int total = cursor.getCount();
        final int[] done = {0};

        while (cursor.moveToNext()) {
            long rowId       = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
            String cloudId   = cursor.getString(cursor.getColumnIndexOrThrow("cloud_id"));

            String username = cursor.getString(cursor.getColumnIndexOrThrow("username"));
            if (!belongsToSignedInUser(username)) {
                done[0]++;
                if (done[0] >= total) onComplete.run();
                continue;
            }

            Map<String, Object> data = new HashMap<>();
            data.put("username",      username);
            data.put("source",        cursor.getString(cursor.getColumnIndexOrThrow("source")));
            data.put("timestamp",     cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")));
            data.put("sampling_rate", cursor.getInt(cursor.getColumnIndexOrThrow("sampling_rate")));
            data.put("moving_avg",    cursor.getInt(cursor.getColumnIndexOrThrow("moving_avg")));
            data.put("duration",      cursor.getFloat(cursor.getColumnIndexOrThrow("duration")));
            data.put("max_value",     cursor.getFloat(cursor.getColumnIndexOrThrow("max_value")));
            data.put("data_size",     cursor.getInt(cursor.getColumnIndexOrThrow("data_size")));
            data.put("updated_at",    cursor.getString(cursor.getColumnIndexOrThrow("updated_at")));

            if (cloudId != null && !cloudId.isEmpty()) {
                // UPDATE existing doc
                db.collection("users").document(uid)
                        .collection("biomarker_experiments").document(cloudId)
                        .set(data, SetOptions.merge())
                        .addOnSuccessListener(unused -> {

                            appDb.markAsSynced("biomarker_experiments", "id", rowId, cloudId);
                            pushBiomarkerDataForExperiment(rowId, cloudId);
                            Log.d(TAG, "Updated biomarker experiment: " + cloudId);
                            done[0]++;
                            if (done[0] >= total) onComplete.run();
                        })
                        .addOnFailureListener(e -> {
                            hadFailure = true;
                            Log.e(TAG, "Failed to update experiment: " + e.getMessage());
                            done[0]++;
                            if (done[0] >= total) onComplete.run();
                        });
            } else {
                // ADD new doc
                db.collection("users").document(uid)
                        .collection("biomarker_experiments").add(data)
                        .addOnSuccessListener(docRef -> {
                            appDb.markAsSynced("biomarker_experiments", "id", rowId, docRef.getId());
                            pushBiomarkerDataForExperiment(rowId, docRef.getId());
                            Log.d(TAG, "Added biomarker experiment: " + docRef.getId());
                            done[0]++;
                            if (done[0] >= total) onComplete.run();
                        })
                        .addOnFailureListener(e -> {
                            hadFailure = true;
                            Log.e(TAG, "Failed to add experiment: " + e.getMessage());
                            done[0]++;
                            if (done[0] >= total) onComplete.run();
                        });
            }
        }
        cursor.close();
    }

    // ====================== BIOMARKER DATA (subcollection) ======================

    private void pushBiomarkerDataForExperiment(long localExperimentId, String cloudExperimentId) {
        Cursor cursor = appDb.getBiomarkerDataForExperiment(localExperimentId);
        if (cursor == null || cursor.getCount() == 0) {
            if (cursor != null) cursor.close();
            return;
        }

        // Use a batch write for efficiency — raw data can have hundreds of rows
        com.google.firebase.firestore.WriteBatch batch = db.batch();
        int batchCount = 0;

        while (cursor.moveToNext()) {
            Map<String, Object> point = new HashMap<>();
            point.put("data_point", cursor.getInt(cursor.getColumnIndexOrThrow("data_point")));
            point.put("time_sec",   cursor.getFloat(cursor.getColumnIndexOrThrow("time_sec")));
            point.put("port0",      cursor.getFloat(cursor.getColumnIndexOrThrow("port0")));
            point.put("port1",      cursor.getFloat(cursor.getColumnIndexOrThrow("port1")));
            point.put("port2",      cursor.getFloat(cursor.getColumnIndexOrThrow("port2")));
            point.put("avg",        cursor.getFloat(cursor.getColumnIndexOrThrow("avg")));

            // Use data_point number as doc ID so re-syncing is idempotent
            String docId = String.valueOf(cursor.getInt(cursor.getColumnIndexOrThrow("data_point")));

            com.google.firebase.firestore.DocumentReference ref =
                    db.collection("users").document(uid)
                            .collection("biomarker_experiments").document(cloudExperimentId)
                            .collection("data_points").document(docId);

            batch.set(ref, point, SetOptions.merge());
            batchCount++;

            // Firestore batch limit is 500 writes
            if (batchCount == 500) {
                batch.commit().addOnFailureListener(e -> {
                    hadFailure = true;
                    Log.e(TAG, "Batch write failed for data_points: " + e.getMessage());
                });
                batch = db.batch();
                batchCount = 0;
            }
        }
        cursor.close();

        if (batchCount > 0) {
            batch.commit()
                    .addOnSuccessListener(unused ->
                            Log.d(TAG, "Synced data_points for experiment: " + cloudExperimentId))
                    .addOnFailureListener(e -> {
                            hadFailure = true;
                            Log.e(TAG, "Final batch write failed: " + e.getMessage());
                    });
        }
    }

    // ====================== QUESTIONNAIRE RESPONSES ======================

    private void pushQuestionnaireResponses(Runnable onComplete) {
        Cursor cursor = appDb.getDirtyRows("questionnaire_responses");
        if (cursor == null || cursor.getCount() == 0) {
            if (cursor != null) cursor.close();
            onComplete.run();
            return;
        }

        final int total = cursor.getCount();
        final int[] done = {0};

        while (cursor.moveToNext()) {
            long rowId     = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
            String cloudId = cursor.getString(cursor.getColumnIndexOrThrow("cloud_id"));
            String rowEmail = cursor.getString(cursor.getColumnIndexOrThrow("user_email"));
            if (!belongsToSignedInUser(rowEmail)) {
                done[0]++;
                if (done[0] >= total) onComplete.run();
                continue;
            }

            Map<String, Object> data = new HashMap<>();

            data.put("section",            cursor.getString(cursor.getColumnIndexOrThrow("section")));
            data.put("question_text",      cursor.getString(cursor.getColumnIndexOrThrow("question_text")));
            data.put("answer",             cursor.getInt(cursor.getColumnIndexOrThrow("answer")));
            data.put("timestamp",          cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")));
            data.put("questionnaire_type", cursor.getString(cursor.getColumnIndexOrThrow("questionnaire_type")));
            data.put("user_email",         rowEmail);
            data.put("updated_at",         cursor.getString(cursor.getColumnIndexOrThrow("updated_at")));

            if (cloudId != null && !cloudId.isEmpty()) {
                db.collection("users").document(uid)
                        .collection("questionnaire_responses").document(cloudId)
                        .set(data, SetOptions.merge())
                        .addOnSuccessListener(unused -> {
                            appDb.markAsSynced("questionnaire_responses", "id", rowId, cloudId);
                            done[0]++;
                            if (done[0] >= total) onComplete.run();
                        })
                        .addOnFailureListener(e -> {
                            hadFailure = true;
                            Log.e(TAG, "Failed to update response: " + e.getMessage());
                            done[0]++;
                            if (done[0] >= total) onComplete.run();
                        });
            } else {
                db.collection("users").document(uid)
                        .collection("questionnaire_responses").add(data)
                        .addOnSuccessListener(docRef -> {
                            appDb.markAsSynced("questionnaire_responses", "id", rowId, docRef.getId());
                            done[0]++;
                            if (done[0] >= total) onComplete.run();
                        })
                        .addOnFailureListener(e -> {
                            hadFailure = true;
                            Log.e(TAG, "Failed to add response: " + e.getMessage());
                            done[0]++;
                            if (done[0] >= total) onComplete.run();
                        });
            }
        }
        cursor.close();
    }

    // ====================== QUESTIONNAIRE SUMMARIES ======================

    private void pushQuestionnaireSummaries(Runnable onComplete) {
        Cursor cursor = appDb.getDirtyRows("questionnaire_summaries");
        if (cursor == null || cursor.getCount() == 0) {
            if (cursor != null) cursor.close();
            onComplete.run();
            return;
        }

        final int total = cursor.getCount();
        final int[] done = {0};

        while (cursor.moveToNext()) {
            long rowId = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
            String rowEmail = cursor.getString(cursor.getColumnIndexOrThrow("user_email"));
            if (!belongsToSignedInUser(rowEmail)) {
                done[0]++;
                if (done[0] >= total) onComplete.run();
                continue;
            }

            Map<String, Object> data = new HashMap<>();
            // all column names now match DB schema
            data.put("user_email",      rowEmail);
            data.put("score_a",         cursor.getInt(cursor.getColumnIndexOrThrow("score_a")));
            data.put("score_b",         cursor.getInt(cursor.getColumnIndexOrThrow("score_b")));
            data.put("score_c",         cursor.getInt(cursor.getColumnIndexOrThrow("score_c")));
            data.put("score_d",         cursor.getInt(cursor.getColumnIndexOrThrow("score_d")));
            data.put("score_e",         cursor.getInt(cursor.getColumnIndexOrThrow("score_e")));
            data.put("score_f",         cursor.getInt(cursor.getColumnIndexOrThrow("score_f")));
            data.put("total_score",     cursor.getInt(cursor.getColumnIndexOrThrow("total_score")));
            data.put("phq9_score",      cursor.getInt(cursor.getColumnIndexOrThrow("phq9_score")));
            data.put("bdi_score",       cursor.getInt(cursor.getColumnIndexOrThrow("bdi_score")));
            data.put("gad7_score",      cursor.getInt(cursor.getColumnIndexOrThrow("gad7_score")));
            data.put("brs_score",       cursor.getFloat(cursor.getColumnIndexOrThrow("brs_score")));
            //  trigger and filled flags
            data.put("phq9_filled",     cursor.getInt(cursor.getColumnIndexOrThrow("phq9_filled")));
            data.put("bdi_filled",      cursor.getInt(cursor.getColumnIndexOrThrow("bdi_filled")));
            data.put("gad7_filled",     cursor.getInt(cursor.getColumnIndexOrThrow("gad7_filled")));
            data.put("brs_filled",      cursor.getInt(cursor.getColumnIndexOrThrow("brs_filled")));
            data.put("phq9_triggered",  cursor.getInt(cursor.getColumnIndexOrThrow("phq9_triggered")));
            data.put("bdi_triggered",   cursor.getInt(cursor.getColumnIndexOrThrow("bdi_triggered")));
            data.put("gad7_triggered",  cursor.getInt(cursor.getColumnIndexOrThrow("gad7_triggered")));
            data.put("brs_triggered",   cursor.getInt(cursor.getColumnIndexOrThrow("brs_triggered")));
            data.put("updated_at",      cursor.getString(cursor.getColumnIndexOrThrow("updated_at")));


            db.collection("users").document(uid)
                    .collection("questionnaire_summaries").document(uid)
                    .set(data, SetOptions.merge())
                    .addOnSuccessListener(unused -> {
                        appDb.markAsSynced("questionnaire_summaries", "id", rowId, uid);
                        Log.d(TAG, "Synced questionnaire summary for UID: " + uid);
                        done[0]++;
                        if (done[0] >= total) onComplete.run();
                    })
                    .addOnFailureListener(e -> {
                        hadFailure = true;
                        Log.e(TAG, "Failed to sync summary: " + e.getMessage());
                        done[0]++;
                        if (done[0] >= total) onComplete.run();
                    });
        }
        cursor.close();
    }
    private void pushUsers(Runnable onComplete) {
        Cursor cursor = userDb.getDirtyRows("users");
        if (cursor == null || cursor.getCount() == 0) {
            if (cursor != null) cursor.close();
            onComplete.run();
            return;
        }

        final int total = cursor.getCount();
        final int[] done = {0};

        while (cursor.moveToNext()) {
            long rowId = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
            String email = cursor.getString(cursor.getColumnIndexOrThrow("email"));
            if (!belongsToSignedInUser(email)) {
                done[0]++;
                if (done[0] >= total) onComplete.run();
                continue;
            }

            Map<String, Object> data = new HashMap<>();
            data.put("email", email);
            data.put("age", cursor.getInt(cursor.getColumnIndexOrThrow("age")));
            data.put("birth_date", cursor.getString(cursor.getColumnIndexOrThrow("birth_date")));
            data.put("sex", cursor.getString(cursor.getColumnIndexOrThrow("sex")));
            data.put("race", cursor.getString(cursor.getColumnIndexOrThrow("race")));
            data.put("relationship_status", cursor.getString(cursor.getColumnIndexOrThrow("relationship_status")));
            data.put("weight_kg", cursor.getFloat(cursor.getColumnIndexOrThrow("weight_kg")));
            data.put("height_m", cursor.getFloat(cursor.getColumnIndexOrThrow("height_m")));
            data.put("bmi", cursor.getFloat(cursor.getColumnIndexOrThrow("bmi")));
            data.put("role", "PATIENT");
            data.put("updated_at", cursor.getString(cursor.getColumnIndexOrThrow("updated_at")));

            db.collection("users").document(uid)
                    .collection("profile").document("data")
                    .set(data, SetOptions.merge())
                    .addOnSuccessListener(unused -> {
                        userDb.markAsSynced("users", "id", rowId, uid);
                        Log.d(TAG, "Synced user profile");
                        done[0]++;
                        if (done[0] >= total) onComplete.run();
                    })
                    .addOnFailureListener(e -> {
                        hadFailure = true;
                        Log.e(TAG, "Failed to sync user: " + e.getMessage());
                        done[0]++;
                        if (done[0] >= total) onComplete.run();
                    });
        }
        cursor.close();
    }
    private void pushCareProviders(Runnable onComplete) {
        if (!"CARE_PROVIDER".equals(FirebaseAuthHelper.getSavedRole(context))) {
            onComplete.run();
            return;
        }
        Cursor cursor = userDb.getDirtyRows("care_providers");
        if (cursor == null || cursor.getCount() == 0) {
            if (cursor != null) cursor.close();
            onComplete.run();
            return;
        }

        final int total = cursor.getCount();
        final int[] done = {0};

        while (cursor.moveToNext()) {
            long rowId = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
            String email = cursor.getString(cursor.getColumnIndexOrThrow("email"));
            if (!belongsToSignedInUser(email)) {
                done[0]++;
                if (done[0] >= total) onComplete.run();
                continue;
            }

            Map<String, Object> data = new HashMap<>();
            data.put("email", email);
            data.put("name", cursor.getString(cursor.getColumnIndexOrThrow("name")));
            data.put("medical_id", cursor.getString(cursor.getColumnIndexOrThrow("medical_id")));
            data.put("organization", cursor.getString(cursor.getColumnIndexOrThrow("organization")));
            data.put("designation", cursor.getString(cursor.getColumnIndexOrThrow("designation")));
            data.put("department", cursor.getString(cursor.getColumnIndexOrThrow("department")));
            data.put("phone", cursor.getString(cursor.getColumnIndexOrThrow("phone")));
            data.put("gender", cursor.getString(cursor.getColumnIndexOrThrow("gender")));
            data.put("dob", cursor.getString(cursor.getColumnIndexOrThrow("dob")));
            data.put("address", cursor.getString(cursor.getColumnIndexOrThrow("address")));
            data.put("role", "CARE_PROVIDER");
            data.put("updated_at", cursor.getString(cursor.getColumnIndexOrThrow("updated_at")));

            db.collection("users").document(uid)
                    .collection("profile").document("data")
                    .set(data, SetOptions.merge())
                    .addOnSuccessListener(unused -> {
                        userDb.markAsSynced("care_providers", "id", rowId, uid);
                        Log.d(TAG, "Synced care provider profile");
                        done[0]++;
                        if (done[0] >= total) onComplete.run();
                    })
                    .addOnFailureListener(e -> {
                        hadFailure = true;
                        Log.e(TAG, "Failed to sync care provider: " + e.getMessage());
                        done[0]++;
                        if (done[0] >= total) onComplete.run();
                    });
        }
        cursor.close();
    }
    private void pushCpPatientInfo(Runnable onComplete) {
        if (!"CARE_PROVIDER".equals(FirebaseAuthHelper.getSavedRole(context))) {
            onComplete.run();
            return;
        }
        Cursor cursor = userDb.getDirtyRows("cp_patient_info");
        if (cursor == null || cursor.getCount() == 0) {
            if (cursor != null) cursor.close();
            onComplete.run();
            return;
        }

        final int total = cursor.getCount();
        final int[] done = {0};

        while (cursor.moveToNext()) {
            String patientEmail = cursor.getString(cursor.getColumnIndexOrThrow("patient_email"));
            String patientId    = cursor.getString(cursor.getColumnIndexOrThrow("patient_id"));

            Map<String, Object> data = new HashMap<>();
            data.put("patient_email",       patientEmail);
            data.put("provider_medical_id", cursor.getString(cursor.getColumnIndexOrThrow("provider_medical_id")));
            data.put("patient_id",          patientId);
            data.put("name",                cursor.getString(cursor.getColumnIndexOrThrow("name")));
            data.put("phone",               cursor.getString(cursor.getColumnIndexOrThrow("phone")));
            data.put("relationship_status", cursor.getString(cursor.getColumnIndexOrThrow("relationship_status")));
            data.put("medical_conditions",  cursor.getString(cursor.getColumnIndexOrThrow("medical_conditions")));
            data.put("updated_at",          cursor.getString(cursor.getColumnIndexOrThrow("updated_at")));

            db.collection("users").document(uid)
                    .collection("patients").document(patientId)
                    .set(data, SetOptions.merge())
                    .addOnSuccessListener(unused -> {
                        userDb.markAsSyncedByEmail("cp_patient_info", patientEmail, patientId);
                        Log.d(TAG, "Synced cp_patient_info record");
                        done[0]++;
                        if (done[0] >= total) onComplete.run();
                    })
                    .addOnFailureListener(e -> {
                        hadFailure = true;
                        Log.e(TAG, "Failed to sync cp_patient_info: " + e.getMessage());
                        done[0]++;
                        if (done[0] >= total) onComplete.run();
                    });
        }
        cursor.close();
    }


    public interface SyncCallback {
        void onSuccess();
        void onFailure(String error);
    }
}
