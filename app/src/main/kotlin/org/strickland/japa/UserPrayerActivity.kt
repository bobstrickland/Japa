package org.strickland.japa

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.abs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.strickland.japa.data.AppDatabase
import org.strickland.japa.data.Record

/**
 * Displays the prayers the user has entered themselves.
 *
 * Laid out like [PrayerActivity] — background image, scrolling text, a spinner of names along the
 * bottom — but the content comes from the Room `records` table instead of the bundled string
 * arrays. Reached by swiping right on the main screen; swiping left goes back.
 */
class UserPrayerActivity : AppCompatActivity() {

    private lateinit var bgImage: ImageView
    private lateinit var tvText: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var spinner: Spinner
    private lateinit var btnAdd: ImageButton
    private lateinit var gestureDetector: GestureDetector

    private var records: List<Record> = emptyList()

    /** Guards against the adapter's initial callback overwriting the restored selection. */
    private var applyingRecords = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_userrecord)
        registerCloseTransition()

        bgImage = findViewById(R.id.bg_image_userrecord)
        tvText = findViewById(R.id.tv_userrecord_text)
        tvEmpty = findViewById(R.id.tv_userrecord_empty)
        spinner = findViewById(R.id.spinner_userrecord)
        btnAdd = findViewById(R.id.btn_add_userrecord)

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                vX: Float,
                vY: Float
            ): Boolean {
                if (e1 == null) return false
                val dX = e2.x - e1.x
                val dY = e2.y - e1.y
                if (abs(dX) > abs(dY) &&
                    abs(dX) > SWIPE_THRESHOLD &&
                    abs(vX) > SWIPE_VEL_THRESHOLD
                ) {
                    if (e1.x > e2.x) { // swipe left — back to the main screen
                        finish()
                        return true
                    }
                }
                return false
            }
        })

        btnAdd.setOnClickListener {
            startActivity(Intent(this, UserRecordEditActivity::class.java).apply {
                putExtra(UserRecordEditActivity.EXTRA_RECORD_ID, selectedRecordId())
            })
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyRecord(position)
                if (!applyingRecords) rememberSelection(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val dao = AppDatabase.getInstance(this).recordDao()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.getAll().collectLatest { showRecords(it) }
            }
        }
    }

    /**
     * Mirrors the open animation set by [MainActivity]: this screen slides back off to the left
     * and the main screen comes in from the right, whichever way it is dismissed.
     *
     * From API 34 the close animation is declared up front; before that it has to be applied to
     * each pending transition, which is why [finish] is overridden as well.
     */
    private fun registerCloseTransition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_CLOSE,
                R.anim.slide_in_from_right,
                R.anim.slide_out_to_left
            )
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        // Covers the swipe, the back gesture, and anything else that finishes this screen.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overridePendingTransition(R.anim.slide_in_from_right, R.anim.slide_out_to_left)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    /** Rebuilds the spinner, keeping the user on the same record across edits where possible. */
    private fun showRecords(newRecords: List<Record>) {
        records = newRecords

        val hasRecords = newRecords.isNotEmpty()
        tvEmpty.visibility = if (hasRecords) View.GONE else View.VISIBLE
        spinner.visibility = if (hasRecords) View.VISIBLE else View.GONE

        if (!hasRecords) {
            tvText.text = ""
            bgImage.setImageDrawable(null)
            bgImage.setTag(R.id.tag_image_uri, null)
            return
        }

        val wantedId = savedRecordId()
        val position = newRecords.indexOfFirst { it.id == wantedId }.coerceAtLeast(0)

        applyingRecords = true
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            newRecords.map { it.name }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(position)
        applyingRecords = false

        // setSelection posts its callback, so paint the current record immediately too.
        applyRecord(position)
        rememberSelection(position)
    }

    private fun applyRecord(position: Int) {
        val record = records.getOrNull(position) ?: return
        tvText.text = record.text
        RecordImages.loadInto(bgImage, record.image, lifecycleScope)
    }

    private fun selectedRecordId(): Long =
        records.getOrNull(spinner.selectedItemPosition)?.id ?: -1L

    private fun rememberSelection(position: Int) {
        val record = records.getOrNull(position) ?: return
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putLong(PREF_RECORD_ID, record.id)
            .apply()
    }

    private fun savedRecordId(): Long =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getLong(PREF_RECORD_ID, -1L)

    companion object {
        const val PREFS_NAME = "UserPrayerPrefs"
        private const val PREF_RECORD_ID = "userPrayerRecordId"
        private const val SWIPE_THRESHOLD = 100f
        private const val SWIPE_VEL_THRESHOLD = 100f
    }
}
