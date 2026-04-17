package org.strickland.japa;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.TypedArray;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.ArrayAdapter;
import android.widget.NumberPicker;
import android.widget.RadioGroup;
import android.widget.Spinner;
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
    private Spinner      spinnerMantra;
    private RadioGroup   radioFeedback;
    private MaterialButton btnSave;
    private MaterialButton btnCancel;

    private String[] mantraNames;
    private String[] mantraAudio;
    private String[] mantraImages;

    private CounterService counterService;
    private boolean        isBound = false;
    private final ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            counterService = ((CounterService.LocalBinder) service).getService();
            isBound = true;
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            counterService = null;
        }
    };

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
        spinnerMantra = findViewById(R.id.spinner_mantra);
        radioFeedback = findViewById(R.id.radio_feedback);
        btnSave       = findViewById(R.id.btn_save);
        btnCancel     = findViewById(R.id.btn_cancel);

        loadMantraArrays();
        setupPickers();
        loadCurrentSettings();

        btnSave.setOnClickListener(v -> saveSettings());
        btnCancel.setOnClickListener(v -> finish());
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, CounterService.class), serviceConn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isBound) {
            unbindService(serviceConn);
            isBound = false;
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void loadMantraArrays() {
        TypedArray mantras = getResources().obtainTypedArray(R.array.mantra_array);
        int count = mantras.length();
        mantraNames  = new String[count];
        mantraAudio  = new String[count];
        mantraImages = new String[count];
        for (int i = 0; i < count; i++) {
            int subId = mantras.getResourceId(i, 0);
            if (subId != 0) {
                String[] sub = getResources().getStringArray(subId);
                mantraNames[i]  = sub.length > 0 ? sub[0] : "";
                mantraAudio[i]  = sub.length > 1 ? sub[1] : "";
                mantraImages[i] = sub.length > 2 ? sub[2] : "";
            }
        }
        mantras.recycle();

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, mantraNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerMantra.setAdapter(adapter);
    }

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
        spinnerMantra.setSelection(p.getInt(CounterService.PREF_MANTRA_INDEX, 0));

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

        int mantaIndex = spinnerMantra.getSelectedItemPosition();

        getSharedPreferences(CounterService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(CounterService.PREF_TOTAL_BEADS,  beads)
                .putInt(CounterService.PREF_TOTAL_ROUNDS, rounds)
                .putString(CounterService.PREF_FEEDBACK,  feedback)
                .putInt(CounterService.PREF_MANTRA_INDEX, mantaIndex)
                .putBoolean(CounterService.PREF_SETTINGS_CHANGED, true)
                .apply();

        if (isBound) {
            counterService.updateBeadSound(mantraAudio[mantaIndex]);
        }

        MainActivity main = MainActivity.instance != null ? MainActivity.instance.get() : null;
        if (main != null) {
            main.applyMantraBackground();
        }

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}
