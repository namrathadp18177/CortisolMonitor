package com.example.myapplication;

import android.content.Context;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;

/**
 * Runs the trained Random Forest ONNX model (random_forest_model_ir9.onnx)
 * to classify overall stress level from questionnaire + biomarker inputs.
 *
 * Input features (9 total, must be in this exact order):
 *   0  PHQ9_Filled          – PHQ-9 score (0-27)
 *   1  BDI_Filled           – BDI score   (0-63)
 *   2  GAD7_Filled          – GAD-7 score (0-21)
 *   3  BRS_Filled           – BRS average score (1.0-5.0)
 *   4  Cortisol_ng_ml       – Cortisol in ng/mL
 *   5  DHEA_ng_ml           – DHEA in ng/mL
 *   6  Cortisol_DHEA_Ratio  – Cortisol / DHEA ratio
 *   7  Age                  – Patient age in years
 *   8  Sex_Enc              – 0 = Female, 1 = Male
 *
 * Outputs:
 *   output_label      – predicted class int64 (0, 1, or 2)
 *   output_probability – map of class → probability
 *
 * Classes: 0 = Healthy/Low, 1 = Moderate Stress, 2 = High Stress
 *
 * StandardScaler parameters extracted from scaler.pkl (sklearn 1.6.1):
 *   mean_  = [9.59, 20.352, 7.95, 3.2715, 124.9059, 10.0677, 12.4035, 41.98, 0.532]
 *   scale_ = [7.2822, 16.2247, 5.6458, 1.1709, 42.4463, 2.9191, 2.0615, 13.7868, 0.499]
 */
public class StressPredictor {

    private static final String TAG = "StressPredictor";
    public static final String MODEL_FILE = "random_forest_model_ir9.onnx";

    private static final Object SESSION_LOCK = new Object();
    private static OrtEnvironment ortEnvironment;
    private static OrtSession ortSession;

    // Predicted class labels
    public static final int CLASS_HEALTHY  = 0;
    public static final int CLASS_MODERATE = 1;
    public static final int CLASS_HIGH     = 2;

    // ── StandardScaler parameters from scaler.pkl ────────────────────────────
    private static final double[] MEAN = {
            9.59,       // PHQ9_Filled
            20.352,     // BDI_Filled
            7.95,       // GAD7_Filled
            3.2715,     // BRS_Filled
            124.9059,   // Cortisol_ng_ml
            10.0677,    // DHEA_ng_ml
            12.4035,    // Cortisol_DHEA_Ratio
            41.98,      // Age
            0.532       // Sex_Enc
    };

    private static final double[] SCALE = {
            7.2822,     // PHQ9_Filled
            16.2247,    // BDI_Filled
            5.6458,     // GAD7_Filled
            1.1709,     // BRS_Filled
            42.4463,    // Cortisol_ng_ml
            2.9191,     // DHEA_ng_ml
            2.0615,     // Cortisol_DHEA_Ratio
            13.7868,    // Age
            0.499       // Sex_Enc
    };

    // ── Result holder ─────────────────────────────────────────────────────────
    public static class Result {
        public final int predictedClass;          // 0, 1, or 2
        public final float[] probabilities;       // [p0, p1, p2]

        Result(int cls, float[] probs) {
            this.predictedClass = cls;
            this.probabilities  = probs;
        }

        /** Human-readable stress level label. */
        public String getLabel() {
            switch (predictedClass) {
                case CLASS_HEALTHY:  return "Healthy";
                case CLASS_MODERATE: return "Moderate Stress";
                case CLASS_HIGH:     return "High Stress";
                default:             return "Unknown";
            }
        }

