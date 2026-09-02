package org.strickland.japa

import android.content.Intent
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import org.strickland.japa.data.AppDatabase
import org.strickland.japa.data.Record

/**
 * Add/edit screen for the user's own prayers.
 *
 * Works on a snapshot of the table rather than a live query so that paging with previous/next is
 * not yanked around while the user is typing. The snapshot is refreshed after every save.
 */
class UserRecordEditActivity : AppCompatActivity() {

    private lateinit var etName: EditText
    private lateinit var etText: EditText
    private lateinit var tvPosition: TextView
    private lateinit var tvImage: TextView
    private lateinit var ivPreview: ImageView
    private lateinit var btnImage: MaterialButton
    private lateinit var btnPrev: MaterialButton
    private lateinit var btnNext: MaterialButton
    private lateinit var btnAdd: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnExport: MaterialButton
    private lateinit var btnImport: MaterialButton
    private lateinit var btnClose: ImageButton

    private val dao by lazy { AppDatabase.getInstance(this).recordDao() }

    private var records: List<Record> = emptyList()

    /** Index into [records], or [NEW_POSITION] while a not-yet-saved record is on screen. */
    private var position = NEW_POSITION

    /** Stored name of the picked background, held separately because it is not an editable field. */
    private var imageName: String = ""

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importImage(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_userrecord_edit)
        keepTypingVisible()

        etName = findViewById(R.id.et_record_name)
        etText = findViewById(R.id.et_record_text)
        tvPosition = findViewById(R.id.tv_record_position)
        tvImage = findViewById(R.id.tv_record_image)
        ivPreview = findViewById(R.id.iv_record_image_preview)
        btnImage = findViewById(R.id.btn_record_image)
        btnPrev = findViewById(R.id.btn_record_prev)
        btnNext = findViewById(R.id.btn_record_next)
        btnAdd = findViewById(R.id.btn_record_add)
        btnSave = findViewById(R.id.btn_record_save)
        btnCancel = findViewById(R.id.btn_record_cancel)
        btnExport = findViewById(R.id.btn_record_export)
        btnImport = findViewById(R.id.btn_record_import)
        btnClose = findViewById(R.id.btn_record_close)

