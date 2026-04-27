package com.example.myapplication;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.card.MaterialCardView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ResultDetailActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result_detail);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Assessment Detail");
        }

        String resultId = getIntent().getStringExtra("result_id");
        if (resultId == null || resultId.isEmpty()) {
            finish();
            return;
        }

        populateDetail(resultId);
    }

    private void populateDetail(String resultId) {
        SharedPreferences prefs = getSharedPreferences("IntegratedResults", MODE_PRIVATE);

        // ── Load flags ─────────────────────────────────────────────────────
        boolean hasBiomarker  = prefs.getBoolean(resultId + "_has_biomarker",  false);
        boolean hasAssessment = prefs.getBoolean(resultId + "_has_assessment", false);
        boolean bothPresent   = hasBiomarker && hasAssessment;

        // ── Load values ────────────────────────────────────────────────────
        long    timestamp    = prefs.getLong   (resultId + "_timestamp",       0);
        String  finalResult  = prefs.getString (resultId + "_final_result",    "—");
        String  stressLevel  = prefs.getString (resultId + "_stress_level",    "—");
        int     totalWeight  = prefs.getInt    (resultId + "_total_weight",    0);

        int     phqScore     = prefs.getInt    (resultId + "_phq_score",       0);
        int     phqWeight    = prefs.getInt    (resultId + "_phq_weight",      0);
        float   resScore     = prefs.getFloat  (resultId + "_resilience_score",0f);
        int     resWeight    = prefs.getInt    (resultId + "_resilience_weight",0);

        float   cortisol     = prefs.getFloat  (resultId + "_cortisol_value",  0f);
        int     cortWeight   = prefs.getInt    (resultId + "_cortisol_weight", 0);
        float   dhea         = prefs.getFloat  (resultId + "_dhea_value",      0f);
        int     dheaWt       = prefs.getInt    (resultId + "_dhea_weight",     0);
        float   ratio        = prefs.getFloat  (resultId + "_ratio_value",     0f);
        int     ratioWt      = prefs.getInt    (resultId + "_ratio_weight",    0);

        // ── Date ───────────────────────────────────────────────────────────
        TextView tvDate = findViewById(R.id.tvDetailDate);
        if (timestamp > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("EEEE, MMM dd, yyyy  hh:mm a", Locale.getDefault());
            tvDate.setText(sdf.format(new Date(timestamp)));
        }

        // ── Overall card ───────────────────────────────────────────────────
        MaterialCardView cardOverall = findViewById(R.id.cardOverall);
        TextView tvOverallResult     = findViewById(R.id.tvOverallResult);
        TextView tvOverallStress     = findViewById(R.id.tvOverallStress);
        TextView tvOverallWeight     = findViewById(R.id.tvOverallWeight);

        if (bothPresent) {
            cardOverall.setCardBackgroundColor(resultColor(finalResult));
            tvOverallResult.setText(finalResult);
            tvOverallStress.setText("Stress level: " + stressLevel);
            tvOverallWeight.setText("Combined weight score: " + totalWeight);
        } else {
            cardOverall.setCardBackgroundColor(Color.parseColor("#F57F17"));
            tvOverallResult.setText("Partial Data");
            tvOverallStress.setText("Overall result requires both biomarker and assessment data.");
            tvOverallWeight.setText(
                    (!hasBiomarker  ? "• Biomarker test not taken\n" : "") +
                    (!hasAssessment ? "• Assessment not completed"   : ""));
        }

        // ── Biomarker card ─────────────────────────────────────────────────
        TextView tvCortisol   = findViewById(R.id.tvDetailCortisol);
        TextView tvCortisolWt = findViewById(R.id.tvDetailCortisolWeight);
        TextView tvDhea       = findViewById(R.id.tvDetailDhea);
        TextView tvDheaWt     = findViewById(R.id.tvDetailDheaWeight);
        TextView tvRatio      = findViewById(R.id.tvDetailRatio);
        TextView tvRatioWt    = findViewById(R.id.tvDetailRatioWeight);

        if (hasBiomarker) {
            tvCortisol.setText(String.format("Cortisol: %.2f ng/mL", cortisol));
            tvCortisolWt.setText("Weight: +" + cortWeight + "  •  " + cortisolLabel(cortisol));
            tvDhea.setText(String.format("DHEA: %.2f ng/mL", dhea));
            tvDheaWt.setText("Weight: +" + dheaWt + "  •  " + dheaLabel(dhea));
            tvRatio.setText(String.format("Cortisol/DHEA Ratio: %.2f", ratio));
            tvRatioWt.setText("Weight: +" + ratioWt + "  •  " + ratioLabel(ratio));
        } else {
            tvCortisol.setText("Biomarker test not taken — please complete the test.");
            tvCortisol.setTextColor(Color.parseColor("#B00020"));
            tvCortisolWt.setVisibility(View.GONE);
            tvDhea.setVisibility(View.GONE);
            tvDheaWt.setVisibility(View.GONE);
            tvRatio.setVisibility(View.GONE);
            tvRatioWt.setVisibility(View.GONE);
        }

        // ── Mental health card ─────────────────────────────────────────────
        TextView tvPhq      = findViewById(R.id.tvDetailPhq);
        TextView tvPhqClass = findViewById(R.id.tvDetailPhqClass);
        TextView tvRes      = findViewById(R.id.tvDetailResilience);
        TextView tvResClass = findViewById(R.id.tvDetailResilienceClass);

        if (hasAssessment) {
            tvPhq.setText("Score: " + phqScore + "  (weight: " + phqWeight + ")");
            tvPhqClass.setText(interpretPhq(phqScore));
            tvRes.setText(String.format("Score: %.2f  (weight: %d)", resScore, resWeight));
            tvResClass.setText(interpretResilience(resScore));
        } else {
            tvPhq.setText("Assessment not completed.");
            tvPhq.setTextColor(Color.parseColor("#B00020"));
            tvPhqClass.setVisibility(View.GONE);
            tvRes.setVisibility(View.GONE);
            tvResClass.setVisibility(View.GONE);
        }

        // ── Recommendations ────────────────────────────────────────────────
        TextView tvRecs = findViewById(R.id.tvDetailRecommendations);
        if (bothPresent) {
            tvRecs.setText(buildRecommendations(finalResult, cortisol, dhea, ratio));
        } else {
            tvRecs.setText("Complete both the biomarker test and mental health assessment to receive personalised recommendations.");
            tvRecs.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }
    }

    // ── Color helpers ──────────────────────────────────────────────────────

    private int resultColor(String result) {
        switch (result) {
            case "Healthy":  return Color.parseColor("#2E7D32");
            case "Concern":  return Color.parseColor("#F57F17");
            case "Mild":     return Color.parseColor("#E65100");
            case "Moderate": return Color.parseColor("#C62828");
            case "Severe":   return Color.parseColor("#B71C1C");
            default:         return Color.GRAY;
        }
    }

    // ── Interpretation helpers ─────────────────────────────────────────────

    private String interpretPhq(int score) {
        if (score < 5)  return "Minimal depression";
        if (score < 10) return "Mild depression";
        if (score < 15) return "Moderate depression";
        if (score < 20) return "Moderately severe depression";
        return "Severe depression";
    }

    private String interpretResilience(float score) {
        if (score < 2.0f) return "Low resilience";
        if (score < 3.0f) return "Low-to-normal resilience";
        if (score < 4.3f) return "Normal resilience";
        return "High resilience";
    }

    private String cortisolLabel(float v) {
        if (v >= 2f && v < 5f) return "Low/Healthy";
        if (v >= 5f && v < 9f) return "Moderate";
        if (v >= 9f)           return "Elevated";
        return "Below range";
    }

    private String dheaLabel(float v) {
        if (v >= 4f && v <= 10f) return "Healthy";
        if (v >= 2f && v < 4f)   return "Moderate";
        if (v < 2f)              return "Low";
        return "Above range";
    }

    private String ratioLabel(float v) {
        if (v >= 0.3f && v <= 1f) return "Healthy";
        if (v > 1f && v <= 2f)    return "Moderate";
        if (v > 2f)               return "Elevated";
        return "Below range";
    }

    // ── Recommendations ────────────────────────────────────────────────────

    private String buildRecommendations(String finalResult, float cortisol, float dhea, float ratio) {
        StringBuilder sb = new StringBuilder();

        switch (finalResult) {
            case "Healthy":
                sb.append("• Continue your current wellness practices.\n");
                sb.append("• Maintain regular physical activity.\n");
                sb.append("• Practice mindfulness to sustain mental health.\n");
                sb.append("• Schedule regular follow-up assessments.\n");
                break;
            case "Concern":
            case "Mild":
                sb.append("• Increase physical activity to at least 30 minutes daily.\n");
                sb.append("• Practice deep breathing exercises twice daily.\n");
                sb.append("• Establish a consistent sleep schedule.\n");
                sb.append("• Consider reducing caffeine and alcohol intake.\n");
                sb.append("• Speak with a healthcare professional if symptoms persist.\n");
                break;
            case "Moderate":
            case "Severe":
                sb.append("• Consult a healthcare professional as soon as possible.\n");
                sb.append("• Implement structured stress-reduction techniques (meditation, yoga).\n");
                sb.append("• Prioritize 7–8 hours of quality sleep nightly.\n");
                sb.append("• Reduce workload and delegate where possible.\n");
                sb.append("• Consider therapy or professional counselling support.\n");
                break;
            default:
                sb.append("• Consult a healthcare professional for personalised advice.\n");
        }

        sb.append("\nBiomarker Insights:\n");
        if (cortisol > 9f)       sb.append("• Elevated cortisol — consider stress-reduction activities.\n");
        else if (cortisol < 2f)  sb.append("• Low cortisol — focus on energy-building and regular sleep.\n");
        else                     sb.append("• Cortisol is within a typical range.\n");

        if (dhea < 2f)  sb.append("• Low DHEA — discuss with a healthcare provider.\n");
        if (ratio > 2f) sb.append("• Elevated cortisol/DHEA ratio — prioritise stress management.\n");

        sb.append("\nGeneral Wellness Tips:\n");
        sb.append("• Stay well hydrated throughout the day.\n");
        sb.append("• Maintain a balanced diet rich in vegetables and whole foods.\n");
        sb.append("• Track your progress with regular follow-up assessments.\n");

        return sb.toString();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
