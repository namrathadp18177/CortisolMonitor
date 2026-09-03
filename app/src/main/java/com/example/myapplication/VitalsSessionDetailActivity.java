package com.example.myapplication;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shows a summary (time range, duration, per-signal sample counts) for one watch-streaming
 *  session, with a button to export just that session's data. Receives "session_id",
 *  "start_ms", "end_ms" extras from {@link VitalsSessionHistoryActivity}. */
public class VitalsSessionDetailActivity extends AppCompatActivity {

    private ExecutorService executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vitals_session_detail);

        Toolbar toolbar = findViewById(R.id.vitalsSessionDetailToolbar);
        setSupportActionBar(toolbar);

        String sessionId = getIntent().getStringExtra("session_id");
        long startMs = getIntent().getLongExtra("start_ms", 0);
        long endMs = getIntent().getLongExtra("end_ms", 0);

        if (getSupportActionBar() != null) {
            SimpleDateFormat dayFmt = new SimpleDateFormat("EEEE, MMM dd, yyyy", Locale.getDefault());
            getSupportActionBar().setTitle(startMs > 0 ? dayFmt.format(new Date(startMs)) : "Session Detail");
        }

        populateSummary(startMs, endMs);

        findViewById(R.id.btnExportVitalsSession).setOnClickListener(v -> {
            if (sessionId == null || sessionId.isEmpty()) return;
            VitalsCsvExporter.exportVitalsSessionAndShare(this, sessionId);
        });

        executor = Executors.newSingleThreadExecutor();
        loadSignalCounts(sessionId);
    }

    private void populateSummary(long startMs, long endMs) {
        TextView tvDate = findViewById(R.id.tvVitalsDetailDate);
        TextView tvRange = findViewById(R.id.tvVitalsDetailTimeRange);

        SimpleDateFormat dayFmt = new SimpleDateFormat("EEEE, MMM dd, yyyy", Locale.getDefault());
        SimpleDateFormat timeFmt = new SimpleDateFormat("hh:mm:ss a", Locale.getDefault());

        if (startMs > 0) {
            tvDate.setText(dayFmt.format(new Date(startMs)));
        }

        if (startMs > 0 && endMs >= startMs) {
            long durationSec = (endMs - startMs) / 1000;
            long min = durationSec / 60;
            long sec = durationSec % 60;
            tvRange.setText(timeFmt.format(new Date(startMs)) + " — " + timeFmt.format(new Date(endMs))
                    + String.format(Locale.US, "  (%d min %d sec)", min, sec));
        } else {
            tvRange.setText("Time range unavailable");
        }
    }

    private void loadSignalCounts(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        executor.execute(() -> {
            VitalsDatabaseHelper.SessionSignalCounts counts =
                    VitalsDatabaseHelper.getInstance(this).getSessionSignalCounts(sessionId);

            runOnUiThread(() -> {
                ((TextView) findViewById(R.id.tvVitalsDetailEda))
                        .setText("EDA (skin conductance): " + counts.edaCount + " samples");
                ((TextView) findViewById(R.id.tvVitalsDetailHr))
                        .setText("Heart rate / IBI: " + counts.hrCount + " samples");
                ((TextView) findViewById(R.id.tvVitalsDetailSkinTemp))
                        .setText("Skin temperature: " + counts.skinTempCount + " samples");
                ((TextView) findViewById(R.id.tvVitalsDetailAccel))
                        .setText("Accelerometer: " + counts.accelCount + " samples");
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdown();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
