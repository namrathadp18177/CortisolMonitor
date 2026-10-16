package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class ModifyPatientActivity extends AppCompatActivity {

    private RecyclerView rvPatients;
    private UserDatabaseHelper dbHelper;
    private String currentCpEmail;
    private String providerMedicalId;

    private PatientCardAdapter adapter;
    private final List<UserDatabaseHelper.CpPatientInfo> patients = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!RoleGuard.requireCareProvider(this)) return;
        setContentView(R.layout.activity_modify_existing_patient);

        dbHelper = UserDatabaseHelper.getInstance(this);
        currentCpEmail = UserDatabaseHelper.getCurrentUserEmail();
        providerMedicalId = dbHelper.getCareProviderMedicalId(currentCpEmail);

        // Set up toolbar with back navigation
        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            }
        }

        rvPatients = findViewById(R.id.rvPatients);

        adapter = new PatientCardAdapter(patients, info -> showPatientOptionsDialog(info));
        rvPatients.setAdapter(adapter);

        loadPatients();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPatients();
    }

    private void loadPatients() {
        List<UserDatabaseHelper.CpPatientInfo> latest =
                dbHelper.getPatientsForProvider(providerMedicalId);
        patients.clear();
        if (latest != null) patients.addAll(latest);
        adapter.notifyDataSetChanged();
    }

    // ── Step 1: Ask what to do with this patient ──
    private void showPatientOptionsDialog(UserDatabaseHelper.CpPatientInfo info) {
        String[] options = {
                "Edit Patient Details",
                "Start Test for this Patient",
                "Start Session for this Patient"
        };

        new AlertDialog.Builder(this)
                .setTitle(info.name != null ? info.name : info.email)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        // existing edit flow — unchanged
                        Intent intent = new Intent(this, EditPatientActivity.class);
                        intent.putExtra("patient_email", info.email);
                        intent.putExtra("patient_id", info.patientId);
                        intent.putExtra("user_role", "CARE_PROVIDER");
                        startActivity(intent);
                    } else if (which == 1) {
                        // existing biomarker-only flow (with hardware step selector) — unchanged
                        showStepSelectorDialog(info);
                    } else {
                        // new: watch + questionnaire + biomarker tied together, see
                        // StartSessionActivity. Deliberately a separate option rather than
                        // rerouting "Start Test for this Patient" through it, since that
                        // existing flow's step selector (mid-hardware-procedure resume) has
                        // no equivalent here yet.
                        Intent intent = new Intent(this, StartSessionActivity.class);
                        intent.putExtra("patient_email", info.email);
                        startActivity(intent);
                    }
                })
                .setCancelable(true)
                .show();
    }

    // ── Step 2: Ask which step to start from ──
    private void showStepSelectorDialog(UserDatabaseHelper.CpPatientInfo info) {
        String[] steps = {
                "Step 1: Scan tube QR",
                "Step 2: Collect saliva",
                "Step 3: Scan cartridge and add sample",
                "Step 4: Insert the cartridge to analyser",
                "Step 5: Measure cortisol"
        };

        new AlertDialog.Builder(this)
                .setTitle("Where would you like to start?")
                .setItems(steps, (dialog, which) -> launchBiomarker(info, which))
                .setCancelable(true)
                .show();
    }


    // ── Step 3: Launch BiomarkerInstructions with patient + step ──
    private void launchBiomarker(UserDatabaseHelper.CpPatientInfo info, int startStep) {
        android.util.Log.d("STEP_DEBUG", "ModifyPatient launching with start_step: " + startStep);

        // Skip BiomarkerInstructions for care providers — go straight to BiomarkerActivty
        Intent intent = new Intent(this, BiomarkerActivty.class);
        intent.putExtra("user_role", "CARE_PROVIDER");
        intent.putExtra("patient_email", info.email);
        intent.putExtra("start_step", startStep);
        startActivity(intent);
    }



    // ── Adapter ──
    public static class PatientCardAdapter extends RecyclerView.Adapter<PatientCardAdapter.PatientViewHolder> {

        public interface OnPatientClickListener {
            void onPatientClick(UserDatabaseHelper.CpPatientInfo info);
        }

        private final List<UserDatabaseHelper.CpPatientInfo> patients;
        private final OnPatientClickListener listener;

        public PatientCardAdapter(List<UserDatabaseHelper.CpPatientInfo> patients,
                                  OnPatientClickListener listener) {
            this.patients = patients;
            this.listener = listener;
        }

        @NonNull
        @Override
        public PatientViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            android.view.View view = android.view.LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_patient_card, parent, false);
            return new PatientViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull PatientViewHolder holder, int position) {
            UserDatabaseHelper.CpPatientInfo info = patients.get(position);
            holder.bind(info, listener);
        }

        @Override
        public int getItemCount() {
            return patients != null ? patients.size() : 0;
        }

        static class PatientViewHolder extends RecyclerView.ViewHolder {

            TextView tvPatientId;
            TextView tvPatientName;

            public PatientViewHolder(@NonNull View itemView) {
                super(itemView);
                tvPatientId   = itemView.findViewById(R.id.tvPatientId);
                tvPatientName = itemView.findViewById(R.id.tvPatientName);
            }

            void bind(final UserDatabaseHelper.CpPatientInfo info,
                      final OnPatientClickListener listener) {
                tvPatientId.setText(info.patientId);
                tvPatientName.setText(info.name != null ? info.name : info.email);
                itemView.setOnClickListener(v -> {
                    if (listener != null) listener.onPatientClick(info);
                });
            }
        }
    }
}
