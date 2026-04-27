package com.example.myapplication;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.card.MaterialCardView;

public class CareProviderInfoActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_care_provider_info);

        // Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Care Provider");
        }

        TextView tvNotLinked      = findViewById(R.id.tvNotLinked);
        MaterialCardView cardInfo = findViewById(R.id.cardProviderInfo);
        TextView tvName           = findViewById(R.id.tvProviderName);
        TextView tvDesignation    = findViewById(R.id.tvProviderDesignation);
        TextView tvOrganization   = findViewById(R.id.tvProviderOrganization);

        String patientEmail = UserDatabaseHelper.getCurrentUserEmail();
        UserDatabaseHelper db = UserDatabaseHelper.getInstance(this);

        String medicalId = (patientEmail != null && !patientEmail.isEmpty())
                ? db.getCareProviderMedicalIdForPatient(patientEmail)
                : null;

        if (medicalId == null || medicalId.isEmpty()) {
            tvNotLinked.setVisibility(android.view.View.VISIBLE);
            cardInfo.setVisibility(android.view.View.GONE);
            return;
        }

        String name         = db.getCareProviderNameByMedicalId(medicalId);
        String designation  = db.getCareProviderDesignationByMedicalId(medicalId);
        String organization = db.getCareProviderOrganizationByMedicalId(medicalId);

        tvName.setText(name != null && !name.isEmpty() ? name : "—");
        tvDesignation.setText(designation != null && !designation.isEmpty() ? designation : "—");
        tvOrganization.setText(organization != null && !organization.isEmpty() ? organization : "—");

        tvNotLinked.setVisibility(android.view.View.GONE);
        cardInfo.setVisibility(android.view.View.VISIBLE);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
