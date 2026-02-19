package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private EditText etSamplesPerSec, etPointsToAvg, etDurationOfExp, etMovingAvgValue;
    private CheckBox checkboxPort0, checkboxPort1, checkboxPort2, checkboxPort3;

    private int samplesPerSecond, pointsToAverage, movingAvgValue;
    private double durationOfExp;
    private boolean[] analogPort = new boolean[4];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // Initialize UI elements
        etSamplesPerSec = findViewById(R.id.etSamplesPerSec);
        etPointsToAvg = findViewById(R.id.etPointsToAvg);
        etDurationOfExp = findViewById(R.id.etDurationOfExp);
        etMovingAvgValue = findViewById(R.id.etMovingAvgValue);
        checkboxPort0 = findViewById(R.id.checkboxPort0);
        checkboxPort1 = findViewById(R.id.checkboxPort1);
        checkboxPort2 = findViewById(R.id.checkboxPort2);
        checkboxPort3 = findViewById(R.id.checkboxPort3);

        // Get settings from the intent
        Intent intent = getIntent();
        samplesPerSecond = intent.getIntExtra("sps", 10);
        pointsToAverage = intent.getIntExtra("avgpoints", 10);
        durationOfExp = intent.getDoubleExtra("duration", 600.0);
        analogPort = (boolean[]) intent.getSerializableExtra("analog_port");
        movingAvgValue = intent.getIntExtra("moving_avg_value", 4);

        // Set UI values from settings
        etSamplesPerSec.setText(String.valueOf(samplesPerSecond));
        etPointsToAvg.setText(String.valueOf(pointsToAverage));
        etDurationOfExp.setText(String.valueOf(durationOfExp));
        etMovingAvgValue.setText(String.valueOf(movingAvgValue));

        checkboxPort0.setChecked(analogPort[0]);
        checkboxPort1.setChecked(analogPort[1]);
        checkboxPort2.setChecked(analogPort[2]);
        checkboxPort3.setChecked(analogPort[3]);
    }

    public void onClickSave(View v) {
        try {
            // Get values from UI
            samplesPerSecond = Integer.parseInt(etSamplesPerSec.getText().toString());
            pointsToAverage = Integer.parseInt(etPointsToAvg.getText().toString());
            durationOfExp = Double.parseDouble(etDurationOfExp.getText().toString());
            movingAvgValue = Integer.parseInt(etMovingAvgValue.getText().toString());

            analogPort[0] = checkboxPort0.isChecked();
            analogPort[1] = checkboxPort1.isChecked();
            analogPort[2] = checkboxPort2.isChecked();
            analogPort[3] = checkboxPort3.isChecked();

            // Validate input
            if (samplesPerSecond <= 0 || pointsToAverage <= 0 || durationOfExp <= 0) {
                Toast.makeText(this, "All values must be positive", Toast.LENGTH_SHORT).show();
                return;
            }

            // Return values to calling activity
            Intent returnIntent = new Intent();
            returnIntent.putExtra("sps", samplesPerSecond);
            returnIntent.putExtra("avgpoints", pointsToAverage);
            returnIntent.putExtra("duration", durationOfExp);
            returnIntent.putExtra("analog_port", analogPort);
            returnIntent.putExtra("moving_avg_value", movingAvgValue);
            setResult(RESULT_OK, returnIntent);
            finish();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Please enter valid numbers", Toast.LENGTH_SHORT).show();
        }
    }

    public void onClickCancel(View v) {
        setResult(RESULT_CANCELED);
        finish();
    }
}