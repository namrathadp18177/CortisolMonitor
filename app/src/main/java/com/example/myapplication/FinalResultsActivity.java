package com.example.myapplication;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FinalResultsActivity extends AppCompatActivity {

    private LinearLayout historyContainer;
    private TextView tvEmptyState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_final_results);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Combined Results");
        }

        historyContainer = findViewById(R.id.historyContainer);
        tvEmptyState     = findViewById(R.id.tvEmptyState);

        findViewById(R.id.btnExportAllData).setOnClickListener(v ->
                VitalsCsvExporter.exportAndShare(this));

        loadHistory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        historyContainer.removeAllViews();
        historyContainer.addView(tvEmptyState);
        tvEmptyState.setVisibility(View.GONE);
        loadHistory();
    }

    private void loadHistory() {
        String username = DatabaseHelper.getCurrentUserEmail();
        if (username == null) username = "";

        SharedPreferences prefs = getSharedPreferences("IntegratedResults", MODE_PRIVATE);
        String historyRaw = prefs.getString(username + "_history", "");

        if (historyRaw.isEmpty()) {
            tvEmptyState.setVisibility(View.VISIBLE);
            return;
        }

        // Collect all valid result IDs
        List<String> ids = new ArrayList<>();
        for (String p : historyRaw.split(",")) {
            String id = p.trim();
            if (!id.isEmpty()) ids.add(id);
        }

        if (ids.isEmpty()) {
            tvEmptyState.setVisibility(View.VISIBLE);
            return;
        }

        // Sort all IDs newest first by timestamp
        SimpleDateFormat dayFmt  = new SimpleDateFormat("yyyy-MM-dd",         Locale.getDefault());
        SimpleDateFormat headFmt = new SimpleDateFormat("EEEE, MMM dd, yyyy", Locale.getDefault());
        SimpleDateFormat timeFmt = new SimpleDateFormat("hh:mm a",            Locale.getDefault());

        Collections.sort(ids, (a, b) -> {
            long tsA = prefs.getLong(a + "_timestamp", 0);
            long tsB = prefs.getLong(b + "_timestamp", 0);
            return Long.compare(tsB, tsA);  // newest first
        });

        // Group by calendar date — ALL entries under each date
        LinkedHashMap<String, List<String>> byDay = new LinkedHashMap<>();
        for (String id : ids) {
            long ts = prefs.getLong(id + "_timestamp", 0);
            String day = ts > 0 ? dayFmt.format(new Date(ts)) : "unknown";
            if (!byDay.containsKey(day)) byDay.put(day, new ArrayList<>());
            byDay.get(day).add(id);
        }

        for (Map.Entry<String, List<String>> entry : byDay.entrySet()) {
            String dayKey = entry.getKey();
            List<String> dayIds = entry.getValue();   // already sorted newest first

            // Date header
            long firstTs = prefs.getLong(dayIds.get(0) + "_timestamp", 0);
            String headDate = firstTs > 0 ? headFmt.format(new Date(firstTs)) : dayKey;
            addDateHeader(headDate, dayIds.size());

            // One row per entry under this date
            for (String resultId : dayIds) {
                long    ts            = prefs.getLong   (resultId + "_timestamp",    0);
                String  finalResult   = prefs.getString (resultId + "_final_result", "—");
                int     totalWeight   = prefs.getInt    (resultId + "_total_weight", 0);
                String  stressLevel   = prefs.getString (resultId + "_stress_level", "—");
                boolean hasBiomarker  = prefs.getBoolean(resultId + "_has_biomarker",  false);
                boolean hasAssessment = prefs.getBoolean(resultId + "_has_assessment", false);
                String  timeStr       = ts > 0 ? timeFmt.format(new Date(ts)) : "—";

                addEntryRow(resultId, timeStr, finalResult, totalWeight,
                            stressLevel, hasBiomarker, hasAssessment);
            }
        }
    }

    /**
     * Reads whatever data already exists in AssessmentResults + BiomarkerResults prefs
     * and writes one seed entry into IntegratedResults so the history list is not empty
     * on first open (before the user runs IntegratedResultsActivity again).
     * Returns true if a seed entry was created.
     */
    private boolean seedFromExistingData(String username) {
        SharedPreferences assessmentPrefs = getSharedPreferences("AssessmentResults", MODE_PRIVATE);
        SharedPreferences biomarkerPrefs  = getSharedPreferences("BiomarkerResults",  MODE_PRIVATE);

        long   assessmentTs  = assessmentPrefs.getLong  ("assessment_date_" + username + "_latest", 0);
        int    phqScore      = assessmentPrefs.getInt   ("phq_"             + username + "_latest", 0);
        float  resScore      = assessmentPrefs.getFloat ("resilience_"      + username + "_latest", 0f);
        boolean hasAssessment = assessmentTs > 0 || phqScore > 0 || resScore > 0;

        String bioHistory    = biomarkerPrefs.getString(username + "_history", "");
        boolean hasBiomarker = !bioHistory.isEmpty();

        if (!hasAssessment && !hasBiomarker) return false;   // nothing to seed

        float cortisol = 0f, dhea = 0f, ratio = 0f;
        if (hasBiomarker) {
            String[] ids = bioHistory.split(",");
            String latestId = ids[ids.length - 1].trim();
            cortisol = biomarkerPrefs.getFloat(latestId + "_cortisol",          0f);
            dhea     = biomarkerPrefs.getFloat(latestId + "_dhea",              0f);
            ratio    = biomarkerPrefs.getFloat(latestId + "_cortisolDheaRatio", 0f);
            if (ratio <= 0 && cortisol > 0 && dhea > 0) ratio = cortisol / dhea;
        }

        // Calculate weights
        int phqWeight  = calcPhqWeight(phqScore);
        int resWeight  = calcResWeight(resScore);
        int cortWeight = calcCortisolWeight(cortisol);
        int dheaWeight = calcDheaWeight(dhea);
        int ratioWeight= calcRatioWeight(ratio);
        int totalWeight = phqWeight + resWeight + cortWeight + dheaWeight + ratioWeight;

        int bioSum = cortWeight + dheaWeight + ratioWeight;
        String stressLevel = bioSum <= 3 ? "Low/Healthy" : bioSum <= 6 ? "Moderate Stress" : "High Stress";
        String finalResult;
        if      (totalWeight <= 5)  finalResult = "Healthy";
        else if (totalWeight <= 8)  finalResult = "Concern";
        else if (totalWeight <= 11) finalResult = "Mild";
        else if (totalWeight <= 14) finalResult = "Moderate";
        else                        finalResult = "Severe";

        // Use assessment timestamp if available, otherwise now
        long timestamp = assessmentTs > 0 ? assessmentTs : System.currentTimeMillis();
        String resultId = username + "_" + timestamp;

        SharedPreferences intPrefs = getSharedPreferences("IntegratedResults", MODE_PRIVATE);
        SharedPreferences.Editor ed = intPrefs.edit();
        ed.putBoolean(resultId + "_has_assessment", hasAssessment);
        ed.putBoolean(resultId + "_has_biomarker",  hasBiomarker);
        ed.putInt    (resultId + "_phq_score",      phqScore);
        ed.putInt    (resultId + "_phq_weight",     phqWeight);
        ed.putFloat  (resultId + "_resilience_score",  resScore);
        ed.putInt    (resultId + "_resilience_weight", resWeight);
        ed.putFloat  (resultId + "_cortisol_value", cortisol);
        ed.putInt    (resultId + "_cortisol_weight",cortWeight);
        ed.putFloat  (resultId + "_dhea_value",     dhea);
        ed.putInt    (resultId + "_dhea_weight",    dheaWeight);
        ed.putFloat  (resultId + "_ratio_value",    ratio);
        ed.putInt    (resultId + "_ratio_weight",   ratioWeight);
        ed.putInt    (resultId + "_total_weight",   totalWeight);
        ed.putString (resultId + "_stress_level",   stressLevel);
        ed.putString (resultId + "_final_result",   finalResult);
        ed.putLong   (resultId + "_timestamp",      timestamp);
        ed.putString (username  + "_history",       resultId);
        ed.apply();
        return true;
    }

    private int calcPhqWeight(int s) {
        if (s <= 4) return 1; if (s <= 9) return 2; if (s <= 14) return 3;
        if (s <= 19) return 4; return 5;
    }
    private int calcResWeight(float s) {
        if (s < 3.0f) return 3; if (s <= 4.3f) return 2; return 1;
    }
    private int calcCortisolWeight(float v) {
        if (v >= 2f && v < 5f) return 1; if (v >= 5f && v < 9f) return 2;
        if (v >= 9f) return 3; return 0;
    }
    private int calcDheaWeight(float v) {
        if (v >= 4f && v <= 10f) return 1; if (v >= 2f) return 2;
        if (v > 0) return 3; return 0;
    }
    private int calcRatioWeight(float v) {
        if (v >= 0.3f && v <= 1f) return 1; if (v <= 2f && v > 1f) return 2;
        if (v > 2f) return 3; return 0;
    }

    /** Section header showing the calendar date and how many entries are below it. */
    private void addDateHeader(String headDate, int count) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dp(4), dp(14), dp(4), dp(6));
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvHead = new TextView(this);
        tvHead.setText(headDate);
        tvHead.setTextSize(13);
        tvHead.setTypeface(null, Typeface.BOLD);
        tvHead.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvHead.setLayoutParams(lp);

        TextView tvCount = new TextView(this);
        tvCount.setText(count + (count == 1 ? " entry" : " entries"));
        tvCount.setTextSize(12);
        tvCount.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));

        header.addView(tvHead);
        header.addView(tvCount);
        historyContainer.addView(header);
    }

    /** Single row card for one assessment session under a date header. */
    private void addEntryRow(String resultId, String timeStr, String finalResult,
                             int totalWeight, String stressLevel,
                             boolean hasBiomarker, boolean hasAssessment) {

        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(cardParams);
        card.setRadius(dp(10));
        card.setCardElevation(0);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(ContextCompat.getColor(this, R.color.border));
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface));
        card.setClickable(true);
        card.setFocusable(true);
        int[] attrs = {android.R.attr.selectableItemBackground};
        android.content.res.TypedArray ta = obtainStyledAttributes(attrs);
        card.setForeground(ta.getDrawable(0));
        ta.recycle();

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setPadding(dp(16), dp(14), dp(16), dp(14));
        inner.setGravity(Gravity.CENTER_VERTICAL);

        // Colored left strip
        View strip = new View(this);
        LinearLayout.LayoutParams stripParams = new LinearLayout.LayoutParams(dp(4), dp(56));
        stripParams.setMargins(0, 0, dp(14), 0);
        strip.setLayoutParams(stripParams);
        strip.setBackgroundColor(statusColor(hasBiomarker, hasAssessment, finalResult));

        // Text block
        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        textBlock.setLayoutParams(
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Time of this entry
        TextView tvTime = new TextView(this);
        tvTime.setText(timeStr);
        tvTime.setTextSize(12);
        tvTime.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));

        // Overall result label
        TextView tvResult = new TextView(this);
        boolean bothPresent = hasBiomarker && hasAssessment;
        tvResult.setText(bothPresent ? finalResult : "Partial data");
        tvResult.setTextSize(15);
        tvResult.setTypeface(null, Typeface.BOLD);
        tvResult.setTextColor(bothPresent
                ? resultColor(finalResult)
                : Color.parseColor("#F57F17"));

        // Status chips row
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(4), 0, 0);
        chips.addView(chip(hasBiomarker  ? "Biomarker ✓"   : "No biomarker",  hasBiomarker));
        chips.addView(chip(hasAssessment ? "Assessment ✓"  : "No assessment", hasAssessment));

        textBlock.addView(tvTime);
        textBlock.addView(tvResult);
        textBlock.addView(chips);

        // Chevron
        TextView tvChevron = new TextView(this);
        tvChevron.setText("›");
        tvChevron.setTextSize(24);
        tvChevron.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));

        inner.addView(strip);
        inner.addView(textBlock);
        inner.addView(tvChevron);
        card.addView(inner);
        historyContainer.addView(card);

        card.setOnClickListener(v -> {
            Intent intent = new Intent(this, ResultDetailActivity.class);
            intent.putExtra("result_id", resultId);
            startActivity(intent);
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        });
    }

    private TextView chip(String label, boolean positive) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(11);
        tv.setTextColor(positive ? Color.parseColor("#2E7D32") : Color.parseColor("#B00020"));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, dp(10), 0);
        tv.setLayoutParams(p);
        return tv;
    }

    private int statusColor(boolean hasBiomarker, boolean hasAssessment, String finalResult) {
        if (!hasBiomarker || !hasAssessment) return Color.parseColor("#F57F17"); // amber = partial
        return resultColor(finalResult);
    }

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

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
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