        btnImage.setOnClickListener { pickImage.launch(arrayOf("image/*")) }
        btnPrev.setOnClickListener {
            // From a new record, "previous" steps back into the saved list at the end.
            val target = if (position == NEW_POSITION) records.lastIndex else position - 1
            confirmDiscard { moveTo(target) }
        }
        btnNext.setOnClickListener { confirmDiscard { moveTo(position + 1) } }
        btnAdd.setOnClickListener { confirmDiscard { startNewRecord() } }
        btnSave.setOnClickListener { save() }
        btnCancel.setOnClickListener { cancel() }
        btnClose.setOnClickListener { confirmDiscard { finish() } }
        btnExport.setOnClickListener { exportPrayer() }
        btnImport.setOnClickListener { QrScan.start(this) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                confirmDiscard {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val restoring = savedInstanceState != null
        val restoredPosition = savedInstanceState?.getInt(STATE_POSITION, NEW_POSITION) ?: NEW_POSITION
        val restoredImage = savedInstanceState?.getString(STATE_IMAGE_URI).orEmpty()
        val requestedId = intent.getLongExtra(EXTRA_RECORD_ID, -1L)

        lifecycleScope.launch {
            records = dao.getAllOnce()
            if (restoring) {
                // Keep the in-progress edit: the EditTexts have already restored their own text.
                position = if (restoredPosition == NEW_POSITION || records.isEmpty()) {
                    NEW_POSITION
                } else {
                    restoredPosition.coerceIn(0, records.lastIndex)
                }
                setImage(restoredImage)
                updateChrome()
            } else {
                val start = records.indexOfFirst { it.id == requestedId }
                if (records.isEmpty()) startNewRecord() else moveTo(if (start >= 0) start else 0)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_POSITION, position)
        outState.putString(STATE_IMAGE_URI, imageName)
    }

    /**
     * Gives the prayer text the whole screen while the keyboard is up.
     *
     * `windowSoftInputMode="adjustResize"` alone does nothing from targetSdk 35, where the app is
     * laid out edge to edge and the window no longer shrinks for the keyboard by itself, so the
     * inset is applied here. The buttons below the text field are collapsed at the same time —
     * none of them can be reached while typing, and the space is worth more to the text.
     */
    private fun keepTypingVisible() {
        val root = findViewById<View>(R.id.record_edit_root)
        val buttonRows = listOf<View>(
            findViewById(R.id.row_record_paging),
            findViewById(R.id.row_record_share),
            findViewById(R.id.row_record_actions)
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val typing = insets.isVisible(WindowInsetsCompat.Type.ime())
            buttonRows.forEach { it.visibility = if (typing) View.GONE else View.VISIBLE }
            // With the rows gone there is nothing left holding space for the navigation bar,
            // so the full keyboard inset applies; at rest the action row's own padding covers it.
            val bottom = if (typing) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottom)
            insets
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun moveTo(newPosition: Int) {
        if (records.isEmpty()) {
            startNewRecord()
            return
        }
        position = newPosition.coerceIn(0, records.lastIndex)
        showRecord(records[position])
    }

    private fun startNewRecord() {
        position = NEW_POSITION
        showRecord(null)
        etName.requestFocus()
    }

    private fun showRecord(record: Record?) {
        etName.error = null
        etName.setText(record?.name ?: "")
        etText.setText(record?.text ?: "")
        setImage(record?.image ?: "")
        updateChrome()
    }

    private fun updateChrome() {
        tvPosition.text = if (position == NEW_POSITION) {
            getString(R.string.record_position_new)
        } else {
            getString(R.string.record_position, position + 1, records.size)
        }
        val hasRecords = records.isNotEmpty()
        btnPrev.isEnabled = hasRecords && (position == NEW_POSITION || position > 0)
        btnNext.isEnabled = hasRecords && position != NEW_POSITION && position < records.lastIndex
        btnPrev.alpha = if (btnPrev.isEnabled) 1f else DISABLED_ALPHA
        btnNext.alpha = if (btnNext.isEnabled) 1f else DISABLED_ALPHA
    }

    // ── Image ─────────────────────────────────────────────────────────────────

    /**
     * Copies the picked image into the app's own storage. The picker's grant lasts only for this
     * activity, so the bytes are taken now rather than the location remembered.
     */
    private fun importImage(source: Uri) {
        btnImage.isEnabled = false
        lifecycleScope.launch {
            val stored = RecordImageStore.importFrom(this@UserRecordEditActivity, source)
            btnImage.isEnabled = true
            if (stored == null) {
                Toast.makeText(
                    this@UserRecordEditActivity,
                    R.string.image_import_failed,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                // Any image this replaces is swept up by RecordImageStore.pruneOrphans.
                setImage(stored)
            }
        }
    }

    private fun setImage(image: String) {
        imageName = image
        if (image.isBlank()) {
            tvImage.setText(R.string.no_image)
            ivPreview.setImageDrawable(null)
            ivPreview.setTag(R.id.tag_image_uri, null)
            ivPreview.visibility = View.GONE
        } else {
            tvImage.text = if (RecordImageStore.isStoredName(image)) {
                getString(R.string.image_saved)
            } else {
                Uri.parse(image).lastPathSegment ?: image
            }
            ivPreview.visibility = View.VISIBLE
            RecordImages.loadInto(ivPreview, image, lifecycleScope)
        }
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Shows this one prayer as a QR code, exactly as it appears on screen — unsaved edits
     * included, since that is what "the prayer displayed" means to someone looking at it.
     *
     * It travels without a set, so whoever scans it gets a loose prayer rather than a set of one.
     */
    private fun exportPrayer() {
        val name = etName.text.toString().trim()
        if (name.isEmpty()) {
            etName.error = getString(R.string.record_name_required)
            etName.requestFocus()
            return
        }
        startActivity(
            Intent(this, QrDisplayActivity::class.java)
                .putExtra(QrDisplayActivity.EXTRA_PRAYER_NAME, name)
                .putExtra(QrDisplayActivity.EXTRA_PRAYER_TEXT, etText.text.toString())
        )
    }

    // ── Save / cancel ─────────────────────────────────────────────────────────

    private fun save() {
        val name = etName.text.toString().trim()
        if (name.isEmpty()) {
            etName.error = getString(R.string.record_name_required)
            etName.requestFocus()
            return
        }
        val text = etText.text.toString()
        val current = records.getOrNull(position).takeIf { position != NEW_POSITION }

        // Names identify a prayer in the spinner, in a set, and when a shared bundle is matched
        // against the library, so they have to be unique. Compared without regard to case.
        if (records.any { it.id != current?.id && it.name.trim().equals(name, ignoreCase = true) }) {
            etName.error = getString(R.string.record_name_duplicate)
            etName.requestFocus()
            return
        }

        lifecycleScope.launch {
            val savedId = try {
                if (current == null) {
                    dao.insert(Record(name = name, image = imageName, text = text))
                } else {
                    dao.update(current.copy(name = name, image = imageName, text = text))
                    current.id
                }
            } catch (e: SQLiteConstraintException) {
                // The unique index is the backstop if the snapshot above was stale.
                etName.error = getString(R.string.record_name_duplicate)
                etName.requestFocus()
                return@launch
            }
            records = dao.getAllOnce()
            // Sorting is by name, so a rename can move the record — follow it by id.
            moveTo(records.indexOfFirst { it.id == savedId }.coerceAtLeast(0))
            Toast.makeText(this@UserRecordEditActivity, R.string.record_saved, Toast.LENGTH_SHORT)
                .show()
        }
    }

    /**
     * Throws away whatever is on screen. With no edits to throw away there is nothing left for
     * cancel to mean, so it closes the screen.
     */
    private fun cancel() {
        if (!isDirty()) {
            finish()
            return
        }
        if (position == NEW_POSITION) {
            // Drop the draft and go back to the list, if there is one.
            if (records.isEmpty()) startNewRecord() else moveTo(0)
        } else {
            showRecord(records[position])
        }
    }

    // ── Dirty tracking ────────────────────────────────────────────────────────

    private fun isDirty(): Boolean {
        val current = records.getOrNull(position).takeIf { position != NEW_POSITION }
        val name = etName.text.toString().trim()
        val text = etText.text.toString()
        return if (current == null) {
            name.isNotEmpty() || text.isNotEmpty() || imageName.isNotEmpty()
        } else {
            name != current.name || text != current.text || imageName != current.image
        }
    }

    private fun confirmDiscard(action: () -> Unit) {
        if (!isDirty()) {
            action()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.discard_changes_title)
            .setMessage(R.string.discard_changes_message)
            .setPositiveButton(R.string.discard) { _, _ -> action() }
            .setNegativeButton(R.string.keep_editing, null)
            .show()
    }

    companion object {
        const val EXTRA_RECORD_ID = "recordId"
        private const val STATE_POSITION = "position"
        private const val STATE_IMAGE_URI = "imageUri"
        private const val NEW_POSITION = -1
        private const val DISABLED_ALPHA = 0.3f
    }
}
