package com.example.myapplication;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.util.Random;

public class CombinedResultsActivity extends AppCompatActivity {
    private static final String TAG = "CombinedResultsActivity";
    private static final long UPDATE_INTERVAL = 2000; // Update every 2 seconds

    // UI Components
    private TextView tvCortisolValue;
    private TextView tvDheaValue;
    private TextView tvRatioValue;
    private TextView tvCortisolRange;
    private TextView tvDheaRange;
    private TextView tvRatioRange;
    private TextView tvCortisolWeight;
    private TextView tvDheaWeight;
    private TextView tvRatioWeight;
    private TextView tvTotalWeight;
    private TextView tvStressLevel;
    private TextView tvUpdateStatus;
    private Button btnSaveResults;
    private Button btnShareResults;
    private Button btnIntegratedResults;
    private Button btnRefresh;
    private Button btnToggleUpdates;
    private Button btnHome; // Added missing btnHome reference

    // Data values - renamed for clarity
    private double spiralTwoBiomarker;  // Value for spiral 2 (DHEA)
    private double spiralThreeBiomarker; // Value for spiral 3 (Cortisol)
    private int dataSize;
    private double recordedTime;
    private String username;

    // Result values
    private double cortisolValue; // Calculated from spiral 3
    private double dheaValue;     // Calculated from spiral 2
    private double cortisolToDheaRatio;

    // Stress weighting values
    private int cortisolWeight;
    private int dheaWeight;
    private int ratioWeight;
    private int totalWeight;
    private String stressLevel;

    // Handler for periodic updates
    private Handler updateHandler;
    private Runnable updateRunnable;
    private boolean isAutoUpdating = false;

