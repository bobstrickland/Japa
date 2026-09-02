package org.strickland.japa

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.strickland.japa.data.AppDatabase
import org.strickland.japa.share.PrayerBundle
import org.strickland.japa.share.PrayerQr

/**
 * Shows a set as a QR code for the people in the room to scan.
 *
 * The screen is driven to full brightness while it is up — scanning a dense code across a hall is
 * far more reliable on a bright display.
 */
class QrDisplayActivity : AppCompatActivity() {

    private lateinit var ivQr: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnClose: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_display)

        ivQr = findViewById(R.id.iv_qr)
        tvTitle = findViewById(R.id.tv_qr_title)
        tvHint = findViewById(R.id.tv_qr_hint)
        btnClose = findViewById(R.id.btn_qr_close)
        btnClose.setOnClickListener { finish() }

        window.attributes = window.attributes.apply { screenBrightness = 1f }

        lifecycleScope.launch {
            val prayerName = intent.getStringExtra(EXTRA_PRAYER_NAME)
            if (prayerName != null) {
                // A single prayer travels without a set, so it imports as a loose prayer.
                showCode(
                    title = prayerName,
                    setName = null,
                    entries = listOf(
                        PrayerBundle.Entry(
                            prayerName,
                            intent.getStringExtra(EXTRA_PRAYER_TEXT).orEmpty(),
                            null
                        )
                    )
                )
            } else {
                showSet(intent.getLongExtra(EXTRA_SET_ID, -1L))
            }
        }
    }

    private suspend fun showSet(setId: Long) {
        val db = AppDatabase.getInstance(this)
        val set = db.prayerSetDao().getSet(setId)
        val records = if (set == null) emptyList() else db.prayerSetDao().getMembersOnce(setId)
        showCode(
            title = set?.name.orEmpty(),
            setName = set?.name,
            entries = records.map { PrayerBundle.Entry(it.name, it.text, null) }
        )
    }

    private suspend fun showCode(
        title: String,
        setName: String?,
        entries: List<PrayerBundle.Entry>
    ) {
        tvTitle.text = title

        if (entries.isEmpty()) {
            tvHint.setText(R.string.share_empty)
            return
        }

        val payload = PrayerQr.encode(setName, entries)
        if (payload == null) {
            // Deliberately specific: the user needs to know the file share still works.
            val used = PrayerQr.charCount(setName, entries)
            tvHint.text = getString(R.string.qr_too_big, used * 100 / PrayerQr.capacity())
            return
        }

        val size = resources.displayMetrics.widthPixels
        val bitmap = withContext(Dispatchers.IO) { PrayerQr.render(payload.text, size) }
        if (bitmap == null) {
            tvHint.setText(R.string.qr_failed)
            return
        }
        ivQr.setImageBitmap(bitmap)
        ivQr.visibility = View.VISIBLE
        tvHint.text = resources.getQuantityString(
            R.plurals.qr_hint,
            payload.entryCount,
            payload.entryCount
        )
    }

    companion object {
        const val EXTRA_SET_ID = "setId"

        /** Exporting one prayer, taken from the edit screen as shown rather than as stored. */
        const val EXTRA_PRAYER_NAME = "prayerName"
        const val EXTRA_PRAYER_TEXT = "prayerText"
    }
}
