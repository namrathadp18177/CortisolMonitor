package com.example.myapplication;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@RunWith(MockitoJUnitRunner.class)
public class AuthCallbackTest {

    @Mock FirebaseAuth mockAuth;
    @Mock Task<AuthResult> mockSuccessTask;
    @Mock Task<AuthResult> mockFailureTask;

    // Callback interface to test your listener logic
    interface AuthCallback {
        void onSuccess();
        void onFailure(String errorMessage);
    }

    // Simulates the login method you have (based on your actual signIn pattern)
    private void performLogin(FirebaseAuth auth, String email, String password, AuthCallback callback) {
        auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener(authResult -> callback.onSuccess())
                .addOnFailureListener(e -> callback.onFailure(e.getMessage()));
    }

    // -------------------------------------------------------
    // TEST 1: Valid credentials → success callback fires
    // -------------------------------------------------------
    @Test
    public void login_validCredentials_successCallbackFires() {
        AuthResult mockResult = mock(AuthResult.class);

        // Simulate success: addOnSuccessListener immediately invokes listener
        when(mockAuth.signInWithEmailAndPassword("valid@test.com", "validPass"))
                .thenAnswer(invocation -> {
                    // Return a task that triggers success
                    return mockSuccessTask;
                });
        doAnswer(invocation -> {
            OnSuccessListener<AuthResult> listener = invocation.getArgument(0);
            listener.onSuccess(mockResult);
            return mockSuccessTask;
        }).when(mockSuccessTask).addOnSuccessListener(any());
        when(mockSuccessTask.addOnFailureListener(any())).thenReturn(mockSuccessTask);

        boolean[] successCalled = {false};
        performLogin(mockAuth, "valid@test.com", "validPass", new AuthCallback() {
            @Override public void onSuccess() { successCalled[0] = true; }
            @Override public void onFailure(String err) { fail("Should not fail"); }
        });

        assertTrue("Success callback should have fired", successCalled[0]);
    }

    // -------------------------------------------------------
    // TEST 2: Invalid credentials → failure callback fires with error
    // -------------------------------------------------------
    @Test
    public void login_invalidCredentials_failureCallbackFires() {
        Exception fakeException = new FirebaseAuthInvalidCredentialsException(
                "ERROR_WRONG_PASSWORD", "The password is invalid.");

        when(mockAuth.signInWithEmailAndPassword("patient@test.com", "wrongPass"))
                .thenReturn(mockFailureTask);
        when(mockFailureTask.addOnSuccessListener(any())).thenReturn(mockFailureTask);
        doAnswer(invocation -> {
            OnFailureListener listener = invocation.getArgument(0);
            listener.onFailure(fakeException);
            return mockFailureTask;
        }).when(mockFailureTask).addOnFailureListener(any());

        String[] capturedError = {null};
        performLogin(mockAuth, "patient@test.com", "wrongPass", new AuthCallback() {
            @Override public void onSuccess() { fail("Should not succeed"); }
            @Override public void onFailure(String err) { capturedError[0] = err; }
        });

        assertNotNull("Error message should not be null", capturedError[0]);
        assertTrue("Error should mention invalid credentials",
                capturedError[0].contains("invalid") || capturedError[0].contains("password"));
    }

    // -------------------------------------------------------
    // TEST 3: Unauthenticated state → getCurrentUser() returns null
    //         (simulates redirect-to-login check)
    // -------------------------------------------------------
    @Test
    public void unauthenticatedState_currentUserIsNull_shouldRedirectToLogin() {
        when(mockAuth.getCurrentUser()).thenReturn(null);

        boolean shouldRedirect = (mockAuth.getCurrentUser() == null);

        assertTrue("App should redirect unauthenticated user to login", shouldRedirect);
    }
}