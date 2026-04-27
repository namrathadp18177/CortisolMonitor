package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * PlusFragment — Add Health Data tab.
 *
 * Each row now navigates to HealthLogActivity, which shows the full history
 * for that category plus a FAB (pencil) to add new entries.
 */
public class PlusFragment extends Fragment {

    // SharedPreferences keys — must match those used in HealthLogActivity / previously stored data
    static final String KEY_NOTES         = "notes";
    static final String KEY_SYMPTOMS      = "symptoms";
    static final String KEY_PRESCRIPTIONS = "prescriptions";
    static final String KEY_REPORTS       = "reports";
    static final String KEY_CONDITIONS    = "conditions";
    static final String KEY_HABITS        = "habits";

    private boolean cardAnimated = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_plus, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupClickListeners(view);

        if (!cardAnimated) {
            animateContent(view);
            cardAnimated = true;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Navigation
    // ─────────────────────────────────────────────────────────────────────────

    private void setupClickListeners(View root) {
        root.findViewById(R.id.rowAddNote)
                .setOnClickListener(v -> openLog(
                        KEY_NOTES,
                        "Notes",
                        "Write your note or observation here...",
                        false,
                        "Note saved."));

        root.findViewById(R.id.rowAddSymptom)
                .setOnClickListener(v -> {
                    if (!isAdded()) return;
                    Intent i = new Intent(requireContext(), HealthCardLogActivity.class);
                    i.putExtra(HealthCardLogActivity.EXTRA_TYPE,
                            HealthCardLogActivity.TYPE_SYMPTOM);
                    startActivity(i);
                });

        root.findViewById(R.id.rowUploadPrescription)
                .setOnClickListener(v -> openLog(
                        KEY_PRESCRIPTIONS,
                        "Prescriptions",
                        null,
                        true,
                        "Prescription saved."));

        root.findViewById(R.id.rowUploadReport)
                .setOnClickListener(v -> openLog(
                        KEY_REPORTS,
                        "Medical Reports",
                        null,
                        true,
                        "Report saved."));

        root.findViewById(R.id.rowAddCondition)
                .setOnClickListener(v -> {
                    if (!isAdded()) return;
                    Intent i = new Intent(requireContext(), HealthCardLogActivity.class);
                    i.putExtra(HealthCardLogActivity.EXTRA_TYPE,
                            HealthCardLogActivity.TYPE_CONDITION);
                    startActivity(i);
                });

        root.findViewById(R.id.rowRecordHabit)
                .setOnClickListener(v -> {
                    if (!isAdded()) return;
                    Intent i = new Intent(requireContext(), HealthCardLogActivity.class);
                    i.putExtra(HealthCardLogActivity.EXTRA_TYPE,
                            HealthCardLogActivity.TYPE_HABIT);
                    startActivity(i);
                });
    }

    private void openLog(String prefsKey, String title,
                         @Nullable String hint, boolean isFile, String successMsg) {
        if (!isAdded()) return;
        Intent intent = new Intent(requireContext(), HealthLogActivity.class);
        intent.putExtra(HealthLogActivity.EXTRA_PREFS_KEY,   prefsKey);
        intent.putExtra(HealthLogActivity.EXTRA_TITLE,       title);
        intent.putExtra(HealthLogActivity.EXTRA_HINT,        hint);
        intent.putExtra(HealthLogActivity.EXTRA_IS_FILE,     isFile);
        intent.putExtra(HealthLogActivity.EXTRA_SUCCESS_MSG, successMsg);
        startActivity(intent);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Animations
    // ─────────────────────────────────────────────────────────────────────────

    private void animateContent(View root) {
        int[] ids = {
                R.id.rowAddNote, R.id.rowAddSymptom,
                R.id.rowUploadPrescription, R.id.rowUploadReport,
                R.id.rowAddCondition, R.id.rowRecordHabit
        };
        for (int i = 0; i < ids.length; i++) {
            View v = root.findViewById(ids[i]);
            if (v == null) continue;
            v.setAlpha(0f);
            v.setTranslationY(20f);
            v.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(300)
                    .setStartDelay(60L + i * 60L)
                    .setInterpolator(new OvershootInterpolator(0.6f))
                    .start();
        }
    }
}
