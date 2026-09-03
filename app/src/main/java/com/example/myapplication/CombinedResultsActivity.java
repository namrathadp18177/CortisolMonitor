package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Legacy combined-results screen. It used to recalculate cortisol/DHEA from voltages
 * (with an inverted mV→V conversion) and optionally jitter those values with a random
 * walk. That is unsafe for research data, so this activity now only forwards to
 * {@link IntegratedResultsActivity}, which stores the ONNX label.
 */
public class CombinedResultsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent next = new Intent(this, IntegratedResultsActivity.class);
        Intent incoming = getIntent();
        if (incoming != null && incoming.getExtras() != null) {
            next.putExtras(incoming.getExtras());
        }
        startActivity(next);
        finish();
    }
}
