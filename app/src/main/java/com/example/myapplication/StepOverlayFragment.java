package com.example.myapplication;

import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.List;
import java.util.Locale;

public class StepOverlayFragment extends Fragment {

    private List<StepModel> steps;
    private int currentIndex = 0;
    private CountDownTimer countDownTimer;
    private boolean timerFinished = false;
    private boolean pendingSkipConfirm = false;

    public interface StepOverlayListener {
        void onOverlayComplete();
        void onStoredForLater();
    }

    private StepOverlayListener listener;

    public static StepOverlayFragment newInstance(List<StepModel> steps) {
        StepOverlayFragment fragment = new StepOverlayFragment();
        fragment.steps = steps;
        return fragment;
    }

    public void setStepOverlayListener(StepOverlayListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_step_overlay, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        renderStep(view, currentIndex);
    }

    private void renderStep(View view, int index) {
        StepModel step = steps.get(index);

        TextView tvLabel      = view.findViewById(R.id.tvStepLabel);
        ImageView imgStep     = view.findViewById(R.id.imgStep);
        TextView tvTitle      = view.findViewById(R.id.tvStepTitle);
        TextView tvDesc       = view.findViewById(R.id.tvStepDescription);
        TextView tvBullets    = view.findViewById(R.id.tvBulletPoints);
        TextView tvTimer      = view.findViewById(R.id.tvTimer);
        TextView tvMotivation = view.findViewById(R.id.tvMotivation);
        Button btnSkip        = view.findViewById(R.id.btnSkip);
        Button btnStoreIt     = view.findViewById(R.id.btnStoreIt);
        Button btnNext        = view.findViewById(R.id.btnNext);

        // reset per‑step state
        pendingSkipConfirm = false;
        timerFinished = false;

        tvLabel.setText("STEP " + (index + 1) + " OF " + steps.size());
        imgStep.setImageResource(step.imageResId);
        tvTitle.setText(step.title);
        tvDesc.setText(step.description);

        // Bullet points
        if (step.bulletPoints != null && !step.bulletPoints.isEmpty()) {
            tvBullets.setVisibility(View.VISIBLE);
            StringBuilder sb = new StringBuilder();
            for (String bullet : step.bulletPoints) {
                sb.append("• ").append(bullet).append("\n");
            }
            tvBullets.setText(sb.toString().trim());
        } else {
            tvBullets.setVisibility(View.GONE);
        }

        // Timer steps
        if (step.hasTimer) {
            tvTimer.setVisibility(View.VISIBLE);
            btnSkip.setVisibility(View.VISIBLE);

            if (step.showMotivation) {
                tvMotivation.setVisibility(View.VISIBLE);
            } else {
                tvMotivation.setVisibility(View.GONE);
            }

            btnNext.setEnabled(false);
            btnNext.setAlpha(0.4f);

            startCountdown(step, tvTimer, tvMotivation, btnNext, btnSkip, view);
        } else {
            tvTimer.setVisibility(View.GONE);
            tvMotivation.setVisibility(View.GONE);
            btnSkip.setVisibility(View.GONE);

            btnNext.setEnabled(true);
            btnNext.setAlpha(1.0f);
        }

        // Storage step
        if (step.isOptionalStorage) {
            btnStoreIt.setVisibility(View.VISIBLE);
            btnNext.setText("Perform Test Now");
        } else {
            btnStoreIt.setVisibility(View.GONE);
            if (index == steps.size() - 1) {
                btnNext.setText("Begin Scan");
            } else {
                btnNext.setText("Next");
            }
        }

        // Next — blocked if timer still running
        btnNext.setOnClickListener(v -> {
            if (step.hasTimer && !timerFinished) {
                Toast.makeText(getContext(),
                        "Please wait for the timer to finish.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            cancelTimer();
            advanceToNextStep(view);
        });

        // Skip — first tap warns, second tap actually skips
        btnSkip.setOnClickListener(v -> {
            if (!timerFinished) {
                if (!pendingSkipConfirm) {
                    pendingSkipConfirm = true;

                    // Compute minutes from this step's duration
                    long minutes = step.timerDurationMs / (60 * 1000);
                    String msg = "Please wait " + minutes +
                            " minutes, or tap Skip again to skip.";

                    Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            // confirmed skip or timer already finished
            cancelTimer();
            timerFinished = true;
            pendingSkipConfirm = false;
            btnSkip.setVisibility(View.GONE);
            advanceToNextStep(view);
        });


        // Store It (Step 3 only)
        btnStoreIt.setOnClickListener(v -> {
            cancelTimer();
            if (listener != null) listener.onStoredForLater();
        });
    }

    private void advanceToNextStep(View view) {
        currentIndex++;
        if (currentIndex >= steps.size()) {
            if (listener != null) listener.onOverlayComplete();
        } else {
            renderStep(view, currentIndex);
        }
    }

    private void startCountdown(StepModel step, TextView tvTimer,
                                TextView tvMotivation, Button btnNext,
                                Button btnSkip, View view) {
        countDownTimer = new CountDownTimer(step.timerDurationMs, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long seconds = millisUntilFinished / 1000;
                long mins = seconds / 60;
                long secs = seconds % 60;
                tvTimer.setText(String.format(Locale.getDefault(),
                        "%02d:%02d", mins, secs));

                if (step.showMotivation) {
                    tvMotivation.setText(getMotivationalMessage(
                            seconds,
                            step.timerDurationMs / 1000
                    ));
                }
            }

            @Override
            public void onFinish() {
                tvTimer.setText("00:00");
                if (step.showMotivation) {
                    tvMotivation.setText("That is it! Great job!");
                }
                timerFinished = true;
                pendingSkipConfirm = false;
                btnNext.setEnabled(true);
                btnNext.setAlpha(1.0f);
                btnSkip.setVisibility(View.GONE);
            }
        }.start();
    }

    private String getMotivationalMessage(long secondsRemaining, long totalSeconds) {
        double progress = (double) secondsRemaining / totalSeconds;
        if (progress > 0.8) return "You are doing great! Just hold still...";
        if (progress > 0.6) return "Hang in there, keep going!";
        if (progress > 0.4) return "Halfway there, you are doing amazing!";
        if (progress > 0.2) return "Almost done, just a little longer!";
        if (progress > 0.05) return "So close now, nearly there!";
        return "That is it! Great job!";
    }

    private void cancelTimer() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelTimer();
    }
}
