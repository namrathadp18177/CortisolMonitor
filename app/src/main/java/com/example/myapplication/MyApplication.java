package com.example.myapplication;

import android.app.Application;
import android.util.Log;

import java.util.Collections;

import io.kommunicate.KmSettings;
import io.kommunicate.Kommunicate;

public class MyApplication extends Application {
    
    private static final String TAG = "MyApplication";
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        // Initialize Kommunicate SDK at application level
        initializeKommunicate();
    }
    
    private void initializeKommunicate() {
        // Try multiple initialization strategies
        boolean initSuccess = false;
        
        // Strategy 1: Standard initialization
        if (!initSuccess) {
            initSuccess = tryStandardInit();
        }
        
        // Strategy 2: Delayed initialization
        if (!initSuccess) {
            initSuccess = tryDelayedInit();
        }
        
        // Strategy 3: Clear preferences and retry
        if (!initSuccess) {
            initSuccess = tryWithClearPreferences();
        }
        
        if (initSuccess) {
            Log.d(TAG, "Kommunicate SDK initialized successfully");
        } else {
            Log.w(TAG, "All Kommunicate initialization strategies failed. Chat will be disabled.");
            // Store a flag indicating chat is unavailable
            getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("chat_available", false)
                .apply();
        }
    }
    
    private boolean tryStandardInit() {
        try {
            Log.d(TAG, "Trying standard Kommunicate initialization...");
            Kommunicate.init(this, "1bcf65a7f3ef233478f2abf50deb08f41");
            configureKommunicate();
            return true;
        } catch (Exception e) {
            Log.d(TAG, "Standard initialization failed: " + e.getMessage());
            return false;
        }
    }
    
    private boolean tryDelayedInit() {
        try {
            Log.d(TAG, "Trying delayed Kommunicate initialization...");
            // Wait a bit for the system to fully initialize
            Thread.sleep(500);
            Kommunicate.init(this, "1bcf65a7f3ef233478f2abf50deb08f41");
            configureKommunicate();
            return true;
        } catch (Exception e) {
            Log.d(TAG, "Delayed initialization failed: " + e.getMessage());
            return false;
        }
    }
    
    private boolean tryWithClearPreferences() {
        try {
            Log.d(TAG, "Trying Kommunicate initialization with cleared preferences...");
            // Clear any potentially corrupted Kommunicate preferences
            clearKommunicatePreferences();
            Thread.sleep(200);
            Kommunicate.init(this, "1bcf65a7f3ef233478f2abf50deb08f41");
            configureKommunicate();
            return true;
        } catch (Exception e) {
            Log.d(TAG, "Initialization with cleared preferences failed: " + e.getMessage());
            return false;
        }
    }
    
    private void configureKommunicate() {
        try {
            // Configure bot settings
            KmSettings.setDefaultBotIds(
                    Collections.singletonList("stress-brbc7")
            );
            
            // Force–assign every new conversation to that bot
            KmSettings.setDefaultAssignee("stress-brbc7");
            
            // Store success flag
            getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("chat_available", true)
                .apply();
                
        } catch (Exception e) {
            Log.e(TAG, "Failed to configure Kommunicate settings: " + e.getMessage());
            throw e; // Re-throw to indicate initialization failure
        }
    }
    
    private void clearKommunicatePreferences() {
        try {
            // Clear various preference files that Kommunicate might use
            getSharedPreferences("com.applozic.mobicomkit", MODE_PRIVATE).edit().clear().apply();
            getSharedPreferences("ApplozicPref", MODE_PRIVATE).edit().clear().apply();
            getSharedPreferences("SecureSharedPreferences", MODE_PRIVATE).edit().clear().apply();
        } catch (Exception e) {
            Log.d(TAG, "Error clearing preferences (this is normal): " + e.getMessage());
        }
    }
} 