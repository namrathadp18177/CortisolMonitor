package com.example.myapplication;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.data.QuestionnaireResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows every question/answer for a single assessment session.
 * Receives extras: "session_id" (String) and "session_date" (String).
 *
 * A stress-result footer card is appended at the bottom of the list
 * when a saved result is available for this session.
 */
public class SessionDetailActivity extends AppCompatActivity {

    private RecyclerView rvResponses;
    private TextView tvEmpty;
    private ExecutorService executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session_detail);

        Toolbar toolbar = findViewById(R.id.sessionDetailToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            String date = getIntent().getStringExtra("session_date");
            getSupportActionBar().setTitle(date != null ? date : "Assessment Detail");
        }

        rvResponses = findViewById(R.id.rvSessionResponses);
        tvEmpty     = findViewById(R.id.tvSessionEmpty);

        rvResponses.setLayoutManager(new LinearLayoutManager(this));

        String sessionIdForExport = getIntent().getStringExtra("session_id");
        findViewById(R.id.btnExportSession).setOnClickListener(v -> {
            if (sessionIdForExport == null || sessionIdForExport.isEmpty()) return;
            VitalsCsvExporter.exportQuestionnaireSessionAndShare(this, sessionIdForExport);
        });

        executor = Executors.newSingleThreadExecutor();
        loadResponses();
    }

    private void loadResponses() {
        String sessionId = getIntent().getStringExtra("session_id");
        if (sessionId == null || sessionId.isEmpty()) {
            showEmpty();
            return;
        }

        // Look up any saved stress result and score details for this session
        String stressResult = getSharedPreferences("SessionStressResults", Context.MODE_PRIVATE)
                .getString(sessionId, null);
        String scoreDetails = getSharedPreferences("SessionScoreDetails", Context.MODE_PRIVATE)
                .getString(sessionId, null);

        executor.execute(() -> {
            DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
            List<QuestionnaireResponse> responses =
                    dbHelper.getResponsesForSession(sessionId);

            // For old sessions without saved score details, compute from DB responses
            final String resolvedScore;
            if (scoreDetails != null) {
                resolvedScore = scoreDetails;
            } else if (responses != null && !responses.isEmpty()) {
                int screenerTotal = 0;
                int phqTotal      = 0;
                int gad7Total     = 0;
                for (com.example.myapplication.data.QuestionnaireResponse r : responses) {
                    String type = r.getQuestionnaireType();
                    if ("MENTAL_HEALTH_SCREENER".equals(type)) screenerTotal += r.getAnswer();
                    else if ("PHQ".equals(type) || "PHQ9".equals(type)) phqTotal += r.getAnswer();
                    else if ("GAD7".equals(type) || "GAD_7".equals(type)) gad7Total += r.getAnswer();
                }
                StringBuilder sb = new StringBuilder();
                if (screenerTotal > 0) sb.append("Screener: ").append(screenerTotal);
                if (phqTotal > 0)      sb.append(sb.length() > 0 ? "  •  " : "").append("PHQ-9: ").append(phqTotal);
                if (gad7Total > 0)     sb.append(sb.length() > 0 ? "  •  " : "").append("GAD-7: ").append(gad7Total);
                resolvedScore = sb.length() > 0 ? sb.toString() : null;
            } else {
                resolvedScore = null;
            }

            // For old sessions without a saved stress level, derive a rough one from screener score
            final String resolvedStress;
            if (stressResult != null) {
                resolvedStress = stressResult;
            } else if (resolvedScore != null && resolvedScore.contains("Screener: ")) {
                String derived;
                try {
                    String after = resolvedScore.substring(resolvedScore.indexOf("Screener: ") + 10);
                    int end = after.indexOf("  ");
                    int total = Integer.parseInt(end > 0 ? after.substring(0, end) : after.trim());
                    if (total <= 15)      derived = "Low Stress";
                    else if (total <= 30) derived = "Medium Stress";
                    else                  derived = "High Stress";
                } catch (Exception e) {
                    derived = null;
                }
                resolvedStress = derived;
            } else {
                resolvedStress = null;
            }

            runOnUiThread(() -> {
                if (responses == null || responses.isEmpty()) {
                    showEmpty();
                } else {
                    tvEmpty.setVisibility(View.GONE);
                    rvResponses.setVisibility(View.VISIBLE);
                    rvResponses.setAdapter(
                            new ResponsesAdapter(responses, resolvedStress, resolvedScore));
                }
            });
        });
    }

    private void showEmpty() {
        tvEmpty.setVisibility(View.VISIBLE);
        rvResponses.setVisibility(View.GONE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdown();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Adapter — response items + optional stress-result footer
    // ─────────────────────────────────────────────────────────────────────────

    private static class ResponsesAdapter
            extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_RESPONSE = 0;
        private static final int TYPE_RESULT   = 1;

        private final List<QuestionnaireResponse> list;
        /** null if no stress result is available for this session */
        private final String stressResult;
        /** null if no score details are available for this session */
        private final String scoreDetails;
        private final boolean hasFooter;

        ResponsesAdapter(List<QuestionnaireResponse> list,
                         String stressResult, String scoreDetails) {
            this.list         = list;
            this.stressResult = stressResult;
            this.scoreDetails = scoreDetails;
            this.hasFooter    = (stressResult != null || scoreDetails != null);
        }

        @Override
        public int getItemViewType(int position) {
            if (hasFooter && position == list.size()) return TYPE_RESULT;
            return TYPE_RESPONSE;
        }

        @Override
        public int getItemCount() {
            return list.size() + (hasFooter ? 1 : 0);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_RESULT) {
                View v = inflater.inflate(R.layout.item_session_stress_result, parent, false);
                return new ResultVH(v);
            }
            View v = inflater.inflate(R.layout.item_response, parent, false);
            return new ResponseVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof ResultVH) {
                ((ResultVH) holder).bind(stressResult, scoreDetails);
                return;
            }

            ResponseVH h = (ResponseVH) holder;
            QuestionnaireResponse r = list.get(position);

            h.tvSection.setText(r.getSection() != null ? r.getSection() : "—");
            h.tvQuestion.setText(r.getQuestionText() != null ? r.getQuestionText() : "—");

            String answerText;
            switch (r.getAnswer()) {
                case 0:  answerText = "Not at all";              break;
                case 1:  answerText = "Several days";            break;
                case 2:  answerText = "More than half the days"; break;
                case 3:  answerText = "Nearly every day";        break;
                default: answerText = String.valueOf(r.getAnswer()); break;
            }
            h.tvAnswer.setText("Answer: " + answerText);
            h.tvTimestamp.setVisibility(View.GONE);
        }

        // ── Response view holder ──────────────────────────────────────────────

        static class ResponseVH extends RecyclerView.ViewHolder {
            TextView tvSection, tvQuestion, tvAnswer, tvTimestamp;
            ResponseVH(@NonNull View v) {
                super(v);
                tvSection   = v.findViewById(R.id.tvSection);
                tvQuestion  = v.findViewById(R.id.tvQuestion);
                tvAnswer    = v.findViewById(R.id.tvAnswer);
                tvTimestamp = v.findViewById(R.id.tvTimestamp);
            }
        }

        // ── Stress-result footer view holder ─────────────────────────────────

        static class ResultVH extends RecyclerView.ViewHolder {
            TextView tvResultLabel;
            TextView tvResultLevel;
            TextView tvScoreDetail;
            com.google.android.material.card.MaterialCardView cardBg;

            ResultVH(@NonNull View v) {
                super(v);
                tvResultLabel = v.findViewById(R.id.tvResultLabel);
                tvResultLevel = v.findViewById(R.id.tvResultLevel);
                tvScoreDetail = v.findViewById(R.id.tvScoreDetail);
                cardBg        = v.findViewById(R.id.resultCardBg);
            }

            void bind(String level, String scoreDetails) {
                // Stress level text
                if (tvResultLevel != null) {
                    tvResultLevel.setText(level != null ? level : "Assessment Scores");
                }

                // Score detail line
                if (tvScoreDetail != null) {
                    if (scoreDetails != null && !scoreDetails.isEmpty()) {
                        tvScoreDetail.setText(scoreDetails);
                        tvScoreDetail.setVisibility(View.VISIBLE);
                    } else {
                        tvScoreDetail.setVisibility(View.GONE);
                    }
                }

                // Card background color based on stress level
                if (cardBg != null) {
                    int color;
                    if (level != null && level.contains("High")) {
                        color = Color.parseColor("#B71C1C");
                    } else if (level != null && level.contains("Medium")) {
                        color = Color.parseColor("#E65100");
                    } else if (level != null && level.contains("Low")) {
                        color = Color.parseColor("#2E7D32");
                    } else {
                        // No stress level, show neutral grey-green
                        color = Color.parseColor("#37474F");
                    }
                    cardBg.setCardBackgroundColor(color);
                }
            }
        }
    }
}
