package com.example.myapplication;

import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;


public class ViewHistoryActivity extends AppCompatActivity {

    private RecyclerView rvHistory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_view_history);

        rvHistory = findViewById(R.id.rvHistory);

        // Add this line:
        rvHistory.setLayoutManager(
                new androidx.recyclerview.widget.LinearLayoutManager(this)
        );

        List<PatientHistoryRow> rows = HistoryDataHelper.getAllPatientHistory(this);
        rvHistory.setAdapter(new HistoryAdapter(rows));
    }


    static class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder> {

        private final List<PatientHistoryRow> rows;

        HistoryAdapter(List<PatientHistoryRow> rows) {
            this.rows = rows;
        }

        @NonNull
        @Override
        public HistoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_history_patient_card, parent, false);
            return new HistoryViewHolder(v);
        }


        @Override
        public void onBindViewHolder(@NonNull HistoryViewHolder holder, int position) {
            PatientHistoryRow row = rows.get(position);
            holder.bind(row);
        }

        @Override
        public int getItemCount() {
            return rows != null ? rows.size() : 0;
        }

        static class HistoryViewHolder extends RecyclerView.ViewHolder {
            TextView tvName, tvEmail, tvVitals, tvCortisol;

            HistoryViewHolder(@NonNull View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvName);
                tvEmail = itemView.findViewById(R.id.tvEmail);
                tvVitals = itemView.findViewById(R.id.tvVitals);
                tvCortisol = itemView.findViewById(R.id.tvCortisol);
            }

            void bind(PatientHistoryRow row) {
                String namePart = (row.name != null && !row.name.isEmpty())
                        ? row.name
                        : "(no name)";
                tvName.setText(namePart);
                tvEmail.setText(row.email);

                tvVitals.setText(String.format(
                        "Ht: %.2f m   Wt: %.1f kg   BMI: %.1f",
                        row.heightM, row.weightKg, row.bmi
                ));

                if (row.latestCortisol >= 0) {
                    tvCortisol.setText(String.format("Last test: %.2f µg/dL", row.latestCortisol));
                } else {
                    tvCortisol.setText("Last test: N/A");
                }

                // add this INSIDE bind
                itemView.setOnClickListener(v -> {
                    Context ctx = v.getContext();
                    Intent intent = new Intent(ctx, PatientHistoryDetailActivity.class);
                    intent.putExtra("patient_email", row.email);
                    ctx.startActivity(intent);
                });
            }

        }

    }
}