        /** Confidence % for the predicted class (0-100). */
        public int getConfidencePct() {
            if (probabilities == null || predictedClass < 0
                    || predictedClass >= probabilities.length) return 0;
            return Math.round(probabilities[predictedClass] * 100f);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Assessment-only prediction — biomarker values are imputed with the
     * training-set mean so they don't bias the result.
     *
     * @param phq9Score   PHQ-9 score (0-27), or 0 if questionnaire not taken
     * @param bdiScore    BDI score   (0-63), or 0 if questionnaire not taken
     * @param gad7Score   GAD-7 score (0-21), or 0 if questionnaire not taken
     * @param brsScore    BRS average (1.0-5.0), or training mean (3.27) if not taken
     * @param ageYears    Patient age, or 0 to use training mean
     * @param isMale      true = Male (1), false = Female (0)
     */
    public static Result predictAssessmentOnly(Context ctx,
                                               int phq9Score, int bdiScore,
                                               int gad7Score, float brsScore,
                                               int ageYears, boolean isMale) {
        double cortisol = MEAN[4];  // impute training mean → z-score = 0
        double dhea     = MEAN[5];
        double ratio    = MEAN[6];
        return predict(ctx, phq9Score, bdiScore, gad7Score, brsScore,
                cortisol, dhea, ratio, ageYears, isMale);
    }

    /**
     * Full prediction with both assessment and biomarker data.
     */
    public static Result predict(Context ctx,
                                 int phq9Score, int bdiScore, int gad7Score, float brsScore,
                                 double cortisolNgMl, double dheaNgMl, double ratio,
                                 int ageYears, boolean isMale) {
        try {
            // Build raw feature vector
            double[] raw = {
                    phq9Score,
                    bdiScore,
                    gad7Score,
                    brsScore,
                    cortisolNgMl,
                    dheaNgMl,
                    ratio,
                    ageYears > 0 ? ageYears : MEAN[7],   // fallback to training mean
                    isMale ? 1.0 : 0.0
            };

            // Apply StandardScaler: z = (x - mean) / scale
            float[] scaled = new float[raw.length];
            for (int i = 0; i < raw.length; i++) {
                scaled[i] = (float) ((raw[i] - MEAN[i]) / SCALE[i]);
            }

            // Load and run the ONNX model
            byte[] modelBytes = loadModelBytes(ctx);
            if (modelBytes == null) {
                Log.e(TAG, "Could not load model from assets");
                return fallback(raw);
            }

            OrtSession session = getOrCreateSession(ctx, modelBytes);
            if (session == null) {
                Log.e(TAG, "Could not create ONNX session");
                return fallback(raw);
            }

            long[] shape = {1L, 9L};
            FloatBuffer buf = FloatBuffer.wrap(scaled);
            OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnvironment, buf, shape);

            Map<String, OnnxTensor> inputs = Collections.singletonMap("float_input", inputTensor);
            OrtSession.Result ortResult = session.run(inputs);

            // Read predicted class label
            // output_label has shape [1], so getValue() returns long[] (not Long scalar)
            Object labelObj = ortResult.get(0).getValue();
            long predictedClass;
            if (labelObj instanceof long[]) {
                predictedClass = ((long[]) labelObj)[0];
            } else if (labelObj instanceof Long) {
                predictedClass = (Long) labelObj;
            } else {
                predictedClass = ((Number) labelObj).longValue();
            }

            // Read probabilities from the ZipMap output (map of int → float)
            float[] probs = new float[3];
            try {
                Object probObj = ortResult.get(1).getValue();
                // probObj is a List<Map<Long, Float>>
                if (probObj instanceof java.util.List) {
                    java.util.List<?> list = (java.util.List<?>) probObj;
                    if (!list.isEmpty() && list.get(0) instanceof java.util.Map) {
                        java.util.Map<?, ?> probMap = (java.util.Map<?, ?>) list.get(0);
                        for (Map.Entry<?, ?> e : probMap.entrySet()) {
                            int cls = ((Number) e.getKey()).intValue();
                            if (cls >= 0 && cls < 3) {
                                probs[cls] = ((Number) e.getValue()).floatValue();
                            }
                        }
                    }
                }
            } catch (Exception probEx) {
                Log.w(TAG, "Could not read probabilities: " + probEx.getMessage());
            }

            ortResult.close();
            inputTensor.close();

            int cls = (int) Math.max(0, Math.min(2, predictedClass));
            Log.d(TAG, "ONNX prediction: class=" + cls
                    + " probs=" + probs[0] + "/" + probs[1] + "/" + probs[2]);
            return new Result(cls, probs);

        } catch (Exception e) {
            Log.e(TAG, "ONNX inference error: " + e.getMessage(), e);
            return null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static OrtSession getOrCreateSession(Context ctx, byte[] modelBytes) {
        synchronized (SESSION_LOCK) {
            if (ortSession != null) return ortSession;
            try {
                ortEnvironment = OrtEnvironment.getEnvironment();
                OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                ortSession = ortEnvironment.createSession(modelBytes, opts);
                return ortSession;
            } catch (Exception e) {
                Log.e(TAG, "Failed to create ONNX session: " + e.getMessage(), e);
                return null;
            }
        }
    }

    private static byte[] loadModelBytes(Context ctx) {
        try (InputStream is = ctx.getAssets().open(MODEL_FILE)) {
            byte[] data = new byte[is.available()];
            is.read(data);
            return data;
        } catch (Exception e) {
            Log.e(TAG, "loadModelBytes error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Simple rule-based fallback if ONNX inference fails.
     * Uses PHQ-9 score (index 0) as the primary signal.
     */
    private static Result fallback(double[] raw) {
        int phq = (int) raw[0];
        int cls = phq <= 4 ? CLASS_HEALTHY : phq <= 14 ? CLASS_MODERATE : CLASS_HIGH;
        return new Result(cls, new float[]{cls == 0 ? 0.8f : 0.1f,
                                           cls == 1 ? 0.7f : 0.1f,
                                           cls == 2 ? 0.8f : 0.1f});
    }
}
