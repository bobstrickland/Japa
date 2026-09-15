package org.strickland.japa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.widget.ImageView
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Displays the background image belonging to a [org.strickland.japa.data.Record].
 *
 * `Record.image` normally holds a filename owned by [RecordImageStore]. Records created before
 * images were copied in hold a `content://` URI instead; those still render here until
 * [RecordImageStore.migrateLegacyImages] converts them.
 */
object RecordImages {

    /**
     * Decodes the image off the main thread and drops the result into [target].
     *
     * The view is tagged with the value so a slow decode belonging to a previous spinner selection
     * cannot overwrite a newer one.
     */
    fun loadInto(target: ImageView, image: String?, defaultResourceId: Int, scope: CoroutineScope) {
        target.setTag(R.id.tag_image_uri, image)
        if (image.isNullOrBlank()) {
            target.setImageDrawable(null)
            if (defaultResourceId != 0) target.setImageResource(defaultResourceId)
            return
        }
        scope.launch {
            val bitmap = decode(target.context, image, target.width, target.height)
            if (target.getTag(R.id.tag_image_uri) == image) {
                target.setImageBitmap(bitmap)
            }
        }
    }

    /** Returns null rather than throwing when the image is missing, revoked, or not an image. */
    suspend fun decode(
        context: Context,
        image: String,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        val metrics = context.resources.displayMetrics
        val targetW = if (reqWidth > 0) reqWidth else metrics.widthPixels
        val targetH = if (reqHeight > 0) reqHeight else metrics.heightPixels
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // ImageDecoder applies the EXIF orientation for us; BitmapFactory does not.
                ImageDecoder.decodeBitmap(imageSource(context, image)) { decoder, info, _ ->
                    decoder.setTargetSampleSize(
                        sampleSize(info.size.width, info.size.height, targetW, targetH)
                    )
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openStream(context, image)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
                }
                openStream(context, image)?.use { BitmapFactory.decodeStream(it, null, opts) }
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun imageSource(context: Context, image: String): ImageDecoder.Source =
        if (RecordImageStore.isStoredName(image)) {
            ImageDecoder.createSource(RecordImageStore.fileFor(context, image))
        } else {
            ImageDecoder.createSource(context.contentResolver, Uri.parse(image))
        }

    private fun openStream(context: Context, image: String): InputStream? =
        if (RecordImageStore.isStoredName(image)) {
            RecordImageStore.fileFor(context, image).takeIf(File::exists)?.inputStream()
        } else {
            context.contentResolver.openInputStream(Uri.parse(image))
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
