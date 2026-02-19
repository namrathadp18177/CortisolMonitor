package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class QuestionnaireMain extends AppCompatActivity {

    // Define questionnaire types
    private static final String MENTAL_HEALTH_SCREENER = "MENTAL_HEALTH_SCREENER";
    private static final String PHQ_9 = "PHQ";
    private static final String BDI = "BDI";
    private static final String GAD_7 = "GAD";
    private static final String RESILIENCE = "RESILIENCE";

    private LinearLayout cardsContainer;
    private DatabaseHelper dbHelper;
    private MaterialCardView instructionsCard;
    private TextView tvInstructions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_questionnaire_main);

        // Set up toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
            getSupportActionBar().setElevation(8f);
            getSupportActionBar().setTitle("");
        }

        // Initialize database helper using the singleton pattern
        dbHelper = DatabaseHelper.getInstance(this);


        // Initialize UI components
        cardsContainer = findViewById(R.id.cardsContainer);
        instructionsCard = findViewById(R.id.instructionsCard);
        tvInstructions = findViewById(R.id.tvInstructions);

        // Remove the btnShowMore reference and functionality
        View btnShowMore = findViewById(R.id.btnShowMore);
        if (btnShowMore != null) {
            btnShowMore.setVisibility(View.GONE);
        }

        // Initialize cards
        setupCards();
        
        // Check if we're returning from a completed screening
        boolean screeningCompleted = getIntent().getBooleanExtra("SCREENING_COMPLETED", false);
        if (screeningCompleted) {
            showCompletionInstructions();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        
        try {
            // Debug: Log current user information
            Log.d("QuestionnaireMain", "=== DEBUG INFO ===");
            Log.d("QuestionnaireMain", "Current user email: " + DatabaseHelper.getCurrentUserEmail());
            
            // Debug: Log user responses
            dbHelper.logUserResponses();
            
            // Refresh card visibility and status based on Mental Health Screener scores
            updateCardVisibility();
            updateCardStatuses();
            updateNextStepsCard();
            
            // Hide the original instructions card since we now have the Next Steps card in the flow
            if (instructionsCard != null) {
                instructionsCard.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            Log.e("QuestionnaireMain", "Error in onResume: " + e.getMessage(), e);
            // If there's any error in onResume, we'll just continue to prevent app crashes
        }
    }

    private void setupCards() {
        // Clear existing cards
        cardsContainer.removeAllViews();

        // Add cards in the specified order
        // 1. Mental Health Screener (always visible)
        addCard(
            "Mental Health Symptom Screener",
            "A comprehensive assessment of various mental health symptoms",
            MENTAL_HEALTH_SCREENER,
            true
        );

        // 2. Next Steps Card (initially hidden, shown after Mental Health completion)
        addNextStepsCard();

        // 3. PHQ-9 (initially hidden)
        addCard(
            "Patient Health Questionnaire (PHQ-9)",
            "Screens for depression symptoms",
            PHQ_9,
            false
        );

        // 4. BDI (initially hidden)
        addCard(
            "Beck Depression Inventory (BDI)",
            "Measures severity of depression",
            BDI,
            false
        );

        // 5. GAD-7 (initially hidden)
        addCard(
            "Generalized Anxiety Disorder (GAD-7)",
            "Screens for anxiety symptoms",
            GAD_7,
            false
        );

        // 6. Resilience Scale (initially hidden)
        addCard(
            "Resilience Scale",
            "Measures your ability to bounce back from challenges",
            RESILIENCE,
            false
        );
    }

    private void addNextStepsCard() {
        try {
            // Inflate a special Next Steps card
            View cardView = getLayoutInflater().inflate(R.layout.item_next_steps_card, cardsContainer, false);
            
            // Set initial visibility (hidden until Mental Health is completed)
            cardView.setVisibility(View.GONE);
            
            // Tag the card for later reference
            cardView.setTag("NEXT_STEPS");
            
            // Add to container
            cardsContainer.addView(cardView);
        } catch (Exception e) {
            Log.e("QuestionnaireMain", "Error inflating Next Steps card: " + e.getMessage(), e);
            // If we can't inflate the Next Steps card, continue without it to prevent crashes
        }
    }

    private void updateNextStepsCard() {
        try {
            // Find the Next Steps card
            View nextStepsCard = null;
            for (int i = 0; i < cardsContainer.getChildCount(); i++) {
                View child = cardsContainer.getChildAt(i);
                if ("NEXT_STEPS".equals(child.getTag())) {
                    nextStepsCard = child;
                    break;
                }
            }

            if (nextStepsCard != null) {
                TextView tvInstructions = nextStepsCard.findViewById(R.id.tvNextStepsInstructions);
                
                if (hasCompletedQuestionnaire(MENTAL_HEALTH_SCREENER)) {
                    // Show the card and update content
                    nextStepsCard.setVisibility(View.VISIBLE);
                    
                    // Get scores and generate instructions
                    int totalScore = getMentalHealthScreenerTotalScore();
                    int partAScore = getMentalHealthScreenerPartScore("Part A: Mood and Energy");
                    int partBScore = getMentalHealthScreenerPartScore("Part B: Anxiety and Stress");
                    int partCScore = getMentalHealthScreenerPartScore("Part C: Sleep and Appetite");
                    int partDScore = getMentalHealthScreenerPartScore("Part D: Cognition and Thinking");
                    int partEScore = getMentalHealthScreenerPartScore("Part E: Repetitive Thoughts and Behaviors");
                    int partFScore = getMentalHealthScreenerPartScore("Part F: Physical Symptoms");
                    
                    String instructions = generateInstructions(totalScore, partAScore, partBScore, 
                        partCScore, partDScore, partEScore, partFScore);
                    
                    if (tvInstructions != null) {
                        tvInstructions.setText(instructions.replace("**", "").replace("🔍", "").replace("📋", "")
                                                      .replace("✅", "").replace("📝", "").replace("💡", ""));
                    }
                } else {
                    // Hide the card if Mental Health questionnaire is not completed
                    nextStepsCard.setVisibility(View.GONE);
                }
            }
        } catch (Exception e) {
            Log.e("QuestionnaireMain", "Error updating Next Steps card: " + e.getMessage(), e);
            // If there's an error, just hide the Next Steps card to prevent crashes
            View nextStepsCard = null;
            for (int i = 0; i < cardsContainer.getChildCount(); i++) {
                View child = cardsContainer.getChildAt(i);
                if ("NEXT_STEPS".equals(child.getTag())) {
                    nextStepsCard = child;
                    break;
                }
            }
            if (nextStepsCard != null) {
                nextStepsCard.setVisibility(View.GONE);
            }
        }
    }

    private void addCard(String title, String description, String questionnaireType, boolean initiallyVisible) {
        // Inflate card view
        View cardView = getLayoutInflater().inflate(R.layout.item_questionnaire_card, cardsContainer, false);
        
        // Set card properties
        MaterialCardView card = cardView.findViewById(R.id.cardQuestionnaire);
        TextView tvTitle = cardView.findViewById(R.id.tvQuestionnaireTitle);
        TextView tvDescription = cardView.findViewById(R.id.tvQuestionnaireDescription);
        
        // Status indicators
        ImageView ivStatusIcon = cardView.findViewById(R.id.ivStatusIcon);
        ImageView ivCheckmark = cardView.findViewById(R.id.ivCheckmark);
        LinearLayout progressLayout = cardView.findViewById(R.id.progressLayout);
        TextView tvProgress = cardView.findViewById(R.id.tvProgress);
        ProgressBar progressBar = cardView.findViewById(R.id.progressBar);
        
        // Action buttons
        MaterialButton btnStart = cardView.findViewById(R.id.btnStartQuestionnaire);
        MaterialButton btnContinue = cardView.findViewById(R.id.btnContinueQuestionnaire);
        MaterialButton btnViewResults = cardView.findViewById(R.id.btnViewResults);
        
        tvTitle.setText(title);
        tvDescription.setText(description);
        
        // Set initial visibility
        cardView.setVisibility(initiallyVisible ? View.VISIBLE : View.GONE);
        
        // Tag the card with its type for later reference
        cardView.setTag(questionnaireType);
        
        // Set click listeners
        card.setOnClickListener(v -> handleCardClick(questionnaireType, v));
        btnStart.setOnClickListener(v -> startQuestionnaire(questionnaireType));
        btnContinue.setOnClickListener(v -> startQuestionnaire(questionnaireType));
        btnViewResults.setOnClickListener(v -> viewResults(questionnaireType));
        
        // Add to container
        cardsContainer.addView(cardView);
    }

    private void handleCardClick(String questionnaireType, View cardView) {
        // Get questionnaire status
        int status = dbHelper.getQuestionnaireStatus(questionnaireType);
        
        // Only allow clicks for non-completed questionnaires
        if (status == 2) { // Completed - do nothing
            Log.d("QuestionnaireMain", "Questionnaire " + questionnaireType + " is completed, no action taken");
            return;
        } else { // Not started or partially completed
            startQuestionnaire(questionnaireType);
        }
    }

    private void startQuestionnaire(String questionnaireType) {
        // All questionnaires now use the same activity
        Intent intent = new Intent(QuestionnaireMain.this, QuestionnaireActivity.class);
        intent.putExtra("QUESTIONNAIRE_TYPE", questionnaireType);
        startActivity(intent);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private void viewResults(String questionnaireType) {
        // Navigate to results activity
        Intent intent = new Intent(QuestionnaireMain.this, IntegratedResultsActivity.class);
        intent.putExtra("QUESTIONNAIRE_TYPE", questionnaireType);
        startActivity(intent);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private void updateCardStatuses() {
        try {
            Log.d("QuestionnaireMain", "Updating card statuses");
            
            for (int i = 0; i < cardsContainer.getChildCount(); i++) {
                View cardView = cardsContainer.getChildAt(i);
                String questionnaireType = (String) cardView.getTag();
                
                // Skip Next Steps card as it doesn't have questionnaire status UI elements
                if (questionnaireType != null && cardView.getVisibility() == View.VISIBLE && 
                    !"NEXT_STEPS".equals(questionnaireType)) {
                    updateCardStatus(cardView, questionnaireType);
                }
            }
        } catch (Exception e) {
            Log.e("QuestionnaireMain", "Error updating card statuses: " + e.getMessage(), e);
            // If there's any error, just continue to prevent the app from crashing
        }
    }

    private void updateCardStatus(View cardView, String questionnaireType) {
        try {
            // Get UI elements
            ImageView ivStatusIcon = cardView.findViewById(R.id.ivStatusIcon);
            ImageView ivCheckmark = cardView.findViewById(R.id.ivCheckmark);
            LinearLayout progressLayout = cardView.findViewById(R.id.progressLayout);
            TextView tvProgress = cardView.findViewById(R.id.tvProgress);
            ProgressBar progressBar = cardView.findViewById(R.id.progressBar);
            
            MaterialButton btnStart = cardView.findViewById(R.id.btnStartQuestionnaire);
            MaterialButton btnContinue = cardView.findViewById(R.id.btnContinueQuestionnaire);
            MaterialButton btnViewResults = cardView.findViewById(R.id.btnViewResults);
            
            // Check if all required UI elements exist (safety check)
            if (ivStatusIcon == null || ivCheckmark == null || progressLayout == null || 
                btnStart == null || btnContinue == null || btnViewResults == null) {
                Log.w("QuestionnaireMain", "Some UI elements are null for card: " + questionnaireType + ", skipping status update");
                return;
            }
            
            // Get status and response count
            int status = dbHelper.getQuestionnaireStatus(questionnaireType);
            int responseCount = dbHelper.getQuestionnaireResponseCount(questionnaireType);
            int expectedCount = getExpectedQuestionCount(questionnaireType);
            
            Log.d("QuestionnaireMain", "Questionnaire: " + questionnaireType + 
                  ", Status: " + status + ", Responses: " + responseCount + "/" + expectedCount);
            
            // Reset all visibility first
            ivStatusIcon.setVisibility(View.GONE);
            ivCheckmark.setVisibility(View.GONE);
            progressLayout.setVisibility(View.GONE);
            btnStart.setVisibility(View.GONE);
            btnContinue.setVisibility(View.GONE);
            btnViewResults.setVisibility(View.GONE);
            
            switch (status) {
                case 0: // Not Started
                    btnStart.setVisibility(View.VISIBLE);
                    break;
                    
                case 1: // Partially Completed
                    ivStatusIcon.setVisibility(View.VISIBLE);
                    progressLayout.setVisibility(View.VISIBLE);
                    btnContinue.setVisibility(View.VISIBLE);
                    
                    // Update progress
                    if (progressBar != null && tvProgress != null) {
                        int progressPercentage = (int) ((float) responseCount / expectedCount * 100);
                        progressBar.setProgress(progressPercentage);
                        tvProgress.setText("Progress: " + responseCount + "/" + expectedCount + " completed");
                    }
                    break;
                    
                case 2: // Completed - only show checkmark, no button
                    ivCheckmark.setVisibility(View.VISIBLE);
                    // Don't show any button for completed questionnaires
                    break;
            }
        } catch (Exception e) {
            Log.e("QuestionnaireMain", "Error updating card status for " + questionnaireType + ": " + e.getMessage(), e);
            // If there's any error, just continue without updating this card to prevent crashes
        }
    }

    private int getExpectedQuestionCount(String questionnaireType) {
        switch (questionnaireType) {
            case "MENTAL_HEALTH_SCREENER":
                return 22; // Part A(5) + Part B(5) + Part C(2) + Part D(5) + Part E(3) + Part F(2) = 22 questions
            case "PHQ":
                return 9; // PHQ-9 has 9 questions
            case "BDI":
                return 21; // Beck Depression Inventory has 21 questions
            case "GAD":
                return 7; // GAD-7 has 7 questions
            case "RESILIENCE":
                return 10; // Resilience Scale has 10 questions (as per implementation)
            default:
                return 10; // Default fallback
        }
    }

    private void updateCardVisibility() {
        Log.d("QuestionnaireMain", "Updating card visibility based on screener scores");
        
        // Check if mental health screener has been completed
        boolean isScreenerCompleted = hasCompletedQuestionnaire(MENTAL_HEALTH_SCREENER);
        Log.d("QuestionnaireMain", "Mental Health Screener completed: " + isScreenerCompleted);
        
        if (!isScreenerCompleted) {
            // Only show Mental Health Screener if it hasn't been completed
            showOnlyScreenerCard();
            Log.d("QuestionnaireMain", "Screener not completed, showing only screener card");
            return;
        }
        
        // Get scores from the database
        int totalScore = getMentalHealthScreenerTotalScore();
        int partAScore = getMentalHealthScreenerPartScore("Part A: Mood and Energy");
        int partBScore = getMentalHealthScreenerPartScore("Part B: Anxiety and Stress");
        int partCScore = getMentalHealthScreenerPartScore("Part C: Sleep and Appetite");
        int partDScore = getMentalHealthScreenerPartScore("Part D: Cognition and Thinking");
        int partEScore = getMentalHealthScreenerPartScore("Part E: Repetitive Thoughts and Behaviors");
        int partFScore = getMentalHealthScreenerPartScore("Part F: Physical Symptoms");
        
        Log.d("QuestionnaireMain", "Scores - Total: " + totalScore + 
              ", A: " + partAScore + ", B: " + partBScore + 
              ", C: " + partCScore + ", D: " + partDScore + 
              ", E: " + partEScore + ", F: " + partFScore);
        
        // Apply visibility logic based on scores
        if (totalScore >= 15) {
            // Show all cards
            showAllCards();
            Log.d("QuestionnaireMain", "Total score >= 15, showing all cards");
        } else if (partAScore >= 5) {
            // Show screener + PHQ card
            showScreenerAndSpecificCard(PHQ_9);
            Log.d("QuestionnaireMain", "Part A score >= 5, showing screener + PHQ card");
        } else if ((partAScore + partDScore >= 10) || (partCScore >= 4)) {
            // Show screener + BDI card
            showScreenerAndSpecificCard(BDI);
            Log.d("QuestionnaireMain", "Part A + D >= 10 or Part C >= 4, showing screener + BDI card");
        } else if (partBScore >= 5 || (partEScore + partFScore >= 6)) {
            // Show screener + GAD-7 card
            showScreenerAndSpecificCard(GAD_7);
            Log.d("QuestionnaireMain", "Part B >= 5 or Part E + F >= 6, showing screener + GAD-7 card");
        } else if (partFScore >= 4) {
            // Show screener + Resilience card
            showScreenerAndSpecificCard(RESILIENCE);
            Log.d("QuestionnaireMain", "Part F >= 4, showing screener + Resilience card");
        } else {
            // No additional questionnaires needed - only show screener
            showOnlyScreenerCard();
            Log.d("QuestionnaireMain", "No conditions met, showing only screener card");
        }
    }

    private void showScreenerAndSpecificCard(String cardType) {
        Log.d("QuestionnaireMain", "Showing screener and specific card: " + cardType);
        for (int i = 0; i < cardsContainer.getChildCount(); i++) {
            View cardView = cardsContainer.getChildAt(i);
            String type = (String) cardView.getTag();
            
            // Show the screener card, Next Steps card (if Mental Health is completed), and the specific card
            boolean isVisible = MENTAL_HEALTH_SCREENER.equals(type) || cardType.equals(type) || 
                               ("NEXT_STEPS".equals(type) && hasCompletedQuestionnaire(MENTAL_HEALTH_SCREENER));
            cardView.setVisibility(isVisible ? View.VISIBLE : View.GONE);
            
            Log.d("QuestionnaireMain", "Card " + type + " visibility: " + (isVisible ? "VISIBLE" : "GONE"));
        }
    }

    private void showOnlyScreenerCard() {
        for (int i = 0; i < cardsContainer.getChildCount(); i++) {
            View cardView = cardsContainer.getChildAt(i);
            String type = (String) cardView.getTag();
            // Show screener and Next Steps card (if Mental Health is completed)
            boolean isVisible = MENTAL_HEALTH_SCREENER.equals(type) || 
                               ("NEXT_STEPS".equals(type) && hasCompletedQuestionnaire(MENTAL_HEALTH_SCREENER));
            cardView.setVisibility(isVisible ? View.VISIBLE : View.GONE);
        }
    }
    
    private void showAllCards() {
        for (int i = 0; i < cardsContainer.getChildCount(); i++) {
            View cardView = cardsContainer.getChildAt(i);
            String type = (String) cardView.getTag();
            // Show all cards, but Next Steps only if Mental Health is completed
            boolean isVisible = !"NEXT_STEPS".equals(type) || hasCompletedQuestionnaire(MENTAL_HEALTH_SCREENER);
            cardView.setVisibility(isVisible ? View.VISIBLE : View.GONE);
        }
    }

    private boolean hasCompletedQuestionnaire(String questionnaireType) {
        // Debug: Check both methods for comparison
        boolean hasAnyResponses = dbHelper.hasCompletedQuestionnaire(questionnaireType);
        boolean isFullyCompleted = dbHelper.isQuestionnaireCompleted(questionnaireType);
        int responseCount = dbHelper.getQuestionnaireResponseCount(questionnaireType);
        
        Log.d("QuestionnaireMain", "Questionnaire " + questionnaireType + ":");
        Log.d("QuestionnaireMain", "  - Has ANY responses: " + hasAnyResponses);
        Log.d("QuestionnaireMain", "  - Is FULLY completed: " + isFullyCompleted);
        Log.d("QuestionnaireMain", "  - Response count: " + responseCount);
        
        // Use the correct method for full completion
        return isFullyCompleted;
    }
    
    private int getMentalHealthScreenerTotalScore() {
        // This should retrieve the total score from your database
        int totalScore = dbHelper.getMentalHealthScreenerTotalScore();
        Log.d("QuestionnaireMain", "Mental Health Screener total score from DB: " + totalScore);
        return totalScore;
    }
    
    private int getMentalHealthScreenerPartScore(String part) {
        // This should retrieve the score for a specific part (A, B, C, D, E, or F)
        int partScore = dbHelper.getMentalHealthScreenerPartScore(part);
        Log.d("QuestionnaireMain", "Mental Health Screener part " + part + " score from DB: " + partScore);
        return partScore;
    }

    private void showCompletionInstructions() {
        // Update card visibility first
        updateCardVisibility();
        updateCardStatuses();
        updateNextStepsCard();
        
        // Show dialog for immediate attention
        new MaterialAlertDialogBuilder(this)
            .setTitle("Mental Health Screening Complete")
            .setMessage("Thank you for completing the screening! Your personalized recommendations are now available in the Next Steps section below.")
            .setPositiveButton("View Recommendations", (dialog, which) -> {
                // Scroll to Next Steps card if needed
                View nextStepsCard = null;
                for (int i = 0; i < cardsContainer.getChildCount(); i++) {
                    View child = cardsContainer.getChildAt(i);
                    if ("NEXT_STEPS".equals(child.getTag())) {
                        nextStepsCard = child;
                        break;
                    }
                }
                if (nextStepsCard != null) {
                    nextStepsCard.requestFocus();
                }
            })
            .setIcon(R.drawable.quesicon)
            .show();
    }
    
    private String generateInstructions(int totalScore, int partAScore, int partBScore, 
                                      int partCScore, int partDScore, int partEScore, int partFScore) {
        StringBuilder instructions = new StringBuilder();
        instructions.append("Based on your Mental Health Symptom Screener results, here are your personalized recommendations:\n\n");
        
        if (totalScore >= 15) {
            instructions.append("🔍 **Comprehensive Assessment Recommended**\n")
                        .append("Your responses indicate multiple areas that could benefit from further assessment. ")
                        .append("We recommend completing all available questionnaires for a complete picture of your mental wellbeing.\n\n");
        } else {
            // Specific recommendations based on part scores
            List<String> recommendations = new ArrayList<>();
            
            if (partAScore >= 5) {
                recommendations.add("📋 **PHQ-9 (Depression Screening)** - Your mood and energy responses suggest this assessment would be beneficial.");
            }
            
            if (partAScore + partDScore >= 10 || partCScore >= 4) {
                recommendations.add("📋 **BDI-II (Depression Inventory)** - Your responses indicate this detailed depression assessment could provide valuable insights.");
            }
            
            if (partBScore >= 5 || partEScore + partFScore >= 6) {
                recommendations.add("📋 **GAD-7 (Anxiety Screening)** - Your anxiety and stress responses suggest this assessment would be helpful.");
            }
            
            if (partFScore >= 4) {
                recommendations.add("📋 **Resilience Scale** - Your responses indicate that measuring your resilience could provide valuable insights for coping strategies.");
            }
            
            if (recommendations.isEmpty()) {
                instructions.append("✅ **Great News!**\n")
                           .append("Your responses indicate relatively low symptom levels. However, you can still complete any additional assessments if you'd like more detailed insights into your mental wellbeing.\n\n");
            } else {
                instructions.append("📝 **Recommended Assessments:**\n\n");
                for (String recommendation : recommendations) {
                    instructions.append(recommendation).append("\n\n");
                }
            }
        }
        
        instructions.append("💡 **Remember:** These assessments are tools for self-awareness and should not replace professional medical advice. ")
                   .append("If you have concerns about your mental health, please consult with a healthcare professional.");
        
        return instructions.toString();
    }

    private void showPersistentInstructions() {
        // Get the total score to determine what instructions to show
        int totalScore = getMentalHealthScreenerTotalScore();
        int partAScore = getMentalHealthScreenerPartScore("Part A: Mood and Energy");
        int partBScore = getMentalHealthScreenerPartScore("Part B: Anxiety and Stress");
        int partCScore = getMentalHealthScreenerPartScore("Part C: Sleep and Appetite");
        int partDScore = getMentalHealthScreenerPartScore("Part D: Cognition and Thinking");
        int partEScore = getMentalHealthScreenerPartScore("Part E: Repetitive Thoughts and Behaviors");
        int partFScore = getMentalHealthScreenerPartScore("Part F: Physical Symptoms");
        
        String instructions = generateInstructions(totalScore, partAScore, partBScore, partCScore, 
                                                  partDScore, partEScore, partFScore);
        
        // Show instructions card without emoji and formatting
        if (instructionsCard != null && tvInstructions != null) {
            tvInstructions.setText(instructions.replace("**", "").replace("🔍", "").replace("📋", "")
                                               .replace("✅", "").replace("📝", "").replace("💡", ""));
            instructionsCard.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            // Handle back button in app bar
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}