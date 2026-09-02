package org.strickland.japa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.strickland.japa.data.RecordDao

/**
 * Owns the background images belonging to user prayers.
 *
 * A picked image is copied into app-private storage rather than referenced where it sits. A
 * `content://` URI from the document picker points at a file this app does not control: the grant
 * can be revoked, the photo can be deleted, and the bytes cannot be bundled into a shared prayer.
 * Copying makes the image part of the record.
 *
 * The copy is downscaled and re-encoded on the way in, which keeps a full-screen background around
 * 250 KB instead of the several megabytes a phone camera produces.
 */
object RecordImageStore {

    private const val DIR = "backgrounds"

    /** Enough to fill the largest phone screen; anything more is invisible and costs memory. */
    private const val MAX_EDGE = 2048
    private const val JPEG_QUALITY = 85

    /** How long a just-picked image is protected from pruning while its edit is unsaved. */
    private const val UNSAVED_GRACE_MS = 60 * 60 * 1000L

    fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    fun fileFor(context: Context, name: String): File = File(dir(context), name)

    /** True for a name this store owns, false for a legacy `content://` value. */
    fun isStoredName(value: String): Boolean = value.isNotBlank() && !value.contains("://")

    /**
     * Copies [source] into app-private storage, downscaled and re-encoded as JPEG.
     * Returns the stored filename, or null if the image could not be read.
     */
    suspend fun importFrom(context: Context, source: Uri): String? = withContext(Dispatchers.IO) {
        val bitmap = decodeForImport(context, source) ?: return@withContext null
        val name = "${UUID.randomUUID()}.jpg"
        val target = fileFor(context, name)
        try {
            FileOutputStream(target).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    target.delete()
                    return@withContext null
                }
            }
            name
        } catch (e: Exception) {
            target.delete()
            null
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Copies already-prepared image bytes straight into the store — used when importing a shared
     * bundle, whose images this app produced and downscaled on the way out.
     *
     * Copied rather than re-encoded so a round trip through sharing does not soften the image.
     * Call off the main thread; the caller keeps ownership of [source] and closes it.
     */
    fun storeCopy(context: Context, source: InputStream, maxBytes: Long): String? {
        val name = "${UUID.randomUUID()}.jpg"
        val target = fileFor(context, name)
        var total = 0L
        try {
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) break
                    out.write(buffer, 0, read)
                }
            }
        } catch (e: Exception) {
            target.delete()
            return null
        }
        if (total == 0L || total > maxBytes) {
            target.delete()
            return null
        }
        return name
    }

    fun delete(context: Context, name: String) {
        if (isStoredName(name)) fileFor(context, name).delete()
    }

    /**
     * Removes stored images no record refers to any more — images replaced during an edit, and
     * images picked in an add that was then cancelled.
     *
     * Recently written files are left alone: an image picked in an edit that has not been saved yet
     * is not referenced by any record, and deleting it would leave the pending save dangling.
     */
    suspend fun pruneOrphans(context: Context, dao: RecordDao) = withContext(Dispatchers.IO) {
        val referenced = dao.getAllOnce().map { it.image }.toSet()
        val cutoff = System.currentTimeMillis() - UNSAVED_GRACE_MS
        dir(context).listFiles()?.forEach { file ->
            if (file.name !in referenced && file.lastModified() < cutoff) file.delete()
        }
    }

    /**
     * Brings records created before images were copied into this store up to date.
     *
     * A record whose image cannot be read is left alone rather than cleared, so a temporarily
     * unavailable file (unmounted storage, say) is retried on the next launch instead of being
     * silently dropped.
     */
    suspend fun migrateLegacyImages(context: Context, dao: RecordDao) = withContext(Dispatchers.IO) {
        dao.getAllOnce().forEach { record ->
            if (record.image.isBlank() || isStoredName(record.image)) return@forEach
            val name = importFrom(context, Uri.parse(record.image)) ?: return@forEach
            dao.update(record.copy(image = name))
        }
    }

    /**
     * Decodes at no more than [MAX_EDGE] on the long side. On API 28+ ImageDecoder scales during
     * the decode, so the full-size bitmap is never allocated; below that the image is sampled down
     * and then scaled exactly.
     */
    private fun decodeForImport(context: Context, source: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(context.contentResolver, source)
            ) { decoder, info, _ ->
                val (w, h) = fit(info.size.width, info.size.height)
                decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            }
            val sampled = context.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            val (w, h) = fit(sampled.width, sampled.height)
            if (w == sampled.width && h == sampled.height) {
                sampled
            } else {
                Bitmap.createScaledBitmap(sampled, w, h, true).also { sampled.recycle() }
            }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /** Scales [width] x [height] down so the long edge is at most [MAX_EDGE], preserving aspect. */
    private fun fit(width: Int, height: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= MAX_EDGE || longest == 0) return width to height
        val scale = MAX_EDGE.toDouble() / longest
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    private fun sampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= MAX_EDGE) sample *= 2
        return sample
    }
}
