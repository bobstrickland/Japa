package org.strickland.japa;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.NumberPicker;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

/**
 * Settings screen lets the user configure:
 *   - Total beads per round (default 108)
 *   - Total rounds         (default 16)
 *   - Feedback type        (vibration / sound / none)
 *
 * On save, a PREF_SETTINGS_CHANGED flag is written so that MainActivity
 * knows to reload preferences and reset the counter when it resumes.
 */
public class SettingsActivity extends AppCompatActivity {

    private NumberPicker pickerBeads;
    private NumberPicker pickerRounds;
    private RadioGroup   radioFeedback;
    private MaterialButton btnSave;
    private MaterialButton btnCancel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_settings);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        pickerBeads   = findViewById(R.id.picker_beads);
        pickerRounds  = findViewById(R.id.picker_rounds);
        radioFeedback = findViewById(R.id.radio_feedback);
        btnSave       = findViewById(R.id.btn_save);
        btnCancel     = findViewById(R.id.btn_cancel);

        setupPickers();
        loadCurrentSettings();

        btnSave.setOnClickListener(v -> saveSettings());
        btnCancel.setOnClickListener(v -> finish());
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void setupPickers() {
        // Beads per round: 1 – 1000
        pickerBeads.setMinValue(1);
        pickerBeads.setMaxValue(200);
        pickerBeads.setWrapSelectorWheel(false);

        // Rounds: 1 – 108
        pickerRounds.setMinValue(1);
        pickerRounds.setMaxValue(50);
        pickerRounds.setWrapSelectorWheel(false);
    }

    private void loadCurrentSettings() {
        SharedPreferences p = getSharedPreferences(CounterService.PREFS_NAME, MODE_PRIVATE);
        pickerBeads.setValue( p.getInt(CounterService.PREF_TOTAL_BEADS,  108));
        pickerRounds.setValue(p.getInt(CounterService.PREF_TOTAL_ROUNDS,  16));

        String feedback = p.getString(CounterService.PREF_FEEDBACK, CounterService.FEEDBACK_VIBRATION);
        switch (feedback) {
            case CounterService.FEEDBACK_SOUND:
                radioFeedback.check(R.id.radio_sound);
                break;
            case CounterService.FEEDBACK_NONE:
                radioFeedback.check(R.id.radio_none);
                break;
            default:
                radioFeedback.check(R.id.radio_vibration);
                break;
        }
    }

    private void saveSettings() {
        int beads  = pickerBeads.getValue();
        int rounds = pickerRounds.getValue();

        String feedback;
        int checked = radioFeedback.getCheckedRadioButtonId();
        if (checked == R.id.radio_sound) {
            feedback = CounterService.FEEDBACK_SOUND;
        } else if (checked == R.id.radio_none) {
            feedback = CounterService.FEEDBACK_NONE;
        } else {
            feedback = CounterService.FEEDBACK_VIBRATION;
        }

        getSharedPreferences(CounterService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(CounterService.PREF_TOTAL_BEADS,  beads)
                .putInt(CounterService.PREF_TOTAL_ROUNDS, rounds)
                .putString(CounterService.PREF_FEEDBACK,  feedback)
                .putBoolean(CounterService.PREF_SETTINGS_CHANGED, true) // signals MainActivity to reload
                .apply();

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}
