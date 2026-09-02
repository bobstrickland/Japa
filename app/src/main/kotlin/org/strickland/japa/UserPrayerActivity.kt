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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import org.strickland.japa.data.AppDatabase
import org.strickland.japa.data.PrayerSet
import org.strickland.japa.data.Record

/**
 * Displays the prayers the user has entered themselves.
 *
 * Laid out like [PrayerActivity] — background image, scrolling text, a spinner of names along the
 * bottom — but the content comes from the Room `records` table instead of the bundled string
 * arrays. Reached by swiping right on the main screen; swiping left goes back.
 *
 * The top spinner narrows the list to one prayer set, in that set's own order, which is how a
 * weekly assembly is worked through. "All prayers" shows everything by name.
 */
class UserPrayerActivity : AppCompatActivity() {

    private lateinit var bgImage: ImageView
    private lateinit var tvText: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var spinner: Spinner
    private lateinit var spinnerSet: Spinner
    private lateinit var btnAdd: ImageButton
    private lateinit var btnManageSets: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var gestureDetector: GestureDetector

    private val db by lazy { AppDatabase.getInstance(this) }
    private val recordDao by lazy { db.recordDao() }
    private val setDao by lazy { db.prayerSetDao() }

    private var records: List<Record> = emptyList()
    private var sets: List<PrayerSet> = emptyList()

    /** Drives which prayers are listed; [ALL_PRAYERS] means every prayer, ordered by name. */
    private val selectedSetId = MutableStateFlow(ALL_PRAYERS)

    /** Guards against an adapter's initial callback overwriting the restored selection. */
    private var applyingRecords = false
    private var applyingSets = false

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_userrecord)
        registerCloseTransition()

        bgImage = findViewById(R.id.bg_image_userrecord)
        tvText = findViewById(R.id.tv_userrecord_text)
        tvEmpty = findViewById(R.id.tv_userrecord_empty)
        spinner = findViewById(R.id.spinner_userrecord)
        spinnerSet = findViewById(R.id.spinner_userrecord_set)
        btnAdd = findViewById(R.id.btn_add_userrecord)
        btnManageSets = findViewById(R.id.btn_manage_sets)
        btnPrev = findViewById(R.id.btn_prev_prayer)
        btnNext = findViewById(R.id.btn_next_prayer)

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
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

        selectedSetId.value = savedSetId()

        btnAdd.setOnClickListener {
            startActivity(Intent(this, UserRecordEditActivity::class.java).apply {
                putExtra(UserRecordEditActivity.EXTRA_RECORD_ID, selectedRecordId())
            })
        }
        btnManageSets.setOnClickListener {
            startActivity(Intent(this, PrayerSetActivity::class.java))
        }
        btnPrev.setOnClickListener { step(-1) }
        btnNext.setOnClickListener { step(+1) }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                applyRecord(position)
                updateStepButtons()
                if (!applyingRecords) rememberSelection(position)
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        spinnerSet.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (applyingSets) return
                // Position 0 is the "All prayers" entry, so sets start at 1.
                val setId = if (position == 0) ALL_PRAYERS else sets[position - 1].id
                selectedSetId.value = setId
                rememberSet(setId)
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        lifecycleScope.launch {
            // Bring records made before images were copied into the store up to date, then reclaim
            // images nothing refers to. Both rewrite the table, so the collectors below refresh.
            RecordImageStore.migrateLegacyImages(applicationContext, recordDao)
            RecordImageStore.pruneOrphans(applicationContext, recordDao)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                setDao.observeSets().collectLatest { showSets(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                selectedSetId
                    .flatMapLatest { id ->
                        if (id == ALL_PRAYERS) recordDao.getAll() else setDao.observeMembers(id)
                    }
                    .collectLatest { showRecords(it) }
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

    // ── Sets ──────────────────────────────────────────────────────────────────

    private fun showSets(newSets: List<PrayerSet>) {
        sets = newSets
        val wanted = selectedSetId.value

        applyingSets = true
        val labels = listOf(getString(R.string.all_prayers)) + newSets.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSet.adapter = adapter

        val index = newSets.indexOfFirst { it.id == wanted }
        spinnerSet.setSelection(if (index >= 0) index + 1 else 0)
        applyingSets = false

        // A set that has been deleted since falls back to showing everything.
        if (wanted != ALL_PRAYERS && index < 0) {
            selectedSetId.value = ALL_PRAYERS
            rememberSet(ALL_PRAYERS)
        }
    }

    // ── Prayers ───────────────────────────────────────────────────────────────

    /** Rebuilds the spinner, keeping the user on the same prayer across edits where possible. */
    private fun showRecords(newRecords: List<Record>) {
        records = newRecords

        val hasRecords = newRecords.isNotEmpty()
        tvEmpty.visibility = if (hasRecords) View.GONE else View.VISIBLE
        spinner.visibility = if (hasRecords) View.VISIBLE else View.GONE
        btnPrev.visibility = if (hasRecords) View.VISIBLE else View.GONE
        btnNext.visibility = if (hasRecords) View.VISIBLE else View.GONE
        tvEmpty.setText(
            if (selectedSetId.value == ALL_PRAYERS) R.string.no_user_prayers else R.string.set_empty
        )

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
        updateStepButtons()
        rememberSelection(position)
    }

    private fun applyRecord(position: Int) {
        val record = records.getOrNull(position) ?: return
        tvText.text = record.text
        RecordImages.loadInto(bgImage, record.image, lifecycleScope)
    }

    /** Moves one prayer along the current set's order — the assembly is worked through in sequence. */
    private fun step(delta: Int) {
        val next = spinner.selectedItemPosition + delta
        if (next in records.indices) spinner.setSelection(next)
    }

    private fun updateStepButtons() {
        val at = spinner.selectedItemPosition
        btnPrev.isEnabled = at > 0
        btnNext.isEnabled = at < records.lastIndex
        btnPrev.alpha = if (btnPrev.isEnabled) 1f else DISABLED_ALPHA
        btnNext.alpha = if (btnNext.isEnabled) 1f else DISABLED_ALPHA
    }

    private fun selectedRecordId(): Long =
        records.getOrNull(spinner.selectedItemPosition)?.id ?: -1L

    // ── Remembered selection ──────────────────────────────────────────────────

    private fun rememberSelection(position: Int) {
        val record = records.getOrNull(position) ?: return
        prefs().edit().putLong(PREF_RECORD_ID, record.id).apply()
    }

    private fun rememberSet(setId: Long) {
        prefs().edit().putLong(PREF_SET_ID, setId).apply()
    }

    private fun savedRecordId(): Long = prefs().getLong(PREF_RECORD_ID, -1L)

    private fun savedSetId(): Long = prefs().getLong(PREF_SET_ID, ALL_PRAYERS)

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "UserPrayerPrefs"
        private const val PREF_RECORD_ID = "userPrayerRecordId"
        private const val PREF_SET_ID = "userPrayerSetId"

        /** Sentinel for the "All prayers" spinner entry; no real set id is negative. */
        private const val ALL_PRAYERS = -1L

        private const val SWIPE_THRESHOLD = 100f
        private const val SWIPE_VEL_THRESHOLD = 100f
        private const val DISABLED_ALPHA = 0.3f
    }
}
