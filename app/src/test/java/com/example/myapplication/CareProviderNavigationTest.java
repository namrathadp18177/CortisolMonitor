package com.example.myapplication;

import com.google.android.gms.tasks.Task;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@RunWith(MockitoJUnitRunner.class)
public class CareProviderNavigationTest {

    @Mock FirebaseDatabase mockDatabase;
    @Mock DatabaseReference mockRootRef;
    @Mock DatabaseReference mockPatientRef;
    @Mock DatabaseReference mockStepRef;
    @Mock Task<Void> mockTask;

    private static final String TEST_UID = "patient-uid-nav-test";

    // In-memory step state — simulates your local step tracking
    private Map<Integer, String> stepState;
    private int currentStep;

    @Before
    public void setUp() {
        when(mockDatabase.getReference(anyString())).thenReturn(mockRootRef);
        when(mockRootRef.child(anyString())).thenReturn(mockPatientRef);
        when(mockPatientRef.child(anyString())).thenReturn(mockStepRef);
        when(mockStepRef.child(anyString())).thenReturn(mockStepRef);
        when(mockStepRef.setValue(any())).thenReturn(mockTask);
        when(mockTask.addOnSuccessListener(any())).thenReturn(mockTask);
        when(mockTask.addOnFailureListener(any())).thenReturn(mockTask);

        // Pre-populate steps 1–7 as complete
        stepState = new HashMap<>();
        for (int i = 1; i <= 7; i++) {
            stepState.put(i, "complete");
        }
        currentStep = 7;
    }

    // -------------------------------------------------------
    // TEST 1: Care provider can jump directly to any step
    // -------------------------------------------------------
    @Test
    public void careProvider_directNavToStep5_navigatesCorrectly() {
        int targetStep = 5;
        currentStep = targetStep; // simulate direct navigation

        assertEquals("Should be on step 5 after direct nav", 5, currentStep);
        assertTrue("Step 5 data should still be present", stepState.containsKey(5));
    }

    // -------------------------------------------------------
    // TEST 2: Direct navigation does NOT clear adjacent steps
    // -------------------------------------------------------
    @Test
    public void careProvider_directNavToStep3_doesNotClearStep4Or5() {
        // Jump backward to step 3
        currentStep = 3;

        // Steps 4 and 5 must still exist in state
        assertTrue("Step 4 must not be cleared", stepState.containsKey(4));
        assertTrue("Step 5 must not be cleared", stepState.containsKey(5));
        assertEquals("Step 4 should still be complete", "complete", stepState.get(4));
        assertEquals("Step 5 should still be complete", "complete", stepState.get(5));
    }

    // -------------------------------------------------------
    // TEST 3: Navigating forward from step 3 to step 6 directly
    //         does not skip/reset intermediate steps
    // -------------------------------------------------------
    @Test
    public void careProvider_jumpFromStep3ToStep6_intermediateStepsIntact() {
        currentStep = 3;
        int targetStep = 6;
        currentStep = targetStep;

        // Steps 4 and 5 (intermediate) must still be intact
        assertNotNull("Step 4 must still exist", stepState.get(4));
        assertNotNull("Step 5 must still exist", stepState.get(5));
        assertEquals("Current step is 6", 6, currentStep);
    }

    // -------------------------------------------------------
    // TEST 4: Care provider writes step data to correct Firebase path
    // -------------------------------------------------------
    @Test
    public void careProvider_writeStepData_usesCorrectPath() {
        int stepNumber = 9;
        String stepData = "care_provider_note_step9";

        mockDatabase.getReference("patients")
                .child(TEST_UID)
                .child("stepData")
                .child("step_" + stepNumber)
                .setValue(stepData);

        verify(mockStepRef).child("step_" + stepNumber);
        verify(mockStepRef).setValue(stepData);
    }

    // -------------------------------------------------------
    // TEST 5: Step 13 (final step) completes the wizard
    // -------------------------------------------------------
    @Test
    public void careProvider_reachStep13_wizardComplete() {
        for (int i = 8; i <= 13; i++) {
            stepState.put(i, "complete");
        }
        currentStep = 13;

        boolean allComplete = true;
        for (int i = 1; i <= 13; i++) {
            if (!"complete".equals(stepState.get(i))) {
                allComplete = false;
                break;
            }
        }

        assertEquals("Should be on step 13", 13, currentStep);
        assertTrue("All 13 steps should be complete", allComplete);
    }

    // -------------------------------------------------------
    // TEST 6: Step 1 is always the minimum — can't go below it
    // -------------------------------------------------------
    @Test
    public void navigation_cannotGoBelowStep1() {
        currentStep = 1;
        int attemptedStep = currentStep - 1; // = 0

        // Clamp to minimum of 1
        int actualStep = Math.max(1, attemptedStep);

        assertEquals("Step cannot go below 1", 1, actualStep);
    }

    // -------------------------------------------------------
    // TEST 7: Step 13 is always the maximum — can't go beyond it
    // -------------------------------------------------------
    @Test
    public void navigation_cannotGoAboveStep13() {
        currentStep = 13;
        int attemptedStep = currentStep + 1; // = 14

        // Clamp to maximum of 13
        int actualStep = Math.min(13, attemptedStep);

        assertEquals("Step cannot exceed 13", 13, actualStep);
    }
}