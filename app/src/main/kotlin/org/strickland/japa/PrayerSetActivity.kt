package org.strickland.japa

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.strickland.japa.data.AppDatabase
import org.strickland.japa.data.PrayerSet
import org.strickland.japa.data.Record
import org.strickland.japa.share.PrayerBundle
import org.strickland.japa.share.PrayerQr

/**
 * Builds the named, ordered collections a mandir works through — "Sunday Assembly" and the like.
 *
 * A prayer can sit in several sets at once, so adding one here never moves it out of another, and
 * deleting a set leaves the prayers themselves alone.
 */
class PrayerSetActivity : AppCompatActivity() {

    private lateinit var spinnerSets: Spinner
    private lateinit var list: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnNew: MaterialButton
    private lateinit var btnRename: MaterialButton
    private lateinit var btnDelete: MaterialButton
    private lateinit var btnAddPrayers: MaterialButton
    private lateinit var btnShare: MaterialButton
    private lateinit var btnImport: MaterialButton
    private lateinit var btnQr: MaterialButton
    private lateinit var btnScan: MaterialButton
    private lateinit var btnClose: ImageButton

    private val db by lazy { AppDatabase.getInstance(this) }
    private val setDao by lazy { db.prayerSetDao() }
    private val recordDao by lazy { db.recordDao() }

    private var sets: List<PrayerSet> = emptyList()
    private var members: List<Record> = emptyList()
    private var applyingSets = false

    /** Restarted whenever the chosen set changes, so only one set's members are collected. */
    private var membersJob: Job? = null

    /** A set just created, to be selected as soon as the sets flow delivers it. */
    private var pendingSelection: Long? = null

    private val adapter = MemberAdapter()

