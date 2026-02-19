package com.example.myapplication;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class PatientListAdapter extends RecyclerView.Adapter<PatientListAdapter.PatientViewHolder> {

    public interface OnPatientClickListener {
        void onPatientClick(String patientEmail);
    }

    private List<String> patients = new ArrayList<>();
    private final OnPatientClickListener listener;

    public PatientListAdapter(OnPatientClickListener listener) {
        this.listener = listener;
    }

    public void setPatients(List<String> patients) {
        this.patients = patients;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PatientViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_patient, parent, false);
        return new PatientViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PatientViewHolder holder, int position) {
        String email = patients.get(position);
        holder.bind(email, listener);
    }

    @Override
    public int getItemCount() {
        return patients.size();
    }

    static class PatientViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvPatientName;
        private final TextView tvPatientEmail;

        public PatientViewHolder(@NonNull View itemView) {
            super(itemView);
            tvPatientName = itemView.findViewById(R.id.tvPatientName);
            tvPatientEmail = itemView.findViewById(R.id.tvPatientEmail);
        }

        public void bind(String email, OnPatientClickListener listener) {
            String displayName = email.split("@")[0];
            displayName = displayName.substring(0, 1).toUpperCase() + displayName.substring(1);
            tvPatientName.setText(displayName);
            tvPatientEmail.setText(email);
            itemView.setOnClickListener(v -> listener.onPatientClick(email));

            // Tap animation
            itemView.setOnTouchListener((v, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start();
                } else if (event.getAction() == android.view.MotionEvent.ACTION_UP
                        || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                }
                return false;
            });
        }
    }
}
