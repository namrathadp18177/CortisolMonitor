package com.example.myapplication;

public class UserData {
    private int id;
    private String email;
    private String birthDate;
    private int age;
    private String sex;
    private String race;
    private String relationshipStatus;
    private float weightKg;
    private float heightM;
    private float bmi;

    // Default constructor
    public UserData() {
    }

    // Constructor with all fields
    public UserData(int id, String email, String birthDate, int age, String sex,
                    String race, String relationshipStatus, float weightKg, float heightM, float bmi) {
        this.id = id;
        this.email = email;
        this.birthDate = birthDate;
        this.age = age;
        this.sex = sex;
        this.race = race;
        this.relationshipStatus = relationshipStatus;
        this.weightKg = weightKg;
        this.heightM = heightM;
        this.bmi = bmi;
    }

    // Getters and Setters
    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(String birthDate) {
        this.birthDate = birthDate;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public String getSex() {
        return sex;
    }

    public void setSex(String sex) {
        this.sex = sex;
    }

    public String getRace() {
        return race;
    }

    public void setRace(String race) {
        this.race = race;
    }

    public String getRelationshipStatus() {
        return relationshipStatus;
    }

    public void setRelationshipStatus(String relationshipStatus) {
        this.relationshipStatus = relationshipStatus;
    }

    public float getWeightKg() {
        return weightKg;
    }

    public void setWeightKg(float weightKg) {
        this.weightKg = weightKg;
    }

    public float getHeightM() {
        return heightM;
    }

    public void setHeightM(float heightM) {
        this.heightM = heightM;
    }

    public float getBmi() {
        return bmi;
    }

    public void setBmi(float bmi) {
        this.bmi = bmi;
    }

    // Helper methods for unit conversions
    public float getWeightLbs() {
        return weightKg * 2.20462f;
    }

    public int getHeightFeet() {
        float totalInches = heightM * 39.3701f;
        return (int) (totalInches / 12);
    }

    public int getHeightInches() {
        float totalInches = heightM * 39.3701f;
        return (int) (totalInches % 12);
    }
}