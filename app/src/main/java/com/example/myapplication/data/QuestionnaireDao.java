package com.example.myapplication.data;

import androidx.lifecycle.LiveData;
import java.util.List;

public interface QuestionnaireDao {
    long insertResponse(QuestionnaireResponse response);
    
    void insertResponses(List<QuestionnaireResponse> responses);
    
    List<QuestionnaireResponse> getAllResponses();
    
    int deleteAllResponses();
} 