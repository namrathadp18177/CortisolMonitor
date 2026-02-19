package com.example.myapplication.model;

public class Question {
    private final int id;
    private final String text;
    private final String section;

    public Question(int id, String text, String section) {
        this.id = id;
        this.text = text;
        this.section = section;
    }

    public int getId() { return id; }
    public String getText() { return text; }
    public String getSection() { return section; }
} 