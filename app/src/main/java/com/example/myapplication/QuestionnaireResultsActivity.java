package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

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
        QuestionnaireSummary summary = dbHelper.getQuestionnaireSummary(DatabaseHelper.getCurrentUserEmail());

        if (summary == null) return;

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