package org.strickland.japa.vipassana

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.NumberPicker
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import org.strickland.japa.R
import java.util.Locale
import kotlin.math.abs

/**
 * Times a silent meditation.
 *
 * One stroke of the chosen sound marks every Notice Time minutes that pass; three strokes
 * mark the end of the Total Time. The minutes still to run are shown for anyone who opens
 * their eyes, but the sound is what the sitting is meant to be followed by.
 */
class VipassanaActivity : AppCompatActivity() {
    private var pickerTotal: NumberPicker? = null
    private var pickerNotice: NumberPicker? = null
    private var pickerFinalCount: NumberPicker? = null
    private var spinnerSound: Spinner? = null
    private var spinnerFinalSound: Spinner? = null
    private var btnStart: MaterialButton? = null
    private var btnStop: MaterialButton? = null
    private var countdownText: TextView? = null

    // Filled by loadSoundArrays() before either is read; a missing entry is a blank string
    // rather than a null, which keeps the spinner from rendering the word "null".
    private var soundNames: Array<String> = emptyArray()
    private var soundFiles: Array<String> = emptyArray()

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private var gestureDetector: GestureDetector? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vipassana)

        pickerTotal = findViewById<NumberPicker>(R.id.picker_total_time)
        pickerNotice = findViewById<NumberPicker>(R.id.picker_notice_time)
        pickerFinalCount = findViewById<NumberPicker>(R.id.picker_vipassana_final_sound_count)
        spinnerSound = findViewById<Spinner>(R.id.spinner_vipassana_sound)
        spinnerFinalSound = findViewById<Spinner>(R.id.spinner_vipassana_final_sound)
        btnStart = findViewById<MaterialButton>(R.id.btn_vipassana_start)
        btnStop = findViewById<MaterialButton>(R.id.btn_vipassana_stop)
        countdownText = findViewById<TextView>(R.id.countdownText)

        gestureDetector = GestureDetector(this, object : SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                vX: Float,
                vY: Float
            ): Boolean {
                if (e1 == null) return false
                val dX = e2.getX() - e1.getX()
                val dY = e2.getY() - e1.getY()
                if (abs(dX) > abs(dY) && abs(dX) > SWIPE_THRESHOLD && abs(vX) > SWIPE_VEL_THRESHOLD) {
                    if (e1.getX() < e2.getX()) { // swipe right — back to the prayer screen
                        finish()
                        return true
                    }
                }
                return false
            }
        })

        loadSoundArrays()

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        pickerTotal!!.setMinValue(TOTAL_MIN)
        pickerTotal!!.setMaxValue(TOTAL_MAX)
        pickerTotal!!.setWrapSelectorWheel(false)
        pickerTotal!!.setValue(
            prefs.getInt(PREF_TOTAL_TIME, DEFAULT_TOTAL).coerceIn(TOTAL_MIN, TOTAL_MAX)
        )

        pickerNotice!!.setMinValue(NOTICE_MIN)
        pickerNotice!!.setMaxValue(TOTAL_MAX)
        pickerNotice!!.setWrapSelectorWheel(false)
        pickerNotice!!.setValue(
            prefs.getInt(PREF_NOTICE_TIME, DEFAULT_NOTICE)
                .coerceIn(NOTICE_MIN, pickerTotal!!.getValue())
        )

        pickerFinalCount!!.setMinValue(MIN_STROKES)
        pickerFinalCount!!.setMaxValue(MAX_STROKES)
        pickerFinalCount!!.setWrapSelectorWheel(false)
        pickerFinalCount!!.setValue(
            prefs.getInt(PREF_FINAL_SOUND_COUNT, DEFAULT_STROKES).coerceIn(MIN_STROKES, MAX_STROKES)
        )

        // Notice Time can never exceed Total Time, so each picker pulls the other along
        // rather than refusing the edit: lowering Total drags Notice down with it, and
        // raising Notice past Total raises Total to match.
        pickerTotal!!.setOnValueChangedListener { _: NumberPicker?, _: Int, newVal: Int ->
            if (pickerNotice!!.getValue() > newVal) pickerNotice!!.setValue(newVal)
        }
        pickerNotice!!.setOnValueChangedListener { _: NumberPicker?, _: Int, newVal: Int ->
            if (newVal > pickerTotal!!.getValue()) pickerTotal!!.setValue(newVal)
        }

        val savedSound = prefs.getInt(PREF_SOUND_INDEX, 0)
            .coerceIn(0, (soundNames.size - 1).coerceAtLeast(0))
        spinnerSound!!.setSelection(savedSound)
        spinnerSound!!.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putInt(PREF_SOUND_INDEX, position).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        })

        val savedFinalSound = prefs.getInt(PREF_FINAL_SOUND_INDEX, 0)
            .coerceIn(0, (soundNames.size - 1).coerceAtLeast(0))
        spinnerFinalSound!!.setSelection(savedFinalSound)
        spinnerFinalSound!!.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putInt(PREF_FINAL_SOUND_INDEX, position).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        })

        btnStart!!.setOnClickListener(View.OnClickListener { v: View? -> startTimer() })
        btnStop!!.setOnClickListener(View.OnClickListener { v: View? -> stopTimer() })

        applyRunningState()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        gestureDetector!!.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        // A meditation belongs to the screen that is timing it; leaving for good ends it.
        stopTimer()
        releaseMediaPlayer()
    }

    private fun loadSoundArrays() {
        val sounds = getResources().obtainTypedArray(R.array.vipassana_sound_array)
        val count = sounds.length()
        soundNames = Array(count) { "" }
        soundFiles = Array(count) { "" }

        for (i in 0..<count) {
            val subId = sounds.getResourceId(i, 0)
            // A missing sub-array leaves the blank defaults in place: a null name would show
            // as "null" in the spinner, and a null file name would throw from getIdentifier().
            if (subId != 0) {
                val sub = getResources().getStringArray(subId)
                soundNames[i] = sub.getOrElse(0) { "" }
                soundFiles[i] = sub.getOrElse(1) { "" }
            }
        }
        sounds.recycle()

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item, soundNames
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSound!!.setAdapter(adapter)
        spinnerFinalSound!!.setAdapter(adapter)
    }

    private fun startTimer() {
        if (running) return

        val totalMinutes = pickerTotal!!.getValue()
        val noticeMinutes = pickerNotice!!.getValue()
        val finalSoundCount = pickerFinalCount!!.getValue()

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putInt(PREF_TOTAL_TIME, totalMinutes)
            .putInt(PREF_NOTICE_TIME, noticeMinutes)
            .putInt(PREF_FINAL_SOUND_COUNT, finalSoundCount)
            .apply()

        handler.removeCallbacksAndMessages(null)

        showMinutesLeft(totalMinutes)
        // The countdown steps down as each minute completes, so the last step lands on 00 at
        // the same moment the closing strokes sound.
        for (minute in 1..totalMinutes) {
            handler.postDelayed(
                { showMinutesLeft(totalMinutes - minute) },
                minute * MILLIS_PER_MINUTE
            )
        }

        // Every stroke is scheduled up front against the moment Start was pressed, so a
        // delayed notice cannot push the ones behind it — the end still lands on time.
        if (noticeMinutes > 0) {
            val position = spinnerSound!!.getSelectedItemPosition()
            var elapsed = noticeMinutes
            while (elapsed < totalMinutes) {
                handler.postDelayed({ playSound(1, position) }, elapsed * MILLIS_PER_MINUTE)
                elapsed += noticeMinutes
            }
        }
        handler.postDelayed({
            val position = spinnerFinalSound!!.getSelectedItemPosition()

            playSound(finalSoundCount, position)
            running = false
            applyRunningState()
        }, totalMinutes * MILLIS_PER_MINUTE)

        running = true
        applyRunningState()
    }

    private fun stopTimer() {
        handler.removeCallbacksAndMessages(null)
        if (!running) return
        running = false
        applyRunningState()
        // Back to the resting display: a frozen part-way count would read as a sitting still
        // under way.
        showMinutesLeft(0)
    }

    /**
     * Start and Stop are never both live, and the settings are frozen for the duration of a
     * sitting so the schedule on the clock stays the one on the screen.
     */
    private fun applyRunningState() {
        setEnabled(btnStart!!, !running)
        setEnabled(btnStop!!, running)
        pickerTotal!!.setEnabled(!running)
        pickerNotice!!.setEnabled(!running)
        spinnerSound!!.setEnabled(!running)
        spinnerFinalSound!!.setEnabled(!running)

        // Handler delays are measured in uptime, which stops advancing in deep sleep, so the
        // screen has to stay on for the sitting to be timed at all.
        if (running) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Minutes are always two digits wide — "07" reads as a clock, "7" reads as a stray
     * number — and in Western digits whatever the device locale would otherwise use.
     */
    private fun showMinutesLeft(minutes: Int) {
        countdownText!!.setText(String.format(Locale.US, "%02d", minutes))
    }

    private fun setEnabled(button: MaterialButton, enabled: Boolean) {
        button.setEnabled(enabled)
        button.setAlpha(if (enabled) 1f else .3f)
    }

    /** Sounds [times] strokes of the selected sound, one after the last has finished. */
    private fun playSound(times: Int, position: Int) {
        if (position < 0 || position >= soundFiles.size) return
        val fileName = soundFiles[position]
        if (fileName.isBlank()) return
        val resId = getResources().getIdentifier(fileName, "raw", getPackageName())
        if (resId == 0) return

        releaseMediaPlayer()
        val player = MediaPlayer.create(this, resId) ?: return
        mediaPlayer = player
        var remaining = times
        player.setOnCompletionListener { mp: MediaPlayer ->
            remaining--
            if (remaining > 0) {
                mp.seekTo(0)
                mp.start()
            } else {
                // Releasing from inside the player's own callback is asking for trouble, so
                // hand it back to the main thread first.
                handler.post { if (mediaPlayer === mp) releaseMediaPlayer() }
            }
        }
        player.start()
    }

    private fun releaseMediaPlayer() {
        if (mediaPlayer != null) {
            if (mediaPlayer!!.isPlaying()) mediaPlayer!!.stop()
            mediaPlayer!!.release()
            mediaPlayer = null
        }
    }

    companion object {
        private const val PREFS_NAME = "vipassana"
        private const val PREF_TOTAL_TIME = "totalTime"
        private const val PREF_NOTICE_TIME = "noticeTime"
        private const val PREF_SOUND_INDEX = "soundIndex"
        private const val PREF_FINAL_SOUND_INDEX = "soundFinalIndex"
        private const val PREF_FINAL_SOUND_COUNT = "soundFinalcount"

        private const val TOTAL_MIN = 1
        private const val TOTAL_MAX = 60
        private const val NOTICE_MIN = 0
        private const val DEFAULT_TOTAL = 15
        private const val DEFAULT_NOTICE = 5

        private const val MAX_STROKES = 5
        private const val MIN_STROKES = 1
        private const val DEFAULT_STROKES = 3
        private const val MILLIS_PER_MINUTE = 60_000L

        private const val SWIPE_THRESHOLD = 100f
        private const val SWIPE_VEL_THRESHOLD = 100f
    }
}
