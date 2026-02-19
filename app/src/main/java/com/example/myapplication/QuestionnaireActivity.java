package com.example.myapplication;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.BounceInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.example.myapplication.model.Question;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.example.myapplication.data.QuestionnaireResponse;
import android.widget.ScrollView;

public class QuestionnaireActivity extends AppCompatActivity {
    private static class Question {
        private final String text;
        private final String[] options;

        public Question(String text, String[] options) {
            this.text = text;
            this.options = options;
        }

        public String getText() { return text; }
        public String[] getOptions() { return options; }
    }

    private List<Question> questions;
    private Map<String, List<Question>> allQuestions;
    private List<String> allParts;
    private String currentPart;
    private Map<String, Integer> responses;
    private int currentQuestionIndex = 0;
    private ArrayList<Integer> answers;
    private String currentQuestionnaireType;
    private List<String> queuedQuestionnaires = new ArrayList<>();

    // UI elements
    private TextView tvPartTitle;
    private LinearLayout questionsContainer;
    private Button btnPreviousPart;
    private Button btnNextPart;
    private ScrollView scrollView;
    private ExecutorService executor;

    private static final String[] ENCOURAGING_MESSAGES = {
            "You're doing great!",
            "Keep going!",
            "Take your time, no rush!",
            "You're making great progress!",
            "Almost there, you've got this!",
            "Thank you for being honest!",
            "I appreciate your openness!"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_questionnaire);

        // Initialize UI elements
        tvPartTitle = findViewById(R.id.tvPartTitle);
        questionsContainer = findViewById(R.id.questionsContainer);
        btnPreviousPart = findViewById(R.id.btnPreviousPart);
        btnNextPart = findViewById(R.id.btnNextPart);
        scrollView = findViewById(R.id.scrollView);

        // Get questionnaire type from intent
        currentQuestionnaireType = getIntent().getStringExtra("QUESTIONNAIRE_TYPE");
        if (currentQuestionnaireType == null) {
            currentQuestionnaireType = "MENTAL_HEALTH_SCREENER"; // Default
        }

        // Set questionnaire title based on type
        TextView tvQuestionnaireName = findViewById(R.id.tvQuestionnaireName);
        if ("PHQ".equals(currentQuestionnaireType)) {
            tvQuestionnaireName.setText("Patient Health Questionnaire (PHQ-9)");
        } else if ("BDI".equals(currentQuestionnaireType)) {
            tvQuestionnaireName.setText("Beck Depression Inventory (BDI-II)");
        } else if ("GAD".equals(currentQuestionnaireType)) {
            tvQuestionnaireName.setText("Generalized Anxiety Disorder (GAD-7)");
        } else if ("RESILIENCE".equals(currentQuestionnaireType)) {
            tvQuestionnaireName.setText("Brief Resilience Scale (BRS)");
        } else {
            tvQuestionnaireName.setText("Mental Health Symptom Screener");
        }

        responses = new HashMap<>();
        executor = Executors.newSingleThreadExecutor();
        setupQuestions(currentQuestionnaireType);
        setupButtons();
        loadQuestionsForCurrentPart();
        updateNavigationButtons();

