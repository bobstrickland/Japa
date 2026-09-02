package org.strickland.japa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads the user-selected background image for a [org.strickland.japa.data.Record].
 *
 * The stored value is a content:// URI handed back by the system document picker, which is why
 * [UserRecordEditActivity] takes a persistable read permission on it — without that the URI stops
 * resolving as soon as the process dies.
 */
object RecordImages {

    /**
     * Decodes [uriString] off the main thread and drops the result into [target].
     *
     * The view is tagged with the URI so a slow decode belonging to a previous spinner selection
     * cannot overwrite a newer one.
     */
    fun loadInto(target: ImageView, uriString: String?, scope: CoroutineScope) {
        target.setTag(R.id.tag_image_uri, uriString)
        if (uriString.isNullOrBlank()) {
            target.setImageDrawable(null)
            return
        }
        scope.launch {
            val bitmap = decode(target.context, uriString, target.width, target.height)
            if (target.getTag(R.id.tag_image_uri) == uriString) {
                target.setImageBitmap(bitmap)
            }
        }
    }

    /** Returns null rather than throwing when the URI is gone, revoked, or not an image. */
    suspend fun decode(
        context: Context,
        uriString: String,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        val metrics = context.resources.displayMetrics
        val targetW = if (reqWidth > 0) reqWidth else metrics.widthPixels
        val targetH = if (reqHeight > 0) reqHeight else metrics.heightPixels
        try {
            val uri = Uri.parse(uriString)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // ImageDecoder applies the EXIF orientation for us; BitmapFactory does not.
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height, targetW, targetH))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
                }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun sampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (height / (sample * 2) >= reqHeight && width / (sample * 2) >= reqWidth) {
            sample *= 2
        }
        return sample
    }
}
