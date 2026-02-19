package com.example.myapplication;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class HistoryDataHelper {

    private static final String TAG = "HistoryDataHelper";

    public static List<PatientHistoryRow> getAllPatientHistory(Context context) {
        UserDatabaseHelper userDb = UserDatabaseHelper.getInstance(context);
        DatabaseHelper appDb = DatabaseHelper.getInstance(context); // your AppData.db helper

        List<PatientHistoryRow> rows = new ArrayList<>();

        Cursor cursor = null;
        try {
            cursor = userDb.getReadableDatabase().query(
                    "users",
                    new String[]{"email", "weight_kg", "height_m", "bmi"},
                    null, null, null, null, null
            );

            if (cursor == null) {
                Log.w(TAG, "User cursor is null");
                return rows;
            }

            Log.d(TAG, "User rows in history: " + cursor.getCount());

            if (cursor.moveToFirst()) {
                do {
                    String email = cursor.getString(cursor.getColumnIndexOrThrow("email"));
                    float weightKg = cursor.getFloat(cursor.getColumnIndexOrThrow("weight_kg"));
                    float heightM = cursor.getFloat(cursor.getColumnIndexOrThrow("height_m"));
                    float bmi = cursor.getFloat(cursor.getColumnIndexOrThrow("bmi"));

                    // Try to get a name from cp_patient_info (any CP)
                    String name = null;
                    Cursor cpCursor = null;
                    try {
                        cpCursor = userDb.getReadableDatabase().query(
                                "cp_patient_info",
                                new String[]{UserDatabaseHelper.COL_CP_NAME},
                                UserDatabaseHelper.COL_CP_EMAIL + "=?",
                                new String[]{email},
                                null, null, null,
                                "1"
                        );
                        if (cpCursor != null && cpCursor.moveToFirst()) {
                            name = cpCursor.getString(
                                    cpCursor.getColumnIndexOrThrow(UserDatabaseHelper.COL_CP_NAME)
                            );
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error reading cp_patient_info for " + email, e);
                    } finally {
                        if (cpCursor != null) cpCursor.close();
                    }

                    // Get latest biomarker experiment for this email (may be none)
                    double latestCortisol = -1;
                    try {
                        List<DatabaseHelper.BiomarkerExperimentSummary> exps =
                                appDb.getLatestBiomarkerExperiments(email, 1);
                        if (!exps.isEmpty()) {
                            latestCortisol = exps.get(0).maxValue;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error getting biomarker for " + email, e);
                    }

                    rows.add(new PatientHistoryRow(
                            name,
                            email,
                            heightM,
                            weightKg,
                            bmi,
                            latestCortisol
                    ));
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in getAllPatientHistory", e);
        } finally {
            if (cursor != null) cursor.close();
        }

        Log.d(TAG, "Returning " + rows.size() + " history rows");
        return rows;
    }
}
