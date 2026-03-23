package com.example.myapplication;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class FirebaseAuthHelper {

    private static final String TAG = "FirebaseAuthHelper";
    private static final String PREFS_NAME = "cortisol_prefs";
    private static final String KEY_UID = "firebase_uid";
    private static final String KEY_ROLE = "user_role";
    private static final String KEY_LAST_SYNC = "last_sync_at";

    private static FirebaseAuth mAuth = FirebaseAuth.getInstance();

    // ── Sign Up ──────────────────────────────────────────
    public interface AuthCallback {
        void onSuccess(String uid);
        void onFailure(String errorMessage);
    }

    public static void signUp(String email, String password, AuthCallback callback) {
        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        String uid = user.getUid();
                        Log.d(TAG, "Sign up successful. UID: " + uid);
                        callback.onSuccess(uid);
                    } else {
                        String error = task.getException() != null
                                ? task.getException().getMessage()
                                : "Sign up failed";
                        Log.e(TAG, "Sign up failed: " + error);
                        callback.onFailure(error);
                    }
                });
    }

    // ── Login ────────────────────────────────────────────
    public static void login(String email, String password, AuthCallback callback) {
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        String uid = user.getUid();
                        Log.d(TAG, "Login successful. UID: " + uid);
                        callback.onSuccess(uid);
                    } else {
                        String error = task.getException() != null
                                ? task.getException().getMessage()
                                : "Login failed";
                        Log.e(TAG, "Login failed: " + error);
                        callback.onFailure(error);
                    }
                });
    }

    // ── Logout ───────────────────────────────────────────
    public static void logout(Context context) {
        mAuth.signOut();
        clearLocalSession(context);
        Log.d(TAG, "User logged out");
    }

    // ── Check if already logged in ───────────────────────
    public static boolean isLoggedIn() {
        return mAuth.getCurrentUser() != null;
    }

    public static String getCurrentUid() {
        FirebaseUser user = mAuth.getCurrentUser();
        return user != null ? user.getUid() : null;
    }

    public static String getCurrentEmail() {
        FirebaseUser user = mAuth.getCurrentUser();
        return user != null ? user.getEmail() : null;
    }

    // ── Local session storage ────────────────────────────
    public static void saveSession(Context context, String uid, String role) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_UID, uid)
                .putString(KEY_ROLE, role)
                .apply();
    }

    public static String getSavedRole(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_ROLE, null);
    }

    public static void clearLocalSession(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
    }

    // ── lastSyncAt storage ───────────────────────────────
    public static void saveLastSyncAt(Context context, String uid, String isoTime) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LAST_SYNC + "_" + uid, isoTime).apply();
    }

    public static String getLastSyncAt(Context context, String uid) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LAST_SYNC + "_" + uid, null);
    }
}
