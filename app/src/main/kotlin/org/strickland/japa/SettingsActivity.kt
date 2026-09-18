package org.strickland.japa

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.ArrayAdapter
import android.widget.NumberPicker
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import org.strickland.japa.CounterService.LocalBinder

/**
 * Settings screen lets the user configure:
 * - Total beads per round (default 108)
 * - Total rounds         (default 16)
 * - Feedback type        (vibration / sound / none)
 * 
 * 
 * On save, a PREF_SETTINGS_CHANGED flag is written so that MainActivity
 * knows to reload preferences and reset the counter when it resumes.
 */
class SettingsActivity : AppCompatActivity() {
    private var pickerBeads: NumberPicker? = null
    private var pickerRounds: NumberPicker? = null
    private var spinnerMantra: Spinner? = null
    private var spinnerText: Spinner? = null
    private var spinnerTextSize: Spinner? = null
    private var sliderMantraSpeed: Slider? = null
    private var radioFeedback: RadioGroup? = null
    private var radioFeedbackRound: RadioGroup? = null
    private var btnSave: MaterialButton? = null
    private var btnCancel: MaterialButton? = null

    // Filled by loadMantraArrays() before either is read; empty until then rather than null,
    // which keeps every use site free of null handling the arrays never actually need.
    private var mantraNames: Array<String> = emptyArray()
    private var mantraAudio: Array<String> = emptyArray()