    private val pickBundle =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                startActivity(
                    Intent(this, PrayerImportActivity::class.java).apply {
                        data = uri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_prayer_set)

        spinnerSets = findViewById(R.id.spinner_sets)
        list = findViewById(R.id.list_set_members)
        tvEmpty = findViewById(R.id.tv_set_empty)
        btnNew = findViewById(R.id.btn_set_new)
        btnRename = findViewById(R.id.btn_set_rename)
        btnDelete = findViewById(R.id.btn_set_delete)
        btnAddPrayers = findViewById(R.id.btn_set_add_prayers)
        btnShare = findViewById(R.id.btn_set_share)
        btnImport = findViewById(R.id.btn_set_import)
        btnQr = findViewById(R.id.btn_set_qr)
        btnScan = findViewById(R.id.btn_set_scan)
        btnClose = findViewById(R.id.btn_set_close)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        btnClose.setOnClickListener { finish() }
        btnNew.setOnClickListener { promptForName(null) }
        btnRename.setOnClickListener { selectedSet()?.let { promptForName(it) } }
        btnDelete.setOnClickListener { selectedSet()?.let { confirmDelete(it) } }
        btnAddPrayers.setOnClickListener { selectedSet()?.let { promptAddPrayers(it) } }
        btnShare.setOnClickListener { selectedSet()?.let { shareSet(it) } }
        // Any file: a bundle arriving through a chat app is often typed as octet-stream.
        btnImport.setOnClickListener { pickBundle.launch(arrayOf("*/*")) }
        btnQr.setOnClickListener {
            selectedSet()?.let {
                startActivity(
                    Intent(this, QrDisplayActivity::class.java)
                        .putExtra(QrDisplayActivity.EXTRA_SET_ID, it.id)
                )
            }
        }
        btnScan.setOnClickListener { QrScan.start(this) }

        spinnerSets.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (!applyingSets) onSetChosen()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                setDao.observeSets().collectLatest { showSets(it) }
            }
        }
    }

    // ── Sets ──────────────────────────────────────────────────────────────────

    private fun showSets(newSets: List<PrayerSet>) {
        val target = pendingSelection ?: selectedSet()?.id ?: PrayerSelection.setId(this)
        sets = newSets

        applyingSets = true
        // "All prayers" leads the list: this spinner also chooses what the prayer screen shows,
        // and that screen must be able to show everything.
        val labels = listOf(getString(R.string.all_prayers)) + newSets.map { it.name }
        val adapterSets = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapterSets.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSets.adapter = adapterSets
        val index = newSets.indexOfFirst { it.id == target }
        spinnerSets.setSelection(if (index >= 0) index + 1 else 0)
        applyingSets = false
        if (newSets.any { it.id == pendingSelection }) pendingSelection = null

        onSetChosen()
    }

    /**
     * Applies the spinner's choice: records it for the prayer screen, and scopes this screen's
     * actions to it. The set-specific buttons mean nothing under "All prayers", so they go dim.
     */
    private fun onSetChosen() {
        val set = selectedSet()
        PrayerSelection.setSetId(this, set?.id ?: PrayerSelection.ALL_PRAYERS)

        val hasSet = set != null
        listOf(btnRename, btnDelete, btnAddPrayers, btnShare, btnQr).forEach {
            it.isEnabled = hasSet
            it.alpha = if (hasSet) 1f else DISABLED_ALPHA
        }

        if (set == null) {
            membersJob?.cancel()
            members = emptyList()
            adapter.submit(emptyList())
            showEmpty(if (sets.isEmpty()) R.string.no_sets else R.string.set_pick_hint)
        } else {
            observeSelectedSet()
        }
    }

    /** Null for the leading "All prayers" entry, which is not a set. */
    private fun selectedSet(): PrayerSet? {
        val position = spinnerSets.selectedItemPosition
        return if (position <= 0) null else sets.getOrNull(position - 1)
    }

    private fun observeSelectedSet() {
        val set = selectedSet() ?: return
        membersJob?.cancel()
        membersJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                setDao.observeMembers(set.id).collectLatest { showMembers(it) }
            }
        }
    }

    private fun showMembers(newMembers: List<Record>) {
        members = newMembers
        adapter.submit(newMembers)
        if (newMembers.isEmpty()) showEmpty(R.string.set_empty) else tvEmpty.visibility = View.GONE
    }

    private fun showEmpty(messageRes: Int) {
        tvEmpty.setText(messageRes)
        tvEmpty.visibility = View.VISIBLE
    }

    private fun promptForName(existing: PrayerSet?) {
        val input = EditText(this).apply {
            setHint(R.string.set_name_hint)
            setText(existing?.name ?: "")
            setSingleLine()
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.new_set else R.string.rename_set)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast(R.string.set_name_required)
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    if (existing == null) {
                        val id = setDao.insertSet(PrayerSet(name = name))
                        // Land on the set just made, once the sets flow delivers it.
                        pendingSelection = id
                    } else {
                        setDao.updateSet(existing.copy(name = name))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(set: PrayerSet) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_set_title)
            .setMessage(R.string.delete_set_message)
            .setPositiveButton(R.string.delete_set) { _, _ ->
                lifecycleScope.launch { setDao.deleteSet(set) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptAddPrayers(set: PrayerSet) {
        lifecycleScope.launch {
            val already = setDao.getMembersOnce(set.id).map { it.id }.toSet()
            val candidates = recordDao.getAllOnce().filter { it.id !in already }
            if (candidates.isEmpty()) {
                toast(R.string.no_prayers_to_add)
                return@launch
            }
            val checked = BooleanArray(candidates.size)
            AlertDialog.Builder(this@PrayerSetActivity)
                .setTitle(R.string.add_prayers)
                .setMultiChoiceItems(
                    candidates.map { it.name }.toTypedArray(),
                    checked
                ) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton(R.string.add) { _, _ ->
                    lifecycleScope.launch {
                        // Appended in the order shown, so the list reads as the user ticked it.
                        candidates.filterIndexed { i, _ -> checked[i] }
                            .forEach { setDao.addToSet(set.id, it.id) }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** Writes the set to a bundle and hands it to the system share sheet. */
    private fun shareSet(set: PrayerSet) {
        lifecycleScope.launch {
            val records = setDao.getMembersOnce(set.id)
            if (records.isEmpty()) {
                toast(R.string.share_empty)
                return@launch
            }
            val file = try {
                PrayerBundle.write(this@PrayerSetActivity, set.name, records)
            } catch (e: Exception) {
                toast(R.string.share_failed)
                return@launch
            }
            val uri = FileProvider.getUriForFile(
                this@PrayerSetActivity,
                "$packageName.fileprovider",
                file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = PrayerBundle.MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, set.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share_via)))
        }
    }

    private fun toast(messageRes: Int) =
        android.widget.Toast.makeText(this, messageRes, android.widget.Toast.LENGTH_SHORT).show()

    // ── Reordering ────────────────────────────────────────────────────────────

    private fun move(from: Int, to: Int) {
        val set = selectedSet() ?: return
        if (to !in members.indices) return
        val reordered = members.map { it.id }.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        lifecycleScope.launch { setDao.reorder(set.id, reordered) }
    }

    private fun remove(record: Record) {
        val set = selectedSet() ?: return
        lifecycleScope.launch {
            setDao.removeMember(set.id, record.id)
            // Close the gap the removal leaves so positions stay dense.
            setDao.reorder(set.id, setDao.getMembersOnce(set.id).map { it.id })
        }
    }

    // ── List ──────────────────────────────────────────────────────────────────

    private inner class MemberAdapter : RecyclerView.Adapter<MemberAdapter.Holder>() {

        private var items: List<Record> = emptyList()

        fun submit(newItems: List<Record>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_set_member, parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val record = items[position]
            holder.name.text = record.name
            // Formatted for the locale so the numbering matches the script the prayers are in.
            holder.position.text = String.format(Locale.getDefault(), "%d", position + 1)

            holder.up.isEnabled = position > 0
            holder.down.isEnabled = position < items.lastIndex
            holder.up.alpha = if (holder.up.isEnabled) 1f else DISABLED_ALPHA
            holder.down.alpha = if (holder.down.isEnabled) 1f else DISABLED_ALPHA

            // bindingAdapterPosition rather than the captured position: rows are rebound on reorder.
            holder.up.setOnClickListener {
                val at = holder.bindingAdapterPosition
                if (at > 0) move(at, at - 1)
            }
            holder.down.setOnClickListener {
                val at = holder.bindingAdapterPosition
                if (at != RecyclerView.NO_POSITION && at < items.lastIndex) move(at, at + 1)
            }
            holder.remove.setOnClickListener { remove(record) }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val position: TextView = view.findViewById(R.id.tv_member_position)
            val name: TextView = view.findViewById(R.id.tv_member_name)
            val up: ImageButton = view.findViewById(R.id.btn_member_up)
            val down: ImageButton = view.findViewById(R.id.btn_member_down)
            val remove: ImageButton = view.findViewById(R.id.btn_member_remove)
        }
    }

    companion object {
        private const val DISABLED_ALPHA = 0.3f
    }
}
