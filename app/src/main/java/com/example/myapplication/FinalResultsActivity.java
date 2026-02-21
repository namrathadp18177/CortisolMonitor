package com.example.myapplication;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class FinalResultsActivity extends AppCompatActivity {

    private static final String TAG = "FinalResultsActivity";

    // UI Elements
    private TextView tvResultTitle, tvResultDescription;
    private TextView tvCortisol, tvDhea, tvRatio;
    private TextView tvMentalHealthScore, tvRecommendations;
    private LinearLayout resultsContainer;
    private CardView resultCategoryCard;
    private ProgressBar progressStressLevel;
    private Button btnSendResults, btnHome;
    private ImageButton btnBack;

    // Data values
    private String userEmail = "";
    private double cortisolLevel = 0.0;
    private double dheaLevel = 0.0;
    private double cortDheaRatio = 0.0;
    private QuestionnaireSummary questionnaireSummary;
    private String stressCategory = "";
    private String recommendations = "";

    // Constants for stress levels
    private static final String STRESS_LOW = "Low Stress";
    private static final String STRESS_MODERATE = "Moderate Stress";
    private static final String STRESS_HIGH = "High Stress";

    // ML Model
    private OrtEnvironment ortEnv;
    private OrtSession ortSession;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_final_results);

        Log.d(TAG, "onCreate: FinalResultsActivity started");

        // Initialize ML environment
        try {
            initializeModel();
        } catch (Exception e) {
            Log.e(TAG, "Error initializing ML model", e);
            Toast.makeText(this, "Using fallback prediction model", Toast.LENGTH_LONG).show();
        }

        // Initialize views
        initializeViews();

        // Process intent data
        Intent intent = getIntent();
        if (intent != null) {
            processIntentData(intent);
        } else {
            Log.e(TAG, "Intent is null");
            Toast.makeText(this, "Error: No data received", Toast.LENGTH_LONG).show();
        }

        // Get questionnaire data
        loadQuestionnaireSummary();

        // Generate predictions
        runPredictionModel();

        // Display final results
        displayResults();

        // Generate recommendations
        generateRecommendations();
    }

    private void initializeModel() throws OrtException {
        ortEnv = OrtEnvironment.getEnvironment();
        try {
            // Copy the model from assets to a temporary file
            // Use the IR9 version of the model
            java.io.InputStream inputStream = getAssets().open("random_forest_model_ir9.onnx");
            java.io.File modelFile = new java.io.File(getCacheDir(), "random_forest_model_ir9.onnx");

            java.io.FileOutputStream outputStream = new java.io.FileOutputStream(modelFile);
            byte[] buffer = new byte[1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            inputStream.close();
            outputStream.flush();
            outputStream.close();

            // Create session using the file path
            OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions();
            ortSession = ortEnv.createSession(modelFile.getAbsolutePath(), sessionOptions);

            Log.d(TAG, "ML model loaded successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load ONNX model", e);
            // Set ortSession to null to indicate failure
            ortSession = null;
            throw new OrtException("Failed to load model: " + e.getMessage());
        }
    }

    private void initializeViews() {
        // Get reference to UI elements
        tvResultTitle = findViewById(R.id.tvResultTitle);
        tvResultDescription = findViewById(R.id.tvResultDescription);
        tvCortisol = findViewById(R.id.tvCortisol);
        tvDhea = findViewById(R.id.tvDhea);
        tvRatio = findViewById(R.id.tvRatio);
        tvMentalHealthScore = findViewById(R.id.tvMentalHealthScore);
        tvRecommendations = findViewById(R.id.tvRecommendations);
        resultsContainer = findViewById(R.id.resultsContainer);
        resultCategoryCard = findViewById(R.id.resultCategoryCard);
        progressStressLevel = findViewById(R.id.progressStressLevel);

        // Buttons
        btnBack = findViewById(R.id.btnBack);
        btnSendResults = findViewById(R.id.btnSendResults);
        btnHome = findViewById(R.id.btnHome);

        // Set button listeners
        btnBack.setOnClickListener(v -> onBackPressed());
        btnSendResults.setOnClickListener(v -> sendResultsToDoctor());
        btnHome.setOnClickListener(v -> goToMainActivity());
    }

    private void processIntentData(Intent intent) {
        // Get user email
        if (intent.hasExtra("user_email")) {
            userEmail = intent.getStringExtra("user_email");
            Log.d(TAG, "User email retrieved: " + userEmail);
        } else {
            userEmail = DatabaseHelper.getCurrentUserEmail();
            Log.d(TAG, "Using current user email: " + userEmail);
        }

        // Get biomarker values
        if (intent.hasExtra("cortisol_level")) {
            cortisolLevel = intent.getDoubleExtra("cortisol_level", 0.0);
            Log.d(TAG, "Cortisol level retrieved: " + cortisolLevel);
        }

        if (intent.hasExtra("dhea_level")) {
            dheaLevel = intent.getDoubleExtra("dhea_level", 0.0);
            Log.d(TAG, "DHEA level retrieved: " + dheaLevel);
        }

        if (intent.hasExtra("ratio")) {
            cortDheaRatio = intent.getDoubleExtra("ratio", 0.0);
            Log.d(TAG, "Cortisol/DHEA ratio retrieved: " + cortDheaRatio);
        } else if (cortisolLevel > 0 && dheaLevel > 0) {
            // Calculate ratio if not provided
            cortDheaRatio = cortisolLevel / dheaLevel;
            Log.d(TAG, "Cortisol/DHEA ratio calculated: " + cortDheaRatio);
        }
    }

    private void loadQuestionnaireSummary() {
        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        questionnaireSummary = dbHelper.getQuestionnaireSummary(userEmail);

        if (questionnaireSummary == null) {
            Log.e(TAG, "Failed to load questionnaire data for user: " + userEmail);
            Toast.makeText(this, "Error: Could not load questionnaire data", Toast.LENGTH_SHORT).show();
            // Create a default summary to prevent null pointer
            questionnaireSummary = new QuestionnaireSummary();
        } else {
            Log.d(TAG, "Questionnaire data loaded. Total score: " + questionnaireSummary.getTotalScore());
        }
    }

    private float[] prepareInputData() {
        // Use the same 9 features as in the training code in the same order
        return new float[] {
                questionnaireSummary.getPhq9Score(),        // PHQ9_Filled
                questionnaireSummary.getBdiScore(),         // BDI_Filled
                questionnaireSummary.getGad7Score(),        // GAD7_Filled
                (float) questionnaireSummary.getBrsScore(), // BRS_Filled - cast to float
                (float) cortisolLevel,                      // Cortisol_ng_ml
                (float) dheaLevel,                          // DHEA_ng_ml
                (float) cortDheaRatio,                      // Cortisol_DHEA_Ratio
                30f,                                        // Age (default to 30 if not available)
                0f                                          // Sex_Enc (0 for female, 1 for male)
        };
    }

    private float[] scaleInputData(float[] rawData) {
        // Exact means from scaler
        float[] means = {
                9.59f,      // PHQ9_Filled
                20.352f,    // BDI_Filled
                7.95f,      // GAD7_Filled
                3.27148f,   // BRS_Filled
                124.9059f,  // Cortisol_ng_ml
                10.0677f,   // DHEA_ng_ml
                12.403464f, // Cortisol_DHEA_Ratio
                41.98f,     // Age
                0.532f      // Sex_Enc
        };

        // Exact standard deviations from  scaler
        float[] stds = {
                7.28216314f,  // PHQ9_Filled
                16.22467553f, // BDI_Filled
                5.64583918f,  // GAD7_Filled
                1.17094022f,  // BRS_Filled
                42.44634194f, // Cortisol_ng_ml
                2.91909748f,  // DHEA_ng_ml
                2.06148096f,  // Cortisol_DHEA_Ratio
                13.78679078f, // Age
                0.49897495f   // Sex_Enc
        };

        float[] scaledData = new float[rawData.length];
        for (int i = 0; i < rawData.length; i++) {
            scaledData[i] = (rawData[i] - means[i]) / stds[i];
        }

        return scaledData;
    }

    private void runPredictionModel() {
        if (ortSession == null) {
            Log.e(TAG, "OrtSession is null, using fallback prediction method");
            useFallbackPrediction();
            return;
        }

        try {
            // Create input tensor with raw data
            float[] rawInputData = prepareInputData();

            // Scale the data using exact means and standard deviations
            float[] scaledInputData = scaleInputData(rawInputData);

            FloatBuffer inputBuffer = FloatBuffer.wrap(scaledInputData);
            long[] shape = {1, scaledInputData.length};  // Batch size of 1, feature count of 9

            OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnv, inputBuffer, shape);

            // Run prediction
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("float_input", inputTensor);  // Use the correct input name from your model

            OrtSession.Result results = ortSession.run(inputs);

            // Process results - assuming model outputs probabilities and class label
            // Get the first output tensor and process it
            Object outputValue = results.get(0).getValue();

            // Determine the output type and extract the predicted class
            int predictedClass = 0;
            if (outputValue instanceof float[][]) {
                float[] outputData = ((float[][]) outputValue)[0];
                predictedClass = Math.round(outputData[0]);
            } else if (outputValue instanceof long[][]) {
                long[] outputData = ((long[][]) outputValue)[0];
                predictedClass = (int) outputData[0];
            }

            // Map prediction to stress category
            switch (predictedClass) {
                case 0:
                    stressCategory = STRESS_LOW;
                    break;
                case 1:
                    stressCategory = STRESS_MODERATE;
                    break;
                case 2:
                    stressCategory = STRESS_HIGH;
                    break;
                default:
                    stressCategory = "Undetermined";
            }

            Log.d(TAG, "Prediction complete. Category: " + stressCategory + " (Class " + predictedClass + ")");

            // Clean up
            inputTensor.close();
            results.close();

        } catch (OrtException e) {
            Log.e(TAG, "Error running prediction model", e);
            useFallbackPrediction();
        }
    }

    private void useFallbackPrediction() {
        stressCategory = "Error - Using Fallback";

        // Fallback method if prediction fails
        int totalScore = questionnaireSummary.getTotalScore();
        if (totalScore < 10) {
            stressCategory = STRESS_LOW;
        } else if (totalScore < 20) {
            stressCategory = STRESS_MODERATE;
        } else {
            stressCategory = STRESS_HIGH;
        }

        Log.d(TAG, "Using fallback category based on total score: " + stressCategory);
    }

    private void displayResults() {
        // Update biomarker display
        tvCortisol.setText(String.format(Locale.getDefault(), "Cortisol: %.1f ng/mL", cortisolLevel));
        tvDhea.setText(String.format(Locale.getDefault(), "DHEA: %.1f ng/mL", dheaLevel));
        tvRatio.setText(String.format(Locale.getDefault(), "Cortisol/DHEA Ratio: %.3f", cortDheaRatio));

        // Update mental health score
        tvMentalHealthScore.setText("Total Score: " + questionnaireSummary.getTotalScore());

        // Update result category and visual indicators
        tvResultTitle.setText(stressCategory);

        // Set progress bar and description based on stress category
        switch (stressCategory) {
            case STRESS_LOW:
                progressStressLevel.setProgress(25);
                resultCategoryCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.stress_low));
                tvResultDescription.setText(getString(R.string.low_stress_description));
                break;

            case STRESS_MODERATE:
                progressStressLevel.setProgress(50);
                resultCategoryCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.stress_moderate));
                tvResultDescription.setText(getString(R.string.moderate_stress_description));
                break;

            case STRESS_HIGH:
                progressStressLevel.setProgress(85);
                resultCategoryCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.stress_high));
                tvResultDescription.setText(getString(R.string.high_stress_description));
                break;

            default:
                progressStressLevel.setProgress(0);
                resultCategoryCard.setCardBackgroundColor(Color.GRAY);
                tvResultDescription.setText("Could not determine stress level accurately. Please consult with a healthcare professional.");
        }

        // Add timestamp
        TextView tvTimestamp = new TextView(this);
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault());
        tvTimestamp.setText("Analysis completed on: " + sdf.format(new Date()));
        tvTimestamp.setPadding(16, 24, 16, 8);
        resultsContainer.addView(tvTimestamp);
    }

    private void generateRecommendations() {
        StringBuilder recommendationsBuilder = new StringBuilder();

        // Add general recommendations based on stress category
        recommendationsBuilder.append("Based on your results, we recommend:\n\n");

        // Add specific recommendations based on stress category
        switch (stressCategory) {
            case STRESS_LOW:
                recommendationsBuilder.append("• Continue your current wellness practices\n");
                recommendationsBuilder.append("• Maintain regular physical activity\n");
                recommendationsBuilder.append("• Practice mindfulness to sustain your mental health\n");
                recommendationsBuilder.append("• Schedule regular check-ins with yourself to monitor your stress levels\n");
                break;

            case STRESS_MODERATE:
                recommendationsBuilder.append("• Increase regular physical activity to at least 30 minutes daily\n");
                recommendationsBuilder.append("• Practice deep breathing exercises twice daily\n");
                recommendationsBuilder.append("• Consider limiting caffeine and alcohol consumption\n");
                recommendationsBuilder.append("• Establish a regular sleep schedule\n");
                recommendationsBuilder.append("• Consider speaking with a mental health professional\n");
                break;

            case STRESS_HIGH:
                recommendationsBuilder.append("• Consult with a healthcare professional as soon as possible\n");
                recommendationsBuilder.append("• Implement stress reduction techniques such as meditation, yoga, or deep breathing\n");
                recommendationsBuilder.append("• Prioritize sleep hygiene and aim for 7-8 hours of quality sleep\n");
                recommendationsBuilder.append("• Reduce workload if possible and delegate tasks\n");
                recommendationsBuilder.append("• Consider professional support through therapy or counseling\n");
                break;

            default:
                recommendationsBuilder.append("• Consult with a healthcare professional for personalized advice\n");
                recommendationsBuilder.append("• Maintain regular physical activity\n");
                recommendationsBuilder.append("• Practice stress management techniques\n");
        }

        // Add biomarker-specific recommendations
        recommendationsBuilder.append("\nBiomarker Insights:\n");

        // Check cortisol levels
        if (cortisolLevel > 25.0) {
            recommendationsBuilder.append("• Your cortisol levels are elevated, indicating heightened stress response\n");
            recommendationsBuilder.append("• Consider stress reduction activities like meditation and yoga\n");
        } else if (cortisolLevel < 5.0) {
            recommendationsBuilder.append("• Your cortisol levels are lower than typical\n");
            recommendationsBuilder.append("• Focus on energy-building activities and regular sleep patterns\n");
        } else {
            recommendationsBuilder.append("• Your cortisol levels are within typical ranges\n");
        }

        // Check DHEA levels
        if (dheaLevel < 2.0) {
            recommendationsBuilder.append("• Your DHEA levels are on the lower side\n");
            recommendationsBuilder.append("• Consider discussing with a healthcare provider\n");
        }

        // Check ratio
        if (cortDheaRatio > 10.0) {
            recommendationsBuilder.append("• Your cortisol/DHEA ratio is elevated, suggesting chronic stress\n");
            recommendationsBuilder.append("• Prioritize stress management and consider professional support\n");
        }

        // Add general health advice
        recommendationsBuilder.append("\nGeneral Wellness Tips:\n");
        recommendationsBuilder.append("• Stay hydrated throughout the day\n");
        recommendationsBuilder.append("• Maintain a balanced diet rich in vegetables and whole foods\n");
        recommendationsBuilder.append("• Practice gratitude journaling to improve mental outlook\n");
        recommendationsBuilder.append("• Schedule regular follow-up assessments to track your progress\n");

        // Set recommendations text
        recommendations = recommendationsBuilder.toString();
        tvRecommendations.setText(recommendations);
    }

    private void sendResultsToDoctor() {
        // Create summary of results to send
        StringBuilder resultsSummary = new StringBuilder();
        resultsSummary.append("Assessment Results for ").append(userEmail).append("\n\n");
        resultsSummary.append("Date: ").append(new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(new Date())).append("\n\n");
        resultsSummary.append("Stress Category: ").append(stressCategory).append("\n\n");

        resultsSummary.append("Biomarkers:\n");
        resultsSummary.append("- Cortisol: ").append(String.format(Locale.getDefault(), "%.1f ng/mL", cortisolLevel)).append("\n");
        resultsSummary.append("- DHEA: ").append(String.format(Locale.getDefault(), "%.1f ng/mL", dheaLevel)).append("\n");
        resultsSummary.append("- Ratio: ").append(String.format(Locale.getDefault(), "%.3f", cortDheaRatio)).append("\n\n");

        resultsSummary.append("Mental Health Screening:\n");
        resultsSummary.append("- Total Score: ").append(questionnaireSummary.getTotalScore()).append("\n");
        if (questionnaireSummary.isPhq9Filled()) {
            resultsSummary.append("- PHQ-9 Score: ").append(questionnaireSummary.getPhq9Score()).append("\n");
        }
        if (questionnaireSummary.isBdiFilled()) {
            resultsSummary.append("- BDI Score: ").append(questionnaireSummary.getBdiScore()).append("\n");
        }
        if (questionnaireSummary.isGad7Filled()) {
            resultsSummary.append("- GAD-7 Score: ").append(questionnaireSummary.getGad7Score()).append("\n");
        }
        if (questionnaireSummary.isBrsFilled()) {
            resultsSummary.append("- BRS Score: ").append(String.format(Locale.getDefault(), "%.2f", questionnaireSummary.getBrsScore())).append("\n");
        }

        resultsSummary.append("\nRecommendations:\n").append(recommendations);

        // Create email intent
        Intent emailIntent = new Intent(Intent.ACTION_SEND);
        emailIntent.setType("message/rfc822");
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Mental Health and Biomarker Assessment Results");
        emailIntent.putExtra(Intent.EXTRA_TEXT, resultsSummary.toString());

        // Try to launch email app
        try {
            startActivity(Intent.createChooser(emailIntent, "Send results via email..."));
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(this, "No email clients installed", Toast.LENGTH_SHORT).show();
        }
    }

    private void goToMainActivity() {
        String role = getIntent().getStringExtra("user_role");
        Intent intent;
        if ("CARE_PROVIDER".equals(role)) {
            intent = new Intent(this, CareProviderDashboardActivity.class);
        } else {
            intent = new Intent(this, DashboardActivity.class);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up ONNX resources
        try {
            if (ortSession != null) {
                ortSession.close();
            }
            if (ortEnv != null) {
                ortEnv.close();
            }
        } catch (OrtException e) {
            Log.e(TAG, "Error closing ONNX resources", e);
        }
    }
}