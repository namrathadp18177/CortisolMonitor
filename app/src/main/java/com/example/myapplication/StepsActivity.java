package com.example.myapplication;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.HapticFeedbackConstants;
import android.view.animation.BounceInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

public class StepsActivity extends AppCompatActivity {
    private static final String TAG = "StepsActivity";
    private TextView tvWelcome;
    private CardView cardStep1, cardStep2, cardStep3, cardStep4;
    private ImageView ivCheckmark1, ivCheckmark2, ivCheckmark3, ivCheckmark4;
    private Button btnStartJourney;
    private int currentStep = 0;
    private boolean[] stepsCompleted = new boolean[4];
    private Handler handler = new Handler(Looper.getMainLooper());
    private UserDatabaseHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_steps);

        dbHelper = UserDatabaseHelper.getInstance(this);



        // Check if user has required age data
        if (!checkUserAgeData()) {
            return; // Activity will be finished in checkUserAgeData if redirect is needed
        }

        initializeViews();
        setupClickListeners();
        welcomeAnimation();
        
        // Mark all steps as completed automatically
        for (int i = 0; i < stepsCompleted.length; i++) {
            stepsCompleted[i] = true;
        }
        
        // Show all checkmarks
//        showAllCheckmarks();
    }

    private boolean checkUserAgeData() {
        String userEmail = DatabaseHelper.getCurrentUserEmail();
        if (userEmail != null && !userEmail.isEmpty()) {
            int userAge = dbHelper.getUserAge(userEmail);
            String userBirthDate = dbHelper.getUserBirthDate(userEmail);
            
            if (userAge <= 0 && (userBirthDate == null || userBirthDate.isEmpty())) {
                Log.d(TAG, "User missing age data, redirecting to UserProfile");
                // User doesn't have age data - redirect to UserProfile
                Intent intent = new Intent(StepsActivity.this, UserProfileActivity.class);
                intent.putExtra("from_login", true);
                startActivity(intent);
                finish();
                return false;
            }
        }
        return true;
    }

    private void initializeViews() {
        tvWelcome = findViewById(R.id.tvWelcome);
        cardStep1 = findViewById(R.id.cardStep1);
        cardStep2 = findViewById(R.id.cardStep2);
        cardStep3 = findViewById(R.id.cardStep3);
        cardStep4 = findViewById(R.id.cardStep4);
        btnStartJourney = findViewById(R.id.btnStartJourney);
        
        // Get email and set personalized welcome
        String userEmail = DatabaseHelper.getCurrentUserEmail();
        if (userEmail != null && !userEmail.isEmpty()) {
            String firstName = userEmail.split("@")[0];
            firstName = firstName.substring(0, 1).toUpperCase() + firstName.substring(1);
            tvWelcome.setText("Welcome, " + firstName );
        } else {
            tvWelcome.setText("Welcome!");
        }

        // Animate cards sequentially
        animateCardsSequentially();
    }

//    private void showAllCheckmarks() {
//        // Show all checkmarks as visible
//        findViewById(R.id.ivCheckmark1).setVisibility(View.VISIBLE);
//        findViewById(R.id.ivCheckmark2).setVisibility(View.VISIBLE);
//        findViewById(R.id.ivCheckmark3).setVisibility(View.VISIBLE);
//        findViewById(R.id.ivCheckmark4).setVisibility(View.VISIBLE);
//    }

    private void setupClickListeners() {
        // Remove all card click listeners
        
        // Only keep the Start Journey button click listener
        btnStartJourney.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            
            // Mark onboarding as completed for this user
            String userEmail = DatabaseHelper.getCurrentUserEmail();
            if (userEmail != null && !userEmail.isEmpty()) {
                SharedPreferences prefs = getSharedPreferences("user_prefs", MODE_PRIVATE);
                prefs.edit().putBoolean("completed_onboarding_" + userEmail, true).apply();
            }
            
            // Navigate directly to Dashboard without checking step completion
            Intent intent = new Intent(StepsActivity.this, DashboardActivity.class);
            startActivity(intent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        });
    }

    private void animateCardsSequentially() {
        CardView[] cards = {cardStep1, cardStep2, cardStep3, cardStep4};
        
        for (int i = 0; i < cards.length; i++) {
            cards[i].setAlpha(0f);
            cards[i].setTranslationY(50f);
            
            cards[i].animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(300)
                    .setStartDelay(100 + (i * 150))
                    .start();
        }
    }

    private void welcomeAnimation() {
        // Simple welcome animation for the text
        tvWelcome.setAlpha(0f);
        tvWelcome.animate()
                .alpha(1f)
                .setDuration(1000)
                .start();
    }

    private void encourageAnimation() {
        // Simple encouragement animation for the welcome text
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(tvWelcome, "scaleX", 1f, 1.05f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(tvWelcome, "scaleY", 1f, 1.05f, 1f);
        scaleX.setDuration(500);
        scaleY.setDuration(500);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(scaleX, scaleY);
        animatorSet.start();
    }

    private void playTapAnimation(View view) {
        ObjectAnimator scaleDownX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 0.9f, 1f);
        ObjectAnimator scaleDownY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 0.9f, 1f);
        scaleDownX.setDuration(200);
        scaleDownY.setDuration(200);

        AnimatorSet scaleDown = new AnimatorSet();
        scaleDown.play(scaleDownX).with(scaleDownY);
        scaleDown.start();
    }

    private void highlightIncompleteSteps() {
        CardView[] cards = {cardStep1, cardStep2, cardStep3, cardStep4};
        
        for (int i = 0; i < cards.length; i++) {
            if (!stepsCompleted[i]) {
                // Animate incomplete cards to draw attention
                ObjectAnimator scaleX = ObjectAnimator.ofFloat(cards[i], "scaleX", 1f, 1.05f, 1f);
                ObjectAnimator scaleY = ObjectAnimator.ofFloat(cards[i], "scaleY", 1f, 1.05f, 1f);
                ObjectAnimator elevationAnim = ObjectAnimator.ofFloat(cards[i], "cardElevation", 4f, 12f, 4f);
                
                scaleX.setDuration(500);
                scaleY.setDuration(500);
                elevationAnim.setDuration(500);
                
                AnimatorSet animatorSet = new AnimatorSet();
                animatorSet.playTogether(scaleX, scaleY, elevationAnim);
                animatorSet.start();
                
                // Scroll to first incomplete step
                if (i == 0 || (i > 0 && stepsCompleted[i-1])) {
                    final int index = i;
                    handler.postDelayed(() -> {
                        ScrollView scrollView = findViewById(R.id.scrollView);
                        scrollView.smoothScrollTo(0, cards[index].getTop());
                    }, 100);
                    break;
                }
            }
        }
        
        // Guide user attention with encouragement animation
        encourageAnimation();
    }
}