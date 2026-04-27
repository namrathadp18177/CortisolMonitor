package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

/**
 * ProfileFragment — Tab 5
 *
 * Organises user-centric content into four sections:
 *
 *   Results & History
 *     • Combined Results     → FinalResultsActivity
 *     • Cortisol Graph       → CortisolGraphActivity
 *     • Questionnaire History → ResponsesViewerActivity
 *
 *   Account
 *     • My Account           → AccountDetailsActivity
 *
 *   Care & Support
 *     • Care Provider        → AccountDetailsActivity (provider info is shown there)
 *     • Help & Support       → ChatbotActivity
 *
 *   Logout
 *     • Confirmation dialog then clears session and navigates to RegisterLoginActivity
 */
public class ProfileFragment extends Fragment {

    private boolean cardAnimated = false;

    // ─────────────────────────────────────────────────────────────────────────
    // Fragment lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        populateUserHeader(view);
        setupClickListeners(view);

        if (!cardAnimated) {
            animateContent(view);
            cardAnimated = true;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // User header
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fills in the avatar initial, display name, and email in the header card.
     * Name is derived from email (same heuristic as DashboardActivity).
     */
    private void populateUserHeader(View root) {
        TextView tvInitial = root.findViewById(R.id.tvAvatarInitial);
        TextView tvName    = root.findViewById(R.id.tvProfileName);
        TextView tvEmail   = root.findViewById(R.id.tvProfileEmail);

        String email     = DatabaseHelper.getCurrentUserEmail();
        String firstName = "User";
        char   initial   = 'U';

        if (email != null && !email.isEmpty()) {
            String raw = email.split("@")[0];
            firstName = raw.substring(0, 1).toUpperCase() + raw.substring(1);
            initial   = firstName.charAt(0);
        }

        if (tvInitial != null) tvInitial.setText(String.valueOf(initial));
        if (tvName    != null) tvName.setText(firstName);
        if (tvEmail   != null) tvEmail.setText(email != null ? email : "");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Click listeners
    // ─────────────────────────────────────────────────────────────────────────

    private void setupClickListeners(View root) {

        // Results & History
        root.findViewById(R.id.rowCombinedResults).setOnClickListener(v ->
                launch(FinalResultsActivity.class));

        root.findViewById(R.id.rowCortisolGraph).setOnClickListener(v -> {
            if (!isAdded()) return;
            Intent intent = new Intent(requireActivity(), CortisolGraphActivity.class);
            intent.putExtra("useremail", DatabaseHelper.getCurrentUserEmail());
            startActivity(intent);
            requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        });

        root.findViewById(R.id.rowViewResponses).setOnClickListener(v ->
                launch(ResponsesViewerActivity.class));

        // Account
        root.findViewById(R.id.rowAccountDetails).setOnClickListener(v ->
                launch(AccountDetailsActivity.class));

        // Care & Support
        root.findViewById(R.id.rowCareProvider).setOnClickListener(v ->
                launch(CareProviderInfoActivity.class));

        root.findViewById(R.id.rowHelp).setOnClickListener(v -> {
            if (!isAdded()) return;
            try {
                launch(ChatbotActivity.class);
            } catch (Exception e) {
                Snackbar.make(requireView(),
                        "Help & Support is not available right now",
                        Snackbar.LENGTH_SHORT).show();
            }
        });

        // Logout
        MaterialButton btnLogout = root.findViewById(R.id.btnLogout);
        btnLogout.setOnClickListener(v -> confirmLogout());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Logout
    // ─────────────────────────────────────────────────────────────────────────

    private void confirmLogout() {
        if (!isAdded()) return;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Log Out")
                .setMessage("Are you sure you want to log out?")
                .setPositiveButton("Log Out", (dialog, which) -> performLogout())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performLogout() {
        if (!isAdded()) return;

        FirebaseAuthHelper.logout(requireActivity());

        requireActivity().getSharedPreferences("session",
                android.content.Context.MODE_PRIVATE).edit().clear().apply();
        requireActivity().getSharedPreferences("user_prefs",
                        android.content.Context.MODE_PRIVATE).edit()
                .putBoolean("remember_me", false)
                .remove("saved_email")
                .remove("saved_password")
                .apply();

        UserDatabaseHelper.setCurrentUserEmail("");
        DatabaseHelper.setCurrentUserEmail("");

        Intent intent = new Intent(requireActivity(), RegisterLoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        requireActivity().finish();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void launch(Class<?> target) {
        if (!isAdded()) return;
        startActivity(new Intent(requireActivity(), target));
        requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Animations
    // ─────────────────────────────────────────────────────────────────────────

    private void animateContent(View root) {
        int[] viewIds = {
                R.id.tvAvatarInitial, // header card parent — rough reference
                R.id.rowCombinedResults,
                R.id.rowAccountDetails,
                R.id.rowCareProvider,
                R.id.btnLogout
        };

        for (int i = 0; i < viewIds.length; i++) {
            View v = root.findViewById(viewIds[i]);
            // If it's a row, animate the parent card for a cleaner group animation
            View target = (v != null && v.getParent() instanceof View && i > 0)
                    ? (View) v.getParent().getParent()
                    : v;
            animateView(target, 60L + i * 100L);
        }
    }

    private void animateView(View v, long startDelay) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationY(25f);
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(380)
                .setStartDelay(startDelay)
                .setInterpolator(new OvershootInterpolator(0.6f))
                .start();
    }
}
