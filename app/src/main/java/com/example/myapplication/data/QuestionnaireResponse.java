package com.example.myapplication.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import com.example.myapplication.DatabaseHelper;

@Entity(tableName = "questionnaire_responses")
public class QuestionnaireResponse {
    @PrimaryKey(autoGenerate = true)
    private long id;
    private String section;
    private String questionText;
    private int answer;
    private long timestamp;
    private String questionnaireType;
    private String userEmail;

    public QuestionnaireResponse(long id, String section, String questionText, int answer) {
        this.id = id;
        this.section = section;
        this.questionText = questionText;
        this.answer = answer;
        this.timestamp = System.currentTimeMillis();
        this.questionnaireType = "MENTAL_HEALTH_SCREENER"; // Default value
        this.userEmail = DatabaseHelper.getCurrentUserEmail(); // Get current user email
    }

    // Getters and Setters
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }
    public String getQuestionText() { return questionText; }
    public void setQuestionText(String questionText) { this.questionText = questionText; }
    public int getAnswer() { return answer; }
    public void setAnswer(int answer) { this.answer = answer; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public String getQuestionnaireType() { return questionnaireType; }
    public void setQuestionnaireType(String questionnaireType) { this.questionnaireType = questionnaireType; }
    public String getUserEmail() { return userEmail; }
    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }
} 