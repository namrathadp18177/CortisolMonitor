package com.example.myapplication;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Generic "log list" screen for any health data category (Notes, Symptoms,
 * Prescriptions, Reports, Conditions, Habits).
 *
 * Caller passes:
 *   EXTRA_PREFS_KEY   — SharedPreferences key for this category
 *   EXTRA_TITLE       — Toolbar title
 *   EXTRA_HINT        — EditText hint for text-entry categories
 *   EXTRA_IS_FILE     — true if entries come from a file picker (not typed text)
 *   EXTRA_SUCCESS_MSG — Snackbar message shown after saving
 */
public class HealthLogActivity extends AppCompatActivity {

    public static final String EXTRA_PREFS_KEY   = "prefs_key";
    public static final String EXTRA_TITLE       = "title";
    public static final String EXTRA_HINT        = "hint";
    public static final String EXTRA_IS_FILE     = "is_file";
    public static final String EXTRA_SUCCESS_MSG = "success_msg";

    private static final String PREFS_NAME      = "plus_fragment_data";
    private static final String ENTRY_SEPARATOR = "|||";

    private String prefsKey;
    private String hint;
    private String successMsg;
    private boolean isFile;

    private RecyclerView recyclerView;
    private View emptyState;
    private EntryAdapter adapter;
    private List<String> entries = new ArrayList<>();

    private ActivityResultLauncher<String[]> filePickerLauncher;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_health_log);

        prefsKey   = getIntent().getStringExtra(EXTRA_PREFS_KEY);
        hint       = getIntent().getStringExtra(EXTRA_HINT);
        successMsg = getIntent().getStringExtra(EXTRA_SUCCESS_MSG);
        isFile     = getIntent().getBooleanExtra(EXTRA_IS_FILE, false);
        String title = getIntent().getStringExtra(EXTRA_TITLE);

        Toolbar toolbar = findViewById(R.id.healthLogToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(title != null ? title : "Log");
        }

        emptyState   = findViewById(R.id.healthLogEmptyState);
        recyclerView = findViewById(R.id.healthLogRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new EntryAdapter(entries);
        recyclerView.setAdapter(adapter);

        setupFilePicker();

        FloatingActionButton fab = findViewById(R.id.healthLogFab);
        fab.setOnClickListener(v -> {
            if (isFile) {
                filePickerLauncher.launch(new String[]{"*/*"});
            } else {
                showAddSheet();
            }
        });

        loadEntries();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File picker
    // ─────────────────────────────────────────────────────────────────────────

    private void setupFilePicker() {
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null) return;
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) {}
                    String name = getFileName(uri);
                    saveEntry(name);
                    loadEntries();
                    Snackbar.make(recyclerView,
                            successMsg != null ? successMsg : "Saved.",
                            Snackbar.LENGTH_SHORT).show();
                });
    }

    private String getFileName(Uri uri) {
        String path = uri.getLastPathSegment();
        if (path != null && path.contains("/")) path = path.substring(path.lastIndexOf('/') + 1);
        return path != null ? path : uri.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bottom sheet for text entry
    // ─────────────────────────────────────────────────────────────────────────

    private void showAddSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        // Title
        TextView tvTitle = new TextView(this);
        tvTitle.setText(getSupportActionBar() != null
                ? getSupportActionBar().getTitle() : "Add Entry");
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(0xFF1C1B1F);
        tvTitle.setPadding(0, 0, 0, dp(12));
        root.addView(tvTitle);

        // Input field
        EditText etInput = new EditText(this);
        etInput.setHint(hint != null ? hint : "Enter details...");
        etInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etInput.setMinLines(3);
        etInput.setMaxLines(8);
        etInput.setBackground(null);
        etInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams etParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        etParams.bottomMargin = dp(16);
        root.addView(etInput, etParams);

        // Save button
        MaterialButton btnSave = new MaterialButton(this);
        btnSave.setText("Save");
        btnSave.setTextColor(0xFFFFFFFF);
        btnSave.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        getResources().getColor(R.color.primary, null)));
        btnSave.setCornerRadius(dp(14));
        btnSave.setOnClickListener(v -> {
            String text = etInput.getText().toString().trim();
            if (TextUtils.isEmpty(text)) {
                etInput.setError("Please enter some text");
                return;
            }
            saveEntry(text);
            loadEntries();
            sheet.dismiss();
            Snackbar.make(recyclerView,
                    successMsg != null ? successMsg : "Saved.",
                    Snackbar.LENGTH_SHORT).show();
        });
        root.addView(btnSave, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        sheet.setContentView(root);
        sheet.show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SharedPreferences persistence
    // ─────────────────────────────────────────────────────────────────────────

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void saveEntry(String value) {
        String timestamp = new SimpleDateFormat("MMM dd, yyyy  hh:mm a", Locale.getDefault())
                .format(new Date());
        String newEntry = timestamp + " \u2014 " + value;

        String existing = prefs().getString(prefsKey, "");
        String combined = TextUtils.isEmpty(existing)
                ? newEntry
                : newEntry + ENTRY_SEPARATOR + existing;

        prefs().edit().putString(prefsKey, combined).apply();
    }

    private void loadEntries() {
        String raw = prefs().getString(prefsKey, "");
        entries.clear();
        if (!TextUtils.isEmpty(raw)) {
            entries.addAll(Arrays.asList(raw.split("\\|\\|\\|")));
        }
        adapter.notifyDataSetChanged();

        if (entries.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RecyclerView adapter
    // ─────────────────────────────────────────────────────────────────────────

    private static class EntryAdapter extends RecyclerView.Adapter<EntryAdapter.VH> {

        private final List<String> data;

        EntryAdapter(List<String> data) { this.data = data; }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_health_log_entry, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            String entry = data.get(position);
            int dashIdx = entry.indexOf(" \u2014 ");
            if (dashIdx > 0) {
                h.tvTimestamp.setText(entry.substring(0, dashIdx));
                h.tvContent.setText(entry.substring(dashIdx + 3));
            } else {
                h.tvContent.setText(entry);
                h.tvTimestamp.setVisibility(View.GONE);
            }
        }

        @Override
        public int getItemCount() { return data.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvContent, tvTimestamp;
            VH(@NonNull View v) {
                super(v);
                tvContent   = v.findViewById(R.id.entryContent);
                tvTimestamp = v.findViewById(R.id.entryTimestamp);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────────────────

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
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