    // Random for simulation
    private Random random = new Random();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_combined_results);

        try {
            // Initialize toolbar
            Toolbar toolbar = findViewById(R.id.toolbar);
            if (toolbar != null) {
                setSupportActionBar(toolbar);
            }

            // Initialize UI components
            initializeViews();

            // Initialize handler for periodic updates
            updateHandler = new Handler(Looper.getMainLooper());
            updateRunnable = this::updateBiomarkerData;

            // Get data from intent with safety checks
            Intent intent = getIntent();
            if (intent == null) {
                handleMissingIntentData();
                return;
            }

            // Get the biomarker values from the intent with default values
            spiralTwoBiomarker = Math.max(intent.getDoubleExtra("maxstr2", 1.0), 0.001);  // DHEA (spiral 2)
            spiralThreeBiomarker = Math.max(intent.getDoubleExtra("maxstr3", 1.0), 0.001); // Cortisol (spiral 3)

            // Log the input values for debugging
            Log.d(TAG, "DHEA Biomarker (from graph): " + spiralTwoBiomarker);
            Log.d(TAG, "Cortisol Biomarker (from graph): " + spiralThreeBiomarker);

            // Get other data with defaults
            username = intent.getStringExtra("username");
            if (username == null || username.isEmpty()) {
                username = "user_" + System.currentTimeMillis(); // Default username if missing
            }

            dataSize = intent.getIntExtra("data_size", 0);
            recordedTime = intent.getDoubleExtra("time", System.currentTimeMillis() / 1000.0);

            // Calculate values based on biomarker readings
            calculateValues();

            // Calculate stress weights
            calculateStressWeights();

            // Display results
            displayValues();
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate", e);
            Toast.makeText(this, "An error occurred while initializing the activity", Toast.LENGTH_SHORT).show();
            // Set default values to prevent crashes in other methods
            setDefaultValues();
            displayValues();
        }
    }

    private void setDefaultValues() {
        spiralTwoBiomarker = 1.0;
        spiralThreeBiomarker = 1.0;
        username = "default_user";
        dataSize = 0;
        recordedTime = System.currentTimeMillis() / 1000.0;
        cortisolValue = 0.0;
        dheaValue = 0.0;
        cortisolToDheaRatio = 0.0;
        cortisolWeight = 0;
        dheaWeight = 0;
        ratioWeight = 0;
        totalWeight = 0;
        stressLevel = "Unknown";
    }

    private void handleMissingIntentData() {
        Toast.makeText(this, "Missing data. Using default values.", Toast.LENGTH_SHORT).show();
        setDefaultValues();
        displayValues();
    }

    private void initializeViews() {
        try {
            // Initialize TextViews with null safety
            tvCortisolValue = findViewById(R.id.tvCortisolValue);
            tvDheaValue = findViewById(R.id.tvDheaValue);
            tvRatioValue = findViewById(R.id.tvRatioValue);
            tvCortisolRange = findViewById(R.id.tvCortisolRange);
            tvDheaRange = findViewById(R.id.tvDheaRange);
            tvRatioRange = findViewById(R.id.tvRatioRange);
            tvCortisolWeight = findViewById(R.id.tvCortisolWeight);
            tvDheaWeight = findViewById(R.id.tvDheaWeight);
            tvRatioWeight = findViewById(R.id.tvRatioWeight);
            tvTotalWeight = findViewById(R.id.tvTotalWeight);
            tvStressLevel = findViewById(R.id.tvStressLevel);
            tvUpdateStatus = findViewById(R.id.tvUpdateStatus);

            // Buttons
            btnSaveResults = findViewById(R.id.btnSaveResults);
            btnShareResults = findViewById(R.id.btnShareResults);

            btnRefresh = findViewById(R.id.btnRefresh);
            btnToggleUpdates = findViewById(R.id.btnToggleUpdates);
            btnHome = findViewById(R.id.btnHome); // Initialize the Home button

            // Set button click listeners with null checks
            if (btnSaveResults != null) {
                btnSaveResults.setOnClickListener(v -> saveResults());
            }

            if (btnShareResults != null) {
                btnShareResults.setOnClickListener(v -> shareResults());
            }

            if (btnIntegratedResults != null) {
                btnIntegratedResults.setOnClickListener(v -> navigateToIntegratedResults());
            }

            // Add listeners for the new real-time update buttons
            if (btnRefresh != null) {
                btnRefresh.setOnClickListener(v -> updateBiomarkerData());
            }

            if (btnToggleUpdates != null) {
                btnToggleUpdates.setOnClickListener(v -> toggleAutoUpdates());
            }

            // Add listener for Home button
            if (btnHome != null) {
                btnHome.setOnClickListener(v -> navigateToIntegratedResults());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing views", e);
            Toast.makeText(this, "Error initializing UI components", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Navigate back to the main activity/home screen
     */
    private void navigateToHome() {
        try {
            // Create intent to the MainActivity or your home screen
            Intent intent = new Intent(CombinedResultsActivity.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP); // Clear the back stack
            startActivity(intent);
            finish(); // Close this activity
        } catch (Exception e) {
            Log.e(TAG, "Error navigating to home", e);
            Toast.makeText(this, "Cannot return to home screen", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Toggle automatic updates on/off
     */
    private void toggleAutoUpdates() {
        try {
            if (isAutoUpdating) {
                // Stop updates
                if (updateHandler != null) {
                    updateHandler.removeCallbacks(updateRunnable);
                }
                isAutoUpdating = false;
                if (btnToggleUpdates != null) {
                    btnToggleUpdates.setText("Start Real-time Updates");
                }
                if (tvUpdateStatus != null) {
                    tvUpdateStatus.setText("Auto-updates: OFF");
                }
                Toast.makeText(this, "Auto-updates disabled", Toast.LENGTH_SHORT).show();
            } else {
                // Start updates
                isAutoUpdating = true;
                if (updateHandler != null && updateRunnable != null) {
                    updateHandler.post(updateRunnable);
                }
                if (btnToggleUpdates != null) {
                    btnToggleUpdates.setText("Stop Real-time Updates");
                }
                if (tvUpdateStatus != null) {
                    tvUpdateStatus.setText("Auto-updates: ON");
                }
                Toast.makeText(this, "Auto-updates enabled", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error toggling updates", e);
            Toast.makeText(this, "Error toggling auto-updates", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Method to update biomarker data in real-time
     */
    private void updateBiomarkerData() {
        try {
            // Remove any pending updates first
            if (updateHandler != null && updateRunnable != null) {
                updateHandler.removeCallbacks(updateRunnable);
            }

            // Fetch latest data
            fetchLatestBiomarkerData();

            // Calculate new values based on updated biomarker readings
            calculateValues();
            calculateStressWeights();
            displayValues();

            // If auto-updating is enabled, schedule the next update
            if (isAutoUpdating && updateHandler != null && updateRunnable != null) {
                updateHandler.postDelayed(updateRunnable, UPDATE_INTERVAL);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating biomarker data", e);
            Toast.makeText(this, "Error updating data", Toast.LENGTH_SHORT).show();

            // If auto-updating is enabled, schedule the next update despite the error
            if (isAutoUpdating && updateHandler != null && updateRunnable != null) {
                updateHandler.postDelayed(updateRunnable, UPDATE_INTERVAL);
            }
        }
    }

    /**
     * Simulate fetching the latest biomarker data
     * In a real application, this would connect to your data source
     */
    private void fetchLatestBiomarkerData() {
        try {
            // This is a simulation for demonstration purposes
            // In a real app, connect to your actual data source (sensors, database, etc.)

            // Generate some random fluctuations to simulate changing data
            double fluctuation1 = (random.nextDouble() - 0.5) * 0.02; // ±1% fluctuation
            double fluctuation2 = (random.nextDouble() - 0.5) * 0.02;

            // Update the biomarker values with "new" data
            // Add a gradual trend to simulate real changes
            spiralTwoBiomarker = Math.max(spiralTwoBiomarker * (1 + fluctuation1), 0.001);
            spiralThreeBiomarker = Math.max(spiralThreeBiomarker * (1 + fluctuation2), 0.001);

            // Update metadata
            dataSize = dataSize + 1;
            recordedTime = System.currentTimeMillis() / 1000.0;

            Log.d(TAG, "Updated DHEA Biomarker: " + spiralTwoBiomarker);
            Log.d(TAG, "Updated Cortisol Biomarker: " + spiralThreeBiomarker);

        } catch (Exception e) {
            Log.e(TAG, "Error fetching latest biomarker data", e);
            Toast.makeText(this, "Error updating data: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            // Don't rethrow - allow the app to continue with previous values
        }
    }

    private void calculateValues() {
        try {
            // spiralTwoBiomarker and spiralThreeBiomarker are voltage readings in millivolts (mV)
            // Convert them to volts (V) by multiplying by 1000
            double spiral2Volts = spiralTwoBiomarker * 1000.0; // Convert from mV to V
            double spiral3Volts = spiralThreeBiomarker * 1000.0; // Convert from mV to V

            // Log the conversion
            Log.d(TAG, "Spiral 2 voltage: " + spiralTwoBiomarker + " mV = " + spiral2Volts + " V");
            Log.d(TAG, "Spiral 3 voltage: " + spiralThreeBiomarker + " mV = " + spiral3Volts + " V");

            // Assign the converted voltage values to x2 and x3
            double x2 = Math.abs(spiral2Volts); // DHEA voltage in volts
            double x3 = Math.abs(spiral3Volts); // Cortisol voltage in volts

            // Safety check for very small voltage values
            if (x2 < 0.001) {
                x2 = 0.001; // Minimum voltage value to prevent division by zero
            }
            if (x3 < 0.001) {
                x3 = 0.001; // Minimum voltage value to prevent division by zero
            }

            // Safety check for maximum values to prevent overflow
            if (x2 > 1.0E10) {
                x2 = 1.0E10; // Maximum voltage value
            }
            if (x3 > 1.0E10) {
                x3 = 1.0E10; // Maximum voltage value
            }

            // Calculate DHEA concentration (y) from voltage (X) using the formula:
            // X = (272.28/y)^0.8578
            // Solving for y: y = 272.28 / (X^(1/0.8578))
            double exponent = 1.0 / 0.8578;
            try {
                dheaValue = 272.28 / Math.pow(x2, exponent);
                // Check for invalid results
                if (Double.isInfinite(dheaValue) || Double.isNaN(dheaValue)) {
                    dheaValue = 0.0;
                }
                // Apply reasonable limits
                if (dheaValue > 100.0) dheaValue = 100.0;
            } catch (Exception e) {
                Log.e(TAG, "Error calculating DHEA value", e);
                dheaValue = 0.0;
            }

            // Calculate Cortisol concentration (y) from voltage (X) using the formula:
            // X = (169.59/y)^2.967
            // Solving for y: y = 169.59 / (X^(1/2.967))
            exponent = 1.0 / 2.967;
            try {
                cortisolValue = 169.59 / Math.pow(x3, exponent);
                // Check for invalid results
                if (Double.isInfinite(cortisolValue) || Double.isNaN(cortisolValue)) {
                    cortisolValue = 0.0;
                }
                // Apply reasonable limits
                if (cortisolValue > 100.0) cortisolValue = 100.0;
            } catch (Exception e) {
                Log.e(TAG, "Error calculating Cortisol value", e);
                cortisolValue = 0.0;
            }

            // Calculate Cortisol to DHEA ratio with safety check for division by zero
            if (dheaValue > 0) {
                cortisolToDheaRatio = cortisolValue / dheaValue;
            } else {
                cortisolToDheaRatio = 0.0; // Default if DHEA is zero
            }

            // Final check to ensure ratio isn't infinite or NaN
            if (Double.isInfinite(cortisolToDheaRatio) || Double.isNaN(cortisolToDheaRatio)) {
                cortisolToDheaRatio = 0.0;
            }

            // Apply reasonable limits to ratio
            if (cortisolToDheaRatio > 20.0) cortisolToDheaRatio = 20.0;

            // Log the calculations
            Log.d(TAG, "DHEA calculation:");
            Log.d(TAG, "X (voltage): " + x2 + " V");
            Log.d(TAG, "Formula applied: y = 272.28 / (X^(1/0.8578))");
            Log.d(TAG, "Calculated y (DHEA concentration): " + dheaValue + " ng/mL");

            Log.d(TAG, "Cortisol calculation:");
            Log.d(TAG, "X (voltage): " + x3 + " V");
            Log.d(TAG, "Formula applied: y = 169.59 / (X^(1/2.967))");
            Log.d(TAG, "Calculated y (Cortisol concentration): " + cortisolValue + " ng/mL");

            Log.d(TAG, "Calculated Cortisol to DHEA ratio: " + cortisolToDheaRatio);

        } catch (Exception e) {
            // Set default values if any calculation fails
            cortisolValue = 0.0;
            dheaValue = 0.0;
            cortisolToDheaRatio = 0.0;

            // Log the error - you can also show a toast if needed
            Log.e(TAG, "Error calculating biomarker values", e);
            e.printStackTrace();
        }
    }

    private void calculateStressWeights() {
        try {
            // Calculate Cortisol Weight
            if (cortisolValue >= 2.0 && cortisolValue < 5.0) {
                cortisolWeight = 1; // Low/Healthy
            } else if (cortisolValue >= 5.0 && cortisolValue < 9.0) {
                cortisolWeight = 2; // Moderate Stress
            } else if (cortisolValue >= 9.0) {
                cortisolWeight = 3; // High Stress
            } else {
                cortisolWeight = 0; // Below normal range
            }

            // Calculate DHEA Weight
            if (dheaValue >= 4.0 && dheaValue <= 10.0) {
                dheaWeight = 1; // Low/Healthy
            } else if (dheaValue >= 2.0 && dheaValue < 4.0) {
                dheaWeight = 2; // Moderate Stress
            } else if (dheaValue < 2.0) {
                dheaWeight = 3; // High Stress
            } else {
                dheaWeight = 0; // Above normal range
            }

            // Calculate Ratio Weight
            if (cortisolToDheaRatio >= 0.3 && cortisolToDheaRatio <= 1.0) {
                ratioWeight = 1; // Low/Healthy
            } else if (cortisolToDheaRatio > 1.0 && cortisolToDheaRatio <= 2.0) {
                ratioWeight = 2; // Moderate Stress
            } else if (cortisolToDheaRatio > 2.0) {
                ratioWeight = 3; // High Stress
            } else {
                ratioWeight = 0; // Below normal range
            }

            // Calculate total weight
            totalWeight = cortisolWeight + dheaWeight + ratioWeight;

            // Determine overall stress level
            if (totalWeight <= 4) {
                stressLevel = "Low/Healthy";
            } else if (totalWeight <= 7) {
                stressLevel = "Moderate Stress";
            } else {
                stressLevel = "High Stress";
            }

            // Log the weights for debugging
            Log.d(TAG, "Cortisol Weight: " + cortisolWeight);
            Log.d(TAG, "DHEA Weight: " + dheaWeight);
            Log.d(TAG, "Ratio Weight: " + ratioWeight);
            Log.d(TAG, "Total Weight: " + totalWeight);
            Log.d(TAG, "Stress Level: " + stressLevel);

        } catch (Exception e) {
            // Set default values if calculation fails
            cortisolWeight = 0;
            dheaWeight = 0;
            ratioWeight = 0;
            totalWeight = 0;
            stressLevel = "Unknown";
            Log.e(TAG, "Error calculating stress weights", e);
        }
    }

    private void displayValues() {
        try {
            // Use null safety when setting text values
            if (tvCortisolValue != null)
                tvCortisolValue.setText(String.format("%.2f ng/mL", cortisolValue));
            if (tvDheaValue != null) tvDheaValue.setText(String.format("%.2f ng/mL", dheaValue));
            if (tvRatioValue != null)
                tvRatioValue.setText(String.format("%.2f", cortisolToDheaRatio));

            // Display ranges
            if (tvCortisolRange != null) tvCortisolRange.setText(getCortisolRangeText());
            if (tvDheaRange != null) tvDheaRange.setText(getDheaRangeText());
            if (tvRatioRange != null) tvRatioRange.setText(getRatioRangeText());

            // Display weights
            if (tvCortisolWeight != null) tvCortisolWeight.setText("+" + cortisolWeight);
            if (tvDheaWeight != null) tvDheaWeight.setText("+" + dheaWeight);
            if (tvRatioWeight != null) tvRatioWeight.setText("+" + ratioWeight);

            // Display total and stress level
            if (tvTotalWeight != null) tvTotalWeight.setText(String.valueOf(totalWeight));
            if (tvStressLevel != null) {
                tvStressLevel.setText(stressLevel);
                // Update color based on stress level (optional enhancement)
                if (stressLevel.equals("High Stress")) {
                    tvStressLevel.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                } else if (stressLevel.equals("Moderate Stress")) {
                    tvStressLevel.setTextColor(getResources().getColor(android.R.color.holo_orange_dark));
                } else {
                    tvStressLevel.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                }
            }

            // Display last update time
            if (tvUpdateStatus != null) {
                if (isAutoUpdating) {
                    tvUpdateStatus.setText("Auto-updates: ON - Last: " +
                            new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()));
                } else {
                    tvUpdateStatus.setText("Last update: " +
                            new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error displaying values", e);
            Toast.makeText(this, "Error updating display", Toast.LENGTH_SHORT).show();
        }
    }

    private String getCortisolRangeText() {
        try {
            if (cortisolValue < 2.0) {
                return "< 2.0 (Below normal)";
            } else if (cortisolValue < 5.0) {
                return "2.0-5.0 (Healthy)";
            } else if (cortisolValue < 9.0) {
                return "5.0-9.0 (Moderate)";
            } else {
                return "> 9.0 (High)";
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting cortisol range text", e);
            return "Range Error";
        }
    }

    private String getDheaRangeText() {
        try {
            if (dheaValue < 2.0) {
                return "< 2.0 (High stress)";
            } else if (dheaValue < 4.0) {
                return "2.0-4.0 (Moderate)";
            } else if (dheaValue <= 10.0) {
                return "4.0-10.0 (Healthy)";
            } else {
                return "> 10.0 (Above normal)";
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting DHEA range text", e);
            return "Range Error";
        }
    }

    private String getRatioRangeText() {
        try {
            if (cortisolToDheaRatio < 0.3) {
                return "< 0.3 (Below normal)";
            } else if (cortisolToDheaRatio <= 1.0) {
                return "0.3-1.0 (Healthy)";
            } else if (cortisolToDheaRatio <= 2.0) {
                return "1.0-2.0 (Moderate)";
            } else {
                return "> 2.0 (High stress)";
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting ratio range text", e);
            return "Range Error";
        }
    }

    private void saveResults() {
        try {
            // Save results to SharedPreferences
            SharedPreferences prefs = getSharedPreferences("BiomarkerResults", MODE_PRIVATE);
            if (prefs == null) {
                Toast.makeText(this, "Unable to access storage", Toast.LENGTH_SHORT).show();
                return;
            }

            SharedPreferences.Editor editor = prefs.edit();
            if (editor == null) {
                Toast.makeText(this, "Unable to access storage editor", Toast.LENGTH_SHORT).show();
                return;
            }

            // Generate a unique ID based on username and timestamp
            long timestamp = System.currentTimeMillis();
            String resultId = (username != null ? username : "unknown") + "_" + timestamp;

            // Save the biomarker data for both spirals
            editor.putString(resultId + "_date", new java.util.Date(timestamp).toString());
            editor.putFloat(resultId + "_spiralTwoBiomarker", (float) spiralTwoBiomarker);
            editor.putFloat(resultId + "_spiralThreeBiomarker", (float) spiralThreeBiomarker);
            editor.putFloat(resultId + "_cortisol", (float) cortisolValue);
            editor.putFloat(resultId + "_dhea", (float) dheaValue);
            editor.putFloat(resultId + "_cortisolDheaRatio", (float) cortisolToDheaRatio);

            // Save the weights and stress level
            editor.putInt(resultId + "_cortisolWeight", cortisolWeight);
            editor.putInt(resultId + "_dheaWeight", dheaWeight);
            editor.putInt(resultId + "_ratioWeight", ratioWeight);
            editor.putInt(resultId + "_totalWeight", totalWeight);
            editor.putString(resultId + "_stressLevel", stressLevel);

            editor.putInt(resultId + "_dataSize", dataSize);
            editor.putFloat(resultId + "_recordedTime", (float) recordedTime);

            // Save as part of the history list
            String historyKey = (username != null ? username : "unknown") + "_history";
            String historyList = prefs.getString(historyKey, "");
            if (historyList == null) historyList = "";

            if (!historyList.isEmpty()) {
                historyList += ",";
            }
            historyList += resultId;
            editor.putString(historyKey, historyList);

            // Apply changes
            editor.apply();

            // Show confirmation to user
            Toast.makeText(this, "Results saved", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error saving results", e);
            Toast.makeText(this, "Error saving results: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void shareResults() {
        try {
            // Create a text summary to share
            StringBuilder summary = new StringBuilder();
            summary.append("Biomarker Results\n\n");
            summary.append("Cortisol: ").append(String.format("%.2f", cortisolValue)).append(" ng/mL")
                    .append(" (Weight: +").append(cortisolWeight).append(")\n");

            summary.append("DHEA: ").append(String.format("%.2f", dheaValue)).append(" ng/mL")
                    .append(" (Weight: +").append(dheaWeight).append(")\n");

            summary.append("Cortisol to DHEA Ratio: ").append(String.format("%.2f", cortisolToDheaRatio))
                    .append(" (Weight: +").append(ratioWeight).append(")\n\n");

            summary.append("Overall Stress Level: ").append(stressLevel)
                    .append(" (Total Weight: ").append(totalWeight).append(")");

            // Create share intent
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Biomarker Results");
            shareIntent.putExtra(Intent.EXTRA_TEXT, summary.toString());

            // Check if there are apps that can handle this intent
            if (shareIntent.resolveActivity(getPackageManager()) != null) {
                // Launch share dialog
                startActivity(Intent.createChooser(shareIntent, "Share Results Via"));
            } else {
                Toast.makeText(this, "No apps available to share content", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sharing results", e);
            Toast.makeText(this, "Error sharing results: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void navigateToIntegratedResults() {
        try {
            // Save the biomarker results first to ensure they're available
            saveResults();

            // Create intent to the IntegratedResultsActivity
            Intent intent = new Intent(CombinedResultsActivity.this, IntegratedResultsActivity.class);
            if (intent == null) {
                Toast.makeText(this, "Unable to create intent", Toast.LENGTH_SHORT).show();
                return;
            }

            // Pass necessary data
            intent.putExtra("username", username);
            intent.putExtra("biomarker_value2", spiralTwoBiomarker);
            intent.putExtra("biomarker_value3", spiralThreeBiomarker);
            intent.putExtra("cortisol_value", cortisolValue);
            intent.putExtra("dhea_value", dheaValue);
            intent.putExtra("cortisol_dhea_ratio", cortisolToDheaRatio);

            // Pass the weights and stress level
            intent.putExtra("cortisol_weight", cortisolWeight);
            intent.putExtra("dhea_weight", dheaWeight);
            intent.putExtra("ratio_weight", ratioWeight);
            intent.putExtra("total_weight", totalWeight);
            intent.putExtra("stress_level", stressLevel);

            // Check if the activity exists in the manifest
            if (intent.resolveActivity(getPackageManager()) != null) {
                // Start the activity
                startActivity(intent);
            } else {
                Toast.makeText(this, "IntegratedResultsActivity not found", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "IntegratedResultsActivity not found in manifest");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error navigating to integrated results", e);
            Toast.makeText(this, "Error navigating to integrated results: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop auto-updates when activity is not visible
        if (isAutoUpdating && updateHandler != null && updateRunnable != null) {
            updateHandler.removeCallbacks(updateRunnable);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Resume auto-updates if they were enabled
        if (isAutoUpdating && updateHandler != null && updateRunnable != null) {
            updateHandler.post(updateRunnable);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Ensure we clean up the handler when activity is destroyed
        if (updateHandler != null && updateRunnable != null) {
            updateHandler.removeCallbacks(updateRunnable);
        }
    }
}