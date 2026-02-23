package com.example.myapplication;

import android.app.TimePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;

import java.util.Calendar;
import java.util.Locale;

public class BiomarkerActivty extends AppCompatActivity {

    private Button btnConnectBluetooth;
    private CardView cardBluetoothConnection;

    // Sampling time UI
    private View layoutSampleTime;
    private TextView tvSampleTimeValue;

    private int selectedHour = -1;
    private int selectedMinute = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_biomarker_activty);

        // Set up toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        // Initialize UI components
        btnConnectBluetooth = findViewById(R.id.btnConnectBluetooth);
        cardBluetoothConnection = findViewById(R.id.cardBluetoothConnection);

        layoutSampleTime = findViewById(R.id.layoutSampleTime);
        tvSampleTimeValue = findViewById(R.id.tvSampleTimeValue);

        // Tap row or value to open time picker dialog
        View.OnClickListener timeClickListener = v -> openTimePicker();
        if (layoutSampleTime != null) {
            layoutSampleTime.setOnClickListener(timeClickListener);
        }
        if (tvSampleTimeValue != null) {
            tvSampleTimeValue.setOnClickListener(timeClickListener);
        }

        // Set up click listeners for connect
        setupClickListeners();
    }

    private void openTimePicker() {
        Calendar now = Calendar.getInstance();
        int hour = (selectedHour >= 0) ? selectedHour : now.get(Calendar.HOUR_OF_DAY);
        int minute = selectedMinute;

        TimePickerDialog dialog = new TimePickerDialog(
                this,
                (view, hourOfDay, minuteOfHour) -> {
                    selectedHour = hourOfDay;
                    selectedMinute = minuteOfHour;
                    if (tvSampleTimeValue != null) {
                        String text = String.format(Locale.getDefault(),
                                "%02d:%02d", hourOfDay, minuteOfHour);
                        tvSampleTimeValue.setText(text);
                    }
                },
                hour,
                minute,
                true   // 24‑hour mode
        );
        dialog.show();
    }

    private void setupClickListeners() {
        // Same behavior for button and whole card
        View.OnClickListener connectListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                // Get role from the intent that opened BiomarkerActivty
                String role = getIntent().getStringExtra("user_role");

                // For patient mode → use their own email
                // For care provider mode → this MUST be the selected patient email
                String currentUserEmail;
                if ("CARE_PROVIDER".equals(role)) {
                    // Patient email must be sent into BiomarkerActivty
                    currentUserEmail = getIntent().getStringExtra("patient_email");
                } else {
                    // Patient mode (existing behavior)
                    currentUserEmail = DatabaseHelper
                            .getInstance(BiomarkerActivty.this)
                            .getCurrentUserEmail();
                }

                if (currentUserEmail == null || currentUserEmail.isEmpty()) {
                    Toast.makeText(BiomarkerActivty.this,
                            "Missing patient email", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Build sample timestamp: today + selected time
                if (selectedHour < 0) {
                    Toast.makeText(BiomarkerActivty.this,
                            "Please select sample time before proceeding",
                            Toast.LENGTH_SHORT).show();
                    return;
                }

                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(System.currentTimeMillis());
                cal.set(Calendar.HOUR_OF_DAY, selectedHour);
                cal.set(Calendar.MINUTE, selectedMinute);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);

                long sampleTimestamp = cal.getTimeInMillis();

                // Navigate to Bluetooth connection page, passing patient email + sample time
                Intent intent = new Intent(BiomarkerActivty.this, OutputBluetooth.class);
                intent.putExtra("user_email", currentUserEmail);    // patient in CP mode
                intent.putExtra("sample_timestamp", sampleTimestamp);
                intent.putExtra("user_role", role);

                startActivity(intent);
            }
        };

        btnConnectBluetooth.setOnClickListener(connectListener);
        cardBluetoothConnection.setOnClickListener(connectListener);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            // Handle back button in app bar
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
