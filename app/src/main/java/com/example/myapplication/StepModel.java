package com.example.myapplication;

import java.util.List;

public class StepModel {
    public String stepLabel;
    public String title;
    public String description;
    public List<String> bulletPoints;
    public int imageResId;
    public boolean hasTimer;
    public boolean isOptionalStorage;
    public long timerDurationMs;
    public boolean showMotivation;

    public StepModel(String stepLabel, String title, String description,
                     List<String> bulletPoints, int imageResId,
                     boolean hasTimer, boolean isOptionalStorage) {
        this.stepLabel = stepLabel;
        this.title = title;
        this.description = description;
        this.bulletPoints = bulletPoints;
        this.imageResId = imageResId;
        this.hasTimer = hasTimer;
        this.isOptionalStorage = isOptionalStorage;
        this.timerDurationMs = 5 * 60 * 1000; // default 5 minutes
        this.showMotivation = true; // default show motivation
    }
}
