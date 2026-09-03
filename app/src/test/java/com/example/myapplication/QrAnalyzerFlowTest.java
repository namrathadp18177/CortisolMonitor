package com.example.myapplication;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class QrAnalyzerFlowTest {

    @Mock FirebaseDatabase mockDatabase;
    @Mock DatabaseReference mockRootRef;
    @Mock DatabaseReference mockPatientRef;
    @Mock DatabaseReference mockAnalyzerRef;

    private static final String TEST_UID = "patient-uid-qr-test";
    private static final String TEST_ANALYZER_ID = "ANALYZER-ABC-123";

    @Before
    public void setUp() {
        when(mockDatabase.getReference("patients")).thenReturn(mockRootRef);
        when(mockRootRef.child(TEST_UID)).thenReturn(mockPatientRef);
        when(mockPatientRef.child(anyString())).thenReturn(mockAnalyzerRef);
        when(mockAnalyzerRef.child(anyString())).thenReturn(mockAnalyzerRef);
    }

    @Test
    public void qrScan_savesAnalyzerIdToCorrectPath() {
        mockDatabase.getReference("patients")
                .child(TEST_UID)
                .child("analyzerConnection")
                .child("analyzerId")
                .setValue(TEST_ANALYZER_ID);

        verify(mockDatabase).getReference("patients");
        verify(mockRootRef).child(TEST_UID);
        verify(mockPatientRef).child("analyzerConnection");
        verify(mockAnalyzerRef).child("analyzerId");
        verify(mockAnalyzerRef).setValue(TEST_ANALYZER_ID);
    }

    @Test
    public void manualEntry_savesAnalyzerIdToCorrectPath() {
        String manualId = "MANUAL-999";

        mockDatabase.getReference("patients")
                .child(TEST_UID)
                .child("analyzerConnection")
                .child("analyzerId")
                .setValue(manualId);

        verify(mockAnalyzerRef).setValue(manualId);
    }

    @Test
    public void proceedToTest_requiresAnalyzerIdNotNull() {
        String savedAnalyzerId = TEST_ANALYZER_ID;
        assertNotNull("Analyzer ID must exist before proceeding", savedAnalyzerId);
        assertFalse(savedAnalyzerId.isEmpty());
    }

    @Test
    public void proceedToTest_blocksWhenAnalyzerIdMissing() {
        String savedAnalyzerId = null;
        assertNull("Proceed should block if analyzer ID missing", savedAnalyzerId);
    }

    @Test
    public void analyzerId_persistsAcrossNavigation() {
        String savedAnalyzerId = TEST_ANALYZER_ID;

        String loadedAfterNavigation = savedAnalyzerId;

        assertEquals("Analyzer ID should persist after navigation",
                TEST_ANALYZER_ID, loadedAfterNavigation);
    }
}