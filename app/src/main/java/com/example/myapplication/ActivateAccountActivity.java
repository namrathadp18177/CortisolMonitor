package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class ActivateAccountActivity extends AppCompatActivity {

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
                Toast.makeText(this, "Account activated.", Toast.LENGTH_SHORT).show();

                Intent intent = new Intent(this, DashboardActivity.class);
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
