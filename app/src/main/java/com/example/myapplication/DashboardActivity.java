package com.example.myapplication;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.BounceInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import android.view.View;
import android.widget.Button;
import android.view.Menu;
import android.view.MenuItem;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import com.google.android.material.snackbar.Snackbar;

public class DashboardActivity extends AppCompatActivity {
    private static final String TAG = "DashboardActivity";
    private TextView tvWelcome;
    private ImageView ivAppIcon;
    private CardView cardQuestionnaire, cardBiomarkerConnection1, cardResults;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean animationsStarted = false;
    private DatabaseHelper dbHelper;
    private UserDatabaseHelper userDbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        userDbHelper = UserDatabaseHelper.getInstance(this);
        // Check if user has required age data
        if (!checkUserAgeData()) {
            return; // Activity will be finished in checkUserAgeData if redirect is needed
        }

        // Set up toolbar
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setElevation(8f);
            getSupportActionBar().setTitle("My Dashboard");
        }

        initializeViews();
        setupClickListeners();

        // Delay animations slightly for a smoother experience
        handler.postDelayed(() -> {
            if (!isFinishing() && !animationsStarted) {
                animateWelcome();
                animateCardsSequentially();
                animationsStarted = true;
            }
        }, 300);
    }

    private boolean checkUserAgeData() {
        String userEmail = UserDatabaseHelper.getCurrentUserEmail();
        if (userEmail != null && !userEmail.isEmpty()) {
            // Get instance of UserDatabaseHelper
            UserDatabaseHelper dbHelper = UserDatabaseHelper.getInstance(this);

            // Call methods on the instance, not the class
            int userAge = dbHelper.getUserAge(userEmail);
            String userBirthDate = dbHelper.getUserBirthDate(userEmail);

            if (userAge <= 0 && (userBirthDate == null || userBirthDate.isEmpty())) {
                Log.d(TAG, "User missing age data, redirecting to UserProfile");
                Intent intent = new Intent(DashboardActivity.this, UserProfileActivity.class);
                startActivity(intent);
                finish();
                return false;
            }
        }
        return true;
    }

    private void initializeViews() {
        tvWelcome = findViewById(R.id.tvWelcome);
        ivAppIcon = findViewById(R.id.ivAppIcon);
        cardQuestionnaire = findViewById(R.id.cardQuestionnaire);
        cardBiomarkerConnection1 = findViewById(R.id.cardBiomarkerConnection1);
        cardResults = findViewById(R.id.cardResults);

        // Prepare initial state for animations
        ivAppIcon.setAlpha(0f);
        ivAppIcon.setScaleX(0.8f);
        ivAppIcon.setScaleY(0.8f);

        cardQuestionnaire.setAlpha(0f);
        cardBiomarkerConnection1.setAlpha(0f);
        cardResults.setAlpha(0f);
        cardQuestionnaire.setTranslationY(50f);
        cardBiomarkerConnection1.setTranslationY(50f);
        cardResults.setTranslationY(50f);

        // Get email from intent or DatabaseHelper
        String userEmail = DatabaseHelper.getCurrentUserEmail();
        if (userEmail != null && !userEmail.isEmpty()) {
            // Extract first name for more personalized welcome
            String firstName = userEmail.split("@")[0];
            // Capitalize first letter
            firstName = firstName.substring(0, 1).toUpperCase() + firstName.substring(1);
            tvWelcome.setText("Welcome, " + firstName + "!");
        }
    }

    private void setupClickListeners() {
        CardView[] cards = {cardQuestionnaire, cardBiomarkerConnection1, cardResults};

        for (CardView card : cards) {
            setupCardTouchAnimation(card);
        }

        cardQuestionnaire.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                playTapAnimation(cardQuestionnaire);

                // Show brief feedback about where user is going
                Snackbar.make(findViewById(android.R.id.content),
                        "Opening health questionnaires...", Snackbar.LENGTH_SHORT).show();

                // Small delay for animation to complete
                handler.postDelayed(() -> {
                    // Navigate to QuestionnaireMain instead of directly to QuestionnaireActivity
                    Intent intent = new Intent(DashboardActivity.this, QuestionnaireMain.class);
                    startActivity(intent);
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
                }, 250);
            }
        });

        cardBiomarkerConnection1.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            playTapAnimation(cardBiomarkerConnection1);

            Snackbar.make(findViewById(android.R.id.content),
                    "Connecting to biomarker device...", Snackbar.LENGTH_SHORT).show();

            handler.postDelayed(() -> {
                Intent intent = new Intent(DashboardActivity.this, BiomarkerInstructions.class);
                String userEmail = DatabaseHelper.getCurrentUserEmail();
                intent.putExtra("user_email", userEmail);
                startActivity(intent);
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
            }, 250);

        });

        cardResults.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            playTapAnimation(cardResults);

            Snackbar.make(findViewById(android.R.id.content),
                    "Loading your assessment results...", Snackbar.LENGTH_SHORT).show();

            handler.postDelayed(() -> {
                startActivity(new Intent(DashboardActivity.this, FinalResultsActivity.class));
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
            }, 250);
        });
    }

    private void setupCardTouchAnimation(CardView card) {
        card.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    ObjectAnimator scaleDownX = ObjectAnimator.ofFloat(v, "scaleX", 0.95f);
                    ObjectAnimator scaleDownY = ObjectAnimator.ofFloat(v, "scaleY", 0.95f);
                    scaleDownX.setDuration(100);
                    scaleDownY.setDuration(100);
                    AnimatorSet scaleDown = new AnimatorSet();
                    scaleDown.play(scaleDownX).with(scaleDownY);
                    scaleDown.start();
                    // Increase card elevation for pressed state
                    card.setCardElevation(16f);
                    break;

                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    ObjectAnimator scaleUpX = ObjectAnimator.ofFloat(v, "scaleX", 1f);
                    ObjectAnimator scaleUpY = ObjectAnimator.ofFloat(v, "scaleY", 1f);
                    scaleUpX.setDuration(100);
                    scaleUpY.setDuration(100);
                    AnimatorSet scaleUp = new AnimatorSet();
                    scaleUp.play(scaleUpX).with(scaleUpY);
                    scaleUp.start();
                    // Return to normal elevation
                    card.setCardElevation(8f);
                    break;
            }
            // Let the click event still be processed
            return false;
        });
    }

    private void animateWelcome() {
        // Animate the app icon first
        if (ivAppIcon != null) {
            ivAppIcon.setAlpha(0f);
            ivAppIcon.setScaleX(0.8f);
            ivAppIcon.setScaleY(0.8f);
            ivAppIcon.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(800)
                    .setInterpolator(new OvershootInterpolator())
                    .start();
        }

        // Then animate the welcome text
        if (tvWelcome != null) {
            tvWelcome.setAlpha(0f);
            tvWelcome.animate()
                    .alpha(1f)
                    .setDuration(1000)
                    .setStartDelay(300)
                    .start();
        }
    }

    private void animateCardsSequentially() {
        CardView[] cards = {cardQuestionnaire, cardBiomarkerConnection1, cardResults};

        for (int i = 0; i < cards.length; i++) {
            final CardView card = cards[i];

            card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(500)
                    .setStartDelay(100 + (i * 150))
                    .setInterpolator(new OvershootInterpolator())
                    .start();
        }
    }

    private void playTapAnimation(CardView card) {
        ObjectAnimator scaleDownX = ObjectAnimator.ofFloat(card, "scaleX", 0.9f, 1.05f, 1f);
        ObjectAnimator scaleDownY = ObjectAnimator.ofFloat(card, "scaleY", 0.9f, 1.05f, 1f);
        scaleDownX.setDuration(350);
        scaleDownY.setDuration(350);

        AnimatorSet scaleDown = new AnimatorSet();
        scaleDown.play(scaleDownX).with(scaleDownY);
        scaleDown.start();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Log.d("DashboardActivity", "onCreateOptionsMenu called");
        getMenuInflater().inflate(R.menu.dashboard_menu, menu);
        Log.d("DashboardActivity", "Menu inflated: " + menu.size() + " items");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_view_responses) {
            Log.d("DashboardActivity", "View responses menu item clicked");

            // Add animation to menu item selection
            View menuView = findViewById(id);
            if (menuView != null) {
                ObjectAnimator.ofFloat(menuView, "rotationY", 0f, 360f)
                        .setDuration(500)
                        .start();
            }

            Intent intent = new Intent(DashboardActivity.this, ResponsesViewerActivity.class);
            startActivity(intent);
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Set initial elevation for all cards
        CardView[] cards = {cardQuestionnaire, cardBiomarkerConnection1, cardResults};
        for (CardView card : cards) {
            card.setCardElevation(8f);
        }
    }

    @Override
    public void onBackPressed() {
        // Show a confirming Snackbar instead of directly closing the app
        Snackbar.make(findViewById(android.R.id.content),
                "Press back again to exit", Snackbar.LENGTH_SHORT).show();

        // Play a small "goodbye" animation on Billy
        animateLogout();

        // Set a small delay before actually closing
        handler.postDelayed(() -> {
            super.onBackPressed();
        }, 2000);
    }

    private void animateLogout() {
        // Professional logout animation
        if (tvWelcome != null) {
            tvWelcome.animate()
                    .alpha(0f)
                    .setDuration(500)
                    .start();
        }
    }
}