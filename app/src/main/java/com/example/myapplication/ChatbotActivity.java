package com.example.myapplication;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.view.animation.BounceInterpolator;

import java.security.SecureRandom;
import java.util.Collections;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import io.kommunicate.KmConversationBuilder;
import io.kommunicate.KmSettings;
import io.kommunicate.Kommunicate;
import io.kommunicate.callbacks.KmCallback;
import io.kommunicate.users.KMUser;

public class ChatbotActivity extends AppCompatActivity {

    private MaterialButton launchChatButton;
    private ImageView ivBilly;
    private TextView titleText, subtitleText, descriptionText, statusText;
    private MaterialCardView featuresCard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            setContentView(R.layout.activity_chatbot);
            
            // Initialize UI components
            initializeViews();

            // Initialize SSL settings
            initializeWithCustomSSL();

            // Check if chat is available (set by Application class)
            boolean chatAvailable = getSharedPreferences("app_prefs", MODE_PRIVATE)
                    .getBoolean("chat_available", false);
                    
            if (chatAvailable) {
                try {
                    if (Kommunicate.isLoggedIn(this)) {
                        updateStatusText("Chat service is ready");
                        Toast.makeText(this, "Chat service is ready.", Toast.LENGTH_SHORT).show();
                    } else {
                        updateStatusText("Chat service is available");
                        Toast.makeText(this, "Chat service is available.", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    chatAvailable = false; // SDK is not working properly
                }
            }
            
            if (!chatAvailable) {
                updateStatusText("Chat service is currently unavailable");
                Toast.makeText(this, "Chat service is currently unavailable. Please try again later.", Toast.LENGTH_LONG).show();
                // Disable the chat button
                if (launchChatButton != null) {
                    launchChatButton.setEnabled(false);
                    launchChatButton.setText("Chat Unavailable");
                    launchChatButton.setAlpha(0.5f);
                }
            }

            if (launchChatButton != null) {
                // Apply bounce animation
                applyButtonAnimation(launchChatButton);
                applyBillyAnimation(ivBilly);

                launchChatButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // Check if chat is available
                        boolean chatStillAvailable = getSharedPreferences("app_prefs", MODE_PRIVATE)
                                .getBoolean("chat_available", false);
                                
                        if (!chatStillAvailable) {
                            Toast.makeText(ChatbotActivity.this, "Chat service is currently unavailable. Please try again later.", Toast.LENGTH_LONG).show();
                            return;
                        }
                        
                        if (isNetworkAvailable()) {
                            launchKommunicateChat();
                        } else {
                            Toast.makeText(ChatbotActivity.this, "No internet connection available. Please check your connection and try again.", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error loading chat. Returning to previous screen.", Toast.LENGTH_LONG).show();
            finish(); // Close the activity if there's a critical error
        }
    }
    
    private void initializeViews() {
        launchChatButton = findViewById(R.id.launchChatButton);
        ivBilly = findViewById(R.id.ivBilly);
        titleText = findViewById(R.id.titleText);
        subtitleText = findViewById(R.id.subtitleText);
        descriptionText = findViewById(R.id.descriptionText);
        statusText = findViewById(R.id.statusText);
        featuresCard = findViewById(R.id.featuresCard);
    }
    
    private void updateStatusText(String status) {
        if (statusText != null) {
            statusText.setText(status + " • Secure • Private • Confidential");
        }
    }
    
    private void applyBillyAnimation(ImageView imageView) {
        if (imageView != null) {
            // Gentle breathing animation for Billy
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(imageView, "scaleX", 1f, 1.05f);
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(imageView, "scaleY", 1f, 1.05f);
            
            scaleX.setRepeatCount(ObjectAnimator.INFINITE);
            scaleX.setRepeatMode(ObjectAnimator.REVERSE);
            scaleY.setRepeatCount(ObjectAnimator.INFINITE);
            scaleY.setRepeatMode(ObjectAnimator.REVERSE);
            
            AnimatorSet animatorSet = new AnimatorSet();
            animatorSet.playTogether(scaleX, scaleY);
            animatorSet.setDuration(2000);
            animatorSet.start();
        }
    }

    private void initializeWithCustomSSL() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[]{};
                        }

                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                            // Skip validation
                        }

                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                            // Skip validation
                        }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            e.printStackTrace();
            // Continue without custom SSL if it fails
            System.out.println("Custom SSL initialization failed, using default SSL settings");
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    private void applyButtonAnimation(View view) {
        try {
            if (view != null) {
                ObjectAnimator scaleX = ObjectAnimator.ofFloat(view, "scaleX", 0.9f, 1.1f);
                ObjectAnimator scaleY = ObjectAnimator.ofFloat(view, "scaleY", 0.9f, 1.1f);

                scaleX.setRepeatCount(1);
                scaleX.setRepeatMode(ObjectAnimator.REVERSE);
                scaleY.setRepeatCount(1);
                scaleY.setRepeatMode(ObjectAnimator.REVERSE);

                AnimatorSet animatorSet = new AnimatorSet();
                animatorSet.playTogether(scaleX, scaleY);
                animatorSet.setDuration(500);
                animatorSet.setInterpolator(new BounceInterpolator());
                animatorSet.start();
            }
        } catch (Exception e) {
            e.printStackTrace();
            // Continue without animation if it fails
        }
    }

    private void launchKommunicateChat() {
        try {
            // Create a user with specific details (optional)
            KMUser kmUser = new KMUser();
            kmUser.setUserId("user_" + System.currentTimeMillis()); // Generate a unique ID
            // Optional: set user details
            kmUser.setDisplayName("App User");

            // Launch the conversation with the configured builder
            new KmConversationBuilder(this)
                    .setKmUser(kmUser)
                    .setSingleConversation(true)  // Set to false if you want to allow multiple conversations
                    .launchConversation(new KmCallback() {
                        @Override
                        public void onSuccess(Object message) {
                            Toast.makeText(ChatbotActivity.this, "Chat launched successfully", Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onFailure(Object error) {
                            Toast.makeText(ChatbotActivity.this, "Chat launch failed: " + error, Toast.LENGTH_LONG).show();
                        }
                    });
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Unable to launch chat. Chat service may not be available.", Toast.LENGTH_LONG).show();
        }
    }
}