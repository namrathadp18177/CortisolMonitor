package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ModifyPatientActivity extends AppCompatActivity {

    private RecyclerView rvPatients;
    private UserDatabaseHelper dbHelper;
    private String currentCpEmail;
    private String providerMedicalId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_modify_existing_patient);

        dbHelper = UserDatabaseHelper.getInstance(this);
        currentCpEmail = UserDatabaseHelper.getCurrentUserEmail();
        providerMedicalId = dbHelper.getCareProviderMedicalId(currentCpEmail);

        rvPatients = findViewById(R.id.rvPatients);

        loadPatients();
    }

    private void loadPatients() {
        List<UserDatabaseHelper.CpPatientInfo> patients =
                dbHelper.getPatientsForProvider(providerMedicalId);

        PatientCardAdapter adapter = new PatientCardAdapter(patients, new PatientCardAdapter.OnPatientClickListener() {
            @Override
            public void onPatientClick(UserDatabaseHelper.CpPatientInfo info) {
                // Open EditPatientActivity with patientId and email
                Intent intent = new Intent(ModifyPatientActivity.this, EditPatientActivity.class);
                intent.putExtra("patient_email", info.email);
                intent.putExtra("patient_id", info.patientId);
                startActivity(intent);
            }
        });

        rvPatients.setAdapter(adapter);
    }

    // Adapter class
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
                tvPatientId = itemView.findViewById(R.id.tvPatientId);
                tvPatientName = itemView.findViewById(R.id.tvPatientName);
            }

            void bind(final UserDatabaseHelper.CpPatientInfo info,
                      final OnPatientClickListener listener) {
                tvPatientId.setText(info.patientId);
                tvPatientName.setText(info.name != null ? info.name : info.email);

                itemView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (listener != null) listener.onPatientClick(info);
                    }
                });
            }
        }
    }
}
