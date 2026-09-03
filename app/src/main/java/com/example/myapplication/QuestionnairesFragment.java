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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * QuestionnairesFragment — Assessments tab
 *
 * Shows Begin / Continue buttons and a history of all assessment sessions
 * (grouped by session ID). Tapping a session opens SessionDetailActivity.
 */
public class QuestionnairesFragment extends Fragment {

    private RecyclerView rvHistory;
    private TextView tvNoHistory;
    private SessionAdapter sessionAdapter;
    private ExecutorService executor;
    private boolean viewsAnimated = false;

    // ─────────────────────────────────────────────────────────────────────────
    // Fragment lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_questionnaires, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        executor = Executors.newSingleThreadExecutor();

        setupViews(view);
        setupClickListeners(view);

        if (!viewsAnimated) {
            animateContent(view);
            viewsAnimated = true;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Reload every time the tab becomes visible (e.g. after completing an assessment)
        loadSessions();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup
    // ─────────────────────────────────────────────────────────────────────────

    private void setupViews(View root) {
        tvNoHistory = root.findViewById(R.id.tvNoHistory);
        rvHistory   = root.findViewById(R.id.rvHistory);

        sessionAdapter = new SessionAdapter();
        rvHistory.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvHistory.setAdapter(sessionAdapter);
        rvHistory.setNestedScrollingEnabled(false);
    }

    private void setupClickListeners(View root) {
        MaterialButton btnBegin = root.findViewById(R.id.btnBeginAssessment);
        btnBegin.setOnClickListener(v -> launchQuestionnaireMain());

        MaterialButton btnExport = root.findViewById(R.id.btnExportAllData);
        btnExport.setOnClickListener(v -> {
            if (!isAdded()) return;
            VitalsCsvExporter.exportQuestionnaireAndShare(requireActivity());
        });
    }

    private void launchQuestionnaireMain() {
        if (!isAdded()) return;
        Intent intent = new Intent(requireActivity(), QuestionnaireMain.class);
        startActivity(intent);
        requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session loading
    // ─────────────────────────────────────────────────────────────────────────

    private void loadSessions() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }
        executor.execute(() -> {
            try {
                DatabaseHelper dbHelper = DatabaseHelper.getInstance(requireContext());
                List<DatabaseHelper.AssessmentSession> sessions =
                        dbHelper.getDistinctSessions();

                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (sessions == null || sessions.isEmpty()) {
                        tvNoHistory.setVisibility(View.VISIBLE);
                        rvHistory.setVisibility(View.GONE);
                    } else {
                        tvNoHistory.setVisibility(View.GONE);
                        rvHistory.setVisibility(View.VISIBLE);
                        sessionAdapter.setSessions(sessions);
                    }
                });
            } catch (Exception e) {
                android.util.Log.e("QuestionnairesFragment", "Error loading sessions", e);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Animations
    // ─────────────────────────────────────────────────────────────────────────

    private void animateContent(View root) {
        animateView(root.findViewById(R.id.btnBeginAssessment), 80L);
    }

    private void animateView(View v, long delay) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationY(24f);
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(350)
                .setStartDelay(delay)
                .setInterpolator(new OvershootInterpolator(0.7f))
                .start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RecyclerView adapter — one card per assessment session
    // ─────────────────────────────────────────────────────────────────────────

    private class SessionAdapter
            extends RecyclerView.Adapter<SessionAdapter.SessionViewHolder> {

        private List<DatabaseHelper.AssessmentSession> sessions;

        void setSessions(List<DatabaseHelper.AssessmentSession> list) {
            this.sessions = list;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public SessionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_assessment_session, parent, false);
            return new SessionViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull SessionViewHolder holder, int position) {
            holder.bind(sessions.get(position));
        }

        @Override
        public int getItemCount() {
            return sessions != null ? sessions.size() : 0;
        }

        class SessionViewHolder extends RecyclerView.ViewHolder {
            private final TextView tvDate;
            private final TextView tvTypes;
            private final TextView tvStressResult;

            SessionViewHolder(@NonNull View itemView) {
                super(itemView);
                tvDate        = itemView.findViewById(R.id.tvSessionDate);
                tvTypes       = itemView.findViewById(R.id.tvSessionTypes);
                tvStressResult = itemView.findViewById(R.id.tvSessionStressResult);
            }

            void bind(DatabaseHelper.AssessmentSession session) {
                if (session == null) return;

                SimpleDateFormat sdf =
                        new SimpleDateFormat("MMM dd, yyyy  hh:mm a", Locale.getDefault());
                tvDate.setText(sdf.format(new Date(session.timestamp)));

                // Prettify the questionnaire type labels
                String types = session.questionnaireTypes
                        .replace("MENTAL_HEALTH_SCREENER", "Mental Health Screener")
                        .replace("PHQ", "PHQ-9")
                        .replace("BDI", "BDI-II")
                        .replace("GAD", "GAD-7")
                        .replace("RESILIENCE", "Resilience Scale");
                tvTypes.setText(types);

                // Show stress result badge if available for this session
                if (tvStressResult != null && session.sessionId != null) {
                    String stressLevel = itemView.getContext()
                            .getSharedPreferences("SessionStressResults",
                                    android.content.Context.MODE_PRIVATE)
                            .getString(session.sessionId, null);

                    if (stressLevel != null) {
                        tvStressResult.setText(stressLevel);
                        tvStressResult.setVisibility(android.view.View.VISIBLE);

                        // Color the badge based on stress level
                        int badgeColor;
                        if (stressLevel.contains("High")) {
                            badgeColor = android.graphics.Color.parseColor("#B71C1C");
                        } else if (stressLevel.contains("Medium")) {
                            badgeColor = android.graphics.Color.parseColor("#E65100");
                        } else {
                            badgeColor = android.graphics.Color.parseColor("#2E7D32");
                        }
                        tvStressResult.setBackgroundTintList(
                                android.content.res.ColorStateList.valueOf(badgeColor));
                    } else {
                        tvStressResult.setVisibility(android.view.View.GONE);
                    }
                }

                // Click → open detail view for this session
                itemView.setOnClickListener(v -> {
                    Intent intent = new Intent(requireActivity(), SessionDetailActivity.class);
                    intent.putExtra("session_id", session.sessionId);
                    intent.putExtra("session_date", sdf.format(new Date(session.timestamp)));
                    startActivity(intent);
                    requireActivity().overridePendingTransition(
                            R.anim.slide_in_right, R.anim.slide_out_left);
                });
            }
        }
    }
}
