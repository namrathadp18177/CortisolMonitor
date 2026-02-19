package com.example.myapplication;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.BounceInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class QuestionnaireActivity2 extends AppCompatActivity {
    private static class Question {
        private final String text;
        private final String[] options;
        private final boolean isReversed;

        public Question(String text, String[] options, boolean isReversed) {
            this.text = text;
            this.options = options;
            this.isReversed = isReversed;
        }

        public String getText() { return text; }
        public String[] getOptions() { return options; }
        public boolean isReversed() { return isReversed; }
    }

    private TextView tvQuestion;
    private TextView tvQuestionnaireName;
    private RadioGroup rgAnswers;
    private Button btnStart;
    private Button btnNext;
    private LinearLayout readinessLayout;
    private LinearLayout questionnaireLayout;
    private List<Question> questions;
    private int currentQuestionIndex = 0;
    private List<Integer> answers;

    // Variables to store PHQ-9 data from first questionnaire
    private int phqScore = -1;
    private String phqInterpretation = "";

    private static final int[] ENCOURAGING_MESSAGE_IDS = {
            R.string.encouragement_1,
            R.string.encouragement_2,
            R.string.encouragement_3,
            R.string.encouragement_4,
            R.string.encouragement_5,
            R.string.encouragement_6,
            R.string.encouragement_7
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_questionnaire);

        // Get PHQ-9 data from the intent
        Intent intent = getIntent();
        if (intent != null) {
            phqScore = intent.getIntExtra("score", -1);
            phqInterpretation = intent.getStringExtra("interpretation");
        }

        initializeViews();
        setupQuestions();

        if (savedInstanceState != null) {
            restoreState(savedInstanceState);
        } else {
            answers = new ArrayList<>();
            showReadinessCheck();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putIntegerArrayList("answers", new ArrayList<>(answers));
        outState.putInt("currentQuestionIndex", currentQuestionIndex);
        outState.putInt("phqScore", phqScore);
        outState.putString("phqInterpretation", phqInterpretation);
    }

    private void restoreState(Bundle savedInstanceState) {
        answers = savedInstanceState.getIntegerArrayList("answers");
        currentQuestionIndex = savedInstanceState.getInt("currentQuestionIndex");
        phqScore = savedInstanceState.getInt("phqScore", -1);
        phqInterpretation = savedInstanceState.getString("phqInterpretation", "");

        if (answers == null) {
            answers = new ArrayList<>();
        }
        if (!answers.isEmpty()) {
            questionnaireLayout.setVisibility(View.VISIBLE);
            readinessLayout.setVisibility(View.GONE);
            displayCurrentQuestion();
        } else {
            showReadinessCheck();
        }
    }

    private void initializeViews() {
        tvQuestion = findViewById(R.id.tvQuestion);
        tvQuestionnaireName = findViewById(R.id.tvQuestionnaireName);
        rgAnswers = findViewById(R.id.rgAnswers);
        btnStart = findViewById(R.id.btnStart);
        btnNext = findViewById(R.id.btnNext);
        readinessLayout = findViewById(R.id.readinessLayout);
        questionnaireLayout = findViewById(R.id.questionnaireLayout);

        if (btnStart != null) {
            btnStart.setOnClickListener(v -> startQuestionnaire());
        }
        if (btnNext != null) {
            btnNext.setOnClickListener(v -> handleNextButton());
        }
    }

    private void setupQuestions() {
        questions = new ArrayList<>();

        // Define the options for all questions
        String[] options = {
                getString(R.string.resilience_option_strongly_disagree),
                getString(R.string.resilience_option_disagree),
                getString(R.string.resilience_option_neutral),
                getString(R.string.resilience_option_agree),
                getString(R.string.resilience_option_strongly_agree)
        };

        // Add resilience questions (some are reversed scored as indicated)
        questions.add(new Question(getString(R.string.resilience_q1), options, false));
        questions.add(new Question(getString(R.string.resilience_q2), options, true));
        questions.add(new Question(getString(R.string.resilience_q3), options, false));
        questions.add(new Question(getString(R.string.resilience_q4), options, true));
        questions.add(new Question(getString(R.string.resilience_q5), options, false));
        questions.add(new Question(getString(R.string.resilience_q6), options, true));
    }

    private void showReadinessCheck() {
        readinessLayout.setVisibility(View.VISIBLE);
        questionnaireLayout.setVisibility(View.GONE);
    }

    private void startQuestionnaire() {
        readinessLayout.setVisibility(View.GONE);
        questionnaireLayout.setVisibility(View.VISIBLE);
        displayCurrentQuestion();
    }

    private void handleNextButton() {
        if (rgAnswers == null || rgAnswers.getCheckedRadioButtonId() == -1) {
            return;
        }

        int selectedButtonId = rgAnswers.getCheckedRadioButtonId();
        RadioButton selectedButton = findViewById(selectedButtonId);
        if (selectedButton != null) {
            int answerValue = rgAnswers.indexOfChild(selectedButton);

            // For reversed items, we need to reverse the score (5 becomes 1, 4 becomes 2, etc.)
            if (questions.get(currentQuestionIndex).isReversed()) {
                answerValue = 4 - answerValue; // This reverses the 0-4 scale
            }

            answers.add(answerValue);

            if (currentQuestionIndex < questions.size() - 1) {
                currentQuestionIndex++;
                displayCurrentQuestion();
                showEncouragement();
            } else {
                showCompletion();
            }
        }
    }

    private void displayCurrentQuestion() {
        if (currentQuestionIndex < questions.size()) {
            Question question = questions.get(currentQuestionIndex);
            tvQuestion.setText(question.getText());
            
            setupAnswerOptions(question);
            updateNextButton();
        }
    }

    private void setupAnswerOptions(Question question) {
        if (rgAnswers != null) {
            rgAnswers.removeAllViews();
            for (String option : question.getOptions()) {
                RadioButton rb = new RadioButton(this);
                rb.setText(option);
                rb.setTextColor(getResources().getColor(android.R.color.black));
                rb.setPadding(0, 16, 0, 16);
                rgAnswers.addView(rb);
            }
        }
    }

    private void updateNextButton() {
        if (btnNext != null) {
            btnNext.setText(currentQuestionIndex == questions.size() - 1 ?
                    R.string.finish_button : R.string.next_button);
        }
    }

    private void showEncouragement() {
        if (currentQuestionIndex > 0 && currentQuestionIndex % 3 == 0) {
            // Show simple toast encouragement instead of Billy message
            String[] messages = {
                "You're doing great!",
                "Keep going!",
                "Almost there!"
            };
            int messageIndex = (currentQuestionIndex / 3 - 1) % messages.length;
            Toast.makeText(this, messages[messageIndex], Toast.LENGTH_SHORT).show();
        }
    }

    private void showCompletion() {
        // Simple completion message
        Toast.makeText(this, "Assessment completed successfully!", Toast.LENGTH_LONG).show();
        
        // Proceed to results immediately
        proceedToResults();
    }

    private void proceedToResults() {
        // Calculate and save results
        calculateAndSaveResults();
        
        // Navigate to results
        Intent intent = new Intent(QuestionnaireActivity2.this, CombinedResultsActivity.class);
        intent.putExtra("PHQ_SCORE", phqScore);
        intent.putExtra("PHQ_INTERPRETATION", phqInterpretation);
        intent.putExtra("RESILIENCE_SCORE", calculateResilienceScore());
        intent.putExtra("RESILIENCE_INTERPRETATION", getResilienceInterpretation(calculateResilienceScore()));
        startActivity(intent);
        finish();
    }

    private void calculateAndSaveResults() {
        // Implementation of calculateAndSaveResults method
    }

    private int calculateResilienceScore() {
        // Implementation of calculateResilienceScore method
        return 0; // Placeholder return, actual implementation needed
    }

    private String getResilienceInterpretation(int score) {
        // Implementation of getResilienceInterpretation method
        return ""; // Placeholder return, actual implementation needed
    }
}