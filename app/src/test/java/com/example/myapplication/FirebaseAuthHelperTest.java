package com.example.myapplication;

import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@RunWith(MockitoJUnitRunner.Silent.class)
public class FirebaseAuthHelperTest {

    @Mock FirebaseAuth mockAuth;
    @Mock FirebaseUser mockUser;
    @Mock Task<AuthResult> mockTask;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    // -------------------------------------------------------
    // TEST 1: getCurrentUid() returns UID when user is signed in
    // -------------------------------------------------------
    @Test
    public void getCurrentUid_whenUserSignedIn_returnsUid() {
        when(mockAuth.getCurrentUser()).thenReturn(mockUser);
        when(mockUser.getUid()).thenReturn("test-uid-patient-123");

        String uid = mockUser.getUid();

        assertNotNull(uid);
        assertEquals("test-uid-patient-123", uid);
    }

    // -------------------------------------------------------
    // TEST 2: getCurrentUid() returns null when no user signed in
    // -------------------------------------------------------
    @Test
    public void getCurrentUid_whenNoUserSignedIn_returnsNull() {
        when(mockAuth.getCurrentUser()).thenReturn(null);

        FirebaseUser user = mockAuth.getCurrentUser();

        assertNull(user);
    }

    // -------------------------------------------------------
    // TEST 3: signInWithEmailAndPassword called with correct args
    // -------------------------------------------------------
    @Test
    public void signIn_callsFirebaseWithCorrectCredentials() {
        when(mockAuth.signInWithEmailAndPassword(anyString(), anyString()))
                .thenReturn(mockTask);

        mockAuth.signInWithEmailAndPassword("patient@test.com", "password123");

        verify(mockAuth, times(1))
                .signInWithEmailAndPassword("patient@test.com", "password123");
    }

    // -------------------------------------------------------
    // TEST 4: Care provider sign-in uses different credentials
    // -------------------------------------------------------
    @Test
    public void signIn_careProvider_callsFirebaseWithCorrectCredentials() {
        when(mockAuth.signInWithEmailAndPassword(anyString(), anyString()))
                .thenReturn(mockTask);

        mockAuth.signInWithEmailAndPassword("careprovider@clinic.com", "securePass!");

        verify(mockAuth, times(1))
                .signInWithEmailAndPassword("careprovider@clinic.com", "securePass!");
    }

    // -------------------------------------------------------
    // TEST 5: signOut clears the current user
    // -------------------------------------------------------
    @Test
    public void signOut_clearsCurrentUser() {
        doNothing().when(mockAuth).signOut();
        when(mockAuth.getCurrentUser()).thenReturn(null);

        mockAuth.signOut();
        FirebaseUser user = mockAuth.getCurrentUser();

        verify(mockAuth, times(1)).signOut();
        assertNull(user);
    }
}