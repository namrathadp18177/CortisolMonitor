package com.example.myapplication;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.BounceInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.constraintlayout.widget.ConstraintLayout;

public class MainActivity extends AppCompatActivity {
    private ImageView robotImage;
    private TextView tvWelcome;
    private Button btnGetStarted;
    private CardView cardView;
    private ConstraintLayout mainLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize views
        robotImage = findViewById(R.id.robotImage);
        tvWelcome = findViewById(R.id.tvWelcome);
        btnGetStarted = findViewById(R.id.btnGetStarted);
        cardView = findViewById(R.id.cardView);
        mainLayout = findViewById(R.id.mainLayout);

        // Set initial states for animation
        robotImage.setAlpha(0f);
        tvWelcome.setAlpha(0f);
        btnGetStarted.setAlpha(0f);
        cardView.setAlpha(0f);
        cardView.setScaleX(0.8f);
        cardView.setScaleY(0.8f);

        // Start animations with delay
        new Handler().postDelayed(this::startAnimations, 300);

        // Set up click listener for the Get Started button
        btnGetStarted.setOnClickListener(v -> {
            // Create bounce animation for button
            ObjectAnimator scaleDownX = ObjectAnimator.ofFloat(btnGetStarted, "scaleX", 1f, 0.9f, 1f);
            ObjectAnimator scaleDownY = ObjectAnimator.ofFloat(btnGetStarted, "scaleY", 1f, 0.9f, 1f);
            scaleDownX.setDuration(300);
            scaleDownY.setDuration(300);
            AnimatorSet scaleDown = new AnimatorSet();
            scaleDown.play(scaleDownX).with(scaleDownY);
            scaleDown.start();

            // Navigate to RegisterLoginActivity with a slight delay for animation
            new Handler().postDelayed(() -> {
                Intent intent = new Intent(MainActivity.this, RegisterLoginActivity.class);
                startActivity(intent);
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
            }, 200);
        });
    }

    private void startAnimations() {
        // Animate card view first
        cardView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(500)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();

        // Animate robot with bounce
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

        // Animate welcome text
        tvWelcome.animate()
                .alpha(1f)
                .setDuration(800)
                .setStartDelay(700)
                .start();

        // Animate button
        btnGetStarted.animate()
                .alpha(1f)
                .setDuration(800)
                .setStartDelay(1000)
                .start();
    }
}