        if (savedInstanceState != null) {
            restoreState(savedInstanceState);
        } else {
            answers = new ArrayList<>();
            showReadinessCheck(currentQuestionnaireType);

            // Check if we need to load additional questionnaires from a previous run
            if ("MENTAL_HEALTH_SCREENER".equals(currentQuestionnaireType)) {
                loadQueuedQuestionnairesFromDatabase();
            }
        }
    }

    /**
     * Loads any questionnaires that were previously triggered but not completed
     * This ensures we don't lose track of required questionnaires between app sessions
     */
    private void loadQueuedQuestionnairesFromDatabase() {
        executor.execute(() -> {
            DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
            String userEmail = DatabaseHelper.getCurrentUserEmail();
            QuestionnaireSummary summary = dbHelper.getQuestionnaireSummary(userEmail);

            if (summary != null) {
                final List<String> queue = new ArrayList<>();

                // Only add questionnaires that are triggered but not yet filled
                if (summary.getPhq9Triggered() > 0 && !summary.isPhq9Filled()) {
                    queue.add("PHQ");
                }
                if (summary.getBdiTriggered() > 0 && !summary.isBdiFilled()) {
                    queue.add("BDI");
                }
                if (summary.getGad7Triggered() > 0 && !summary.isGad7Filled()) {
                    queue.add("GAD");
                }
                if (summary.getBrsTriggered() > 0 && !summary.isBrsFilled()) {
                    queue.add("RESILIENCE");
                }

                runOnUiThread(() -> {
                    queuedQuestionnaires.clear();
                    queuedQuestionnaires.addAll(queue);
                    Log.d("QuestionnaireActivity", "Loaded " + queuedQuestionnaires.size() +
                            " queued questionnaires from database");
                });
            }
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putIntegerArrayList("answers", answers);
        outState.putInt("currentQuestionIndex", currentQuestionIndex);
        outState.putString("currentQuestionnaireType", currentQuestionnaireType);
        outState.putStringArrayList("queuedQuestionnaires", new ArrayList<>(queuedQuestionnaires));
    }

    private void restoreState(Bundle savedInstanceState) {
        answers = savedInstanceState.getIntegerArrayList("answers");
        currentQuestionIndex = savedInstanceState.getInt("currentQuestionIndex");
        currentQuestionnaireType = savedInstanceState.getString("currentQuestionnaireType");
        queuedQuestionnaires = savedInstanceState.getStringArrayList("queuedQuestionnaires");

        if (answers == null) {
            answers = new ArrayList<>();
        }
        if (queuedQuestionnaires == null) {
            queuedQuestionnaires = new ArrayList<>();
        }

        if (!answers.isEmpty()) {
            findViewById(R.id.questionnaireLayout).setVisibility(View.VISIBLE);
            findViewById(R.id.readinessLayout).setVisibility(View.GONE);
            loadQuestionsForCurrentPart();
        } else {
            showReadinessCheck(currentQuestionnaireType);
        }
    }

    private void setupButtons() {
        Button btnStart = findViewById(R.id.btnStart);
        if (btnStart != null) {
            btnStart.setOnClickListener(v -> {
                View readinessLayout = findViewById(R.id.readinessLayout);
                View questionnaireLayout = findViewById(R.id.questionnaireLayout);

                if (readinessLayout != null) {
                    readinessLayout.setVisibility(View.GONE);
                }
                if (questionnaireLayout != null) {
                    questionnaireLayout.setVisibility(View.VISIBLE);
                }

                loadQuestionsForCurrentPart();
                scrollToTop();
                showRandomEncouragement();
            });
        }

        btnPreviousPart.setOnClickListener(v -> {
            // No need to save answers as they're saved in real-time
            moveToPreviousPart();
        });

        btnNextPart.setOnClickListener(v -> {
            if (isCurrentPartComplete()) {
                if (currentPart.equals(allParts.get(allParts.size() - 1))) {
                    showSubmitConfirmation();
                } else {
                    moveToNextPart();
                }
            } else {
                Toast.makeText(this, "Please answer all questions", Toast.LENGTH_SHORT).show();
                logUnansweredQuestions();
            }
        });
    }

    private void setupQuestions(String questionnaireType) {
        allQuestions = new HashMap<>();

        if ("PHQ".equals(questionnaireType)) {
            setupPHQQuestions();
        } else if ("BDI".equals(questionnaireType)) {
            setupBDIQuestions();
        } else if ("GAD".equals(questionnaireType)) {
            setupGADQuestions();
        } else if ("RESILIENCE".equals(questionnaireType)) {
            setupResilienceQuestions();
        } else {
            setupMentalHealthScreenerQuestions();
        }
    }

    private void setupPHQQuestions() {
        String[] options = getOptionsArray();

        List<Question> phqQuestions = new ArrayList<>();
        phqQuestions.add(new Question("1. Little interest or pleasure in doing things", options));
        phqQuestions.add(new Question("2. Feeling down, depressed, or hopeless", options));
        phqQuestions.add(new Question("3. Trouble falling or staying asleep, or sleeping too much", options));
        phqQuestions.add(new Question("4. Feeling tired or having little energy", options));
        phqQuestions.add(new Question("5. Poor appetite or overeating", options));
        phqQuestions.add(new Question("6. Feeling bad about yourself — or that you are a failure or have let yourself or your family down", options));
        phqQuestions.add(new Question("7. Trouble concentrating on things, such as reading the newspaper or watching television", options));
        phqQuestions.add(new Question("8. Moving or speaking so slowly that other people could have noticed? Or the opposite — being so fidgety or restless that you have been moving around a lot more than usual", options));
        phqQuestions.add(new Question("9. Thoughts that you would be better off dead or of hurting yourself in some way", options));

        allQuestions.put("Depression Screening (PHQ-9)", phqQuestions);

        // Set up the order of parts - just one part for PHQ-9
        allParts = new ArrayList<>(Arrays.asList(
                "Depression Screening (PHQ-9)"
        ));

        currentPart = allParts.get(0);
    }

    private void setupBDIQuestions() {
        String[] options = new String[] {
                "0 - Not at all",
                "1 - Mild",
                "2 - Moderate",
                "3 - Severe"
        };

        List<Question> bdiQuestions = new ArrayList<>();
        bdiQuestions.add(new Question("1. Sadness", options));
        bdiQuestions.add(new Question("2. Pessimism", options));
        bdiQuestions.add(new Question("3. Past Failure", options));
        bdiQuestions.add(new Question("4. Loss of Pleasure", options));
        bdiQuestions.add(new Question("5. Guilty Feelings", options));
        bdiQuestions.add(new Question("6. Punishment Feelings", options));
        bdiQuestions.add(new Question("7. Self-Dislike", options));
        bdiQuestions.add(new Question("8. Self-Criticalness", options));
        bdiQuestions.add(new Question("9. Suicidal Thoughts or Wishes", options));
        bdiQuestions.add(new Question("10. Crying", options));
        bdiQuestions.add(new Question("11. Agitation", options));
        bdiQuestions.add(new Question("12. Loss of Interest", options));
        bdiQuestions.add(new Question("13. Indecisiveness", options));
        bdiQuestions.add(new Question("14. Worthlessness", options));
        bdiQuestions.add(new Question("15. Loss of Energy", options));
        bdiQuestions.add(new Question("16. Changes in Sleeping Pattern", options));
        bdiQuestions.add(new Question("17. Irritability", options));
        bdiQuestions.add(new Question("18. Changes in Appetite", options));
        bdiQuestions.add(new Question("19. Concentration Difficulty", options));
        bdiQuestions.add(new Question("20. Tiredness or Fatigue", options));
        bdiQuestions.add(new Question("21. Loss of Interest in Sex", options));

        allQuestions.put("Beck Depression Inventory (BDI-II)", bdiQuestions);

        // Set up the order of parts - just one part for BDI-II
        allParts = new ArrayList<>(Arrays.asList(
                "Beck Depression Inventory (BDI-II)"
        ));

        currentPart = allParts.get(0);
    }

    private void setupGADQuestions() {
        String[] options = getOptionsArray();

        List<Question> gadQuestions = new ArrayList<>();
        gadQuestions.add(new Question("1. Feeling nervous, anxious, or on edge", options));
        gadQuestions.add(new Question("2. Not being able to stop or control worrying", options));
        gadQuestions.add(new Question("3. Worrying too much about different things", options));
        gadQuestions.add(new Question("4. Trouble relaxing", options));
        gadQuestions.add(new Question("5. Being so restless that it is hard to sit still", options));
        gadQuestions.add(new Question("6. Becoming easily annoyed or irritable", options));
        gadQuestions.add(new Question("7. Feeling afraid, as if something awful might happen", options));

        allQuestions.put("Generalized Anxiety Disorder (GAD-7)", gadQuestions);

        // Set up the order of parts - just one part for GAD-7
        allParts = new ArrayList<>(Arrays.asList(
                "Generalized Anxiety Disorder (GAD-7)"
        ));

        currentPart = allParts.get(0);
    }

    private void setupResilienceQuestions() {
        String[] options = new String[] {
                "1 - Strongly disagree",
                "2 - Disagree",
                "3 - Neutral",
                "4 - Agree",
                "5 - Strongly agree"
        };

        // Brief Resilience Scale (BRS)
        List<Question> brsQuestions = new ArrayList<>();
        brsQuestions.add(new Question("1. I tend to bounce back quickly after hard times", options));
        brsQuestions.add(new Question("2. I have a hard time making it through stressful events", options));
        brsQuestions.add(new Question("3. It does not take me long to recover from a stressful event", options));
        brsQuestions.add(new Question("4. It is hard for me to snap back when something bad happens", options));
        brsQuestions.add(new Question("5. I usually come through difficult times with little trouble", options));
        brsQuestions.add(new Question("6. I tend to take a long time to get over set-backs in my life", options));

        allQuestions.put("Brief Resilience Scale (BRS)", brsQuestions);

        // Set up the order of parts - just one part for BRS
        allParts = new ArrayList<>(Arrays.asList(
                "Brief Resilience Scale (BRS)"
        ));

        currentPart = allParts.get(0);
    }

    private void setupMentalHealthScreenerQuestions() {
        String[] options = getOptionsArray();

        // Part A: Mood and Energy
        List<Question> partA = new ArrayList<>();
        partA.add(new Question("1. Feeling down, depressed, irritable, or hopeless", options));
        partA.add(new Question("2. Little interest or pleasure in doing things you used to enjoy", options));
        partA.add(new Question("3. Feeling tired or having little energy", options));
        partA.add(new Question("4. Feeling bad about yourself, feeling that you are a failure, or feeling that you have let yourself or your family down", options));
        partA.add(new Question("5. Thoughts that you would be better off dead or of hurting yourself in some way", options));
        allQuestions.put("Part A: Mood and Energy", partA);

        // Part B: Anxiety and Stress
        List<Question> partB = new ArrayList<>();
        partB.add(new Question("6. Feeling nervous, anxious, or on edge", options));
        partB.add(new Question("7. Not being able to stop or control worrying", options));
        partB.add(new Question("8. Worrying too much about different things", options));
        partB.add(new Question("9. Trouble relaxing or feeling restless", options));
        partB.add(new Question("10. Being so restless that it's hard to sit still", options));
        allQuestions.put("Part B: Anxiety and Stress", partB);

        // Part C: Sleep and Appetite
        List<Question> partC = new ArrayList<>();
        partC.add(new Question("11. Trouble falling or staying asleep, or sleeping too much", options));
        partC.add(new Question("12. Poor appetite or overeating", options));
        allQuestions.put("Part C: Sleep and Appetite", partC);

        // Part D: Cognition and Thinking
        List<Question> partD = new ArrayList<>();
        partD.add(new Question("13. Trouble concentrating on things such as reading or watching TV", options));
        partD.add(new Question("14. Moving or speaking so slowly that other people could have noticed, or being fidgety or restless", options));
        partD.add(new Question("15. Racing thoughts or feeling that your mind is going too fast", options));
        partD.add(new Question("16. Easily distracted by unimportant things", options));
        partD.add(new Question("17. Making decisions quickly without thinking about consequences", options));
        allQuestions.put("Part D: Cognition and Thinking", partD);

        // Part E: Repetitive Thoughts and Behaviors
        List<Question> partE = new ArrayList<>();
        partE.add(new Question("18. Having the same negative thoughts over and over again", options));
        partE.add(new Question("19. Unable to control or stop certain thoughts even when you try", options));
        partE.add(new Question("20. Finding it hard to stop analyzing situations or overthinking problems", options));
        allQuestions.put("Part E: Repetitive Thoughts and Behaviors", partE);

        // Part F: Physical Symptoms
        List<Question> partF = new ArrayList<>();
        partF.add(new Question("21. Sudden episodes of intense fear with physical symptoms (racing heart, shortness of breath, dizziness)", options));
        partF.add(new Question("22. Physical symptoms (like headaches, stomach problems, muscle tension) that occur when you feel stressed or anxious", options));
        allQuestions.put("Part F: Physical Symptoms", partF);

        // Set up the order of parts
        allParts = new ArrayList<>(Arrays.asList(
                "Part A: Mood and Energy",
                "Part B: Anxiety and Stress",
                "Part C: Sleep and Appetite",
                "Part D: Cognition and Thinking",
                "Part E: Repetitive Thoughts and Behaviors",
                "Part F: Physical Symptoms"
        ));

        currentPart = allParts.get(0);
    }

    private String[] getOptionsArray() {
        return new String[] {
                "Not at all",
                "Several days",
                "More than half the days",
                "Nearly every day"
        };
    }

    private void showReadinessCheck(String questionnaireType) {
        TextView tvQuestionnaireName = findViewById(R.id.tvQuestionnaireName);
        TextView tvReadyToStart = findViewById(R.id.tvReadyToStart);
        Button btnStart = findViewById(R.id.btnStart);

        if ("PHQ".equals(questionnaireType)) {
            if (tvQuestionnaireName != null) {
                tvQuestionnaireName.setText("Patient Health Questionnaire (PHQ-9)");
            }
            if (tvReadyToStart != null) {
                tvReadyToStart.setText("This questionnaire will ask about your mood and energy levels over the past 2 weeks.");
            }
            if (btnStart != null) {
                btnStart.setText("Start Depression Screening");
            }
        } else if ("BDI".equals(questionnaireType)) {
            if (tvQuestionnaireName != null) {
                tvQuestionnaireName.setText("Beck Depression Inventory (BDI-II)");
            }
            if (tvReadyToStart != null) {
                tvReadyToStart.setText("This questionnaire will ask about your feelings and behaviors over the past 2 weeks.");
            }
            if (btnStart != null) {
                btnStart.setText("Start BDI Assessment");
            }
        } else if ("GAD".equals(questionnaireType)) {
            if (tvQuestionnaireName != null) {
                tvQuestionnaireName.setText("Generalized Anxiety Disorder (GAD-7)");
            }
            if (tvReadyToStart != null) {
                tvReadyToStart.setText("This questionnaire will ask about your anxiety levels over the past 2 weeks.");
            }
            if (btnStart != null) {
                btnStart.setText("Start Anxiety Screening");
            }
        } else if ("RESILIENCE".equals(questionnaireType)) {
            if (tvQuestionnaireName != null) {
                tvQuestionnaireName.setText("Brief Resilience Scale (BRS)");
            }
            if (tvReadyToStart != null) {
                tvReadyToStart.setText("This questionnaire will ask about your ability to bounce back from stress and difficulties.");
            }
            if (btnStart != null) {
                btnStart.setText("Start Resilience Assessment");
            }
        } else {
            if (tvQuestionnaireName != null) {
                tvQuestionnaireName.setText("Mental Health Symptom Screener");
            }
            if (tvReadyToStart != null) {
                tvReadyToStart.setText("This questionnaire covers different aspects of mental health including mood, anxiety, sleep, and social functioning.");
            }
            if (btnStart != null) {
                btnStart.setText("Start Mental Health Screening");
            }
        }

        View readinessLayout = findViewById(R.id.readinessLayout);
        View questionnaireLayout = findViewById(R.id.questionnaireLayout);

        if (readinessLayout != null) {
            readinessLayout.setVisibility(View.VISIBLE);
        }
        if (questionnaireLayout != null) {
            questionnaireLayout.setVisibility(View.GONE);
        }
    }

    private void startQuestionnaire() {
        if (findViewById(R.id.readinessLayout) != null) {
            findViewById(R.id.readinessLayout).setVisibility(View.GONE);
        }
        if (findViewById(R.id.questionnaireLayout) != null) {
            findViewById(R.id.questionnaireLayout).setVisibility(View.VISIBLE);
        }
        loadQuestionsForCurrentPart();
    }

    private void moveToNextPart() {
        int currentIndex = allParts.indexOf(currentPart);
        if (currentIndex < allParts.size() - 1) {
            currentPart = allParts.get(currentIndex + 1);
            loadQuestionsForCurrentPart();
            updateNavigationButtons();
            scrollToTop();
        }
    }

    private void moveToPreviousPart() {
        int currentIndex = allParts.indexOf(currentPart);
        if (currentIndex > 0) {
            currentPart = allParts.get(currentIndex - 1);
            loadQuestionsForCurrentPart();
            updateNavigationButtons();
            scrollToTop();
        }
    }

    private void scrollToTop() {
        if (scrollView != null) {
            scrollView.post(() -> scrollView.smoothScrollTo(0, 0));
        }
    }

    private void showRandomEncouragement() {
        // Show simple toast encouragement instead of Billy message
        Random random = new Random();
        String message = ENCOURAGING_MESSAGES[random.nextInt(ENCOURAGING_MESSAGES.length)];
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void loadQuestionsForCurrentPart() {
        questions = allQuestions.get(currentPart);
        displayQuestions();
        scrollToTop();
    }

    private void displayQuestions() {
        questionsContainer.removeAllViews();
        tvPartTitle.setText(currentPart);

        Log.d("QuestionnaireActivity", "Displaying questions for part: " + currentPart);
        Log.d("QuestionnaireActivity", "Number of questions: " + questions.size());

        for (int i = 0; i < questions.size(); i++) {
            Question question = questions.get(i);
            View questionView = getLayoutInflater().inflate(R.layout.item_question, questionsContainer, false);

            TextView tvQuestion = questionView.findViewById(R.id.tvQuestion);
            RadioGroup rgOptions = questionView.findViewById(R.id.rgOptions);

            // Set a unique ID for the RadioGroup to avoid conflicts
            rgOptions.setId(View.generateViewId());

            tvQuestion.setText(question.getText());

            // Store the question index and text as tags using resource IDs
            questionView.setTag(R.id.question_index_tag, i);
            questionView.setTag(R.id.question_text_tag, question.getText());

            // Set up radio buttons
            rgOptions.clearCheck();

            // Remove any existing radio buttons
            rgOptions.removeAllViews();

            // Add radio buttons for each option
            for (int j = 0; j < question.getOptions().length; j++) {
                RadioButton rb = new RadioButton(this);
                rb.setId(View.generateViewId());
                rb.setText(question.getOptions()[j]);
                rb.setTag(j); // Store the option index as a tag

                // Check if we have a saved response for this question
                if (responses.containsKey(question.getText()) &&
                        responses.get(question.getText()) == j) {
                    rb.setChecked(true);
                }

                rgOptions.addView(rb);
            }

            // Set listener for radio group
            final int questionIndex = i;
            rgOptions.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId != -1) {
                    RadioButton selectedButton = findViewById(checkedId);
                    if (selectedButton != null) {
                        int optionIndex = (int) selectedButton.getTag();
                        String questionText = question.getText();
                        responses.put(questionText, optionIndex);
                        Log.d("QuestionnaireActivity", "Selected answer for question " + questionIndex +
                                ": " + questionText + " = " + optionIndex);
                    }
                }
            });

            questionsContainer.addView(questionView);
        }
    }

    private void updateNavigationButtons() {
        int currentIndex = allParts.indexOf(currentPart);

        // Hide previous button if we're on the first part
        if (currentIndex > 0) {
            btnPreviousPart.setVisibility(View.VISIBLE);
            btnPreviousPart.setEnabled(true);
        } else {
            btnPreviousPart.setVisibility(View.GONE);
        }

        // Update next button text based on whether we're on the last part
        btnNextPart.setText(currentIndex == allParts.size() - 1 ? "Submit" : "Next");
    }

    private boolean isCurrentPartComplete() {
        boolean complete = true;

        Log.d("QuestionnaireActivity", "Checking if part is complete: " + currentPart);
        Log.d("QuestionnaireActivity", "Number of questions: " + questions.size());
        Log.d("QuestionnaireActivity", "Current responses: " + responses.toString());

        for (Question question : questions) {
            String questionText = question.getText();
            if (!responses.containsKey(questionText)) {
                complete = false;
                Log.d("QuestionnaireActivity", "Unanswered question: " + questionText);
            }
        }

        Log.d("QuestionnaireActivity", "Is part complete: " + complete);
        return complete;
    }

    private void showSubmitConfirmation() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Submit Questionnaire")
                .setMessage("Are you sure you want to submit your responses?")
                .setPositiveButton("Submit", (dialog, which) -> {
                    submitResponses();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void submitResponses() {
        executor.execute(() -> {
            DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
            dbHelper.ensureQuestionnaireSummaryTableExists(); // Ensure the table exists

            String questionnaireType = currentQuestionnaireType;
            if (questionnaireType == null) {
                questionnaireType = "MENTAL_HEALTH_SCREENER"; // Default
            }

            // Create a final copy for use in the lambda
            final String finalQuestionnaireType = questionnaireType;

            // Get current user email
            String userEmail = DatabaseHelper.getCurrentUserEmail();
            Log.d("QuestionnaireActivity", "Submitting responses for user: " + userEmail);

            // Save individual question responses
            for (Map.Entry<String, Integer> entry : responses.entrySet()) {
                String questionText = entry.getKey();
                int answer = entry.getValue();

                // Find which part this question belongs to
                String section = "";
                for (Map.Entry<String, List<Question>> partEntry : allQuestions.entrySet()) {
                    for (Question q : partEntry.getValue()) {
                        if (q.getText().equals(questionText)) {
                            section = partEntry.getKey();
                            break;
                        }
                    }
                    if (!section.isEmpty()) break;
                }

                QuestionnaireResponse response = new QuestionnaireResponse(
                        0, // Question ID will be auto-generated
                        section,
                        questionText,
                        answer
                );

                // Set questionnaire type and timestamp
                response.setQuestionnaireType(finalQuestionnaireType);
                response.setTimestamp(System.currentTimeMillis());

                // Explicitly set user email
                response.setUserEmail(userEmail);

                dbHelper.getQuestionnaireDao().insertResponse(response);
            }

            // Get or create questionnaire summary
            QuestionnaireSummary summary = dbHelper.getQuestionnaireSummary(userEmail);
            if (summary == null) {
                summary = new QuestionnaireSummary();
                summary.setUserEmail(userEmail);
            }

            // Handle specific questionnaire processing
            if ("MENTAL_HEALTH_SCREENER".equals(finalQuestionnaireType)) {
                // Calculate section scores for the screener
                int scoreA = 0, scoreB = 0, scoreC = 0, scoreD = 0, scoreE = 0, scoreF = 0;
                int totalScore = 0;

                // Calculate scores for Part A
                for (Question q : allQuestions.get("Part A: Mood and Energy")) {
                    if (responses.containsKey(q.getText())) {
                        scoreA += responses.get(q.getText());
                        totalScore += responses.get(q.getText());
                    }
                }

                // Calculate scores for Part B
                for (Question q : allQuestions.get("Part B: Anxiety and Stress")) {
                    if (responses.containsKey(q.getText())) {
                        scoreB += responses.get(q.getText());
                        totalScore += responses.get(q.getText());
                    }
                }

                // Calculate scores for Part C
                for (Question q : allQuestions.get("Part C: Sleep and Appetite")) {
                    if (responses.containsKey(q.getText())) {
                        scoreC += responses.get(q.getText());
                        totalScore += responses.get(q.getText());
                    }
                }

                // Calculate scores for Part D
                for (Question q : allQuestions.get("Part D: Cognition and Thinking")) {
                    if (responses.containsKey(q.getText())) {
                        scoreD += responses.get(q.getText());
                        totalScore += responses.get(q.getText());
                    }
                }

                // Calculate scores for Part E
                for (Question q : allQuestions.get("Part E: Repetitive Thoughts and Behaviors")) {
                    if (responses.containsKey(q.getText())) {
                        scoreE += responses.get(q.getText());
                        totalScore += responses.get(q.getText());
                    }
                }

                // Calculate scores for Part F
                for (Question q : allQuestions.get("Part F: Physical Symptoms")) {
                    if (responses.containsKey(q.getText())) {
                        scoreF += responses.get(q.getText());
                        totalScore += responses.get(q.getText());
                    }
                }

                // Update summary with section scores
                summary.setScoreA(scoreA);
                summary.setScoreB(scoreB);
                summary.setScoreC(scoreC);
                summary.setScoreD(scoreD);
                summary.setScoreE(scoreE);
                summary.setScoreF(scoreF);
                summary.setTotalScore(totalScore);

                // Apply the decision tree logic from the flowchart to set trigger flags
                boolean triggerPhq = false;
                boolean triggerBdi = false;
                boolean triggerGad = false;
                boolean triggerBrs = false;

                if (totalScore > 15) {
                    // If total score > 15, show all questionnaires
                    triggerPhq = true;
                    triggerBdi = true;
                    triggerGad = true;
                    triggerBrs = true;
                } else {
                    // Check each condition in order
                    if (scoreA >= 5) {
                        triggerPhq = true;
                    } else if ((scoreA + scoreD >= 10) || (scoreC >= 4)) {
                        triggerBdi = true;
                    } else if ((scoreB >= 5) || (scoreE + scoreF >= 6)) {
                        triggerGad = true;
                    } else if (scoreF >= 4) {
                        triggerBrs = true;
                    }
                }

                // Set triggered flags in summary
                summary.setPhq9Triggered(triggerPhq ? 1 : 0);
                summary.setBdiTriggered(triggerBdi ? 1 : 0);
                summary.setGad7Triggered(triggerGad ? 1 : 0);
                summary.setBrsTriggered(triggerBrs ? 1 : 0);

                // Build queue of questionnaires to be filled based on triggers
                queuedQuestionnaires = new ArrayList<>();
                if (triggerPhq) queuedQuestionnaires.add("PHQ");
                if (triggerBdi) queuedQuestionnaires.add("BDI");
                if (triggerGad) queuedQuestionnaires.add("GAD");
                if (triggerBrs) queuedQuestionnaires.add("RESILIENCE");

                Log.d("QuestionnaireActivity", "Triggered questionnaires: " + queuedQuestionnaires.toString());

            } else if ("PHQ".equals(finalQuestionnaireType)) {
                // Calculate PHQ-9 score
                int phq9Score = 0;
                for (Question q : allQuestions.get("Depression Screening (PHQ-9)")) {
                    if (responses.containsKey(q.getText())) {
                        phq9Score += responses.get(q.getText());
                    }
                }

                // Update summary with PHQ-9 score
                summary.setPhq9Score(phq9Score);
                summary.setPhq9Filled(true);

            } else if ("BDI".equals(finalQuestionnaireType)) {
                // Calculate BDI-II score
                int bdiScore = 0;
                for (Question q : allQuestions.get("Beck Depression Inventory (BDI-II)")) {
                    if (responses.containsKey(q.getText())) {
                        bdiScore += responses.get(q.getText());
                    }
                }

                // Update summary with BDI-II score
                summary.setBdiScore(bdiScore);
                summary.setBdiFilled(true);

            } else if ("GAD".equals(finalQuestionnaireType)) {
                // Calculate GAD-7 score
                int gad7Score = 0;
                for (Question q : allQuestions.get("Generalized Anxiety Disorder (GAD-7)")) {
                    if (responses.containsKey(q.getText())) {
                        gad7Score += responses.get(q.getText());
                    }
                }

                // Update summary with GAD-7 score
                summary.setGad7Score(gad7Score);
                summary.setGad7Filled(true);

            } else if ("RESILIENCE".equals(finalQuestionnaireType)) {
                // Calculate BRS score with proper reverse scoring
                int brsTotal = 0;
                int brsQuestions = 0;

                for (Question question : allQuestions.get("Brief Resilience Scale (BRS)")) {
                    String questionText = question.getText();
                    if (responses.containsKey(questionText)) {
                        // Add 1 to the option index since our radio buttons are 0-based but scores are 1-5
                        int response = responses.get(questionText) + 1;

                        // Apply reverse scoring for items 2, 4, and 6
                        if (questionText.startsWith("2.") ||
                                questionText.startsWith("4.") ||
                                questionText.startsWith("6.")) {
                            // Reverse score (5 becomes 1, 4 becomes 2, etc.)
                            brsTotal += (6 - response);
                        } else {
                            brsTotal += response;
                        }
                        brsQuestions++;
                    }
                }

                // Calculate the average BRS score and round to 2 decimal places
                double brsScore = 0.0;
                if (brsQuestions > 0) {
                    brsScore = (double) brsTotal / brsQuestions;
                    brsScore = Math.round(brsScore * 100) / 100.0;
                }

                // Update summary with BRS score
                summary.setBrsScore(brsScore);
                summary.setBrsFilled(true);
            }

            // Save the updated summary
            dbHelper.saveQuestionnaireSummary(summary);

            // Final questionnaire type for UI updates
            final String questType = finalQuestionnaireType;
            final boolean hasQueuedQuestionnaires = !queuedQuestionnaires.isEmpty();

            // Handle UI updates on the main thread
            runOnUiThread(() -> {
                Log.d("QuestionnaireActivity", "Completed " + questType +
                        ", queue has " + queuedQuestionnaires.size() + " items");

                if ("MENTAL_HEALTH_SCREENER".equals(questType)) {
                    if (hasQueuedQuestionnaires) {
                        // Show a dialog to inform the user about additional questionnaires
                        new MaterialAlertDialogBuilder(this)
                                .setTitle("Additional Assessments Needed")
                                .setMessage("Based on your responses, we need some additional information. " +
                                        "Please complete the following assessment.")
                                .setPositiveButton("Continue", (dialog, which) -> {
                                    loadNextQuestionnaire();
                                })
                                .setCancelable(false)
                                .show();
                    } else {
                        // If no additional questionnaires needed, go straight to results
                        navigateToResults();
                    }
                } else {
                    // For non-screener questionnaires
                    if (hasQueuedQuestionnaires) {
                        // Show a dialog to inform the user about next questionnaire
                        new MaterialAlertDialogBuilder(this)
                                .setTitle("Continue Assessment")
                                .setMessage("Thank you for completing this questionnaire. " +
                                        "There is one more assessment to complete.")
                                .setPositiveButton("Continue", (dialog, which) -> {
                                    loadNextQuestionnaire();
                                })
                                .setCancelable(false)
                                .show();
                    } else {
                        // If this was the last questionnaire, go to results
                        navigateToResults();
                    }
                }
            });
        });
    }

    private void loadNextQuestionnaire() {
        if (!queuedQuestionnaires.isEmpty()) {
            // Get the next questionnaire from the queue
            String nextQuestionnaire = queuedQuestionnaires.remove(0);

            Log.d("QuestionnaireActivity", "Loading next questionnaire: " + nextQuestionnaire);

            // Reset responses for the new questionnaire
            responses = new HashMap<>();

            // Set current questionnaire type
            currentQuestionnaireType = nextQuestionnaire;

            // Setup questions for this questionnaire
            setupQuestions(nextQuestionnaire);

            // Update UI
            TextView tvQuestionnaireName = findViewById(R.id.tvQuestionnaireName);
            if ("PHQ".equals(nextQuestionnaire)) {
                tvQuestionnaireName.setText("Patient Health Questionnaire (PHQ-9)");
            } else if ("BDI".equals(nextQuestionnaire)) {
                tvQuestionnaireName.setText("Beck Depression Inventory (BDI-II)");
            } else if ("GAD".equals(nextQuestionnaire)) {
                tvQuestionnaireName.setText("Generalized Anxiety Disorder (GAD-7)");
            } else if ("RESILIENCE".equals(nextQuestionnaire)) {
                tvQuestionnaireName.setText("Brief Resilience Scale (BRS)");
            }

            // Show readiness check for the new questionnaire
            showReadinessCheck(nextQuestionnaire);
        } else {
            // If no more questionnaires in queue, navigate to results
            navigateToResults();
        }
    }

    private void navigateToResults() {
        Intent intent = new Intent(this, QuestionnaireResultsActivity.class);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdown();
        }
    }

    // Add this method to help debug which questions are unanswered
    private void logUnansweredQuestions() {
        for (Question question : questions) {
            if (!responses.containsKey(question.getText())) {
                Log.d("QuestionnaireActivity", "Unanswered question: " + question.getText());
            }
        }
        Log.d("QuestionnaireActivity", "Current responses: " + responses.toString());
    }
}