package org.strickland.japa

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.strickland.japa.share.PrayerQr

/**
 * Reads a prayer QR and hands it to [PrayerImportActivity].
 *
 * The Play Services scanner brings its own camera UI, so the app needs no camera permission of its
 * own. Shared by the sets screen and the add/edit screen, which offer the same action.
 */
object QrScan {

    fun start(activity: Activity) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(activity, options).startScan()
            .addOnSuccessListener { barcode ->
                val text = barcode.rawValue
                if (text == null || PrayerQr.decode(text) == null) {
                    toast(activity, R.string.scan_not_ours)
                    return@addOnSuccessListener
                }
                activity.startActivity(
                    Intent(activity, PrayerImportActivity::class.java)
                        .putExtra(PrayerImportActivity.EXTRA_QR_PAYLOAD, text)
                )
            }
            .addOnFailureListener { toast(activity, R.string.scan_failed) }
    }

    private fun toast(activity: Activity, messageRes: Int) =
        Toast.makeText(activity, messageRes, Toast.LENGTH_SHORT).show()
}
