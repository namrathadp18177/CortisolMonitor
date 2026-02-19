package com.example.myapplication;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;

import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class IntegratedResultsActivity extends AppCompatActivity {

    private static final String TAG = "IntegratedResults";

    // UI Components
    private TextView tvIntegratedTitle, tvIntegratedScore, tvIntegratedInterpretation;
    private Button btnHome;
    // Note: Professional layout doesn't include mascot images
    private TextView tvPhqScore;
    private TextView tvPhqWeight;
    private TextView tvPhqClassification;
    private TextView tvResilienceScore;
    private TextView tvResilienceWeight;
    private TextView tvResilienceClassification;
    private TextView tvCortisolValue;
    private TextView tvCortisolWeight;
    private TextView tvDheaValue;
    private TextView tvDheaWeight;
    private TextView tvRatioValue;
    private TextView tvRatioWeight;
    private TextView tvTotalWeight;
    private TextView tvStressLevel;
    private TextView tvFinalResult;
    private ImageView ivBilly;
    private Button btnSaveIntegratedResults;
    private Button btnShareIntegratedResults;

    // Data values
    private String username;

    // Mental health assessment data
    private int phqScore;
    private int phqWeight;
    private float resilienceScore;
    private int resilienceWeight;

    // Biomarker data
    private double cortisolValue;
    private int cortisolWeight;
    private double dheaValue;
    private int dheaWeight;
    private double cortisolToDheaRatio;
    private int ratioWeight;

    // Integrated results
    private int totalWeight;
    private String stressLevel;
    private String finalResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            setContentView(R.layout.activity_integrated_results);

            // No toolbar in the simplified layout
            // We're using a simple header instead

            // Initialize views
            initializeViews();

            // Get data from intent
            Intent intent = getIntent();
            processIncomingIntent(intent);

            // Load any missing data from saved preferences
            loadSavedData();

            // Calculate final results
            calculateIntegratedResults();

            // Display results
            displayResults();

            // Set button click listeners
            setButtonListeners();
            
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate: " + e.getMessage(), e);
            e.printStackTrace(); // Print full stack trace for debugging
            // If there's an error, redirect to dashboard
            Toast.makeText(this, "Error loading Results. Returning Dashboard", Toast.LENGTH_LONG).show();
            navigateHome();
        }
    }

    private void initializeViews() {
        Log.d(TAG, "Initializing views...");
        
        // Mental health assessment views
        tvPhqScore = findViewById(R.id.tvPhqScore);
        tvPhqWeight = findViewById(R.id.tvPhqWeight);
        tvPhqClassification = findViewById(R.id.tvPhqClassification);
        tvResilienceScore = findViewById(R.id.tvResilienceScore);
        tvResilienceWeight = findViewById(R.id.tvResilienceWeight);
        tvResilienceClassification = findViewById(R.id.tvResilienceClassification);

        // Biomarker views
        tvCortisolValue = findViewById(R.id.tvCortisolValue);
        tvCortisolWeight = findViewById(R.id.tvCortisolWeight);
        tvDheaValue = findViewById(R.id.tvDheaValue);
        tvDheaWeight = findViewById(R.id.tvDheaWeight);
        tvRatioValue = findViewById(R.id.tvRatioValue);
        tvRatioWeight = findViewById(R.id.tvRatioWeight);

        // Summary views
        tvTotalWeight = findViewById(R.id.tvOverallTotalWeight);
        tvStressLevel = findViewById(R.id.tvStressLevel);
        tvFinalResult = findViewById(R.id.tvFinalResult);

        // Other UI components
        // Note: ivBilly is not in the new simplified layout
        btnSaveIntegratedResults = findViewById(R.id.btnSaveIntegratedResults);
        btnShareIntegratedResults = findViewById(R.id.btnShareIntegratedResults);
        btnHome = findViewById(R.id.btnHome);
        
        // Check for null views and log which ones are missing
        boolean hasErrors = false;
        if (tvPhqScore == null) {
            Log.e(TAG, "tvPhqScore is null");
            hasErrors = true;
        }
        if (tvStressLevel == null) {
            Log.e(TAG, "tvStressLevel is null");
            hasErrors = true;
        }
        if (tvFinalResult == null) {
            Log.e(TAG, "tvFinalResult is null");
            hasErrors = true;
        }
        if (btnHome == null) {
            Log.e(TAG, "btnHome is null");
            hasErrors = true;
        }
        if (btnSaveIntegratedResults == null) {
            Log.e(TAG, "btnSaveIntegratedResults is null");
            hasErrors = true;
        }
        if (tvRatioWeight == null) {
            Log.e(TAG, "tvRatioWeight is null");
            hasErrors = true;
        }
        if (tvTotalWeight == null) {
            Log.e(TAG, "tvTotalWeight (tvOverallTotalWeight) is null");
            hasErrors = true;
        }
        
        if (hasErrors) {
            Log.e(TAG, "Some UI elements are null after findViewById");
            throw new RuntimeException("Missing UI elements in layout");
        }
        
        Log.d(TAG, "Views initialized successfully");
    }

    private void processIncomingIntent(Intent intent) {
        if (intent == null) {
            Log.e(TAG, "Intent is null");
            return;
        }

        // Get username from intent or DatabaseHelper
        username = intent.getStringExtra("username");
        if (username == null || username.isEmpty()) {
            username = DatabaseHelper.getCurrentUserEmail();
            if (username == null || username.isEmpty()) {
                username = "default_user";
                Log.w(TAG, "No username found, using default_user");
            }
        }
        Log.d(TAG, "Using username: " + username);

        // Process PHQ-9 data
        if (intent.hasExtra("phq_score")) {
            phqScore = intent.getIntExtra("phq_score", 0);
            phqWeight = intent.hasExtra("phq_weight") ?
                    intent.getIntExtra("phq_weight", 0) :
                    calculatePhqWeight(phqScore);
        }

        // Process Resilience data
        if (intent.hasExtra("resilience_score")) {
            resilienceScore = intent.getFloatExtra("resilience_score", 0.0f);
            resilienceWeight = intent.hasExtra("resilience_weight") ?
                    intent.getIntExtra("resilience_weight", 0) :
                    calculateResilienceWeight(resilienceScore);
        }

        // Process Biomarker data
        // Cortisol
        if (intent.hasExtra("cortisol_value") || intent.hasExtra("cortisol_level")) {
            cortisolValue = intent.hasExtra("cortisol_value") ?
                    intent.getDoubleExtra("cortisol_value", 0.0) :
                    intent.getFloatExtra("cortisol_level", 0.0f);

            cortisolWeight = intent.hasExtra("cortisol_weight") ?
                    intent.getIntExtra("cortisol_weight", 0) :
                    calculateCortisolWeight(cortisolValue);
        }

        // DHEA
        if (intent.hasExtra("dhea_value") || intent.hasExtra("dhea_level")) {
            dheaValue = intent.hasExtra("dhea_value") ?
                    intent.getDoubleExtra("dhea_value", 0.0) :
                    intent.getFloatExtra("dhea_level", 0.0f);

            dheaWeight = intent.hasExtra("dhea_weight") ?
                    intent.getIntExtra("dhea_weight", 0) :
                    calculateDheaWeight(dheaValue);
        }

        // Cortisol to DHEA Ratio
        if (intent.hasExtra("cortisol_dhea_ratio") || intent.hasExtra("cort_dhea_ratio")) {
            cortisolToDheaRatio = intent.hasExtra("cortisol_dhea_ratio") ?
                    intent.getDoubleExtra("cortisol_dhea_ratio", 0.0) :
                    intent.getFloatExtra("cort_dhea_ratio", 0.0f);

            ratioWeight = intent.hasExtra("ratio_weight") ?
                    intent.getIntExtra("ratio_weight", 0) :
                    (intent.hasExtra("cort_dhea_weight") ?
                            intent.getIntExtra("cort_dhea_weight", 0) :
                            calculateRatioWeight(cortisolToDheaRatio));
        }

        // Get total weight if available
        if (intent.hasExtra("total_weight")) {
            totalWeight = intent.getIntExtra("total_weight", 0);
        }

        // Get stress level if available
        if (intent.hasExtra("stress_level")) {
            stressLevel = intent.getStringExtra("stress_level");
        }

        logReceivedData();
    }

    private void logReceivedData() {
        Log.d(TAG, "Received data:");
        Log.d(TAG, "Username: " + username);
        Log.d(TAG, "PHQ-9 Score: " + phqScore + ", Weight: " + phqWeight);
        Log.d(TAG, "Resilience Score: " + resilienceScore + ", Weight: " + resilienceWeight);
        Log.d(TAG, "Cortisol Value: " + cortisolValue + ", Weight: " + cortisolWeight);
        Log.d(TAG, "DHEA Value: " + dheaValue + ", Weight: " + dheaWeight);
        Log.d(TAG, "Cortisol/DHEA Ratio: " + cortisolToDheaRatio + ", Weight: " + ratioWeight);
    }

    private void loadSavedData() {
        SharedPreferences assessmentPrefs = getSharedPreferences("AssessmentResults", MODE_PRIVATE);
        SharedPreferences biomarkerPrefs = getSharedPreferences("BiomarkerResults", MODE_PRIVATE);

        // Load PHQ data if needed
        if (phqScore <= 0) {
            phqScore = assessmentPrefs.getInt("phq_" + username + "_latest", 5);
            phqWeight = assessmentPrefs.getInt("phq_weight_" + username + "_latest", calculatePhqWeight(phqScore));
        }

        // Load Resilience data if needed
        if (resilienceScore <= 0) {
            resilienceScore = assessmentPrefs.getFloat("resilience_" + username + "_latest", 3.0f);
            resilienceWeight = assessmentPrefs.getInt("resilience_weight_" + username + "_latest", calculateResilienceWeight(resilienceScore));
        }

        // Load biomarker data if needed
        // First, try to get the latest result ID
        String historyList = biomarkerPrefs.getString(username + "_history", "");
        if (!historyList.isEmpty() && (cortisolValue <= 0 || dheaValue <= 0)) {
            String[] resultIds = historyList.split(",");
            if (resultIds.length > 0) {
                // Use the most recent result (last in the list)
                String latestResultId = resultIds[resultIds.length - 1];

                if (cortisolValue <= 0) {
                    cortisolValue = biomarkerPrefs.getFloat(latestResultId + "_cortisol", 7.0f);
                    cortisolWeight = calculateCortisolWeight(cortisolValue);
                }

                if (dheaValue <= 0) {
                    dheaValue = biomarkerPrefs.getFloat(latestResultId + "_dhea", 3.0f);
                    dheaWeight = calculateDheaWeight(dheaValue);
                }

                if (cortisolToDheaRatio <= 0) {
                    cortisolToDheaRatio = biomarkerPrefs.getFloat(latestResultId + "_cortisolDheaRatio", 2.0f);
                    ratioWeight = calculateRatioWeight(cortisolToDheaRatio);
                }
            }
        }

        // If we still don't have values, use defaults for demo
        if (cortisolValue <= 0) cortisolValue = 7.0;
        if (dheaValue <= 0) dheaValue = 3.0;
        if (cortisolToDheaRatio <= 0) cortisolToDheaRatio = cortisolValue / dheaValue;

        if (cortisolWeight <= 0) cortisolWeight = calculateCortisolWeight(cortisolValue);
        if (dheaWeight <= 0) dheaWeight = calculateDheaWeight(dheaValue);
        if (ratioWeight <= 0) ratioWeight = calculateRatioWeight(cortisolToDheaRatio);
    }

    private void calculateIntegratedResults() {
        // Calculate total weight
        totalWeight = phqWeight + resilienceWeight + cortisolWeight + dheaWeight + ratioWeight;

        // Calculate stress level based on biomarker weights
        int biomarkerSum = cortisolWeight + dheaWeight + ratioWeight;

        if (biomarkerSum <= 3) {
            stressLevel = "Low/Healthy";
        } else if (biomarkerSum <= 6) {
            stressLevel = "Moderate Stress";
        } else {
            stressLevel = "High Stress";
        }

        // Calculate final result based on total weight
        if (totalWeight <= 5) {
            finalResult = "Healthy";
        } else if (totalWeight <= 8) {
            finalResult = "Concern";
        } else if (totalWeight <= 11) {
            finalResult = "Mild";
        } else if (totalWeight <= 14) {
            finalResult = "Moderate";
        } else {
            finalResult = "Severe";
        }
    }

    private void displayResults() {
        try {
            // Display Mental Health Assessment data
            if (tvPhqScore != null) {
                tvPhqScore.setText("Score: " + phqScore);
            }
            if (tvPhqWeight != null) {
                tvPhqWeight.setText(String.format("Weight: %d (%s)", phqWeight, getPhqWeightDescription(phqWeight)));
            }
            if (tvPhqClassification != null) {
                tvPhqClassification.setText(interpretPhqScore(phqScore));
            }

            if (tvResilienceScore != null) {
                tvResilienceScore.setText(String.format("Score: %.1f", resilienceScore));
            }
            if (tvResilienceWeight != null) {
                tvResilienceWeight.setText(String.format("Weight: %d (%s)", resilienceWeight, getResilienceWeightDescription(resilienceWeight)));
            }
            if (tvResilienceClassification != null) {
                tvResilienceClassification.setText(interpretResilienceScore(resilienceScore));
            }

            // Display Biomarker data
            if (tvCortisolValue != null) {
                tvCortisolValue.setText(String.format("Cortisol Value: %.2f ng/mL", cortisolValue));
            }
            if (tvCortisolWeight != null) {
                tvCortisolWeight.setText(String.format("Weight: +%d", cortisolWeight));
            }

            if (tvDheaValue != null) {
                tvDheaValue.setText(String.format("DHEA Value: %.2f ng/mL", dheaValue));
            }
            if (tvDheaWeight != null) {
                tvDheaWeight.setText(String.format("Weight: +%d", dheaWeight));
            }

            if (tvRatioValue != null) {
                tvRatioValue.setText(String.format("Cortisol/DHEA Ratio: %.2f", cortisolToDheaRatio));
            }
            if (tvRatioWeight != null) {
                tvRatioWeight.setText(String.format("Weight: +%d", ratioWeight));
            }

            // Display Summary data
            if (tvTotalWeight != null) {
                tvTotalWeight.setText(String.valueOf(totalWeight));
            }
            if (tvStressLevel != null) {
                tvStressLevel.setText("Stress Level: " + stressLevel);
            }
            if (tvFinalResult != null) {
                tvFinalResult.setText(finalResult);

                // Set appropriate color for final result
                int resultColor;
                if (finalResult.equals("Healthy")) {
                    resultColor = getResources().getColor(android.R.color.holo_green_dark);
                } else if (finalResult.equals("Concern") || finalResult.equals("Mild")) {
                    resultColor = getResources().getColor(android.R.color.holo_orange_dark);
                } else {
                    resultColor = getResources().getColor(android.R.color.holo_red_dark);
                }
                tvFinalResult.setTextColor(resultColor);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error displaying results: " + e.getMessage(), e);
            // Continue with initialization, some UI elements might be missing but don't crash
        }
    }

    private void setButtonListeners() {
        try {
            if (btnSaveIntegratedResults != null) {
                btnSaveIntegratedResults.setOnClickListener(v -> saveResults());
            }
            if (btnShareIntegratedResults != null) {
                btnShareIntegratedResults.setOnClickListener(v -> shareResults());
            }
            if (btnHome != null) {
                btnHome.setOnClickListener(v -> navigateHome());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting button listeners: " + e.getMessage(), e);
            // Continue, some buttons might not work but app won't crash
        }
    }

    private int calculatePhqWeight(int score) {
        if (score >= 0 && score <= 4) {
            return 1; // None-minimal
        } else if (score >= 5 && score <= 9) {
            return 2; // Mild
        } else if (score >= 10 && score <= 14) {
            return 3; // Moderate
        } else if (score >= 15 && score <= 19) {
            return 4; // Moderately severe
        } else if (score >= 20 && score <= 27) {
            return 5; // Severe
        }
        return 0; // Invalid score
    }

    private String getPhqWeightDescription(int weight) {
        switch (weight) {
            case 1: return "None-minimal";
            case 2: return "Mild";
            case 3: return "Moderate";
            case 4: return "Moderately severe";
            case 5: return "Severe";
            default: return "Unknown";
        }
    }

    private String interpretPhqScore(int score) {
        if (score < 5) return "Minimal Depression";
        if (score < 10) return "Mild Depression";
        if (score < 15) return "Moderate Depression";
        if (score < 20) return "Moderately Severe Depression";
        return "Severe Depression";
    }

    private int calculateResilienceWeight(float score) {
        if (score >= 0 && score < 3.0) {
            return 3; // Low resilience
        } else if (score >= 3.0 && score <= 4.3) {
            return 2; // Normal resilience
        } else if (score > 4.3 && score <= 6.0) {
            return 1; // High resilience
        }
        return 0; // Invalid score
    }

    private String getResilienceWeightDescription(int weight) {
        switch (weight) {
            case 1: return "High resilience";
            case 2: return "Normal resilience";
            case 3: return "Low resilience";
            default: return "Unknown";
        }
    }

    private String interpretResilienceScore(float score) {
        if (score < 2.00) return "Low Resilience";
        if (score < 3.00) return "Low to Normal Resilience";
        if (score < 4.30) return "Normal Resilience";
        return "High Resilience";
    }

    private int calculateCortisolWeight(double level) {
        if (level >= 2.0 && level < 5.0) {
            return 1; // Low/Healthy
        } else if (level >= 5.0 && level < 9.0) {
            return 2; // Moderate Stress
        } else if (level >= 9.0) {
            return 3; // High Stress
        } else {
            return 0; // Below normal range
        }
    }

    private int calculateDheaWeight(double level) {
        if (level >= 4.0 && level <= 10.0) {
            return 1; // Healthy
        } else if (level >= 2.0 && level < 4.0) {
            return 2; // Moderate Stress
        } else if (level < 2.0) {
            return 3; // High Stress
        } else {
            return 0; // Above normal range
        }
    }

    private int calculateRatioWeight(double ratio) {
        if (ratio >= 0.3 && ratio <= 1.0) {
            return 1; // Healthy
        } else if (ratio > 1.0 && ratio <= 2.0) {
            return 2; // Moderate Stress
        } else if (ratio > 2.0) {
            return 3; // High Stress
        } else {
            return 0; // Below normal range
        }
    }

    private void saveResults() {
        // Save the comprehensive results to SharedPreferences
        SharedPreferences prefs = getSharedPreferences("IntegratedResults", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        // Generate a unique ID based on username and timestamp
        long timestamp = System.currentTimeMillis();
        String resultId = username + "_" + timestamp;

        // Save mental health assessment data
        editor.putInt(resultId + "_phq_score", phqScore);
        editor.putInt(resultId + "_phq_weight", phqWeight);
        editor.putFloat(resultId + "_resilience_score", resilienceScore);
        editor.putInt(resultId + "_resilience_weight", resilienceWeight);

        // Save biomarker data
        editor.putFloat(resultId + "_cortisol_value", (float)cortisolValue);
        editor.putInt(resultId + "_cortisol_weight", cortisolWeight);
        editor.putFloat(resultId + "_dhea_value", (float)dheaValue);
        editor.putInt(resultId + "_dhea_weight", dheaWeight);
        editor.putFloat(resultId + "_ratio_value", (float)cortisolToDheaRatio);
        editor.putInt(resultId + "_ratio_weight", ratioWeight);

        // Save summary data
        editor.putInt(resultId + "_total_weight", totalWeight);
        editor.putString(resultId + "_stress_level", stressLevel);
        editor.putString(resultId + "_final_result", finalResult);
        editor.putLong(resultId + "_timestamp", timestamp);

        // Save as part of the history list
        String historyList = prefs.getString(username + "_history", "");
        if (!historyList.isEmpty()) {
            historyList += ",";
        }
        historyList += resultId;
        editor.putString(username + "_history", historyList);

        // Apply changes
        editor.apply();

        // Show confirmation to user
        Toast.makeText(this, "Integrated results saved", Toast.LENGTH_SHORT).show();
    }

    private void shareResults() {
        // Create a text summary to share
        StringBuilder summary = new StringBuilder();
        summary.append("Comprehensive Health Assessment\n\n");

        // Mental Health section
        summary.append("MENTAL HEALTH ASSESSMENT\n");
        summary.append("-------------------------\n");
        summary.append("PHQ-9 Score: ").append(phqScore)
                .append(" (").append(interpretPhqScore(phqScore)).append(", Weight: ").append(phqWeight).append(")\n");
        summary.append("Resilience Score: ").append(String.format("%.1f", resilienceScore))
                .append(" (").append(interpretResilienceScore(resilienceScore)).append(", Weight: ").append(resilienceWeight).append(")\n\n");

        // Biomarker section
        summary.append("BIOMARKER ANALYSIS\n");
        summary.append("------------------\n");
        summary.append("Cortisol: ").append(String.format("%.2f", cortisolValue)).append(" ng/mL")
                .append(" (Weight: +").append(cortisolWeight).append(")\n");
        summary.append("DHEA: ").append(String.format("%.2f", dheaValue)).append(" ng/mL")
                .append(" (Weight: +").append(dheaWeight).append(")\n");
        summary.append("Cortisol/DHEA Ratio: ").append(String.format("%.2f", cortisolToDheaRatio))
                .append(" (Weight: +").append(ratioWeight).append(")\n\n");

        // Summary section
        summary.append("SUMMARY\n");
        summary.append("-------\n");
        summary.append("Total Weight: ").append(totalWeight).append("\n");
        summary.append("Stress Level: ").append(stressLevel).append("\n");
        summary.append("Overall Status: ").append(finalResult).append("\n\n");

        summary.append("Report generated on: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date()));

        // Create share intent
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Comprehensive Health Assessment Results");
        shareIntent.putExtra(Intent.EXTRA_TEXT, summary.toString());

        // Launch share dialog
        startActivity(Intent.createChooser(shareIntent, "Share Results Via"));
    }

    private void navigateHome() {
        Intent homeIntent = new Intent(this, DashboardActivity.class);
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(homeIntent);
        finish();
    }

    private void navigateResources() {
        // Implementation of navigateResources method
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Update any data that might have changed
        loadSavedData();
        calculateIntegratedResults();
        displayResults();
    }
}