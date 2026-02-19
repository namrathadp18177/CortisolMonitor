package com.example.myapplication;

import android.util.Log;

public class QuestionnaireSummary {
    private static final String TAG = "QuestionnaireSummary";

    private int id;
    private String userEmail;

    // Section scores from mental health screener
    private int scoreA;
    private int scoreB;
    private int scoreC;
    private int scoreD;
    private int scoreE;
    private int scoreF;
    private int totalScore;

    // Individual questionnaire scores
    private int phq9Score = -1;  // Default to -1 (not filled)
    private int bdiScore = -1;   // Default to -1 (not filled)
    private int gad7Score = -1;  // Default to -1 (not filled)
    private double brsScore = -1.0; // Default to -1 (not filled)

    // Filled status for each questionnaire (used internally)
    private boolean phq9Filled = false;
    private boolean bdiFilled = false;
    private boolean gad7Filled = false;
    private boolean brsFilled = false;

    // Triggered flags (0 = not triggered, 1 = triggered)
    private int phq9Triggered = 0;
    private int bdiTriggered = 0;
    private int gad7Triggered = 0;
    private int brsTriggered = 0;

    // Constructor
    public QuestionnaireSummary() {
        // Default constructor
    }

    // Getters and setters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getUserEmail() { return userEmail; }
    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }

    // Section scores getters/setters
    public int getScoreA() { return scoreA; }
    public void setScoreA(int scoreA) { this.scoreA = scoreA; }

    public int getScoreB() { return scoreB; }
    public void setScoreB(int scoreB) { this.scoreB = scoreB; }

    public int getScoreC() { return scoreC; }
    public void setScoreC(int scoreC) { this.scoreC = scoreC; }

    public int getScoreD() { return scoreD; }
    public void setScoreD(int scoreD) { this.scoreD = scoreD; }

    public int getScoreE() { return scoreE; }
    public void setScoreE(int scoreE) { this.scoreE = scoreE; }

    public int getScoreF() { return scoreF; }
    public void setScoreF(int scoreF) { this.scoreF = scoreF; }

    public int getTotalScore() { return totalScore; }
    public void setTotalScore(int totalScore) { this.totalScore = totalScore; }

    // Questionnaire scores getters/setters
    public int getPhq9Score() { return phq9Filled ? phq9Score : -1; }
    public void setPhq9Score(int phq9Score) {
        this.phq9Score = phq9Score;
        this.phq9Filled = true;
    }

    public int getBdiScore() { return bdiFilled ? bdiScore : -1; }
    public void setBdiScore(int bdiScore) {
        this.bdiScore = bdiScore;
        this.bdiFilled = true;
    }

    public int getGad7Score() { return gad7Filled ? gad7Score : -1; }
    public void setGad7Score(int gad7Score) {
        this.gad7Score = gad7Score;
        this.gad7Filled = true;
    }

    public double getBrsScore() { return brsFilled ? brsScore : -1.0; }
    public void setBrsScore(double brsScore) {
        this.brsScore = brsScore;
        this.brsFilled = true;
    }

    // Filled status getters/setters
    public boolean isPhq9Filled() { return phq9Filled; }
    public void setPhq9Filled(boolean phq9Filled) {
        this.phq9Filled = phq9Filled;
        if (!phq9Filled) {
            this.phq9Score = -1; // Reset to default if marked as unfilled
        }
    }

    public boolean isBdiFilled() { return bdiFilled; }
    public void setBdiFilled(boolean bdiFilled) {
        this.bdiFilled = bdiFilled;
        if (!bdiFilled) {
            this.bdiScore = -1; // Reset to default if marked as unfilled
        }
    }

    public boolean isGad7Filled() { return gad7Filled; }
    public void setGad7Filled(boolean gad7Filled) {
        this.gad7Filled = gad7Filled;
        if (!gad7Filled) {
            this.gad7Score = -1; // Reset to default if marked as unfilled
        }
    }

    public boolean isBrsFilled() { return brsFilled; }
    public void setBrsFilled(boolean brsFilled) {
        this.brsFilled = brsFilled;
        if (!brsFilled) {
            this.brsScore = -1.0; // Reset to default if marked as unfilled
        }
    }

    // Triggered flags getters/setters
    public int getPhq9Triggered() { return phq9Triggered; }
    public void setPhq9Triggered(int phq9Triggered) {
        this.phq9Triggered = phq9Triggered > 0 ? 1 : 0;  // Ensure it's always 0 or 1
    }

    public int getBdiTriggered() { return bdiTriggered; }
    public void setBdiTriggered(int bdiTriggered) {
        this.bdiTriggered = bdiTriggered > 0 ? 1 : 0;  // Ensure it's always 0 or 1
    }

    public int getGad7Triggered() { return gad7Triggered; }
    public void setGad7Triggered(int gad7Triggered) {
        this.gad7Triggered = gad7Triggered > 0 ? 1 : 0;  // Ensure it's always 0 or 1
    }

    public int getBrsTriggered() { return brsTriggered; }
    public void setBrsTriggered(int brsTriggered) {
        this.brsTriggered = brsTriggered > 0 ? 1 : 0;  // Ensure it's always 0 or 1
    }
}