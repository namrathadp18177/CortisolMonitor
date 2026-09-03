package com.example.myapplication;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class ActivateAccountActivity extends AppCompatActivity {

    private static final String TAG = "ActivateAccountActivity";
    private UserDatabaseHelper db;
    private String email;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_activate_account);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Activate Account");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        db = UserDatabaseHelper.getInstance(this);

        TextView tvEmail = findViewById(R.id.tvEmail);
        EditText etPassword = findViewById(R.id.etPassword);
        EditText etConfirmPassword = findViewById(R.id.etConfirmPassword);
        Button btnActivate = findViewById(R.id.btnActivate);

        email = getIntent().getStringExtra("email");
        // Always normalise to lowercase so DB lookups match consistently
        if (email != null) email = email.trim().toLowerCase();
        tvEmail.setText(email != null ? email : "N/A");

        btnActivate.setOnClickListener(v -> {
            String pass = etPassword.getText().toString().trim();
            String confirm = etConfirmPassword.getText().toString().trim();

            if (pass.isEmpty() || confirm.isEmpty()) {
                Toast.makeText(this, "Please enter password in both fields.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!pass.equals(confirm)) {
                Toast.makeText(this, "Passwords do not match.", Toast.LENGTH_SHORT).show();
                return;
            }

            boolean updated = db.updateUserPassword(email, pass);
            if (updated) {
                UserDatabaseHelper.setCurrentUserEmail(email);
                DatabaseHelper.setCurrentUserEmail(email);

                // Persist current user session
                SharedPreferences sessionPrefs = getSharedPreferences("session", MODE_PRIVATE);
                sessionPrefs.edit()
                        .putString("current_user_email", email)
                        .putString("user_role", "PATIENT")
                        .apply();

                // If Firebase already created an account during the registration
                // attempt, update the Firebase password to match what the user
                // just chose in this activation screen.
                FirebaseUser fbUser = FirebaseAuth.getInstance().getCurrentUser();
                if (fbUser != null) {
                    fbUser.updatePassword(pass)
                            .addOnSuccessListener(unused ->
                                    Log.d(TAG, "Firebase password updated"))
                            .addOnFailureListener(e ->
                                    Log.w(TAG, "Firebase password update failed: " + e.getMessage()));
                }

                Toast.makeText(this, "Account activated.", Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(this, PatientMainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
            } else {
                Toast.makeText(this, "Could not activate account.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