    //private String[] mantraImages;
    private var counterService: CounterService? = null
    private var isBound = false
    private val serviceConn: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            counterService = (service as LocalBinder).service
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            counterService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_settings)
        setSupportActionBar(toolbar)
        if (getSupportActionBar() != null) {
            getSupportActionBar()!!.setDisplayHomeAsUpEnabled(true)
        }

        pickerBeads = findViewById<NumberPicker>(R.id.picker_beads)
        pickerRounds = findViewById<NumberPicker>(R.id.picker_rounds)
        spinnerMantra = findViewById<Spinner>(R.id.spinner_mantra)
        spinnerText = findViewById<Spinner>(R.id.spinner_text)
        spinnerTextSize = findViewById<Spinner>(R.id.spinner_text_size)
        sliderMantraSpeed = findViewById<Slider>(R.id.slider_mantra_speed)
        radioFeedback = findViewById<RadioGroup>(R.id.radio_feedback)
        radioFeedbackRound = findViewById<RadioGroup>(R.id.radio_feedback_round)
        btnSave = findViewById<MaterialButton>(R.id.btn_save)
        btnCancel = findViewById<MaterialButton>(R.id.btn_cancel)

        val adapter = ArrayAdapter<String?>(
            this,
            android.R.layout.simple_spinner_item, arrayOf<String>("Roman", "Devanagari")
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerText!!.setAdapter(adapter)
        loadMantraArrays()
        setupPickers()
        loadCurrentSettings()

        btnSave!!.setOnClickListener(View.OnClickListener { v: View? -> saveSettings() })
        btnCancel!!.setOnClickListener(View.OnClickListener { v: View? -> finish() })
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, CounterService::class.java), serviceConn, BIND_AUTO_CREATE)
        isBound = true
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConn)
            isBound = false
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ── Private helpers ───────────────────────────────────────────────────────
    private fun loadMantraArrays() {
        val mantras = resources.obtainTypedArray(R.array.mantra_array)
        val names = ArrayList<String>(mantras.length())
        val audio = ArrayList<String>(mantras.length())
        for (i in 0 until mantras.length()) {
            val subId = mantras.getResourceId(i, 0)
            // A missing sub-array leaves a blank row rather than a null the spinner would
            // render as the word "null".
            val sub = if (subId != 0) resources.getStringArray(subId) else emptyArray()
            names += sub.getOrElse(0) { "" }
            audio += sub.getOrElse(1) { "" }
            //mantraImages[i] = sub.length > 2 ? sub[2] : "";
        }
        mantras.recycle()

        mantraNames = names.toTypedArray()
        mantraAudio = audio.toTypedArray()

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mantraNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMantra!!.adapter = adapter
    }

    private fun setupPickers() {
        // Beads per round: 1 – 1000
        pickerBeads!!.setMinValue(1)
        pickerBeads!!.setMaxValue(200)
        pickerBeads!!.setWrapSelectorWheel(false)

        // Rounds: 1 – 108
        pickerRounds!!.setMinValue(1)
        pickerRounds!!.setMaxValue(50)
        pickerRounds!!.setWrapSelectorWheel(false)
    }

    private fun loadCurrentSettings() {
        val p = getSharedPreferences(CounterService.PREFS_NAME, MODE_PRIVATE)
        pickerBeads!!.setValue(p.getInt(CounterService.PREF_TOTAL_BEADS, 108))
        pickerRounds!!.setValue(p.getInt(CounterService.PREF_TOTAL_ROUNDS, 16))
        spinnerMantra!!.setSelection(p.getInt(CounterService.PREF_MANTRA_INDEX, 0))
        spinnerText!!.setSelection(p.getInt(CounterService.PREF_MANTRA_TEXT, 0))
        spinnerTextSize!!.setSelection(
            clampTextSize(
                p.getInt(
                    CounterService.PREF_TEXT_SIZE,
                    0
                )
            )
        )
        sliderMantraSpeed!!.setValue(
            p.getInt(CounterService.PREF_MANTRA_SPEED, 0)
            .coerceIn(-50, 50)
            .toFloat()
        )

        var feedback: String = p.getString(
            CounterService.PREF_FEEDBACK,
            CounterService.FEEDBACK_VIBRATION
        )!!
        when (feedback) {
            CounterService.FEEDBACK_SOUND -> radioFeedback!!.check(R.id.radio_sound)
            CounterService.FEEDBACK_NONE -> radioFeedback!!.check(R.id.radio_none)
            else -> radioFeedback!!.check(R.id.radio_vibration)
        }
        feedback = p.getString(
            CounterService.PREF_FEEDBACK_ROUND,
            CounterService.FEEDBACK_VIBRATION
        )!!
        when (feedback) {
            CounterService.FEEDBACK_SOUND -> radioFeedbackRound!!.check(R.id.radio_sound_round)
            CounterService.FEEDBACK_NONE -> radioFeedbackRound!!.check(R.id.radio_none_round)
            else -> radioFeedbackRound!!.check(R.id.radio_vibration_round)
        }
    }

    private fun saveSettings() {
        val beads = pickerBeads!!.getValue()
        val rounds = pickerRounds!!.getValue()

        val feedback: String?
        var checked = radioFeedback!!.getCheckedRadioButtonId()
        if (checked == R.id.radio_sound) {
            feedback = CounterService.FEEDBACK_SOUND
        } else if (checked == R.id.radio_none) {
            feedback = CounterService.FEEDBACK_NONE
        } else {
            feedback = CounterService.FEEDBACK_VIBRATION
        }
        val feedbackRound: String?
        checked = radioFeedbackRound!!.getCheckedRadioButtonId()
        if (checked == R.id.radio_sound_round) {
            feedbackRound = CounterService.FEEDBACK_SOUND
        } else if (checked == R.id.radio_none_round) {
            feedbackRound = CounterService.FEEDBACK_NONE
        } else {
            feedbackRound = CounterService.FEEDBACK_VIBRATION
        }

        val mantaIndex = spinnerMantra!!.getSelectedItemPosition()
        val mantaTextIndex = spinnerText!!.getSelectedItemPosition()
        val textSizeIndex = spinnerTextSize!!.getSelectedItemPosition()

        getSharedPreferences(CounterService.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(CounterService.PREF_TOTAL_BEADS, beads)
            .putInt(CounterService.PREF_TOTAL_ROUNDS, rounds)
            .putString(CounterService.PREF_FEEDBACK, feedback)
            .putString(CounterService.PREF_FEEDBACK_ROUND, feedbackRound)
            .putInt(CounterService.PREF_MANTRA_INDEX, mantaIndex)
            .putInt(CounterService.PREF_MANTRA_TEXT, mantaTextIndex)
            .putInt(
                CounterService.PREF_MANTRA_SPEED,
                sliderMantraSpeed!!.getValue().toInt()
                    .coerceIn(-50, 50)
            )
            .putInt(CounterService.PREF_TEXT_SIZE, textSizeIndex)
            .putBoolean(CounterService.PREF_SETTINGS_CHANGED, true)
            .apply()

        if (isBound) {
            counterService?.updateBeadSound(mantraAudio[mantaIndex])
        }

        // Read through the weak reference once: `instance` is mutable static state that
        // MainActivity clears on exit, so a null check followed by a separate get() is not
        // something the compiler will let us smart-cast across.
        val main = MainActivity.instance?.get()
        if (main != null) {
            main.applyMantraBackground()
            main.applyTextSize()
        }

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** A stored position can outlive the array it indexed, so never seed the spinner past its end.  */
    private fun clampTextSize(index: Int): Int {
        val count = getResources().getStringArray(R.array.text_size_labels).size
        if (index < 0) return 0
        return if (index >= count) count - 1 else index
    }
}
