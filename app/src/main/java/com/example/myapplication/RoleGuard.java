package com.example.myapplication;

import android.app.Activity;
import android.content.Intent;

/**
 * Hard gate so a patient Activity cannot stay open under a care-provider session
 * (and vice versa). Call at the top of {@code onCreate} and abort if it returns false.
 */
public final class RoleGuard {

    private RoleGuard() {}

    public static boolean requirePatient(Activity activity) {
        if (!FirebaseAuthHelper.isLoggedIn()) {
            bounceToLauncher(activity);
            return false;
        }
        if ("CARE_PROVIDER".equals(FirebaseAuthHelper.getSavedRole(activity))) {
            activity.startActivity(new Intent(activity, CareProviderDashboardActivity.class));
            activity.finish();
            return false;
        }
        return true;
    }

    public static boolean requireCareProvider(Activity activity) {
        if (!FirebaseAuthHelper.isLoggedIn()) {
            bounceToLauncher(activity);
            return false;
        }
        if (!"CARE_PROVIDER".equals(FirebaseAuthHelper.getSavedRole(activity))) {
            activity.startActivity(new Intent(activity, PatientMainActivity.class));
            activity.finish();
            return false;
        }
        return true;
    }

    private static void bounceToLauncher(Activity activity) {
        Intent intent = new Intent(activity, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finish();
    }
}
