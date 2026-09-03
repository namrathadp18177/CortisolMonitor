package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.card.MaterialCardView;
import java.util.Locale;

public class QuestionnaireResultsActivity extends AppCompatActivity {

    private static final String TAG = "QuestionnaireResults";
    private LinearLayout resultsContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_questionnaire_results);

        resultsContainer = findViewById(R.id.resultsContainer);

        // Set up the back button in the toolbar
        findViewById(R.id.btnBack).setOnClickListener(v -> onBackPressed());

        // Set up the Biomarker Activity button with clear label
        Button btnBiomarker = findViewById(R.id.btnBiomarker);
        btnBiomarker.setText("Continue to Biomarker Activity");
        btnBiomarker.setOnClickListener(v -> navigateToBiomarkerActivity());

        // Make the button more prominent if possible
        btnBiomarker.setBackgroundColor(getResources().getColor(R.color.colorPrimary));
        btnBiomarker.setTextColor(getResources().getColor(android.R.color.white));
        btnBiomarker.setPadding(20, 15, 20, 15);

        // Display notification about remaining questionnaires
        displayRemainingQuestionnairesNotification();

        // Display results
        displayResults();

        // Log information about triggered questionnaires
        logQuestionnaireSummary();

        // Show a toast instructing the user
        Toast.makeText(this, "Please review your results and click 'Continue' when ready",
                Toast.LENGTH_LONG).show();
    }

    private void displayRemainingQuestionnairesNotification() {
        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        QuestionnaireSummary summary = dbHelper.getQuestionnaireSummary(DatabaseHelper.getCurrentUserEmail());

        if (summary == null) return;

        // Count remaining questionnaires
        int remainingCount = 0;
        StringBuilder remainingNames = new StringBuilder();

        // Check PHQ-9 - Fixed the comparison to use int value comparison
        if (summary.getPhq9Triggered() > 0 && !summary.isPhq9Filled()) {
            remainingCount++;
            if (remainingNames.length() > 0) remainingNames.append(", ");
            remainingNames.append("Depression Screening (PHQ-9)");
        }

        // Check BDI - Fixed the comparison to use int value comparison
        if (summary.getBdiTriggered() > 0 && !summary.isBdiFilled()) {
            remainingCount++;
            if (remainingNames.length() > 0) remainingNames.append(", ");
            remainingNames.append("Beck Depression Inventory (BDI)");
        }

        // Check GAD-7 - Fixed the comparison to use int value comparison
        if (summary.getGad7Triggered() > 0 && !summary.isGad7Filled()) {
            remainingCount++;
            if (remainingNames.length() > 0) remainingNames.append(", ");
            remainingNames.append("Generalized Anxiety Disorder (GAD-7)");
        }

        // Check BRS - Fixed the comparison to use int value comparison
        if (summary.getBrsTriggered() > 0 && !summary.isBrsFilled()) {
            remainingCount++;
            if (remainingNames.length() > 0) remainingNames.append(", ");
            remainingNames.append("Brief Resilience Scale (BRS)");
        }

        // Display notification if there are remaining questionnaires
        if (remainingCount > 0) {
            // Create a container for the notification with a different background
            LinearLayout notificationContainer = new LinearLayout(this);
            notificationContainer.setOrientation(LinearLayout.VERTICAL);
            notificationContainer.setBackgroundColor(getResources().getColor(android.R.color.holo_red_light));
            notificationContainer.setPadding(16, 16, 16, 16);

            // Add notification title
            TextView titleView = new TextView(this);
            titleView.setText("ATTENTION REQUIRED");
            titleView.setTextSize(18);
            titleView.setTypeface(null, android.graphics.Typeface.BOLD);
            titleView.setTextColor(getResources().getColor(android.R.color.white));
            notificationContainer.addView(titleView);

            // Add notification message
            TextView messageView = new TextView(this);
            String message;
            if (remainingCount == 1) {
                message = "You have 1 questionnaire left to complete: " + remainingNames.toString();
            } else {
                message = "You have " + remainingCount + " questionnaires left to complete: " + remainingNames.toString();
            }
            messageView.setText(message);
            messageView.setTextColor(getResources().getColor(android.R.color.white));
            messageView.setPadding(0, 8, 0, 0);
            notificationContainer.addView(messageView);

            // Add a button to go back to questionnaires
            Button btnComplete = new Button(this);
            btnComplete.setText("Complete Remaining Questionnaires");
            btnComplete.setOnClickListener(v -> {
                Intent intent = new Intent(QuestionnaireResultsActivity.this, QuestionnaireActivity.class);
                startActivity(intent);
            });
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            buttonParams.setMargins(0, 16, 0, 0);
            btnComplete.setLayoutParams(buttonParams);
            notificationContainer.addView(btnComplete);

            // Add this container at the top of the results container
            resultsContainer.addView(notificationContainer, 0);

            // Also show a Toast notification
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } else {
            // All questionnaires completed - show a success message
            LinearLayout completionContainer = new LinearLayout(this);
            completionContainer.setOrientation(LinearLayout.VERTICAL);
            completionContainer.setBackgroundColor(getResources().getColor(android.R.color.holo_green_light));
            completionContainer.setPadding(16, 16, 16, 16);

            TextView completionMessage = new TextView(this);
            completionMessage.setText("All questionnaires completed. Thank you!");
            completionMessage.setTextColor(getResources().getColor(android.R.color.white));
            completionMessage.setTypeface(null, android.graphics.Typeface.BOLD);
            completionContainer.addView(completionMessage);

            resultsContainer.addView(completionContainer, 0);
        }
    }

    private void logQuestionnaireSummary() {
        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        QuestionnaireSummary summary = dbHelper.getQuestionnaireSummary(DatabaseHelper.getCurrentUserEmail());

        if (summary != null) {
            Log.d(TAG, "Mental Health Screener Total Score: " + summary.getTotalScore());
            Log.d(TAG, "Triggered questionnaires: " +
                    "PHQ=" + summary.getPhq9Triggered() + ", " +
                    "BDI=" + summary.getBdiTriggered() + ", " +
                    "GAD=" + summary.getGad7Triggered() + ", " +
                    "BRS=" + summary.getBrsTriggered());
            Log.d(TAG, "Completed questionnaires: " +
                    "PHQ=" + summary.isPhq9Filled() + ", " +
                    "BDI=" + summary.isBdiFilled() + ", " +
                    "GAD=" + summary.isGad7Filled() + ", " +
                    "BRS=" + summary.isBrsFilled());
        } else {
            Log.d(TAG, "No questionnaire summary found");
        }
    }

    private void displayResults() {
        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        String email = DatabaseHelper.getCurrentUserEmail();
        QuestionnaireSummary summary = dbHelper.getQuestionnaireSummary(email);

        if (summary == null) return;

        // Save latest assessment scores to SharedPreferences so IntegratedResultsActivity
        // can load them even when not passed directly via Intent.
        if (email != null && !email.isEmpty()) {
            long now = System.currentTimeMillis();

            android.content.SharedPreferences.Editor editor =
                    getSharedPreferences("AssessmentResults", MODE_PRIVATE).edit();
            editor.putInt("phq_" + email + "_latest", summary.getPhq9Score());
            editor.putFloat("resilience_" + email + "_latest", (float) summary.getBrsScore());
            editor.putInt("screener_total_" + email + "_latest", summary.getTotalScore());
            editor.putLong("assessment_date_" + email + "_latest", now);
            editor.apply();

            // Also write a dated entry directly into IntegratedResults so Combined Results
            // updates immediately after every assessment (biomarker data added later when taken).
            saveAssessmentToIntegratedResults(email, summary, now);
        }

        // ── Stress level summary card (shown before the detailed scores) ────────
        addStressLevelCard(summary);

        // Display Mental Health Screener results
        addSectionTitle(getString(R.string.mental_health_screener_results));

        // Display section scores
        addScoreRow(getString(R.string.mood_and_energy), summary.getScoreA());
        addScoreRow(getString(R.string.anxiety_and_stress), summary.getScoreB());
        addScoreRow(getString(R.string.sleep_and_appetite), summary.getScoreC());
        addScoreRow(getString(R.string.cognition_and_thinking), summary.getScoreD());
        addScoreRow(getString(R.string.repetitive_thoughts), summary.getScoreE());
        addScoreRow(getString(R.string.physical_symptoms), summary.getScoreF());
        addScoreRow(getString(R.string.total_score), summary.getTotalScore());

        addDivider();

        // Display results of completed questionnaires
        if (summary.isPhq9Filled()) {
            addSectionTitle(getString(R.string.depression_screening_phq9));
            addScoreRow(getString(R.string.score), summary.getPhq9Score());
            addDivider();
        }

        if (summary.isBdiFilled()) {
            addSectionTitle(getString(R.string.beck_depression_inventory));
            addScoreRow(getString(R.string.score), summary.getBdiScore());
            addDivider();
        }

        if (summary.isGad7Filled()) {
            addSectionTitle(getString(R.string.generalized_anxiety_disorder));
            addScoreRow(getString(R.string.score), summary.getGad7Score());
            addDivider();
        }

        if (summary.isBrsFilled()) {
            addSectionTitle(getString(R.string.brief_resilience_scale));
            addScoreRow(getString(R.string.score), String.format("%.2f", summary.getBrsScore()));
            addDivider();
        }

        // Add note about next steps
        addSectionTitle(getString(R.string.next_steps));
        TextView nextStepsText = new TextView(this);
        nextStepsText.setText(getString(R.string.next_steps_description));
        nextStepsText.setPadding(16, 8, 16, 16);
        resultsContainer.addView(nextStepsText);

        // Add a final instruction to direct users to the button
        TextView finalInstruction = new TextView(this);
        finalInstruction.setText("When you're ready, please click the 'Continue to Biomarker Activity' button below.");
        finalInstruction.setTextSize(16);
        finalInstruction.setTypeface(null, android.graphics.Typeface.BOLD);
        finalInstruction.setPadding(16, 24, 16, 24);
        resultsContainer.addView(finalInstruction);
    }

    /**
     * Builds and inserts a MaterialCardView at the very top of resultsContainer (index 0)
     * showing the overall stress classification derived from the PHQ-9, BRS, and screener scores.
     * Uses the same weight thresholds as saveAssessmentToIntegratedResults().
     */
    private void addStressLevelCard(QuestionnaireSummary summary) {
        // Helper to convert dp → pixels
        float density = getResources().getDisplayMetrics().density;

        int   phqScore  = summary.isPhq9Filled() ? summary.getPhq9Score() : 0;
        int   bdiScore  = summary.isBdiFilled()  ? summary.getBdiScore()  : 0;
        int   gad7Score = summary.isGad7Filled() ? summary.getGad7Score() : 0;
        // BRS: if not taken, use training-set mean (3.27) so the model gets a neutral signal
        float brsScore  = summary.isBrsFilled()
                ? (float) summary.getBrsScore()
                : 3.2715f;

        // ── User demographics from profile ─────────────────────────────────────
        String email = DatabaseHelper.getCurrentUserEmail();
        UserDatabaseHelper udb = UserDatabaseHelper.getInstance(this);
        int ageYears = udb.getUserAge(email);
        boolean isMale = false;
        try {
            UserData ud = udb.getUserData(email);
            if (ud != null && ud.getSex() != null) {
                String s = ud.getSex().trim().toLowerCase();
                isMale = s.startsWith("m");   // "male", "m", "man" → 1
            }
        } catch (Exception ignored) {}

        // ── Run ONNX model ────────────────────────────────────────────────────
        StressPredictor.Result prediction = StressPredictor.predictAssessmentOnly(
                this, phqScore, bdiScore, gad7Score, brsScore, ageYears, isMale);

        // ── Map predicted class → display values ──────────────────────────────
        String level, description;
        int    cardColor;
        if (prediction == null) {
            // Absolute fallback if model fails entirely
            level       = "Unable to assess";
            description = "Please complete more questionnaires for a full assessment.";
            cardColor   = android.graphics.Color.parseColor("#757575");
        } else {
            switch (prediction.predictedClass) {
                case StressPredictor.CLASS_HEALTHY:
                    level       = "Low Stress";
                    description = "Your scores suggest low stress levels. Keep up your current wellness habits.";
                    cardColor   = android.graphics.Color.parseColor("#2E7D32");
                    break;
                case StressPredictor.CLASS_MODERATE:
                    level       = "Medium Stress";
                    description = "Medium stress detected. Regular self-care and mindfulness can help.";
                    cardColor   = android.graphics.Color.parseColor("#E65100");
                    break;
                case StressPredictor.CLASS_HIGH:
                    level       = "High Stress";
                    description = "Significant stress indicators. Speaking with a healthcare professional is recommended.";
                    cardColor   = android.graphics.Color.parseColor("#B71C1C");
                    break;
                default:
                    level       = "Low Stress";
                    description = "Your scores suggest low stress levels. Keep up your current wellness habits.";
                    cardColor   = android.graphics.Color.parseColor("#2E7D32");
            }
        }

        Log.d(TAG, "addStressLevelCard: level=" + level
                + (prediction != null ? ", confidence=" + prediction.getConfidencePct() + "%" : ", model=null"));

        // Persist stress level keyed by sessionId so history screens can display it
        String sessionIdForSave = getIntent().getStringExtra("session_id");
        if (sessionIdForSave != null && !sessionIdForSave.isEmpty()) {
            getSharedPreferences("SessionStressResults", MODE_PRIVATE)
                    .edit()
                    .putString(sessionIdForSave, level)
                    .apply();

            // Also persist the score breakdown so SessionDetailActivity can show it
            StringBuilder scoreDetail = new StringBuilder("Screener: ").append(summary.getTotalScore());
            if (summary.isPhq9Filled())  scoreDetail.append("  •  PHQ-9: ").append(phqScore);
            if (summary.isGad7Filled())  scoreDetail.append("  •  GAD-7: ").append(gad7Score);
            if (summary.isBrsFilled())   scoreDetail.append("  •  BRS: ").append(
                    String.format(Locale.getDefault(), "%.1f", brsScore));
            getSharedPreferences("SessionScoreDetails", MODE_PRIVATE)
                    .edit()
                    .putString(sessionIdForSave, scoreDetail.toString())
                    .apply();
        }

        // ── Outer MaterialCardView ────────────────────────────────────────────
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        int marginPx = (int)(12 * density);
        cardParams.setMargins(0, 0, 0, marginPx);
        card.setLayoutParams(cardParams);
        card.setRadius(12 * density);
        card.setCardElevation(4 * density);
        card.setCardBackgroundColor(cardColor);
        card.setUseCompatPadding(true);

        // ── Inner LinearLayout (content) ─────────────────────────────────────
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(20 * density);
        inner.setPadding(pad, pad, pad, pad);

        // "YOUR STRESS LEVEL" label
        TextView tvLabel = new TextView(this);
        tvLabel.setText("YOUR STRESS LEVEL");
        tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvLabel.setTextColor(android.graphics.Color.argb(200, 255, 255, 255));
        tvLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        tvLabel.setLetterSpacing(0.12f);
        inner.addView(tvLabel);

        // Big level name + confidence
        TextView tvLevel = new TextView(this);
        tvLevel.setText(level);
        tvLevel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        tvLevel.setTypeface(null, android.graphics.Typeface.BOLD);
        tvLevel.setTextColor(android.graphics.Color.WHITE);
        LinearLayout.LayoutParams levelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        levelParams.setMargins(0, (int)(4*density), 0, (int)(8*density));
        tvLevel.setLayoutParams(levelParams);
        inner.addView(tvLevel);

        // Score detail line
        TextView tvScoreDetail = new TextView(this);
        StringBuilder scoreBuilder = new StringBuilder();
        if (summary.isPhq9Filled()) {
            scoreBuilder.append("PHQ-9: ").append(phqScore).append("  •  ");
        }
        if (summary.isGad7Filled()) {
            scoreBuilder.append("GAD-7: ").append(gad7Score).append("  •  ");
        }
        if (summary.isBrsFilled()) {
            scoreBuilder.append(String.format("BRS: %.1f  •  ", brsScore));
        }
        scoreBuilder.append("Screener: ").append(summary.getTotalScore());
        tvScoreDetail.setText(scoreBuilder.toString());
        tvScoreDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tvScoreDetail.setTextColor(android.graphics.Color.argb(210, 255, 255, 255));
        LinearLayout.LayoutParams scoreDetailParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        scoreDetailParams.setMargins(0, 0, 0, (int)(10*density));
        tvScoreDetail.setLayoutParams(scoreDetailParams);
        inner.addView(tvScoreDetail);

        // Description
        TextView tvDesc = new TextView(this);
        tvDesc.setText(description);
        tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tvDesc.setTextColor(android.graphics.Color.argb(230, 255, 255, 255));
        inner.addView(tvDesc);

        card.addView(inner);

        // Insert at position 0 so it is always the very first item visible
        resultsContainer.addView(card, 0);
    }

    private void addSectionTitle(String title) {
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(18);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setPadding(16, 24, 16, 8);
        resultsContainer.addView(titleView);
    }

    private void addScoreRow(String label, int score) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(16, 8, 16, 8);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f));

        TextView scoreView = new TextView(this);
        scoreView.setText(String.valueOf(score));
        scoreView.setTypeface(null, android.graphics.Typeface.BOLD);
        scoreView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.3f));

        row.addView(labelView);
        row.addView(scoreView);
        resultsContainer.addView(row);
    }

    private void addScoreRow(String label, String score) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(16, 8, 16, 8);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f));

        TextView scoreView = new TextView(this);
        scoreView.setText(score);
        scoreView.setTypeface(null, android.graphics.Typeface.BOLD);
        scoreView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.3f));

        row.addView(labelView);
        row.addView(scoreView);
        resultsContainer.addView(row);
    }

    private void addDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(getResources().getColor(R.color.divider_color));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2 // height in pixels
        );
        params.setMargins(16, 16, 16, 16); // margins
        divider.setLayoutParams(params);
        resultsContainer.addView(divider);
    }

    /**
     * Saves assessment-only entry to IntegratedResults SharedPreferences.
     * has_biomarker = false so Combined Results shows "Biomarker test not taken".
     * When the user later completes biomarker + IntegratedResultsActivity, a new
     * full entry is added for that date.
     */
    private void saveAssessmentToIntegratedResults(String email,
                                                    QuestionnaireSummary summary,
                                                    long timestamp) {
        int   phqScore  = summary.isPhq9Filled() ? summary.getPhq9Score() : -1;
        float resScore  = summary.isBrsFilled()  ? (float) summary.getBrsScore() : -1f;
        int   screener  = summary.getTotalScore();

        // Derive weights using same logic as addStressLevelCard (screener fallback when not filled)
        int phqWeight;
        if (phqScore >= 0) {
            phqWeight = phqScore <= 4 ? 1 : phqScore <= 9 ? 2 : phqScore <= 14 ? 3
                      : phqScore <= 19 ? 4 : 5;
        } else {
            phqWeight = screener <= 10 ? 1 : screener <= 20 ? 2 : screener <= 32 ? 3
                      : screener <= 44 ? 4 : 5;
        }
        int resWeight;
        if (resScore >= 0f) {
            resWeight = resScore < 3.0f ? 3 : resScore <= 4.3f ? 2 : 1;
        } else {
            resWeight = screener <= 15 ? 1 : screener <= 35 ? 2 : 3;
        }

        // Use 0 for the actual score fields when not filled (so history shows N/A)
        int   savedPhqScore  = phqScore  >= 0 ? phqScore  : 0;
        float savedResScore  = resScore  >= 0f ? resScore  : 0f;

        int totalWeight = phqWeight + resWeight;  // biomarker weights are 0 — not taken yet

        String stressLevel = "Assessment only";
        String finalResult;
        if      (totalWeight <= 2) finalResult = "Healthy";
        else if (totalWeight <= 4) finalResult = "Concern";
        else if (totalWeight <= 6) finalResult = "Mild";
        else if (totalWeight <= 8) finalResult = "Moderate";
        else                       finalResult = "Severe";

        String resultId = email + "_" + timestamp;

        android.content.SharedPreferences prefs =
                getSharedPreferences("IntegratedResults", MODE_PRIVATE);
        android.content.SharedPreferences.Editor ed = prefs.edit();

        ed.putBoolean(resultId + "_has_assessment", true);
        ed.putBoolean(resultId + "_has_biomarker",  false);
        ed.putInt    (resultId + "_phq_score",      savedPhqScore);
        ed.putInt    (resultId + "_phq_weight",     phqWeight);
        ed.putFloat  (resultId + "_resilience_score",  savedResScore);
        ed.putInt    (resultId + "_resilience_weight", resWeight);
        ed.putFloat  (resultId + "_cortisol_value", 0f);
        ed.putInt    (resultId + "_cortisol_weight", 0);
        ed.putFloat  (resultId + "_dhea_value",     0f);
        ed.putInt    (resultId + "_dhea_weight",    0);
        ed.putFloat  (resultId + "_ratio_value",    0f);
        ed.putInt    (resultId + "_ratio_weight",   0);
        ed.putInt    (resultId + "_total_weight",   totalWeight);
        ed.putString (resultId + "_stress_level",   stressLevel);
        ed.putString (resultId + "_final_result",   finalResult);
        ed.putLong   (resultId + "_timestamp",      timestamp);

        // Append to history list
        String history = prefs.getString(email + "_history", "");
        history = history.isEmpty() ? resultId : history + "," + resultId;
        ed.putString(email + "_history", history);
        ed.apply();
    }

    private void navigateToBiomarkerActivity() {
        // Fixed the class name typo
        Intent intent = new Intent(QuestionnaireResultsActivity.this, BiomarkerInstructions.class);
        startActivity(intent);
        finish(); // Close the results activity
    }

    @Override
    public void onBackPressed() {
        // Override to prevent going back to questionnaires
        // Instead, take the user to the main screen
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }
}