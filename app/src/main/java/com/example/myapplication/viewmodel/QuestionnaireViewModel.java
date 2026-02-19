package com.example.myapplication.viewmodel;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.myapplication.DatabaseHelper;
import com.example.myapplication.data.QuestionnaireDao;
import com.example.myapplication.data.QuestionnaireResponse;
import com.example.myapplication.model.Question;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

public class QuestionnaireViewModel extends AndroidViewModel {
    private final MutableLiveData<Integer> currentSection;
    private final MutableLiveData<Map<Integer, Integer>> responses;
    private final List<String> sections;
    private final DatabaseHelper dbHelper;

    public QuestionnaireViewModel(@NonNull Application application) {
        super(application);
        currentSection = new MutableLiveData<>(0);
        responses = new MutableLiveData<>(new HashMap<>());

        dbHelper = DatabaseHelper.getInstance(getApplication());

        sections = Arrays.asList(
            "Part A: Mood and Energy",
            "Part B: Anxiety and Worry",
            "Part C: Sleep Patterns",
            "Part D: Social Interactions",
            "Part E: Physical Symptoms",
            "Part F: Daily Functioning"
        );
    }

    public LiveData<Integer> getCurrentSection() {
        return currentSection;
    }

    public LiveData<Map<Integer, Integer>> getResponses() {
        return responses;
    }

    public String getCurrentSectionTitle() {
        Integer value = currentSection.getValue();
        return sections.get(value != null ? value : 0);
    }

    public void moveToNextSection() {
        Integer current = currentSection.getValue();
        if (current != null) {
            currentSection.setValue(current + 1);
        }
    }

    public void moveToPreviousSection() {
        Integer current = currentSection.getValue();
        if (current != null && current > 0) {
            currentSection.setValue(current - 1);
        }
    }

    public void setResponse(int questionId, int answer) {
        Map<Integer, Integer> currentResponses = responses.getValue();
        if (currentResponses != null) {
            Map<Integer, Integer> newResponses = new HashMap<>(currentResponses);
            newResponses.put(questionId, answer);
            responses.setValue(newResponses);
        }
    }

    public boolean isCurrentSectionComplete() {
        Integer currentSectionValue = currentSection.getValue();
        Map<Integer, Integer> currentResponses = responses.getValue();
        if (currentSectionValue == null || currentResponses == null) {
            return false;
        }

        List<Question> sectionQuestions = getQuestionsForSection(currentSectionValue);
        for (Question question : sectionQuestions) {
            if (!currentResponses.containsKey(question.getId())) {
                return false;
            }
        }
        return true;
    }

    public boolean isLastSection() {
        Integer current = currentSection.getValue();
        return current != null && current == sections.size() - 1;
    }

    public void submitResponses(ExecutorService executor, Runnable onComplete) {
        executor.execute(() -> {
            Map<Integer, Integer> currentResponses = responses.getValue();
            if (currentResponses != null) {
                List<QuestionnaireResponse> responseList = new ArrayList<>();
                for (Map.Entry<Integer, Integer> entry : currentResponses.entrySet()) {
                    responseList.add(new QuestionnaireResponse(
                        entry.getKey(),
                        sections.get(entry.getKey() / 4), // Assuming 4 questions per section
                        getQuestionText(entry.getKey()),
                        entry.getValue()
                    ));
                }
                dbHelper.getQuestionnaireDao().insertResponses(responseList);
                new Handler(Looper.getMainLooper()).post(onComplete);
            }
        });
    }

    // Helper method to get questions for a section
    private List<Question> getQuestionsForSection(int sectionIndex) {  
        // Implementation depends on how you store your questions
        return new ArrayList<>(); // Placeholder
    }

    private String getQuestionText(int questionId) {
        // Implementation depends on how you store your questions
        return ""; // Placeholder
    }
} 