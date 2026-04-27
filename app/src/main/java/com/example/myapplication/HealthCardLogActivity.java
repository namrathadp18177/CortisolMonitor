package com.example.myapplication;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Generic health-card logger that replaces SymptomLogActivity, ConditionLogActivity
 * and HabitLogActivity. The screen behaves identically to those three; the only thing
 * that changes is the {@link Spec} configuration chosen via the {@link #EXTRA_TYPE}
 * intent extra.
 *
 * Stored entry formats (unchanged from the original activities so existing user data
 * keeps rendering correctly):
 *   Symptom:   "SYMPTOM:<name>|SEV:<severity>|NOTE:<notes>|TS:<ts>"
 *   Condition: "COND:<name>|STATUS:<status>|NOTE:<notes>|TS:<ts>"
 *   Habit:     "HABIT:<cat>|EMOJI:<emoji>|AMOUNT:<x>|UNIT:<unit>|COLOR:<#hex>|NOTE:<notes>|TS:<ts>"
 *
 * @see PlusFragment for the call sites.
 */
public class HealthCardLogActivity extends AppCompatActivity {

    public static final String EXTRA_TYPE       = "type";
    public static final String TYPE_SYMPTOM     = "symptom";
    public static final String TYPE_CONDITION   = "condition";
    public static final String TYPE_HABIT       = "habit";

    private static final String PREFS_NAME = "plus_fragment_data";
    private static final String ENTRY_SEP  = "|||";
    private static final String FIELD_SEP  = "|";

    // ─────────────────────────────────────────────────────────────────────────
    // Per-type configuration
    // ─────────────────────────────────────────────────────────────────────────

    /** Tells the activity which kind of layout / data shape it is dealing with. */
    private enum Kind { CHIP, HABIT }

    /** Static per-type configuration: titles, layout choices, taxonomy. */
    private static final class Spec {
        final Kind   kind;
        final String prefsKey;
        final String entryPrefix;     // e.g. "SYMPTOM"
        final String secondaryKey;    // e.g. "SEV", "STATUS", or null for habit
        final String title;
        final String emptyEmoji;
        final String emptyTitle;
        final String emptySubtitle;
        final String leadingEmoji;    // emoji at the start of each card row
        final String sheetTitle;
        final String nameHint;
        final String chipLabel;       // "Severity" / "Status" — null for habit
        final String[] chipOptions;   // 3 button labels — null for habit
        final int[]   chipColors;     // 3 active button colors — null for habit
        final String saveButtonText;
        final String savedSnackbar;
        final String deletedSnackbar;

        Spec(Kind kind, String prefsKey, String entryPrefix, String secondaryKey,
             String title, String emptyEmoji, String emptyTitle, String emptySubtitle,
             String leadingEmoji, String sheetTitle, String nameHint,
             String chipLabel, String[] chipOptions, int[] chipColors,
             String saveButtonText, String savedSnackbar, String deletedSnackbar) {
            this.kind = kind;
            this.prefsKey = prefsKey;
            this.entryPrefix = entryPrefix;
            this.secondaryKey = secondaryKey;
            this.title = title;
            this.emptyEmoji = emptyEmoji;
            this.emptyTitle = emptyTitle;
            this.emptySubtitle = emptySubtitle;
            this.leadingEmoji = leadingEmoji;
            this.sheetTitle = sheetTitle;
            this.nameHint = nameHint;
            this.chipLabel = chipLabel;
            this.chipOptions = chipOptions;
            this.chipColors = chipColors;
            this.saveButtonText = saveButtonText;
            this.savedSnackbar = savedSnackbar;
            this.deletedSnackbar = deletedSnackbar;
        }
    }

    /** Returns the Spec matching the EXTRA_TYPE value (defaults to symptom). */
    private static Spec specFor(String type) {
        if (TYPE_CONDITION.equals(type)) {
            return new Spec(
                    Kind.CHIP, "conditions", "COND", "STATUS",
                    "Medical Conditions", "📋", "No conditions recorded",
                    "Tap + to add a medical condition",
                    "🏥", "Add Medical Condition",
                    "Condition name (e.g. Hypertension, Asthma)",
                    "Status",
                    new String[]{"Active", "Managed", "Resolved"},
                    new int[]{
                            Color.parseColor("#B71C1C"),  // Active   — red
                            Color.parseColor("#E65100"),  // Managed  — orange
                            Color.parseColor("#2E7D32")   // Resolved — green
                    },
                    "Save Condition", "Condition saved.", "Condition deleted"
            );
        }
        if (TYPE_HABIT.equals(type)) {
            return new Spec(
                    Kind.HABIT, "habits", "HABIT", null,
                    "Habits", "🌱", "No habits tracked yet",
                    "Tap + to record sleep, exercise, water and more",
                    "📌", "Record a Habit", null,
                    null, null, null,
                    "Save Habit", "Habit recorded.", "Habit deleted"
            );
        }
        // Default — symptom
        return new Spec(
                Kind.CHIP, "symptoms", "SYMPTOM", "SEV",
                "Symptoms", "🩺", "No symptoms logged yet",
                "Tap + to log a symptom with severity",
                "🩺", "Log a Symptom",
                "Symptom name (e.g. Headache, Fatigue)",
                "Severity",
                new String[]{"Mild", "Moderate", "Severe"},
                new int[]{
                        Color.parseColor("#2E7D32"),  // Mild     — green
                        Color.parseColor("#E65100"),  // Moderate — orange
                        Color.parseColor("#B71C1C")   // Severe   — red
                },
                "Save Symptom", "Symptom logged.", "Symptom deleted"
        );
    }

    // Habit categories: label, emoji, unit suggestion, card color
    private static final String[][] HABIT_CATEGORIES = {
            {"Sleep",      "🛌", "hours",   "#1565C0"},
            {"Exercise",   "🏃", "minutes", "#2E7D32"},
            {"Water",      "💧", "glasses", "#0277BD"},
            {"Nutrition",  "🥗", "meals",   "#558B2F"},
            {"Meditation", "🧘", "minutes", "#6A1B9A"},
            {"Medication", "💊", "doses",   "#AD1457"},
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Instance state
    // ─────────────────────────────────────────────────────────────────────────

    private Spec               spec;
    private RecyclerView       rv;
    private View               emptyState;
    private RecyclerView.Adapter<?> adapter;
    private final List<String> entries = new ArrayList<>();

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_health_card_log);

        spec = specFor(getIntent().getStringExtra(EXTRA_TYPE));

        setupToolbar();
        setupEmptyState();
        setupRecyclerView();
        setupFab();
        attachSwipeToDelete();
        loadEntries();
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.hcToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(spec.title);
        }
    }

    private void setupEmptyState() {
        emptyState = findViewById(R.id.hcEmptyState);
        ((TextView) findViewById(R.id.hcEmptyEmoji)).setText(spec.emptyEmoji);
        ((TextView) findViewById(R.id.hcEmptyTitle)).setText(spec.emptyTitle);
        ((TextView) findViewById(R.id.hcEmptySubtitle)).setText(spec.emptySubtitle);
    }

    private void setupRecyclerView() {
        rv = findViewById(R.id.hcRecyclerView);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = (spec.kind == Kind.HABIT)
                ? new HabitAdapter(entries)
                : new ChipCardAdapter(entries, spec);
        rv.setAdapter(adapter);
    }

    private void setupFab() {
        FloatingActionButton fab = findViewById(R.id.hcFab);
        fab.setOnClickListener(v -> showAddSheet());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Swipe-to-delete (shared by both shapes)
    // ─────────────────────────────────────────────────────────────────────────

    private void attachSwipeToDelete() {
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView r,
                                  @NonNull RecyclerView.ViewHolder v,
                                  @NonNull RecyclerView.ViewHolder t) { return false; }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int pos = viewHolder.getAdapterPosition();
                if (pos == RecyclerView.NO_ID) return;
                String removed = entries.get(pos);
                entries.remove(pos);
                persistEntries();
                adapter.notifyItemRemoved(pos);
                toggleEmptyState();

                Snackbar.make(rv, spec.deletedSnackbar, Snackbar.LENGTH_LONG)
                        .setAction("Undo", v2 -> {
                            entries.add(pos, removed);
                            persistEntries();
                            adapter.notifyItemInserted(pos);
                            toggleEmptyState();
                        }).show();
            }
        }).attachToRecyclerView(rv);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bottom sheets — one per Kind
    // ─────────────────────────────────────────────────────────────────────────

    private void showAddSheet() {
        if (spec.kind == Kind.HABIT) {
            showHabitSheet();
        } else {
            showChipSheet();
        }
    }

    /** Sheet used by Symptom + Condition (chip-toggle entry). */
    private void showChipSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View root = getLayoutInflater().inflate(R.layout.sheet_add_chip_entry, null);

        // Show chip block, hide habit block
        root.findViewById(R.id.chipBlock).setVisibility(View.VISIBLE);
        root.findViewById(R.id.habitBlock).setVisibility(View.GONE);

        TextView       tvTitle    = root.findViewById(R.id.tvChipSheetTitle);
        TextView       tvChipLbl  = root.findViewById(R.id.tvChipSectionLabel);
        com.google.android.material.textfield.TextInputLayout nameLayout
                = root.findViewById(R.id.chipNameInputLayout);
        EditText       etName     = root.findViewById(R.id.etChipName);
        EditText       etNotes    = root.findViewById(R.id.etChipNotes);
        MaterialButton btnOpt1    = root.findViewById(R.id.btnChipOpt1);
        MaterialButton btnOpt2    = root.findViewById(R.id.btnChipOpt2);
        MaterialButton btnOpt3    = root.findViewById(R.id.btnChipOpt3);
        MaterialButton btnSave    = root.findViewById(R.id.btnChipSave);

        tvTitle.setText(spec.sheetTitle);
        tvChipLbl.setText(spec.chipLabel);
        nameLayout.setHint(spec.nameHint);
        btnOpt1.setText(spec.chipOptions[0]);
        btnOpt2.setText(spec.chipOptions[1]);
        btnOpt3.setText(spec.chipOptions[2]);
        btnSave.setText(spec.saveButtonText);

        final int[] selected = {0};
        applyChipSelection(btnOpt1, btnOpt2, btnOpt3, 0);

        btnOpt1.setOnClickListener(v -> { selected[0] = 0; applyChipSelection(btnOpt1, btnOpt2, btnOpt3, 0); });
        btnOpt2.setOnClickListener(v -> { selected[0] = 1; applyChipSelection(btnOpt1, btnOpt2, btnOpt3, 1); });
        btnOpt3.setOnClickListener(v -> { selected[0] = 2; applyChipSelection(btnOpt1, btnOpt2, btnOpt3, 2); });

        btnSave.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            if (TextUtils.isEmpty(name)) {
                etName.setError("Please enter a name");
                return;
            }
            String notes = etNotes.getText().toString().trim();
            String ts    = new SimpleDateFormat("MMM dd, yyyy  hh:mm a", Locale.getDefault())
                    .format(new Date());
            String entry = spec.entryPrefix + ":" + name
                    + FIELD_SEP + spec.secondaryKey + ":" + spec.chipOptions[selected[0]]
                    + FIELD_SEP + "NOTE:" + notes
                    + FIELD_SEP + "TS:" + ts;
            saveEntry(entry);
            loadEntries();
            sheet.dismiss();
            Snackbar.make(rv, spec.savedSnackbar, Snackbar.LENGTH_SHORT).show();
        });

        sheet.setContentView(root);
        sheet.show();
    }

    /**
     * Highlights the active chip-toggle button using {@link Spec#chipColors}.
     * Buttons are passed in the same order as the chipOptions array.
     */
    private void applyChipSelection(MaterialButton b1, MaterialButton b2,
                                    MaterialButton b3, int selected) {
        int inactive    = Color.parseColor("#E8F5ED");
        int inactiveTxt = Color.parseColor("#2E7D52");
        MaterialButton[] btns = {b1, b2, b3};
        for (int i = 0; i < btns.length; i++) {
            boolean on = (i == selected);
            btns[i].setBackgroundTintList(ColorStateList.valueOf(on ? spec.chipColors[i] : inactive));
            btns[i].setTextColor(on ? Color.WHITE : inactiveTxt);
        }
    }

    /** Sheet used by Habit (chip-group + amount/unit entry). */
    private void showHabitSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View root = getLayoutInflater().inflate(R.layout.sheet_add_chip_entry, null);

        // Show habit block, hide chip block
        root.findViewById(R.id.chipBlock).setVisibility(View.GONE);
        root.findViewById(R.id.habitBlock).setVisibility(View.VISIBLE);

        TextView  tvTitle      = root.findViewById(R.id.tvChipSheetTitle);
        ChipGroup chipGroup    = root.findViewById(R.id.habitCategoryChips);
        EditText  etAmount     = root.findViewById(R.id.etHabitAmount);
        TextView  tvUnit       = root.findViewById(R.id.tvHabitUnit);
        EditText  etNotes      = root.findViewById(R.id.etChipNotes);
        MaterialButton btnSave = root.findViewById(R.id.btnChipSave);

        tvTitle.setText(spec.sheetTitle);
        btnSave.setText(spec.saveButtonText);

        final int[] selectedCat = {0};
        for (int i = 0; i < HABIT_CATEGORIES.length; i++) {
            Chip chip = new Chip(this);
            chip.setText(HABIT_CATEGORIES[i][0] + "  " + HABIT_CATEGORIES[i][1]);
            chip.setCheckable(true);
            chip.setCheckedIconVisible(false);
            chip.setTag(i);
            if (i == 0) {
                chip.setChecked(true);
                tvUnit.setText(HABIT_CATEGORIES[0][2]);
            }
            chipGroup.addView(chip);
        }
        chipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            View c = group.findViewById(checkedIds.get(0));
            if (c != null && c.getTag() instanceof Integer) {
                selectedCat[0] = (Integer) c.getTag();
                tvUnit.setText(HABIT_CATEGORIES[selectedCat[0]][2]);
            }
        });

        btnSave.setOnClickListener(v -> {
            String amount = etAmount.getText().toString().trim();
            if (TextUtils.isEmpty(amount)) {
                etAmount.setError("Enter amount");
                return;
            }
            String[] cat = HABIT_CATEGORIES[selectedCat[0]];
            String notes = etNotes.getText().toString().trim();
            String ts    = new SimpleDateFormat("MMM dd, yyyy  hh:mm a", Locale.getDefault())
                    .format(new Date());
            String entry = "HABIT:" + cat[0]
                    + FIELD_SEP + "EMOJI:"  + cat[1]
                    + FIELD_SEP + "AMOUNT:" + amount
                    + FIELD_SEP + "UNIT:"   + cat[2]
                    + FIELD_SEP + "COLOR:"  + cat[3]
                    + FIELD_SEP + "NOTE:"   + notes
                    + FIELD_SEP + "TS:"     + ts;
            saveEntry(entry);
            loadEntries();
            sheet.dismiss();
            Snackbar.make(rv, spec.savedSnackbar, Snackbar.LENGTH_SHORT).show();
        });

        sheet.setContentView(root);
        sheet.show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Persistence (identical to the original three activities)
    // ─────────────────────────────────────────────────────────────────────────

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void saveEntry(String entry) {
        String existing = prefs().getString(spec.prefsKey, "");
        String combined = TextUtils.isEmpty(existing)
                ? entry : entry + ENTRY_SEP + existing;
        prefs().edit().putString(spec.prefsKey, combined).apply();
    }

    /** Writes the current in-memory list back to SharedPreferences. */
    private void persistEntries() {
        String combined = TextUtils.join(ENTRY_SEP, entries);
        prefs().edit().putString(spec.prefsKey, combined).apply();
    }

    private void loadEntries() {
        String raw = prefs().getString(spec.prefsKey, "");
        entries.clear();
        if (!TextUtils.isEmpty(raw)) {
            entries.addAll(Arrays.asList(raw.split("\\|\\|\\|")));
        }
        adapter.notifyDataSetChanged();
        toggleEmptyState();
    }

    private void toggleEmptyState() {
        boolean empty = entries.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        rv.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared parseField helper (used by both adapters)
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns the value after "KEY:" prefix, stopping at the next "|". */
    private static String parseField(String entry, String key) {
        String prefix = key + ":";
        int start = entry.indexOf(prefix);
        if (start < 0) return "";
        start += prefix.length();
        int end = entry.indexOf("|", start);
        return end < 0 ? entry.substring(start).trim()
                       : entry.substring(start, end).trim();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Adapter A — chip card (Symptom + Condition share this)
    // ─────────────────────────────────────────────────────────────────────────

    private static class ChipCardAdapter
            extends RecyclerView.Adapter<ChipCardAdapter.VH> {

        private final List<String> data;
        private final Spec spec;

        ChipCardAdapter(List<String> data, Spec spec) {
            this.data = data;
            this.spec = spec;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_chip_card, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            String entry = data.get(position);

            String name      = parseField(entry, spec.entryPrefix);
            String secondary = spec.secondaryKey != null
                    ? parseField(entry, spec.secondaryKey) : "";
            String notes     = parseField(entry, "NOTE");
            String ts        = parseField(entry, "TS");

            h.tvLeadingEmoji.setText(spec.leadingEmoji);

            // Legacy plain-text entry — fall back to simple display
            if (name.isEmpty() && !entry.startsWith(spec.entryPrefix + ":")) {
                h.tvName.setText(entry);
                h.tvChip.setVisibility(View.GONE);
                h.tvNotes.setVisibility(View.GONE);
                h.tvTimestamp.setText("");
                return;
            }

            h.tvName.setText(name.isEmpty() ? "—" : name);
            h.tvTimestamp.setText(ts);

            if (!secondary.isEmpty()) {
                h.tvChip.setVisibility(View.VISIBLE);
                h.tvChip.setText(secondary);
                h.tvChip.setBackgroundTintList(
                        ColorStateList.valueOf(colorForOption(secondary)));
            } else {
                h.tvChip.setVisibility(View.GONE);
            }

            if (!notes.isEmpty()) {
                h.tvNotes.setText(notes);
                h.tvNotes.setVisibility(View.VISIBLE);
            } else {
                h.tvNotes.setVisibility(View.GONE);
            }
        }

        /** Looks up the active color for a chip option by matching its label. */
        private int colorForOption(String value) {
            if (spec.chipOptions != null && spec.chipColors != null) {
                for (int i = 0; i < spec.chipOptions.length; i++) {
                    if (spec.chipOptions[i].equals(value)) {
                        return spec.chipColors[i];
                    }
                }
            }
            return Color.parseColor("#2E7D32");  // sensible default — green
        }

        @Override
        public int getItemCount() { return data.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tvLeadingEmoji, tvName, tvChip, tvNotes, tvTimestamp;
            VH(@NonNull View v) {
                super(v);
                tvLeadingEmoji = v.findViewById(R.id.tvChipCardLeadingEmoji);
                tvName         = v.findViewById(R.id.tvChipCardName);
                tvChip         = v.findViewById(R.id.tvChipCardChip);
                tvNotes        = v.findViewById(R.id.tvChipCardNotes);
                tvTimestamp    = v.findViewById(R.id.tvChipCardTimestamp);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Adapter B — habit card (kept verbatim from HabitLogActivity)
    // ─────────────────────────────────────────────────────────────────────────

    private static class HabitAdapter
            extends RecyclerView.Adapter<HabitAdapter.VH> {

        private final List<String> data;
        HabitAdapter(List<String> data) { this.data = data; }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_chip_card, parent, false);
            // Show habit content, hide chip content
            v.findViewById(R.id.habitContent).setVisibility(View.VISIBLE);
            v.findViewById(R.id.chipContent).setVisibility(View.GONE);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            String entry = data.get(position);

            String category = parseField(entry, "HABIT");
            String emoji    = parseField(entry, "EMOJI");
            String amount   = parseField(entry, "AMOUNT");
            String unit     = parseField(entry, "UNIT");
            String color    = parseField(entry, "COLOR");
            String notes    = parseField(entry, "NOTE");
            String ts       = parseField(entry, "TS");

            if (category.isEmpty() && !entry.startsWith("HABIT:")) {
                // Legacy plain-text entry
                h.tvHabitEmoji.setText("📌");
                h.tvHabitName.setText(entry);
                h.tvHabitAmount.setVisibility(View.GONE);
                h.tvHabitNotes.setVisibility(View.GONE);
                h.tvTimestamp.setText("");
                h.emojiContainer.setBackgroundTintList(
                        ColorStateList.valueOf(Color.parseColor("#2E7D52")));
                return;
            }

            h.tvHabitEmoji.setText(emoji.isEmpty() ? "📌" : emoji);
            h.tvHabitName.setText(category.isEmpty() ? "Habit" : category);
            h.tvTimestamp.setText(ts);

            if (!amount.isEmpty()) {
                h.tvHabitAmount.setVisibility(View.VISIBLE);
                h.tvHabitAmount.setText(amount + " " + unit);
            } else {
                h.tvHabitAmount.setVisibility(View.GONE);
            }

            if (!notes.isEmpty()) {
                h.tvHabitNotes.setText(notes);
                h.tvHabitNotes.setVisibility(View.VISIBLE);
            } else {
                h.tvHabitNotes.setVisibility(View.GONE);
            }

            if (!color.isEmpty()) {
                try {
                    h.emojiContainer.setBackgroundTintList(
                            ColorStateList.valueOf(Color.parseColor(color)));
                } catch (Exception ignored) {}
            }
        }

        @Override
        public int getItemCount() { return data.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tvHabitEmoji, tvHabitName, tvHabitAmount, tvHabitNotes, tvTimestamp;
            final View emojiContainer;
            VH(@NonNull View v) {
                super(v);
                tvHabitEmoji   = v.findViewById(R.id.tvHabitEmoji);
                tvHabitName    = v.findViewById(R.id.tvHabitName);
                tvHabitAmount  = v.findViewById(R.id.tvHabitAmount);
                tvHabitNotes   = v.findViewById(R.id.tvHabitNotes);
                tvTimestamp    = v.findViewById(R.id.tvHabitTimestamp);
                emojiContainer = v.findViewById(R.id.habitEmojiContainer);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
