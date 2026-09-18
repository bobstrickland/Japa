package org.strickland.japa

import android.media.MediaPlayer
import android.media.MediaPlayer.OnCompletionListener
import android.os.Bundle
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import kotlin.math.abs

class PrayerActivity : AppCompatActivity() {
    private var bgImage: ImageView? = null
    private var tvPrayerText: TextView? = null
    private var spinnerPrayer: Spinner? = null
    private var btnPray: MaterialButton? = null

    // Filled by loadPrayerArrays() before any of them is read; a missing entry is a blank
    // string rather than a null, which keeps every use site free of null handling and stops
    // the spinner from rendering the word "null".
    private var prayerNames: Array<String> = emptyArray()
    private var prayerAudio: Array<String> = emptyArray()
    private var prayerImages: Array<String> = emptyArray()
    private var prayerTexts: Array<String> = emptyArray()
    private var prayerDevTexts: Array<String> = emptyArray()

    private var activeAudio: String? = null
    private var mediaPlayer: MediaPlayer? = null
    private var gestureDetector: GestureDetector? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_prayer)

        bgImage = findViewById<ImageView>(R.id.bg_image_prayer)
        tvPrayerText = findViewById<TextView>(R.id.tv_prayer_text)
        spinnerPrayer = findViewById<Spinner>(R.id.spinner_prayer)
        btnPray = findViewById<MaterialButton>(R.id.btn_pray)

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
                    if (e1.getX() < e2.getX()) { // swipe right
                        finish()
                        return true
                    }
                }
                return false
            }
        })

        loadPrayerArrays()

        val prefs = getSharedPreferences(CounterService.PREFS_NAME, MODE_PRIVATE)
        // A stored position can outlive the prayer list that produced it, so never seed the
        // spinner past its end.
        val savedIndex = prefs.getInt(PREF_PRAYER_INDEX, 0)
            .coerceIn(0, (prayerNames.size - 1).coerceAtLeast(0))
        applyPrayerSelection(savedIndex)
        spinnerPrayer!!.setSelection(savedIndex)

        spinnerPrayer!!.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                applyPrayerSelection(position)
                getSharedPreferences(CounterService.PREFS_NAME, MODE_PRIVATE)
                    .edit().putInt(PREF_PRAYER_INDEX, position).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        })

        btnPray!!.setOnClickListener(View.OnClickListener { v: View? -> playPrayer() })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        gestureDetector!!.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaPlayer()
    }

    override fun onResume() {
        super.onResume()
        TextScale.applyTo(tvPrayerText!!)
    }

    private fun loadPrayerArrays() {
        val prayers = getResources().obtainTypedArray(R.array.prayer_array)
        val count = prayers.length()
        prayerNames = Array(count) { "" }
        prayerAudio = Array(count) { "" }
        prayerImages = Array(count) { "" }
        prayerTexts = Array(count) { "" }
        prayerDevTexts = Array(count) { "" }

        for (i in 0..<count) {
            val subId = prayers.getResourceId(i, 0)
            // A missing sub-array leaves the blank defaults above in place: a null image name
            // would throw from getIdentifier(), and a null name would show as "null" in the
            // spinner.
            if (subId != 0) {
                val sub = getResources().getStringArray(subId)
                prayerNames[i] = sub.getOrElse(0) { "" }
                prayerAudio[i] = sub.getOrElse(1) { "" }
                prayerImages[i] = sub.getOrElse(2) { "" }
                prayerTexts[i] = sub.getOrElse(3) { "" }
                prayerDevTexts[i] = sub.getOrElse(4) { "" }
            }
        }
        prayers.recycle()

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item, prayerNames
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPrayer!!.setAdapter(adapter)
    }

    private fun applyPrayerSelection(position: Int) {
        if (position < 0 || position >= prayerNames.size) return

        val resId =
            getResources().getIdentifier(prayerImages[position], "drawable", getPackageName())
        if (resId != 0) bgImage!!.setImageResource(resId)
        val p = getSharedPreferences(CounterService.PREFS_NAME, MODE_PRIVATE)
        val textIndex = p.getInt(CounterService.PREF_MANTRA_TEXT, 0)

        if (textIndex == 1 && prayerDevTexts[position].isNotBlank()) {
            tvPrayerText!!.setText(prayerDevTexts[position])
        } else {
            tvPrayerText!!.setText(prayerTexts[position])
        }

        activeAudio = prayerAudio[position]
        if (activeAudio.isNullOrBlank()) {
            btnPray!!.setEnabled(false)
            btnPray!!.setAlpha(.3f)
        } else {
            btnPray!!.setEnabled(true)
            btnPray!!.setAlpha(1f)
        }
    }

    private fun playPrayer() {
        if (activeAudio.isNullOrBlank()) return
        val resId = getResources().getIdentifier(activeAudio, "raw", getPackageName())
        if (resId == 0) return
        releaseMediaPlayer()
        mediaPlayer = MediaPlayer.create(this, resId)
        if (mediaPlayer != null) {
            mediaPlayer!!.setOnCompletionListener(OnCompletionListener { mp: MediaPlayer? -> releaseMediaPlayer() })
            mediaPlayer!!.start()
        }
    }

    private fun releaseMediaPlayer() {
        if (mediaPlayer != null) {
            if (mediaPlayer!!.isPlaying()) mediaPlayer!!.stop()
            mediaPlayer!!.release()
            mediaPlayer = null
        }
    }

    companion object {
        private const val PREF_PRAYER_INDEX = "prayerIndex"
        private const val SWIPE_THRESHOLD = 100f
        private const val SWIPE_VEL_THRESHOLD = 100f
    }
}
