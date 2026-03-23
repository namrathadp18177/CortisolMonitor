package com.example.myapplication;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.MenuItem;
import android.widget.Button;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.LegendRenderer;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

public class DeveloperModeActivity extends AppCompatActivity {

    private static final String TAG = "DevMode";

    private GraphView graph;
    private LineGraphSeries<DataPoint> seriesPort1;
    private LineGraphSeries<DataPoint> seriesAvg;

    private final Handler handler = new Handler();

    // Tracks last plotted index so we only append NEW points
    private int lastPlottedIndex = 0;

    // Update graph every 200ms
    private final Runnable updateGraph = new Runnable() {
        @Override
        public void run() {

            int len = OutputBluetooth.devModeXAxis.size();

            // Append only new data
            while (lastPlottedIndex < len) {

                double t = OutputBluetooth.devModeXAxis.get(lastPlottedIndex);
                double y1 = OutputBluetooth.devModeYAxis.get(lastPlottedIndex);
                double yav = (OutputBluetooth.devModeAvgY.size() > lastPlottedIndex)
                        ? OutputBluetooth.devModeAvgY.get(lastPlottedIndex)
                        : y1;

                seriesPort1.appendData(
                        new DataPoint(t, y1),
                        true,      // scroll to end
                        5000       // max points kept in memory
                );

                seriesAvg.appendData(
                        new DataPoint(t, yav),
                        true,
                        5000
                );

                lastPlottedIndex++;
            }

            handler.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_developer_mode);

        // IMPORTANT: Activate developer mode data feed
        OutputBluetooth.devModeActive = true;

        Toolbar toolbar = findViewById(R.id.devToolbar);
        setSupportActionBar(toolbar);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Developer Mode");
        }

        Button settingsBtn = findViewById(R.id.devSettingsButton);
        settingsBtn.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(this, SettingsActivity.class);
                intent.putExtra("sps", OutputBluetooth.devSamplesPerSecond);
                intent.putExtra("avgpoints", OutputBluetooth.devMovingAvgValue);
                intent.putExtra("duration", OutputBluetooth.devDurationOfExp);
                intent.putExtra("analog_port", OutputBluetooth.devAnalogPort);
                startActivityForResult(intent, 1);
            } catch (Exception e) {
                Log.e(TAG, "Error starting Dev Settings", e);
            }
        });

        setupGraph();

        handler.post(updateGraph);
    }

    private void setupGraph() {

        graph = findViewById(R.id.graphViewDev);
        graph.setTitle("Voltage (V) vs Time (s)");

        // X axis
        graph.getViewport().setXAxisBoundsManual(true);
        graph.getViewport().setMinX(0);
        graph.getViewport().setMaxX(OutputBluetooth.devDurationOfExp);

        // Y axis fixed 0–5V
        graph.getViewport().setYAxisBoundsManual(true);
        graph.getViewport().setMinY(0.0);
        graph.getViewport().setMaxY(5.0);

        graph.getViewport().setScrollable(true);
        graph.getViewport().setScalable(true);

        graph.getGridLabelRenderer().setNumVerticalLabels(6);
        graph.getGridLabelRenderer().setNumHorizontalLabels(6);

        // Create series once
        seriesPort1 = new LineGraphSeries<>();
        seriesPort1.setTitle("Port 1");
        seriesPort1.setColor(Color.MAGENTA);
        seriesPort1.setThickness(2);

        seriesAvg = new LineGraphSeries<>();
        seriesAvg.setTitle("Filtered");
        seriesAvg.setColor(Color.BLACK);
        seriesAvg.setThickness(3);

        graph.addSeries(seriesPort1);
        graph.addSeries(seriesAvg);

        graph.getLegendRenderer().setVisible(true);
        graph.getLegendRenderer().setAlign(LegendRenderer.LegendAlign.TOP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {

            OutputBluetooth.devSamplesPerSecond =
                    data.getIntExtra("sps", OutputBluetooth.devSamplesPerSecond);

            OutputBluetooth.devMovingAvgValue =
                    data.getIntExtra("avgpoints", OutputBluetooth.devMovingAvgValue);

            OutputBluetooth.devDurationOfExp =
                    data.getDoubleExtra("duration", OutputBluetooth.devDurationOfExp);

            boolean[] newPorts = data.getBooleanArrayExtra("analog_port");
            if (newPorts != null) {
                OutputBluetooth.devAnalogPort = newPorts;
            }

            // Clear buffers for fresh plotting
            OutputBluetooth.devModeXAxis.clear();
            OutputBluetooth.devModeYAxis.clear();
            OutputBluetooth.devModeAvgY.clear();

            lastPlottedIndex = 0;

            seriesPort1.resetData(new DataPoint[]{});
            seriesAvg.resetData(new DataPoint[]{});

            graph.getViewport().setMaxX(OutputBluetooth.devDurationOfExp);

            Log.i(TAG, "Developer settings updated");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        handler.removeCallbacks(updateGraph);

        OutputBluetooth.devModeActive = false;

        OutputBluetooth.devModeXAxis.clear();
        OutputBluetooth.devModeYAxis.clear();
        OutputBluetooth.devModeAvgY.clear();
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