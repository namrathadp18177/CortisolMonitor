package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lists every past watch-streaming session (one per Start/Stop cycle) for the currently
 * logged-in user, newest first. Tapping a session opens {@link VitalsSessionDetailActivity}
 * to view a summary and export just that session's data.
 */
public class VitalsSessionHistoryActivity extends AppCompatActivity {

    private RecyclerView rvSessions;
    private TextView tvEmpty;
    private ExecutorService executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vitals_session_history);

        Toolbar toolbar = findViewById(R.id.vitalsHistoryToolbar);
        setSupportActionBar(toolbar);

        rvSessions = findViewById(R.id.rvVitalsSessions);
        tvEmpty    = findViewById(R.id.tvNoVitalsSessions);
        rvSessions.setLayoutManager(new LinearLayoutManager(this));

        executor = Executors.newSingleThreadExecutor();
        loadSessions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSessions();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdown();
    }

    private void loadSessions() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }
        String userEmail = DatabaseHelper.getCurrentUserEmail();
        executor.execute(() -> {
            List<VitalsDatabaseHelper.SessionSummary> sessions =
                    VitalsDatabaseHelper.getInstance(this).getDistinctSessions(userEmail);

            runOnUiThread(() -> {
                if (sessions == null || sessions.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    rvSessions.setVisibility(View.GONE);
                } else {
                    tvEmpty.setVisibility(View.GONE);
                    rvSessions.setVisibility(View.VISIBLE);
                    rvSessions.setAdapter(new SessionAdapter(sessions));
                }
            });
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.VH> {

        private final List<VitalsDatabaseHelper.SessionSummary> sessions;

        SessionAdapter(List<VitalsDatabaseHelper.SessionSummary> sessions) {
            this.sessions = sessions;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_vitals_session, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.bind(sessions.get(position));
        }

        @Override
        public int getItemCount() {
            return sessions.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView tvDate;
            final TextView tvMeta;

            VH(@NonNull View itemView) {
                super(itemView);
                tvDate = itemView.findViewById(R.id.tvVitalsSessionDate);
                tvMeta = itemView.findViewById(R.id.tvVitalsSessionMeta);
            }

            void bind(VitalsDatabaseHelper.SessionSummary s) {
                SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy  hh:mm a", Locale.getDefault());
                tvDate.setText(sdf.format(new Date(s.startMs)));

                long durationSec = Math.max(0, (s.endMs - s.startMs) / 1000);
                long min = durationSec / 60;
                long sec = durationSec % 60;
                tvMeta.setText(String.format(Locale.US, "%d min %d sec  •  %,d samples",
                        min, sec, s.sampleCount));

                itemView.setOnClickListener(v -> {
                    Intent intent = new Intent(VitalsSessionHistoryActivity.this,
                            VitalsSessionDetailActivity.class);
                    intent.putExtra("session_id", s.sessionId);
                    intent.putExtra("start_ms", s.startMs);
                    intent.putExtra("end_ms", s.endMs);
                    startActivity(intent);
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
                });
            }
        }
    }
}
