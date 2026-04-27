package com.example.myapplication;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.BounceInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

public class MainActivity extends AppCompatActivity {
    private ImageView robotImage;
    private TextView tvWelcome;
    private Button btnGetStarted;
    private CardView cardView;
    private View mainLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (FirebaseAuthHelper.isLoggedIn()) {
            routeLoggedInUser();
            return;
        }

        setContentView(R.layout.activity_main);

        robotImage = findViewById(R.id.robotImage);
        tvWelcome = findViewById(R.id.tvWelcome);
        btnGetStarted = findViewById(R.id.btnGetStarted);
        cardView = findViewById(R.id.cardView);
        mainLayout = findViewById(R.id.mainLayout);

        if (robotImage != null) {
            robotImage.setAlpha(0f);
        }
        tvWelcome.setAlpha(0f);
        btnGetStarted.setAlpha(0f);
        cardView.setAlpha(0f);
        cardView.setScaleX(0.8f);
        cardView.setScaleY(0.8f);

        new Handler().postDelayed(this::startAnimations, 300);

        btnGetStarted.setOnClickListener(v -> {
            ObjectAnimator scaleDownX = ObjectAnimator.ofFloat(btnGetStarted, "scaleX", 1f, 0.9f, 1f);
            ObjectAnimator scaleDownY = ObjectAnimator.ofFloat(btnGetStarted, "scaleY", 1f, 0.9f, 1f);
            scaleDownX.setDuration(300);
            scaleDownY.setDuration(300);

            AnimatorSet scaleDown = new AnimatorSet();
            scaleDown.play(scaleDownX).with(scaleDownY);
            scaleDown.start();

            new Handler().postDelayed(() -> {
                Intent intent = new Intent(MainActivity.this, RegisterLoginActivity.class);
                startActivity(intent);
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
            }, 200);
        });
    }

    private void routeLoggedInUser() {
        String uid = FirebaseAuthHelper.getCurrentUid();
        String email = FirebaseAuthHelper.getCurrentEmail();
        String role = FirebaseAuthHelper.getSavedRole(this);

        if (email != null && !email.isEmpty()) {
            DatabaseHelper.setCurrentUserEmail(email);
            UserDatabaseHelper.setCurrentUserEmail(email);
        }

        // Cross-check: if the saved role is not CARE_PROVIDER, verify against the
        // local SQLite care_providers table. This fixes the case where the role was
        // saved incorrectly as PATIENT during a previous login (e.g. SQLite was empty
        // at that time) but has since been restored (e.g. by a subsequent correct login
        // on the same device or by the Firestore fallback in RegisterLoginActivity).
        if (!"CARE_PROVIDER".equals(role) && email != null && !email.isEmpty()) {
            UserDatabaseHelper dbHelper = UserDatabaseHelper.getInstance(this);
            if (dbHelper.isCareProviderRegistered(email)) {
                role = "CARE_PROVIDER";
                // Correct the persisted role so future restarts also route correctly.
                if (uid != null) {
                    FirebaseAuthHelper.saveSession(this, uid, "CARE_PROVIDER");
                }
            }
        }

        if ("CARE_PROVIDER".equals(role)) {
            Intent intent = new Intent(MainActivity.this, CareProviderDashboardActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        SharedPreferences userPrefs = getSharedPreferences("user_prefs", MODE_PRIVATE);
        boolean hasCompletedOnboarding = email != null &&
                userPrefs.getBoolean("completed_onboarding_" + email, false);

        Intent intent;
        if (hasCompletedOnboarding) {
            intent = new Intent(MainActivity.this, PatientMainActivity.class);
        } else {
            intent = new Intent(MainActivity.this, StepsActivity.class);
        }

        startActivity(intent);
        finish();
    }

    private void startAnimations() {
        cardView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(500)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();

        robotImage.animate()
                .alpha(1f)
                .setDuration(800)
                .setStartDelay(200)
                .start();

        ObjectAnimator bounceAnimator = ObjectAnimator.ofFloat(robotImage, "translationY", -20f, 0f);
        bounceAnimator.setInterpolator(new BounceInterpolator());
        bounceAnimator.setDuration(1000);
        bounceAnimator.setStartDelay(500);
        bounceAnimator.start();

        tvWelcome.animate()
                .alpha(1f)
                .setDuration(800)
                .setStartDelay(700)
                .start();

        btnGetStarted.animate()
                .alpha(1f)
                .setDuration(800)
                .setStartDelay(1000)
                .start();
    }
}