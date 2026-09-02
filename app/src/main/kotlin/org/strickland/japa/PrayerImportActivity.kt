package org.strickland.japa

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import org.strickland.japa.data.AppDatabase
import org.strickland.japa.share.PrayerBundle
import org.strickland.japa.share.PrayerQr

/**
 * Confirms and applies a shared prayer bundle.
 *
 * Reached either by opening a `.japa` file from outside the app, or from the Import button on
 * [PrayerSetActivity]. Nothing is written until the user confirms, so an unexpected file can be
 * inspected and dismissed.
 */
class PrayerImportActivity : AppCompatActivity() {

    private lateinit var tvSummary: TextView
    private lateinit var tvContents: TextView
    private lateinit var tvConflicts: TextView
    private lateinit var rgConflict: RadioGroup
    private lateinit var rbReplace: RadioButton
    private lateinit var btnImport: MaterialButton
    private lateinit var btnCancel: MaterialButton

    private var source: Uri? = null

    /** Set instead of [source] when the prayers arrived by QR, which carries no images. */
    private var scanned: PrayerBundle.Manifest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_prayer_import)

        tvSummary = findViewById(R.id.tv_import_summary)
        tvContents = findViewById(R.id.tv_import_contents)
        tvConflicts = findViewById(R.id.tv_import_conflicts)
        rgConflict = findViewById(R.id.rg_import_conflict)
        rbReplace = findViewById(R.id.rb_import_replace)
        btnImport = findViewById(R.id.btn_import_confirm)
        btnCancel = findViewById(R.id.btn_import_cancel)

        btnCancel.setOnClickListener { finish() }
        btnImport.setOnClickListener { runImport() }

        load()
    }


    /**
     * Another bundle can arrive while this screen is already up — a second file opened from a chat
     * app, say. Without this the new one would be silently ignored and the old preview left in place.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        load()
    }

    /** Reads whatever this screen was launched with and describes it, committing nothing. */
    private fun load() {
        scanned = null
        source = null
        btnImport.isEnabled = false

        val payload = intent?.getStringExtra(EXTRA_QR_PAYLOAD)
        if (payload != null) {
            val manifest = PrayerQr.decode(payload)
            if (manifest == null) {
                failed()
                return
            }
            scanned = manifest
            lifecycleScope.launch { describe(manifest) }
            return
        }

        source = resolveSource()
        val uri = source
        if (uri == null) {
            failed()
            return
        }
        lifecycleScope.launch {
            val manifest = PrayerBundle.readManifest(this@PrayerImportActivity, uri)
            if (manifest == null) failed() else describe(manifest)
        }
    }

    /** Accepts both a file opened directly and one handed over by a sharing app. */
    private fun resolveSource(): Uri? = intent?.data ?: extraStream()

    @Suppress("DEPRECATION")
    private fun extraStream(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent?.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private suspend fun describe(manifest: PrayerBundle.Manifest) {
        if (manifest.entries.isEmpty() ||
            manifest.formatVersion > PrayerBundle.FORMAT_VERSION
        ) {
            failed()
            return
        }
        val count = manifest.entries.size
        tvSummary.text = if (manifest.setName != null) {
            resources.getQuantityString(R.plurals.import_summary, count, manifest.setName, count)
        } else {
            resources.getQuantityString(R.plurals.import_summary_unnamed, count, count)
        }
        tvContents.text = manifest.entries.joinToString("\n") { it.name }
        showConflicts(manifest)
        btnImport.isEnabled = true
    }

    /**
     * Names the prayers already in the library, and asks what should happen to them. Hidden
     * entirely when nothing clashes, so a clean import is a single tap.
     */
    private suspend fun showConflicts(manifest: PrayerBundle.Manifest) {
        val existing = withContext(Dispatchers.IO) {
            AppDatabase.getInstance(this@PrayerImportActivity).recordDao().getAllOnce()
                .map { PrayerBundle.nameKey(it.name) }
                .toSet()
        }
        val clashing = manifest.entries
            .map { it.name.trim() }
            .filter { PrayerBundle.nameKey(it) in existing }
            .distinct()
        if (clashing.isEmpty()) return

        tvConflicts.text = resources.getQuantityString(
            R.plurals.import_conflicts,
            clashing.size,
            clashing.size,
            clashing.joinToString(", ")
        )
        tvConflicts.visibility = View.VISIBLE
        rgConflict.visibility = View.VISIBLE
    }

    private fun runImport() {
        btnImport.isEnabled = false
        lifecycleScope.launch {
            val fromQr = scanned
            val result = if (fromQr != null) {
                PrayerBundle.importManifest(
                    this@PrayerImportActivity,
                    fromQr,
                    rbReplace.isChecked
                ) { emptyMap() }
            } else {
                val uri = source ?: return@launch
                PrayerBundle.import(
                    this@PrayerImportActivity,
                    uri,
                    rbReplace.isChecked
                )
            }
            if (result == null) {
                btnImport.isEnabled = true
                failed()
                return@launch
            }
            Toast.makeText(
                this@PrayerImportActivity,
                getString(R.string.import_done, result.imported, result.replaced, result.kept),
                Toast.LENGTH_LONG
            ).show()
            // Opened from outside the app, land the user on their prayers rather than nowhere.
            if (isTaskRoot) {
                startActivity(Intent(this@PrayerImportActivity, UserPrayerActivity::class.java))
            }
            finish()
        }
    }

    private fun failed() {
        Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        /** A scanned QR payload, as an alternative to opening a bundle file. */
        const val EXTRA_QR_PAYLOAD = "qrPayload"
    }
}
