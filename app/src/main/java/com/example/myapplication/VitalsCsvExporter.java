package com.example.myapplication;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Exports everything in {@link VitalsDatabaseHelper} (EDA, heart rate + IBI, skin
 * temperature, accelerometer) plus the existing cortisol/biomarker and questionnaire results
 * from {@link DatabaseHelper} to CSV files in the app's external files directory, ready to be
 * shared off the phone via {@link androidx.core.content.FileProvider}.
 *
 * Kept as separate files per signal rather than one merged/aligned table because the
 * trackers don't sample at identical instants — resampling/alignment is better done in
 * Python (e.g. pandas merge_asof) during ML preprocessing than lossily inside the app.
 *
 * The app is single-patient-per-login (no "view all patients" admin mode), so every bulk
 * export here ({@link #exportAll}, {@link #exportQuestionnaireOnly}) is scoped to whoever is
 * currently logged in — see DatabaseHelper.getCurrentUserEmail(). For finer-grained exports
 * (one questionnaire sitting, one day's cortisol tests, one watch-streaming session), see the
 * per-session methods further down, used by SessionDetailActivity, PatientResultsActivity,
 * and the vitals session history screen respectively.
 *
 * Every vitals row carries session_id and participant_email so recordings and subjects can
 * be told apart later; join the cortisol CSV to the vitals CSVs on participant_email plus
 * timestamp proximity (use received_at_ms on the vitals side — see chat notes on why that
 * column and not timestamp_ms is the one comparable to the cortisol timestamp).
 *
 * Must be called off the main thread — does file I/O and synchronous SQLite reads.
 */
public class VitalsCsvExporter {

    public static class ExportResult {
        public final List<Uri> fileUris;
        public final int edaRows;
        public final int hrRows;
        public final int skinTempRows;
        public final int accelRows;
        public final int cortisolRows;
        public final int questionnaireRows;

        ExportResult(List<Uri> fileUris, int edaRows, int hrRows, int skinTempRows,
                     int accelRows, int cortisolRows, int questionnaireRows) {
            this.fileUris = fileUris;
            this.edaRows = edaRows;
            this.hrRows = hrRows;
            this.skinTempRows = skinTempRows;
            this.accelRows = accelRows;
            this.cortisolRows = cortisolRows;
            this.questionnaireRows = questionnaireRows;
        }

        public int totalRows() {
            return edaRows + hrRows + skinTempRows + accelRows + cortisolRows + questionnaireRows;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BULK EXPORT — scoped to whoever is currently logged in
    // ═══════════════════════════════════════════════════════════════════════

    public static ExportResult exportAll(Context context) throws IOException {
        String userEmail = DatabaseHelper.getCurrentUserEmail();
        VitalsDatabaseHelper vdb = VitalsDatabaseHelper.getInstance(context);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            throw new IOException("External files directory unavailable");
        }

        String authority = context.getPackageName() + ".fileprovider";
        List<Uri> uris = new ArrayList<>();

        File edaFile = new File(dir, "eda_" + stamp + ".csv");
        int edaRows = writeEda(vdb, userEmail, edaFile);
        uris.add(FileProvider.getUriForFile(context, authority, edaFile));

        File hrFile = new File(dir, "hr_" + stamp + ".csv");
        int hrRows = writeHr(vdb, userEmail, hrFile);
        uris.add(FileProvider.getUriForFile(context, authority, hrFile));

        File tempFile = new File(dir, "skin_temp_" + stamp + ".csv");
        int tempRows = writeSkinTemp(vdb, userEmail, tempFile);
        uris.add(FileProvider.getUriForFile(context, authority, tempFile));

        File accelFile = new File(dir, "accel_" + stamp + ".csv");
        int accelRows = writeAccel(vdb, userEmail, accelFile);
        uris.add(FileProvider.getUriForFile(context, authority, accelFile));

        File cortisolFile = new File(dir, "cortisol_" + stamp + ".csv");
        DatabaseHelper db = DatabaseHelper.getInstance(context);
        int cortisolRows = writeCortisolCursor(db.getBiomarkerExperimentsForUser(userEmail), cortisolFile);
        uris.add(FileProvider.getUriForFile(context, authority, cortisolFile));

        File questionnaireFile = new File(dir, "questionnaire_" + stamp + ".csv");
        int questionnaireRows = writeQuestionnaireCursor(
                db.getQuestionnaireResponsesForUserWithSessionBounds(userEmail), questionnaireFile);
        uris.add(FileProvider.getUriForFile(context, authority, questionnaireFile));

        return new ExportResult(uris, edaRows, hrRows, tempRows, accelRows, cortisolRows, questionnaireRows);
    }

    /** Exports only the four vitals CSVs (EDA/HR/skinTemp/accel) for whoever is currently
     *  logged in — no cortisol or questionnaire files. Used by the Live Vitals screen, which
     *  should only export its own data rather than the full bundle (that's what Combined
     *  Results is for). */
    public static ExportResult exportVitalsOnly(Context context) throws IOException {
        String userEmail = DatabaseHelper.getCurrentUserEmail();
        VitalsDatabaseHelper vdb = VitalsDatabaseHelper.getInstance(context);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            throw new IOException("External files directory unavailable");
        }
        String authority = context.getPackageName() + ".fileprovider";
        List<Uri> uris = new ArrayList<>();

        File edaFile = new File(dir, "eda_" + stamp + ".csv");
        int edaRows = writeEda(vdb, userEmail, edaFile);
        uris.add(FileProvider.getUriForFile(context, authority, edaFile));

        File hrFile = new File(dir, "hr_" + stamp + ".csv");
        int hrRows = writeHr(vdb, userEmail, hrFile);
        uris.add(FileProvider.getUriForFile(context, authority, hrFile));

        File tempFile = new File(dir, "skin_temp_" + stamp + ".csv");
        int tempRows = writeSkinTemp(vdb, userEmail, tempFile);
        uris.add(FileProvider.getUriForFile(context, authority, tempFile));

        File accelFile = new File(dir, "accel_" + stamp + ".csv");
        int accelRows = writeAccel(vdb, userEmail, accelFile);
        uris.add(FileProvider.getUriForFile(context, authority, accelFile));

        return new ExportResult(uris, edaRows, hrRows, tempRows, accelRows, 0, 0);
    }

    /** Exports only the questionnaire CSV for whoever is currently logged in — used by the
     *  Questionnaires/Assessments tab, which should only export its own data rather than the
     *  full vitals+cortisol bundle. */
    public static ExportResult exportQuestionnaireOnly(Context context) throws IOException {
        String userEmail = DatabaseHelper.getCurrentUserEmail();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            throw new IOException("External files directory unavailable");
        }
        String authority = context.getPackageName() + ".fileprovider";

        File questionnaireFile = new File(dir, "questionnaire_" + stamp + ".csv");
        DatabaseHelper db = DatabaseHelper.getInstance(context);
        int questionnaireRows = writeQuestionnaireCursor(
                db.getQuestionnaireResponsesForUserWithSessionBounds(userEmail), questionnaireFile);
        Uri uri = FileProvider.getUriForFile(context, authority, questionnaireFile);

        return new ExportResult(Collections.singletonList(uri), 0, 0, 0, 0, 0, questionnaireRows);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PER-SESSION EXPORT — one sitting / one day / one recording at a time
    // ═══════════════════════════════════════════════════════════════════════

    /** Exports only one questionnaire sitting (one session_id) — used by the "Export CSV"
     *  button on SessionDetailActivity, so exporting one assessment doesn't pull in the
     *  user's entire questionnaire history. */
    public static ExportResult exportQuestionnaireSession(Context context, String sessionId) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            throw new IOException("External files directory unavailable");
        }
        String authority = context.getPackageName() + ".fileprovider";

        File file = new File(dir, "questionnaire_session_" + stamp + ".csv");
        DatabaseHelper db = DatabaseHelper.getInstance(context);
        int rows = writeQuestionnaireCursor(
                db.getQuestionnaireResponsesForSessionWithBounds(sessionId), file);
        Uri uri = FileProvider.getUriForFile(context, authority, file);

        return new ExportResult(Collections.singletonList(uri), 0, 0, 0, 0, 0, rows);
    }

    /** Exports only one day's cortisol/biomarker tests for one patient — used by the "Export
     *  CSV" button on PatientResultsActivity (the day-detail screen reached from Cortisol
     *  Graph), so exporting one day doesn't pull in the patient's entire test history. */
    public static ExportResult exportCortisolForDay(Context context, String userEmail,
                                                      long dayStartMs, long dayEndMs) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            throw new IOException("External files directory unavailable");
        }
        String authority = context.getPackageName() + ".fileprovider";

        File file = new File(dir, "cortisol_day_" + stamp + ".csv");
        DatabaseHelper db = DatabaseHelper.getInstance(context);
        int rows = writeCortisolCursor(
                db.getBiomarkerExperimentsForUserAndRange(userEmail, dayStartMs, dayEndMs), file);
        Uri uri = FileProvider.getUriForFile(context, authority, file);

        return new ExportResult(Collections.singletonList(uri), 0, 0, 0, 0, rows, 0);
    }

    /** Exports only one watch-streaming session (one session_id) across all four vitals
     *  signal tables — used by the vitals session history screen. */
    public static ExportResult exportVitalsSession(Context context, String sessionId) throws IOException {
        VitalsDatabaseHelper vdb = VitalsDatabaseHelper.getInstance(context);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            throw new IOException("External files directory unavailable");
        }
        String authority = context.getPackageName() + ".fileprovider";
        List<Uri> uris = new ArrayList<>();

        File edaFile = new File(dir, "eda_session_" + stamp + ".csv");
        int edaRows = writeEdaForSession(vdb, sessionId, edaFile);
        uris.add(FileProvider.getUriForFile(context, authority, edaFile));

        File hrFile = new File(dir, "hr_session_" + stamp + ".csv");
        int hrRows = writeHrForSession(vdb, sessionId, hrFile);
        uris.add(FileProvider.getUriForFile(context, authority, hrFile));

        File tempFile = new File(dir, "skin_temp_session_" + stamp + ".csv");
        int tempRows = writeSkinTempForSession(vdb, sessionId, tempFile);
        uris.add(FileProvider.getUriForFile(context, authority, tempFile));

        File accelFile = new File(dir, "accel_session_" + stamp + ".csv");
        int accelRows = writeAccelForSession(vdb, sessionId, accelFile);
        uris.add(FileProvider.getUriForFile(context, authority, accelFile));

        return new ExportResult(uris, edaRows, hrRows, tempRows, accelRows, 0, 0);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Share-sheet entry points — safe to call directly from any click listener
    // ═══════════════════════════════════════════════════════════════════════

    private interface Exporter {
        ExportResult run(Context context) throws IOException;
    }

    /** Runs {@link #exportAll} off the main thread, then opens the system share sheet with
     *  all resulting CSV files for the currently logged-in user, or shows a toast on
     *  failure/empty. Used by the Live Vitals screen and the Combined Results screen, both
     *  of which export everything. */
    public static void exportAndShare(Activity activity) {
        runExportAndShare(activity, VitalsCsvExporter::exportAll, "Export all data");
    }

    /** Same as {@link #exportAndShare}, but only the current user's questionnaire CSV — used
     *  by the Questionnaires/Assessments tab. */
    public static void exportQuestionnaireAndShare(Activity activity) {
        runExportAndShare(activity, VitalsCsvExporter::exportQuestionnaireOnly, "Export questionnaire data");
    }

    /** Same as {@link #exportAndShare}, but only the current user's vitals CSVs (no
     *  cortisol/questionnaire) — used by the Live Vitals screen. */
    public static void exportVitalsAndShare(Activity activity) {
        runExportAndShare(activity, VitalsCsvExporter::exportVitalsOnly, "Export vitals data");
    }

    /** Exports and shares just one questionnaire sitting — used by SessionDetailActivity. */
    public static void exportQuestionnaireSessionAndShare(Activity activity, String sessionId) {
        runExportAndShare(activity, ctx -> exportQuestionnaireSession(ctx, sessionId), "Export session data");
    }

    /** Exports and shares just one day's cortisol tests for one patient — used by
     *  PatientResultsActivity. */
    public static void exportCortisolDayAndShare(Activity activity, String userEmail,
                                                   long dayStartMs, long dayEndMs) {
        runExportAndShare(activity,
                ctx -> exportCortisolForDay(ctx, userEmail, dayStartMs, dayEndMs),
                "Export cortisol data");
    }

    /** Exports and shares just one watch-streaming session — used by the vitals session
     *  history screen. */
    public static void exportVitalsSessionAndShare(Activity activity, String sessionId) {
        runExportAndShare(activity, ctx -> exportVitalsSession(ctx, sessionId), "Export session data");
    }

    /** Shared threading/share-sheet plumbing behind every *AndShare entry point above —
     *  handles its own threading, so it's safe to call directly from any Activity or
     *  Fragment's click listener. */
    private static void runExportAndShare(Activity activity, Exporter exporter, String label) {
        Toast.makeText(activity, "Exporting…", Toast.LENGTH_SHORT).show();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                ExportResult result = exporter.run(activity.getApplicationContext());
                main.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (result.totalRows() == 0) {
                        Toast.makeText(activity, "No data recorded yet — nothing to export", Toast.LENGTH_LONG).show();
                        return;
                    }
                    Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                    shareIntent.setType("text/csv");
                    shareIntent.putParcelableArrayListExtra(
                            Intent.EXTRA_STREAM, new ArrayList<>(result.fileUris));
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.startActivity(Intent.createChooser(shareIntent,
                            label + " (" + result.totalRows() + " rows)"));
                });
            } catch (IOException e) {
                Log.e("VitalsCsvExporter", "CSV export failed", e);
                main.post(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        Toast.makeText(activity, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Vitals CSV writers — full user history (bulk export)
    // ═══════════════════════════════════════════════════════════════════════

    private static int writeEda(VitalsDatabaseHelper db, String userEmail, File file) throws IOException {
        try (Cursor c = db.getReadableDatabase().query(
                VitalsDatabaseHelper.TABLE_EDA,
                new String[]{"session_id", "participant_email", "timestamp_ms", "conductance", "status", "received_at"},
                "participant_email = ?", new String[]{userEmail}, null, null, "timestamp_ms ASC")) {
            return writeEdaRows(c, file);
        }
    }

    private static int writeEdaForSession(VitalsDatabaseHelper db, String sessionId, File file) throws IOException {
        try (Cursor c = db.getReadableDatabase().query(
                VitalsDatabaseHelper.TABLE_EDA,
                new String[]{"session_id", "participant_email", "timestamp_ms", "conductance", "status", "received_at"},
                "session_id = ?", new String[]{sessionId}, null, null, "timestamp_ms ASC")) {
            return writeEdaRows(c, file);
        }
    }

    private static int writeEdaRows(Cursor c, File file) throws IOException {
        try (FileWriter w = new FileWriter(file)) {
            w.write("session_id,participant_email,timestamp_ms,conductance_uS,status,received_at_ms\n");
            int rows = 0;
            while (c.moveToNext()) {
                w.write(csvEscape(c.getString(0)) + "," + csvEscape(c.getString(1)) + "," + c.getLong(2) + ","
                        + c.getDouble(3) + "," + c.getInt(4) + "," + c.getLong(5) + "\n");
                rows++;
            }
            return rows;
        }
    }

    private static int writeHr(VitalsDatabaseHelper db, String userEmail, File file) throws IOException {
        try (Cursor c = db.getReadableDatabase().query(
                VitalsDatabaseHelper.TABLE_HR,
                new String[]{"session_id", "participant_email", "timestamp_ms", "heart_rate", "hr_status",
                        "ibi_list", "ibi_status_list", "received_at"},
                "participant_email = ?", new String[]{userEmail}, null, null, "timestamp_ms ASC")) {
            return writeHrRows(c, file);
        }
    }

    private static int writeHrForSession(VitalsDatabaseHelper db, String sessionId, File file) throws IOException {
        try (Cursor c = db.getReadableDatabase().query(
                VitalsDatabaseHelper.TABLE_HR,
                new String[]{"session_id", "participant_email", "timestamp_ms", "heart_rate", "hr_status",
                        "ibi_list", "ibi_status_list", "received_at"},
                "session_id = ?", new String[]{sessionId}, null, null, "timestamp_ms ASC")) {
            return writeHrRows(c, file);
        }
    }

    private static int writeHrRows(Cursor c, File file) throws IOException {
        try (FileWriter w = new FileWriter(file)) {
            w.write("session_id,participant_email,timestamp_ms,heart_rate_bpm,hr_status,ibi_list_ms,ibi_status_list,received_at_ms\n");
            int rows = 0;
            while (c.moveToNext()) {
                w.write(csvEscape(c.getString(0)) + "," + csvEscape(c.getString(1)) + "," + c.getLong(2) + ","
                        + c.getInt(3) + "," + c.getInt(4) + ","
                        + csvEscape(c.getString(5)) + "," + csvEscape(c.getString(6)) + "," + c.getLong(7) + "\n");
                rows++;
            }
            return rows;
        }
    }

    private static int writeSkinTemp(VitalsDatabaseHelper db, String userEmail, File file) throws IOException {
        try (Cursor c = db.getReadableDatabase().query(
                VitalsDatabaseHelper.TABLE_SKIN_TEMP,
                new String[]{"session_id", "participant_email", "timestamp_ms", "ambient_c", "skin_c", "status", "received_at"},
                "participant_email = ?", new String[]{userEmail}, null, null, "timestamp_ms ASC")) {
            return writeSkinTempRows(c, file);
        }
    }

    private static int writeSkinTempForSession(VitalsDatabaseHelper db, String sessionId, File file) throws IOException {
        try (Cursor c = db.getReadableDatabase().query(
                VitalsDatabaseHelper.TABLE_SKIN_TEMP,
                new String[]{"session_id", "participant_email", "timestamp_ms", "ambient_c", "skin_c", "status", "received_at"},
                "session_id = ?", new String[]{sessionId}, null, null, "timestamp_ms ASC")) {
            return writeSkinTempRows(c, file);
        }
    }

    private static int writeSkinTempRows(Cursor c, File file) throws IOException {
        try (FileWriter w = new FileWriter(file)) {
            w.write("session_id,participant_email,timestamp_ms,ambient_c,skin_c,status,received_at_ms\n");
            int rows = 0;
            while (c.moveToNext()) {
                w.write(csvEscape(c.getString(0)) + "," + csvEscape(c.getString(1)) + "," + c.getLong(2) + ","
                        + c.getDouble(3) + "," + c.getDouble(4) + "," + c.getInt(5) + "," + c.getLong(6) + "\n");
                rows++;
            }
            return rows;
        }
    }

    private static int writeAccel(VitalsDatabaseHelper db, String userEmail, File file) throws IOException {
        try (Cursor c = db.getReadableDatabase().query(
                VitalsDatabaseHelper.TABLE_ACCEL,
                new String[]{"session_id", "participant_email", "timestamp_ms", "x", "y", "z", "status", "received_at"},
                "participant_email = ?", new String[]{userEmail}, null, null, "timestamp_ms ASC")) {
            return writeAccelRows(c, file);
        }
    }

    private static int writeAccelForSession(VitalsDatabaseHelper db, String sessionId, File file) throws IOException {
        try (Cursor c = db.getReadableDatabase().query(
                VitalsDatabaseHelper.TABLE_ACCEL,
                new String[]{"session_id", "participant_email", "timestamp_ms", "x", "y", "z", "status", "received_at"},
                "session_id = ?", new String[]{sessionId}, null, null, "timestamp_ms ASC")) {
            return writeAccelRows(c, file);
        }
    }

    private static int writeAccelRows(Cursor c, File file) throws IOException {
        try (FileWriter w = new FileWriter(file)) {
            // x/y/z are raw accelerometer counts, not physical units. Convert in your
            // analysis pipeline: divide by 4096 for g-force, or divide by 4096 and
            // multiply by 9.80665 for m/s² (per Samsung's accelerometer conversion notes,
            // confirmed against real data: a stationary reading works out to ~1.0g).
            w.write("session_id,participant_email,timestamp_ms,x_raw,y_raw,z_raw,status,received_at_ms\n");
            int rows = 0;
            while (c.moveToNext()) {
                w.write(csvEscape(c.getString(0)) + "," + csvEscape(c.getString(1)) + "," + c.getLong(2) + ","
                        + c.getDouble(3) + "," + c.getDouble(4) + "," + c.getDouble(5) + ","
                        + c.getInt(6) + "," + c.getLong(7) + "\n");
                rows++;
            }
            return rows;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Cortisol / questionnaire generic cursor dumpers — shared by bulk and
    // per-session/per-day export paths so the CSV format only lives in one place
    // ═══════════════════════════════════════════════════════════════════════

    /** Dumps a biomarker_experiments cursor (whatever rows the caller already scoped —
     *  one user's full history, or one user's one day) using the cursor's own column names,
     *  so it doesn't need updating if that table's columns change.
     *
     *  Appends two computed columns, test_start_ms and test_end_ms, so it's immediately
     *  obvious when each individual test (even repeat tests by the same person on the same
     *  day) started and ended, without the analyst having to compute it themselves —
     *  test_start_ms is the existing "timestamp" column, test_end_ms adds "duration"
     *  (seconds, per OutputBluetooth's durationOfExp field) converted to milliseconds.
     *  "timestamp" is already System.currentTimeMillis()-based — the same epoch as the
     *  vitals CSVs' received_at_ms — so no conversion is needed to join the two. */
    private static int writeCortisolCursor(Cursor c, File file) throws IOException {
        try (Cursor cursor = c; FileWriter w = new FileWriter(file)) {
            String[] columnNames = cursor.getColumnNames();
            int timestampIdx = cursor.getColumnIndex("timestamp");
            int durationIdx = cursor.getColumnIndex("duration");
            boolean canComputeEnd = timestampIdx >= 0 && durationIdx >= 0;

            w.write(String.join(",", columnNames));
            if (canComputeEnd) w.write(",test_start_ms,test_end_ms");
            w.write("\n");

            int rows = 0;
            while (cursor.moveToNext()) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < columnNames.length; i++) {
                    if (i > 0) line.append(",");
                    line.append(csvEscape(cursor.getString(i)));
                }
                if (canComputeEnd) {
                    long startMs = cursor.getLong(timestampIdx);
                    double durationSec = cursor.getDouble(durationIdx);
                    long endMs = startMs + Math.round(durationSec * 1000);
                    line.append(",").append(startMs).append(",").append(endMs);
                }
                w.write(line + "\n");
                rows++;
            }
            return rows;
        }
    }

    /** Dumps a questionnaire_responses (+ session bounds) cursor — whatever rows the caller
     *  already scoped (one user's full history, or a single session_id). Each row carries
     *  session_start_ms/session_end_ms — the earliest/latest response timestamp among that
     *  same session_id + questionnaire_type + participant combination — so it's immediately
     *  clear when that particular sitting started and ended, even if the same person retakes
     *  the same questionnaire multiple times in one day. */
    private static int writeQuestionnaireCursor(Cursor c, File file) throws IOException {
        try (Cursor cursor = c; FileWriter w = new FileWriter(file)) {
            String[] columnNames = cursor.getColumnNames();
            w.write(String.join(",", columnNames) + "\n");
            int rows = 0;
            while (cursor.moveToNext()) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < columnNames.length; i++) {
                    if (i > 0) line.append(",");
                    line.append(csvEscape(cursor.getString(i)));
                }
                w.write(line + "\n");
                rows++;
            }
            return rows;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DAY-RANGE EXPORT — one vital signal (hr/eda/skin_temp), one local calendar day at a
    // time. Used by VitalsDayDetailActivity's "Export this day as CSV" button. For heart
    // rate, also writes a one-row HRV summary CSV (RMSSD/SDNN/pNN50/mean HR/mean IBI) next
    // to the raw samples, computed the same way as the on-screen HRV card
    // (see HrvCalculator).
    // ═══════════════════════════════════════════════════════════════════════

    /** Exports one vital signal's raw samples for one local calendar day (scoped to whoever
     *  is currently logged in), plus an HRV summary CSV when vitalType is heart rate. */
    public static ExportResult exportVitalsDay(Context context, String vitalType,
                                                long dayStartMs, long dayEndMs) throws IOException {
        String userEmail = DatabaseHelper.getCurrentUserEmail();
        VitalsDatabaseHelper vdb = VitalsDatabaseHelper.getInstance(context);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            throw new IOException("External files directory unavailable");
        }
        String authority = context.getPackageName() + ".fileprovider";
        List<Uri> uris = new ArrayList<>();
        int edaRows = 0, hrRows = 0, tempRows = 0;

        if (VitalsDatabaseHelper.VITAL_TYPE_HR.equals(vitalType)) {
            File hrFile = new File(dir, "hr_day_" + stamp + ".csv");
            hrRows = writeHrForDay(vdb, userEmail, dayStartMs, dayEndMs, hrFile);
            uris.add(FileProvider.getUriForFile(context, authority, hrFile));

            File hrvFile = new File(dir, "hrv_summary_day_" + stamp + ".csv");
            writeHrvSummaryForDay(vdb, userEmail, dayStartMs, dayEndMs, hrvFile);
            uris.add(FileProvider.getUriForFile(context, authority, hrvFile));
        } else if (VitalsDatabaseHelper.VITAL_TYPE_EDA.equals(vitalType)) {
            File edaFile = new File(dir, "eda_day_" + stamp + ".csv");
            edaRows = writeEdaForDay(vdb, userEmail, dayStartMs, dayEndMs, edaFile);
            uris.add(FileProvider.getUriForFile(context, authority, edaFile));
        } else if (VitalsDatabaseHelper.VITAL_TYPE_SKIN_TEMP.equals(vitalType)) {
            File tempFile = new File(dir, "skin_temp_day_" + stamp + ".csv");
            tempRows = writeSkinTempForDay(vdb, userEmail, dayStartMs, dayEndMs, tempFile);
            uris.add(FileProvider.getUriForFile(context, authority, tempFile));
        }

        return new ExportResult(uris, edaRows, hrRows, tempRows, 0, 0, 0);
    }

    /** Exports and shares one vital signal's data for one day — used by
     *  VitalsDayDetailActivity. */
    public static void exportVitalsDayAndShare(Activity activity, String vitalType,
                                                 long dayStartMs, long dayEndMs) {
        runExportAndShare(activity,
                ctx -> exportVitalsDay(ctx, vitalType, dayStartMs, dayEndMs),
                "Export day data");
    }

    /** Exports ALL FOUR vitals signals (EDA, heart rate + HRV summary, skin temperature,
     *  accelerometer) for one local calendar day at once, scoped to whoever is currently
     *  logged in -- same day range as {@link #exportVitalsDay}, just not limited to a single
     *  vitalType. Used by the "Export all signals for this day" button on
     *  VitalsDayDetailActivity, for a day-wise equivalent of {@link #exportVitalsSession}
     *  (which does the same 4-signal bundle but scoped to one session_id instead of one
     *  calendar day). Any signal with zero rows that day still gets a header-only CSV, same
     *  as the single-signal day export, so the file set is consistent day to day. */
    public static ExportResult exportAllVitalsDay(Context context, long dayStartMs, long dayEndMs)
            throws IOException {
        String userEmail = DatabaseHelper.getCurrentUserEmail();
        VitalsDatabaseHelper vdb = VitalsDatabaseHelper.getInstance(context);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            throw new IOException("External files directory unavailable");
        }
        String authority = context.getPackageName() + ".fileprovider";
        List<Uri> uris = new ArrayList<>();

        File edaFile = new File(dir, "eda_day_" + stamp + ".csv");
        int edaRows = writeEdaForDay(vdb, userEmail, dayStartMs, dayEndMs, edaFile);
        uris.add(FileProvider.getUriForFile(context, authority, edaFile));

        File hrFile = new File(dir, "hr_day_" + stamp + ".csv");
        int hrRows = writeHrForDay(vdb, userEmail, dayStartMs, dayEndMs, hrFile);
        uris.add(FileProvider.getUriForFile(context, authority, hrFile));

        File hrvFile = new File(dir, "hrv_summary_day_" + stamp + ".csv");
        writeHrvSummaryForDay(vdb, userEmail, dayStartMs, dayEndMs, hrvFile);
        uris.add(FileProvider.getUriForFile(context, authority, hrvFile));

        File tempFile = new File(dir, "skin_temp_day_" + stamp + ".csv");
        int tempRows = writeSkinTempForDay(vdb, userEmail, dayStartMs, dayEndMs, tempFile);
        uris.add(FileProvider.getUriForFile(context, authority, tempFile));

        File accelFile = new File(dir, "accel_day_" + stamp + ".csv");
        int accelRows = writeAccelForDay(vdb, userEmail, dayStartMs, dayEndMs, accelFile);
        uris.add(FileProvider.getUriForFile(context, authority, accelFile));

        return new ExportResult(uris, edaRows, hrRows, tempRows, accelRows, 0, 0);
    }

    /** Exports and shares all four vitals signals for one day at once — used by the "Export
     *  all signals for this day" button on VitalsDayDetailActivity. */
    public static void exportAllVitalsDayAndShare(Activity activity, long dayStartMs, long dayEndMs) {
        runExportAndShare(activity,
                ctx -> exportAllVitalsDay(ctx, dayStartMs, dayEndMs),
                "Export all vitals for this day");
    }

    private static int writeEdaForDay(VitalsDatabaseHelper db, String userEmail,
                                       long startMs, long endMs, File file) throws IOException {
        try (Cursor c = db.getReadableDatabase().query(
                VitalsDatabaseHelper.TABLE_EDA,
                new String[]{"session_id", "participant_email", "timestamp_ms", "conductance", "status", "received_at"},
                "participant_email = ? AND timestamp_ms >= ? AND timestamp_ms <= ?",
                new String[]{userEmail, String.valueOf(startMs), String.valueOf(endMs)},
                null, null, "timestamp_ms ASC")) {
            return writeEdaRows(c, file);
        }
    }

    private static int writeHrForDay(VitalsDatabaseHelper db, String userEmail,
                                      long startMs, long endMs, File file) throws IOException {
        try (Cursor c = db.getReadableDatabase().query(
                VitalsDatabaseHelper.TABLE_HR,
                new String[]{"session_id", "participant_email", "timestamp_ms", "heart_rate", "hr_status",
                        "ibi_list", "ibi_status_list", "received_at"},
                "participant_email = ? AND timestamp_ms >= ? AND timestamp_ms <= ?",
                new String[]{userEmail, String.valueOf(startMs), String.valueOf(endMs)},
                null, null, "timestamp_ms ASC")) {
            return writeHrRows(c, file);
        }
    }

    private static int writeSkinTempForDay(VitalsDatabaseHelper db, String userEmail,
                                            long startMs, long endMs, File file) throws IOException {
        try (Cursor c = db.getReadableDatabase().query(
                VitalsDatabaseHelper.TABLE_SKIN_TEMP,
                new String[]{"session_id", "participant_email", "timestamp_ms", "ambient_c", "skin_c", "status", "received_at"},
                "participant_email = ? AND timestamp_ms >= ? AND timestamp_ms <= ?",
                new String[]{userEmail, String.valueOf(startMs), String.valueOf(endMs)},
                null, null, "timestamp_ms ASC")) {
            return writeSkinTempRows(c, file);
        }
    }

    private static int writeAccelForDay(VitalsDatabaseHelper db, String userEmail,
                                         long startMs, long endMs, File file) throws IOException {
        try (Cursor c = db.getReadableDatabase().query(
                VitalsDatabaseHelper.TABLE_ACCEL,
                new String[]{"session_id", "participant_email", "timestamp_ms", "x", "y", "z", "status", "received_at"},
                "participant_email = ? AND timestamp_ms >= ? AND timestamp_ms <= ?",
                new String[]{userEmail, String.valueOf(startMs), String.valueOf(endMs)},
                null, null, "timestamp_ms ASC")) {
            return writeAccelRows(c, file);
        }
    }

    /** Writes a single-row CSV of RMSSD/SDNN/pNN50/mean HR/mean IBI for one day, computed
     *  from that day's valid IBI values the same way as VitalsDayDetailActivity's on-screen
     *  HRV card (see HrvCalculator) — so the exported numbers always match what was shown in
     *  the app. Writes a header-only file (with a note row) if there isn't enough IBI data. */
    private static void writeHrvSummaryForDay(VitalsDatabaseHelper db, String userEmail,
                                               long startMs, long endMs, File file) throws IOException {
        List<Integer> ibi = db.getValidIbiSeriesForRange(userEmail, startMs, endMs);
        HrvCalculator.HrvMetrics hrv = HrvCalculator.compute(ibi);
        try (FileWriter w = new FileWriter(file)) {
            w.write("day_start_ms,day_end_ms,valid_beat_count,mean_hr_bpm,mean_ibi_ms,sdnn_ms,rmssd_ms,pnn50_percent,low_confidence\n");
            if (hrv == null) {
                w.write(startMs + "," + endMs + ",0,,,,,,true\n");
            } else {
                w.write(startMs + "," + endMs + "," + hrv.beatCount + ","
                        + hrv.meanHrBpm + "," + hrv.meanIbiMs + "," + hrv.sdnnMs + ","
                        + hrv.rmssdMs + "," + hrv.pnn50Percent + "," + (!hrv.hasEnoughData()) + "\n");
            }
        }
    }

    /** Quotes a field if it contains a comma (e.g. our ibi_list/ibi_status_list JSON arrays
     *  like "[812,798,845]", or any text field that happens to contain one). */
    private static String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
