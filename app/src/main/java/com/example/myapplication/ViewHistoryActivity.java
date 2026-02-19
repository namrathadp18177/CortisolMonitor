package com.example.myapplication;

import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

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
        public HistoryViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            android.view.View v = android.view.LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_history_row, parent, false);
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
            TextView tvNameEmail, tvVitals, tvCortisol;

            HistoryViewHolder(@NonNull android.view.View itemView) {
                super(itemView);
                tvNameEmail = itemView.findViewById(R.id.tvNameEmail);
                tvVitals = itemView.findViewById(R.id.tvVitals);
                tvCortisol = itemView.findViewById(R.id.tvCortisol);
            }

            void bind(PatientHistoryRow row) {
                String namePart = (row.name != null && !row.name.isEmpty())
                        ? row.name
                        : "(no name)";
                tvNameEmail.setText(namePart + " (" + row.email + ")");

                tvVitals.setText(String.format(
                        "Ht: %.2f m  Wt: %.1f kg  BMI: %.1f",
                        row.heightM, row.weightKg, row.bmi
                ));

                if (row.latestCortisol >= 0) {
                    tvCortisol.setText(String.format("Latest cortisol: %.2f", row.latestCortisol));
                } else {
                    tvCortisol.setText("Latest cortisol: N/A");
                }
            }
        }
    }
}
