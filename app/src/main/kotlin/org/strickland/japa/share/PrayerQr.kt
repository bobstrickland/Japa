package org.strickland.japa.share

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Carries a set of prayers in a single QR code — the in-the-room half of sharing.
 *
 * The payload is the same manifest [PrayerBundle] writes into a file, minus the image fields:
 * deflated, then Base45'd so the code stays in QR's alphanumeric mode. Devanagari is verbose in
 * UTF-8 but highly repetitive, so deflate takes roughly 70% off and a long prayer fits comfortably.
 *
 * Backgrounds are deliberately left out. The words are the shared content; the picture is personal
 * taste, and carrying it would blow the capacity many times over.
 */
object PrayerQr {

    /** Identifies our payload and tells a future version what it is looking at. */
    private const val PREFIX = "JAPA1:"

    /**
     * Character budget for a version-40 code at error correction L in alphanumeric mode.
     * L is ample for a phone screen held at arm's length.
     */
    private const val MAX_CHARS = 4296

    data class Payload(val text: String, val entryCount: Int)

    /** Returns null when the set is too large for one code; the caller should offer the file share. */
    fun encode(setName: String?, entries: List<PrayerBundle.Entry>): Payload? {
        if (entries.isEmpty()) return null
        // Images cannot travel by QR, so the manifest is rebuilt without them.
        val textOnly = entries.map { PrayerBundle.Entry(it.name, it.text, null) }
        val json = PrayerBundle.toJson(
            PrayerBundle.Manifest(PrayerBundle.FORMAT_VERSION, setName, textOnly)
        )
        val text = PREFIX + Base45.encode(deflate(json.toByteArray()))
        return if (text.length > MAX_CHARS) null else Payload(text, entries.size)
    }

    /** Parses a scanned code, or returns null if it is not one of ours. */
    fun decode(scanned: String): PrayerBundle.Manifest? {
        if (!scanned.startsWith(PREFIX)) return null
        val bytes = Base45.decode(scanned.removePrefix(PREFIX)) ?: return null
        val json = inflate(bytes) ?: return null
        val manifest = PrayerBundle.fromJson(json.decodeToString()) ?: return null
        return manifest.takeIf { it.formatVersion <= PrayerBundle.FORMAT_VERSION }
    }

    /** How full the code is, for telling the user a set no longer fits. */
    fun charCount(setName: String?, entries: List<PrayerBundle.Entry>): Int {
        val textOnly = entries.map { PrayerBundle.Entry(it.name, it.text, null) }
        val json = PrayerBundle.toJson(
            PrayerBundle.Manifest(PrayerBundle.FORMAT_VERSION, setName, textOnly)
        )
        return PREFIX.length + Base45.encode(deflate(json.toByteArray())).length
    }

    fun capacity(): Int = MAX_CHARS

    fun render(text: String, sizePx: Int): Bitmap? = try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "ISO-8859-1"
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            val row = y * matrix.width
            for (x in 0 until matrix.width) {
                pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) {
        null
    }

    // ── Compression ───────────────────────────────────────────────────────────

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        return try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream(input.size / 2)
            val buffer = ByteArray(4096)
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer))
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(input: ByteArray): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(input)
            val out = ByteArrayOutputStream(input.size * 4)
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read == 0 && inflater.needsInput()) return null
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            inflater.end()
        }
    }
}
