package com.example.myapplication;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.snackbar.Snackbar;

import java.util.Calendar;

/**
 * HomeFragment — Tab 1
 *
 * Displays the two test cards:
 *   • Cortisol Test  – active, "Take a Test" launches BiomarkerInstructions
 *   • DHEA Test      – disabled, "Under Development"
 */
public class HomeFragment extends Fragment {

    private NestedScrollView scrollView;
    private MaterialCardView cardCortisolTest;
    private boolean cardAnimated = false;

    // ─────────────────────────────────────────────────────────────────────────
    // Fragment lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        scrollView       = view.findViewById(R.id.fragment_home_scroll);
        cardCortisolTest = view.findViewById(R.id.cardCortisolTest);

        setGreeting(view);
        setupButtons(view);
        setupCardTouchAnimation(cardCortisolTest);

        if (!cardAnimated) {
            animateCards(view);
            cardAnimated = true;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI setup
    // ─────────────────────────────────────────────────────────────────────────

    /** Sets a time-appropriate greeting and extracts the first name from the user's email. */
    private void setGreeting(View root) {
        TextView tvGreeting  = root.findViewById(R.id.tvGreeting);
        TextView tvSubtitle  = root.findViewById(R.id.tvGreetingSubtitle);

        // Derive first name from email (same logic as original DashboardActivity)
        String email     = DatabaseHelper.getCurrentUserEmail();
        String firstName = "there";
        if (email != null && !email.isEmpty()) {
            String raw = email.split("@")[0];
            firstName = raw.substring(0, 1).toUpperCase() + raw.substring(1);
        }

        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String timeGreeting = hour < 12 ? "Good morning"
                : hour < 17 ? "Good afternoon"
                :             "Good evening";

        tvGreeting.setText(timeGreeting + ", " + firstName + "!");
        if (tvSubtitle != null) {
            tvSubtitle.setText("What would you like to test today?");
        }
    }

    private void setupButtons(View root) {
        MaterialButton btnTakeTest = root.findViewById(R.id.btnTakeCortisolTest);

        btnTakeTest.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);

            Snackbar.make(requireView(), "Starting cortisol measurement…", Snackbar.LENGTH_SHORT).show();

            // Small delay for the snackbar to show, then launch the existing measurement flow
            v.postDelayed(() -> {
                if (!isAdded()) return;
                Intent intent = new Intent(requireActivity(), BiomarkerInstructions.class);
                String userEmail = DatabaseHelper.getCurrentUserEmail();
                intent.putExtra("user_email",  userEmail);
                intent.putExtra("user_role",   "PATIENT");
                startActivity(intent);
                requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
            }, 250);
        });

        // DHEA card tap — show informational Snackbar
        MaterialCardView cardDHEA = root.findViewById(R.id.cardDHEATest);
        cardDHEA.setOnClickListener(v ->
                Snackbar.make(requireView(),
                        "DHEA Test coming in a future update",
                        Snackbar.LENGTH_SHORT).show()
        );

        // Watch Vitals card — opens the live HRV/EDA/skin-temp screen fed by the watch app
        MaterialButton btnLiveVitals = root.findViewById(R.id.btnViewLiveVitals);
        if (btnLiveVitals != null) {
            btnLiveVitals.setOnClickListener(v -> {
                if (!isAdded()) return;
                startActivity(new Intent(requireActivity(), LiveVitalsActivity.class));
            });
        }

        // Start Session card — ties a watch recording + questionnaire + biomarker test
        // together under one app_session_id (see StartSessionActivity).
        MaterialButton btnStartSession = root.findViewById(R.id.btnStartSession);
        if (btnStartSession != null) {
            btnStartSession.setOnClickListener(v -> {
                if (!isAdded()) return;
                startActivity(new Intent(requireActivity(), StartSessionActivity.class));
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Animations
    // ─────────────────────────────────────────────────────────────────────────

    private void animateCards(View root) {
        int[] cardIds = {R.id.cardCortisolTest, R.id.cardDHEATest};
        for (int i = 0; i < cardIds.length; i++) {
            View card = root.findViewById(cardIds[i]);
            if (card == null) continue;
            card.setAlpha(0f);
            card.setTranslationY(40f);
            card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(450)
                    .setStartDelay(100L + i * 150L)
                    .setInterpolator(new OvershootInterpolator(0.8f))
                    .start();
        }
    }

    private void setupCardTouchAnimation(MaterialCardView card) {
        if (card == null) return;
        card.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    ObjectAnimator sx = ObjectAnimator.ofFloat(v, "scaleX", 0.97f);
                    ObjectAnimator sy = ObjectAnimator.ofFloat(v, "scaleY", 0.97f);
                    sx.setDuration(80); sy.setDuration(80);
                    AnimatorSet downSet = new AnimatorSet();
                    downSet.playTogether(sx, sy);
                    downSet.start();
                    break;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    ObjectAnimator sx = ObjectAnimator.ofFloat(v, "scaleX", 1f);
                    ObjectAnimator sy = ObjectAnimator.ofFloat(v, "scaleY", 1f);
                    sx.setDuration(80); sy.setDuration(80);
                    AnimatorSet upSet = new AnimatorSet();
                    upSet.playTogether(sx, sy);
                    upSet.start();
                    break;
                }
            }
            return false;
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API for PatientMainActivity
    // ─────────────────────────────────────────────────────────────────────────

    /** Called by PatientMainActivity when the user re-taps the already-selected Home tab. */
    public void scrollToTop() {
        if (scrollView != null) {
            scrollView.smoothScrollTo(0, 0);
        }
    }
}
