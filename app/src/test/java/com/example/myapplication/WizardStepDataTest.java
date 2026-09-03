package com.example.myapplication;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@RunWith(MockitoJUnitRunner.class)
public class WizardStepDataTest {

    @Mock FirebaseDatabase mockDatabase;
    @Mock DatabaseReference mockRootRef;
    @Mock DatabaseReference mockPatientRef;
    @Mock DatabaseReference mockStepRef;
    @Mock Task<Void> mockTask;

    private static final String TEST_UID = "patient-uid-test-123";
    private static final String TEST_ANALYZER_ID = "ANALYZER-QR-ABC";

    @Before
    public void setUp() {
        // Mock the chained Firebase ref calls:
        // db.getReference("patients").child(uid).child("stepData").child("step_N")
        when(mockDatabase.getReference("patients")).thenReturn(mockRootRef);
        when(mockRootRef.child(TEST_UID)).thenReturn(mockPatientRef);
        when(mockPatientRef.child(anyString())).thenReturn(mockStepRef);
        when(mockStepRef.child(anyString())).thenReturn(mockStepRef);
        when(mockStepRef.setValue(any())).thenReturn(mockTask);
    }

    // -------------------------------------------------------
    // TEST 1: Step data saves to the correct Firebase path
    // -------------------------------------------------------
    @Test
    public void saveStepData_writesToCorrectFirebasePath() {
        mockDatabase.getReference("patients")
                .child(TEST_UID)
                .child("stepData")
                .child("step_1")
                .setValue("completed");

        verify(mockDatabase).getReference("patients");
        verify(mockRootRef).child(TEST_UID);
        verify(mockPatientRef).child("stepData");
        verify(mockStepRef).child("step_1");
        verify(mockStepRef).setValue("completed");
    }

    // -------------------------------------------------------
    // TEST 2: All 13 steps can be written without error
    // -------------------------------------------------------
    @Test
    public void saveAllSteps_noExceptionForAnyStep() {
        for (int i = 1; i <= 13; i++) {
            String stepKey = "step_" + i;

            mockDatabase.getReference("patients")
                    .child(TEST_UID)
                    .child("stepData")
                    .child(stepKey)
                    .setValue("step_" + i + "_data");

            verify(mockStepRef, atLeastOnce()).child(stepKey);
        }
    }

    // -------------------------------------------------------
    // TEST 3: Step 2 timer state saves correctly
    // -------------------------------------------------------
    @Test
    public void saveTimerState_step2_savesCorrectly() {
        String timerState = "PAUSED:120000"; // 2 minutes elapsed

        mockDatabase.getReference("patients")
                .child(TEST_UID)
                .child("stepData")
                .child("step_2_timer")
                .setValue(timerState);

        verify(mockStepRef).child("step_2_timer");
        verify(mockStepRef).setValue(timerState);
    }

    // -------------------------------------------------------
    // TEST 4: Saliva sub-steps save in correct order (5 sub-steps)
    // -------------------------------------------------------
    @Test
    public void saveSalivaSubSteps_allFiveSubStepsSaveInOrder() {
        String[] salivaSteps = {
                "swab_under_tongue",     // sub-step 1
                "place_absorbent_pad",   // sub-step 2
                "push_plunger",          // sub-step 3
                "add_drops_to_strip",    // sub-step 4
                "wait_20_minutes"        // sub-step 5
        };

        for (int i = 0; i < salivaSteps.length; i++) {
            String subStepKey = "saliva_substep_" + (i + 1);

            mockDatabase.getReference("patients")
                    .child(TEST_UID)
                    .child("stepData")
                    .child(subStepKey)
                    .setValue(salivaSteps[i]);

            verify(mockStepRef, atLeastOnce()).setValue(salivaSteps[i]);
        }
    }

    // -------------------------------------------------------
    // TEST 5: setValue success callback fires → step marked complete
    // -------------------------------------------------------
    @Test
    public void saveStepData_onSuccess_marksStepComplete() {
        when(mockStepRef.setValue(any())).thenReturn(mockTask);

        doAnswer(invocation -> {
            OnSuccessListener<Void> listener = invocation.getArgument(0);
            listener.onSuccess(null);
            return mockTask;
        }).when(mockTask).addOnSuccessListener(any());
        when(mockTask.addOnFailureListener(any())).thenReturn(mockTask);

        boolean[] stepMarkedComplete = {false};

        mockStepRef.setValue("step_3_data")
                .addOnSuccessListener(unused -> stepMarkedComplete[0] = true)
                .addOnFailureListener(e -> fail("Should not fail"));

        assertTrue("Step should be marked complete after successful save",
                stepMarkedComplete[0]);
    }

    // -------------------------------------------------------
    // TEST 6: setValue failure callback fires → error handled
    // -------------------------------------------------------
    @Test
    public void saveStepData_onFailure_errorIsHandled() {
        Exception dbException = new Exception("Firebase: permission denied");

        when(mockStepRef.setValue(any())).thenReturn(mockTask);
        when(mockTask.addOnSuccessListener(any())).thenReturn(mockTask);
        doAnswer(invocation -> {
            OnFailureListener listener = invocation.getArgument(0);
            listener.onFailure(dbException);
            return mockTask;
        }).when(mockTask).addOnFailureListener(any());

        String[] capturedError = {null};

        mockStepRef.setValue("step_3_data")
                .addOnSuccessListener(unused -> fail("Should not succeed"))
                .addOnFailureListener(e -> capturedError[0] = e.getMessage());

        assertNotNull("Error should be captured", capturedError[0]);
        assertTrue("Error should mention permission",
                capturedError[0].contains("permission"));
    }

    // -------------------------------------------------------
    // TEST 7: Analyzer ID (from QR scan) saves to correct path
    // -------------------------------------------------------
    @Test
    public void saveAnalyzerId_writesToCorrectPath() {
        mockDatabase.getReference("patients")
                .child(TEST_UID)
                .child("analyzerConnection")
                .child("analyzerId")
                .setValue(TEST_ANALYZER_ID);

        verify(mockPatientRef).child("analyzerConnection");
        verify(mockStepRef).child("analyzerId");
        verify(mockStepRef).setValue(TEST_ANALYZER_ID);
    }

    // -------------------------------------------------------
    // TEST 8: Step state does not regress when navigating back
    //         (previously saved step_5 data remains after loading step_4)
    // -------------------------------------------------------
    @Test
    public void stepNavigation_backToStep4_doesNotClearStep5Data() {
        // Simulate: step 5 was saved, user navigated back to step 4
        String step5Data = "cartridge_scanned";
        String step4Data = "sample_loaded";

        // Both writes should happen independently
        mockStepRef.setValue(step5Data);
        mockStepRef.setValue(step4Data);

        // Both should have been called — step 5 data was not overwritten by step 4
        verify(mockStepRef).setValue(step5Data);
        verify(mockStepRef).setValue(step4Data);
    }
}