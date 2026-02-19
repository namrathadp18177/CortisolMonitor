package com.example.myapplication;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.data.QuestionnaireResponse;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class
ResponsesViewerActivity extends AppCompatActivity {

    private RecyclerView rvResponses;
    private TextView tvNoResponses;
    private ExecutorService executor;
    private ResponsesAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_responses_viewer);

        // Set up toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowHomeEnabled(true);

        // Initialize UI components
        rvResponses = findViewById(R.id.rvResponses);
        tvNoResponses = findViewById(R.id.tvNoResponses);

        // Set up RecyclerView
        rvResponses.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ResponsesAdapter();
        rvResponses.setAdapter(adapter);

        // Initialize executor for database operations
        executor = Executors.newSingleThreadExecutor();

        // Load responses
        loadResponses();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.responses_viewer_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        
        if (id == android.R.id.home) {
            onBackPressed();
            return true;
        } else if (id == R.id.action_delete_all) {
            showDeleteConfirmationDialog();
            return true;
        }
        
        return super.onOptionsItemSelected(item);
    }

    private void showDeleteConfirmationDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Delete All Responses")
            .setMessage("Are you sure you want to delete all questionnaire responses? This action cannot be undone.")
            .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    deleteAllResponses();
                }
            })
            .setNegativeButton("Cancel", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show();
    }

    private void deleteAllResponses() {
        executor.execute(() -> {
            try {
                DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
                // Add a method to delete all responses in the QuestionnaireDao
                int deletedCount = dbHelper.getQuestionnaireDao().deleteAllResponses();
                
                runOnUiThread(() -> {
                    Toast.makeText(this, deletedCount + " responses deleted", Toast.LENGTH_SHORT).show();
                    // Reload the empty list
                    loadResponses();
                });
            } catch (Exception e) {
                Log.e("ResponsesViewerActivity", "Error deleting responses: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Failed to delete responses: " + e.getMessage(), 
                        Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void loadResponses() {
        executor.execute(() -> {
            try {
                DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
                List<QuestionnaireResponse> responses = dbHelper.getQuestionnaireDao().getAllResponses();

                runOnUiThread(() -> {
                    if (responses == null || responses.isEmpty()) {
                        tvNoResponses.setVisibility(View.VISIBLE);
                        rvResponses.setVisibility(View.GONE);
                        tvNoResponses.setText("No questionnaire responses found.\n\nComplete a questionnaire from the dashboard to see your responses here.");
                    } else {
                        tvNoResponses.setVisibility(View.GONE);
                        rvResponses.setVisibility(View.VISIBLE);
                        adapter.setResponses(responses);
                    }
                });
            } catch (Exception e) {
                Log.e("ResponsesViewerActivity", "Error loading responses: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    tvNoResponses.setVisibility(View.VISIBLE);
                    rvResponses.setVisibility(View.GONE);
                    tvNoResponses.setText("Error loading responses.\nPlease try again later.");
                    Toast.makeText(ResponsesViewerActivity.this, 
                        "Failed to load responses: " + e.getMessage(), 
                        Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdown();
        }
    }

    private class ResponsesAdapter extends RecyclerView.Adapter<ResponsesAdapter.ResponseViewHolder> {
        private List<QuestionnaireResponse> responses;

        public void setResponses(List<QuestionnaireResponse> responses) {
            this.responses = responses;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ResponseViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.item_response, parent, false);
            return new ResponseViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ResponseViewHolder holder, int position) {
            QuestionnaireResponse response = responses.get(position);
            holder.bind(response);
        }

        @Override
        public int getItemCount() {
            return responses != null ? responses.size() : 0;
        }

        class ResponseViewHolder extends RecyclerView.ViewHolder {
            private TextView tvSection, tvQuestion, tvAnswer, tvTimestamp;

            public ResponseViewHolder(@NonNull View itemView) {
                super(itemView);
                tvSection = itemView.findViewById(R.id.tvSection);
                tvQuestion = itemView.findViewById(R.id.tvQuestion);
                tvAnswer = itemView.findViewById(R.id.tvAnswer);
                tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
            }

            public void bind(QuestionnaireResponse response) {
                try {
                    if (response == null) {
                        Log.w("ResponsesViewerActivity", "Null response provided to bind method");
                        return;
                    }
                    
                    tvSection.setText(response.getSection() != null ? response.getSection() : "Unknown Section");
                    tvQuestion.setText(response.getQuestionText() != null ? response.getQuestionText() : "Unknown Question");
                    
                    // Convert answer value to text
                    String answerText;
                    switch (response.getAnswer()) {
                        case 0:
                            answerText = "Not at all";
                            break;
                        case 1:
                            answerText = "Several days";
                            break;
                        case 2:
                            answerText = "More than half the days";
                            break;
                        case 3:
                            answerText = "Nearly every day";
                            break;
                        default:
                            answerText = "Unknown";
                    }
                    tvAnswer.setText("Answer: " + answerText);
                    
                    // Format timestamp if available
                    if (response.getTimestamp() > 0) {
                        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());
                        String date = sdf.format(new Date(response.getTimestamp()));
                        tvTimestamp.setText(date);
                        tvTimestamp.setVisibility(View.VISIBLE);
                    } else {
                        tvTimestamp.setVisibility(View.GONE);
                    }
                } catch (Exception e) {
                    Log.e("ResponsesViewerActivity", "Error binding response data: " + e.getMessage(), e);
                    // Set fallback values
                    tvSection.setText("Error loading section");
                    tvQuestion.setText("Error loading question");
                    tvAnswer.setText("Error loading answer");
                    tvTimestamp.setVisibility(View.GONE);
                }
            }
        }
    }
} 