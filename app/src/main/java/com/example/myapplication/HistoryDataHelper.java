package com.example.myapplication;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class HistoryDataHelper {

    private static final String TAG = "HistoryDataHelper";

    /**
     * Caseload for the currently logged-in care provider only — not every user
     * row on this device.
     */
    public static List<PatientHistoryRow> getAllPatientHistory(Context context) {
        UserDatabaseHelper userDb = UserDatabaseHelper.getInstance(context);
        DatabaseHelper appDb = DatabaseHelper.getInstance(context);
        List<PatientHistoryRow> rows = new ArrayList<>();

        String providerEmail = UserDatabaseHelper.getCurrentUserEmail();
        if (providerEmail == null || providerEmail.isEmpty()) {
            providerEmail = FirebaseAuthHelper.getCurrentEmail();
        }
        String medicalId = userDb.getCareProviderMedicalId(providerEmail);
        if (medicalId == null || medicalId.isEmpty()) {
            Log.w(TAG, "No provider medical id; returning empty caseload");
            return rows;
        }

        List<UserDatabaseHelper.CpPatientInfo> patients = userDb.getPatientsForProvider(medicalId);
        for (UserDatabaseHelper.CpPatientInfo info : patients) {
            String email = info.email;
            float heightM = 0f;
            float weightKg = 0f;
            float bmi = 0f;
            UserData userData = userDb.getUserData(email);
            if (userData != null) {
                heightM = userData.getHeightM();
                weightKg = userData.getWeightKg();
                bmi = userData.getBmi();
            }

            double latestCortisol = -1;
            try {
                List<DatabaseHelper.BiomarkerExperimentSummary> exps =
                        appDb.getLatestBiomarkerExperiments(email, 1);
                if (!exps.isEmpty()) {
                    latestCortisol = exps.get(0).maxValue;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting biomarker for caseload row", e);
            }

            String name = info.name;
            if (name == null || name.isEmpty()) {
                name = email;
            }

            rows.add(new PatientHistoryRow(name, email, heightM, weightKg, bmi, latestCortisol));
        }

        Log.d(TAG, "Returning " + rows.size() + " caseload rows");
        return rows;
    }
}
