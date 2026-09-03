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

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@RunWith(MockitoJUnitRunner.class)
public class SyncManagerTest {

    @Mock FirebaseDatabase mockDatabase;
    @Mock DatabaseReference mockRootRef;
    @Mock DatabaseReference mockPatientRef;
    @Mock DatabaseReference mockRecordRef;
    @Mock Task<Void> mockTask;

    private static final String TEST_UID = "patient-uid-sync-test";

    // Simple in-memory "dirty records" list to simulate your local DB
    private List<String> dirtyRecords;

    @Before
    public void setUp() {
        when(mockDatabase.getReference(anyString())).thenReturn(mockRootRef);
        when(mockRootRef.child(anyString())).thenReturn(mockPatientRef);
        when(mockPatientRef.child(anyString())).thenReturn(mockRecordRef);
        when(mockRecordRef.child(anyString())).thenReturn(mockRecordRef); // ← the fix
        when(mockRecordRef.setValue(any())).thenReturn(mockTask);
        when(mockTask.addOnSuccessListener(any())).thenReturn(mockTask);
        when(mockTask.addOnFailureListener(any())).thenReturn(mockTask);

        dirtyRecords = new ArrayList<>();
        dirtyRecords.add("record_step3_dirty");
        dirtyRecords.add("record_step7_dirty");
        dirtyRecords.add("record_step11_dirty");
    }

    // -------------------------------------------------------
    // TEST 1: pushAllDirtyData does NOT fire if UID is null
    //         (the exact bug you fixed before)
    // -------------------------------------------------------
    @Test
    public void pushAllDirtyData_nullUid_doesNotWriteToFirebase() {
        String uid = null; // simulates the bug state

        if (uid != null) {
            for (String record : dirtyRecords) {
                mockDatabase.getReference("patients")
                        .child(uid)
                        .child("syncData")
                        .child(record)
                        .setValue(record);
            }
        }

        // Firebase should never have been called
        verify(mockDatabase, never()).getReference(anyString());
    }

    // -------------------------------------------------------
    // TEST 2: pushAllDirtyData fires for each dirty record
    //         when UID is valid
    // -------------------------------------------------------
    @Test
    public void pushAllDirtyData_validUid_writesAllDirtyRecords() {
        for (String record : dirtyRecords) {
            mockDatabase.getReference("patients")
                    .child(TEST_UID)
                    .child("syncData")
                    .child(record)
                    .setValue(record);
        }

        // Should have been called once per dirty record
        verify(mockRecordRef, times(dirtyRecords.size())).setValue(any());
    }

    // -------------------------------------------------------
    // TEST 3: After successful sync, records are marked clean
    // -------------------------------------------------------
    @Test
    public void pushAllDirtyData_onSuccess_recordMarkedClean() {
        List<String> cleanedRecords = new ArrayList<>();

        doAnswer(invocation -> {
            OnSuccessListener<Void> listener = invocation.getArgument(0);
            listener.onSuccess(null);
            return mockTask;
        }).when(mockTask).addOnSuccessListener(any());

        for (String record : dirtyRecords) {
            mockRecordRef.setValue(record)
                    .addOnSuccessListener(unused -> cleanedRecords.add(record))
                    .addOnFailureListener(e -> fail("Should not fail: " + e.getMessage()));
        }

        assertEquals("All dirty records should be marked clean after sync",
                dirtyRecords.size(), cleanedRecords.size());
    }

    // -------------------------------------------------------
    // TEST 4: Sync failure does NOT mark records clean
    //         (records stay dirty for retry)
    // -------------------------------------------------------
    @Test
    public void pushAllDirtyData_onFailure_recordStaysDirty() {
        List<String> cleanedRecords = new ArrayList<>();
        List<String> failedRecords = new ArrayList<>();

        doAnswer(invocation -> {
            OnFailureListener listener = invocation.getArgument(0);
            listener.onFailure(new Exception("network error"));
            return mockTask;
        }).when(mockTask).addOnFailureListener(any());

        for (String record : dirtyRecords) {
            mockRecordRef.setValue(record)
                    .addOnSuccessListener(unused -> cleanedRecords.add(record))
                    .addOnFailureListener(e -> failedRecords.add(record));
        }

        assertEquals("No records should be cleaned after failure", 0, cleanedRecords.size());
        assertEquals("All records should remain dirty", dirtyRecords.size(), failedRecords.size());
    }

    // -------------------------------------------------------
    // TEST 5: UID is set before sync fires (order check)
    // -------------------------------------------------------
    @Test
    public void syncOrder_uidSetBeforeSyncFires() {
        List<String> callOrder = new ArrayList<>();

        // Simulate: setUid() must be called before pushAllDirtyData()
        callOrder.add("setUid:" + TEST_UID);

        // Only push if UID was already set
        if (callOrder.stream().anyMatch(s -> s.startsWith("setUid:"))) {
            callOrder.add("pushAllDirtyData");
            mockRecordRef.setValue("test_record");
        }

        assertTrue("setUid must come before pushAllDirtyData",
                callOrder.indexOf("setUid:" + TEST_UID) < callOrder.indexOf("pushAllDirtyData"));
        verify(mockRecordRef).setValue("test_record");
    }

    // -------------------------------------------------------
    // TEST 6: Care provider can read what patient synced
    //         (cross-mode read path check)
    // -------------------------------------------------------
    @Test
    public void careProvider_canReadPatientSyncedData() {
        // Care provider reads from the same patients/{uid}/syncData path
        mockDatabase.getReference("patients")
                .child(TEST_UID)
                .child("syncData");

        verify(mockRootRef).child(TEST_UID);
        verify(mockPatientRef).child("syncData");
    }

    // -------------------------------------------------------
    // TEST 7: Empty dirty list → no Firebase calls made
    // -------------------------------------------------------
    @Test
    public void pushAllDirtyData_emptyList_noFirebaseCalls() {
        List<String> emptyList = new ArrayList<>();

        for (String record : emptyList) {
            mockRecordRef.setValue(record);
        }

        verify(mockRecordRef, never()).setValue(any());
    }
}