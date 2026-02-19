package com.example.myapplication;

public class PatientHistoryRow {
    public String name;          // from cp_patient_info if available
    public String email;         // from users
    public float heightM;
    public float weightKg;
    public float bmi;
    public double latestCortisol; // from biomarker max_value, can be -1 if none

    public PatientHistoryRow(String name, String email,
                             float heightM, float weightKg, float bmi,
                             double latestCortisol) {
        this.name = name;
        this.email = email;
        this.heightM = heightM;
        this.weightKg = weightKg;
        this.bmi = bmi;
        this.latestCortisol = latestCortisol;
    }
}
