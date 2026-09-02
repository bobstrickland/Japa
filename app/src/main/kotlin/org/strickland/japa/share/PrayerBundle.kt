package org.strickland.japa.share

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.strickland.japa.RecordImageStore
import org.strickland.japa.data.AppDatabase
import org.strickland.japa.data.PrayerSet
import org.strickland.japa.data.Record

/**
 * The interchange format for sharing prayers between devices.
 *
 * A bundle is a zip holding `manifest.json` and the background images it references. The manifest
 * is the whole of the shareable content — a QR code carries the same JSON with the image fields
 * dropped, so both surfaces are built from one encoder.
 *
 * Images are named by a hash of their contents, so a set where several prayers share a background
 * carries it once.
 */
object PrayerBundle {

    const val FORMAT_VERSION = 1
    const val EXTENSION = "japa"

    /** Sent as a zip so mail and messaging apps will carry it; the extension identifies it as ours. */
    const val MIME_TYPE = "application/zip"

    private const val MANIFEST = "manifest.json"
    private const val IMAGE_DIR = "img/"

    /** Refuses absurd images on import; anything this app produces is a fraction of it. */
    private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024

    data class Entry(val name: String, val text: String, val imagePath: String?)

    data class Manifest(
        val formatVersion: Int,
        val setName: String?,
        val entries: List<Entry>
    )

    data class ImportResult(
        val imported: Int,
        val replaced: Int,
        val kept: Int,
        val setName: String?
    )

    // ── Writing ───────────────────────────────────────────────────────────────

    /**
     * Writes [records] — in the order given — to a bundle in the cache directory,
     * returning the file to hand to a share intent.
     */
    suspend fun write(
        context: Context,
        setName: String?,
        records: List<Record>
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shares").apply { mkdirs() }
        // One file per bundle name, overwritten, so repeated shares do not pile up in the cache.
        val file = File(dir, "${fileBaseName(setName)}.$EXTENSION")

        val entries = mutableListOf<Entry>()
        val images = mutableMapOf<String, File>() // zip path -> source file

        for (record in records) {
            val source = record.image
                .takeIf { RecordImageStore.isStoredName(it) }
                ?.let { RecordImageStore.fileFor(context, it) }
                ?.takeIf { it.exists() }

            val path = source?.let {
                val entryPath = IMAGE_DIR + contentHash(it) + ".jpg"
                images[entryPath] = it
                entryPath
            }
            entries += Entry(record.name, record.text, path)
        }

        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(toJson(Manifest(FORMAT_VERSION, setName, entries)).toByteArray())
            zip.closeEntry()

            images.forEach { (path, source) ->
                zip.putNextEntry(ZipEntry(path))
                source.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        file
    }

    fun toJson(manifest: Manifest): String {
        val records = JSONArray()
        manifest.entries.forEach { entry ->
            records.put(
                JSONObject().apply {
                    put("name", entry.name)
                    put("text", entry.text)
                    entry.imagePath?.let { put("image", it) }
                }
            )
        }
        return JSONObject().apply {
            put("formatVersion", manifest.formatVersion)
            manifest.setName?.let { put("setName", it) }
            put("records", records)
        }.toString()
    }

    fun fromJson(json: String): Manifest? = try {
        val root = JSONObject(json)
        val array = root.getJSONArray("records")
        val entries = (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            Entry(
                name = item.getString("name"),
                text = item.optString("text", ""),
                imagePath = item.optString("image", "").takeIf { it.isNotBlank() }
            )
        }
        Manifest(
            formatVersion = root.optInt("formatVersion", 1),
            setName = root.optString("setName", "").takeIf { it.isNotBlank() },
            entries = entries
        )
    } catch (e: Exception) {
        null
    }

    // ── Reading ───────────────────────────────────────────────────────────────

    /** Reads just the manifest, so the import screen can describe the bundle before committing. */
    suspend fun readManifest(context: Context, uri: Uri): Manifest? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.name == MANIFEST) {
                            return@withContext fromJson(zip.readBytes().decodeToString())
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Adds the bundle's prayers to the library.
     *
     * When [skipExisting] is set, a prayer whose name is already present is left alone and the
     * existing one is used for set membership — receiving a set you mostly already have should not
     * fill the library with duplicates.
     */
    suspend fun import(
        context: Context,
        uri: Uri,
        overwriteExisting: Boolean
    ): ImportResult? = withContext(Dispatchers.IO) {
        val manifest = readManifest(context, uri) ?: return@withContext null
        // A newer format may mean fields this build cannot honour, so refuse rather than guess.
        if (manifest.formatVersion > FORMAT_VERSION) return@withContext null

        return@withContext importManifest(context, manifest, overwriteExisting) { wanted ->
            extractImages(context, uri, wanted)
        }
    }

    /**
     * Applies an already-parsed manifest.
     *
     * Shared by the file and QR paths; [resolveImages] supplies stored filenames for the image
     * paths that will actually be used, and returns nothing for a QR, which carries no images.
     *
     * Prayer names are unique, so an incoming prayer that matches one already here is either
     * replaced or left alone, never duplicated. [overwriteExisting] is the user's answer to that.
     */
    suspend fun importManifest(
        context: Context,
        manifest: Manifest,
        overwriteExisting: Boolean,
        resolveImages: (Set<String>) -> Map<String, String>
    ): ImportResult = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val recordDao = db.recordDao()
        val setDao = db.prayerSetDao()

        // Kept current as records are inserted, so a bundle holding its own duplicate resolves
        // against the copy just added rather than inserting it twice.
        val byName = HashMap<String, Record>()
        recordDao.getAllOnce().forEach { byName[nameKey(it.name)] = it }

        val neededImages = manifest.entries
            .filter { byName[nameKey(it.name)] == null || overwriteExisting }
            .mapNotNull { it.imagePath }
            .toSet()
        val storedImages = resolveImages(neededImages)

        var imported = 0
        var replaced = 0
        var kept = 0
        val orderedIds = mutableListOf<Long>()

        for (entry in manifest.entries) {
            val name = entry.name.trim()
            val key = nameKey(name)
            val existing = byName[key]
            val incomingImage = entry.imagePath?.let { storedImages[it] }

            when {
                existing == null -> {
                    val record = Record(
                        name = name,
                        image = incomingImage.orEmpty(),
                        text = entry.text
                    )
                    val id = recordDao.insert(record)
                    byName[key] = record.copy(id = id)
                    imported++
                    orderedIds += id
                }

                overwriteExisting -> {
                    // A QR carries no images, so a missing one means "not supplied" rather than
                    // "remove the background" — replacing must not wipe a picture it never had.
                    val updated = existing.copy(
                        image = incomingImage ?: existing.image,
                        text = entry.text
                    )
                    recordDao.update(updated)
                    byName[key] = updated
                    replaced++
                    orderedIds += existing.id
                }

                else -> {
                    kept++
                    orderedIds += existing.id
                }
            }
        }

        // A named bundle rebuilds the set on this device, in the order it was shared.
        val setName = manifest.setName
        if (setName != null && orderedIds.isNotEmpty()) {
            val existingSet = setDao.getSetsOnce()
                .firstOrNull { it.name.equals(setName, ignoreCase = true) }
            val setId = existingSet?.id ?: setDao.insertSet(PrayerSet(name = setName))
            orderedIds.distinct().forEach { setDao.addToSet(setId, it) }
            setDao.reorder(setId, setDao.getMembersOnce(setId).map { it.id })
        }

        ImportResult(imported, replaced, kept, setName)
    }

    /** Names are compared trimmed and case-insensitively wherever prayers are matched. */
    fun nameKey(name: String): String = name.trim().lowercase()

    /** Copies the [wanted] images into the app's store, returning zip path -> stored filename. */
    private fun extractImages(
        context: Context,
        uri: Uri,
        wanted: Set<String>
    ): Map<String, String> {
        if (wanted.isEmpty()) return emptyMap()
        val stored = mutableMapOf<String, String>()
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.name in wanted && entry.name !in stored) {
                            RecordImageStore.storeCopy(context, zip, MAX_IMAGE_BYTES)
                                ?.let { stored[entry.name] = it }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Prayers still import; they simply arrive without their backgrounds.
        }
        return stored
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun contentHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    /** Keeps the shared filename recognisable while staying safe on any filesystem. */
    fun fileBaseName(setName: String?): String {
        val cleaned = setName.orEmpty().replace(Regex("[^A-Za-z0-9 _-]"), "").trim().take(40)
        return cleaned.ifBlank { "prayers" }.replace(' ', '-')
    }
}
