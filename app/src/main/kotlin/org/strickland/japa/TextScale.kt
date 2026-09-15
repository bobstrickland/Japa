package org.strickland.japa

import android.content.Context
import android.util.TypedValue
import android.widget.TextView

/**
 * Applies the text size chosen in Settings to the screens that show prayer text.
 *
 * The listed views are designed at several different sizes — a prayer body at 22sp, the field
 * labels and placeholders well below that — so the setting is applied as a *scale* rather than as
 * one flat size, which keeps each screen's hierarchy intact at every setting. The scale is taken
 * from the chosen entry of `text_size_values` against its first entry, so the prayer text, which
 * is already designed at that first value, lands on exactly the sp figure the array names, and
 * everything else grows in step with it.
 */
object TextScale {

    /** How much larger than its designed size text should be drawn, 1.0 at the first entry. */
    @JvmStatic
    fun scaleFor(context: Context): Float {
        val values = context.resources.getStringArray(R.array.text_size_values)
        if (values.size < 2) return 1f

        val stored = context
            .getSharedPreferences(CounterService.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(CounterService.PREF_TEXT_SIZE, 0)
        val index = stored.coerceIn(0, values.size - 1)

        val baseline = parseSize(values[0]) ?: return 1f
        val chosen = parseSize(values[index]) ?: return 1f
        return if (baseline > 0f) chosen / baseline else 1f
    }

    /** Reads "33sp" as 33. Returns null for anything unparseable, so a typo is ignored. */
    private fun parseSize(value: String): Float? =
        value.trim().removeSuffix("sp").removeSuffix("dp").trim().toFloatOrNull()

    /**
     * Sizes [views] for the current setting. Safe to call again on every resume: the first call
     * records each view's original size, and later calls rescale that rather than the last result.
     */
    @JvmStatic
    fun applyTo(vararg views: TextView) {
        val context = views.firstOrNull()?.context ?: return
        val scale = scaleFor(context)
        for (view in views) {
            // Kept in pixels, which already carry the device's own font-scale setting; the user's
            // choice here multiplies that rather than replacing it.
            val base = view.getTag(R.id.tag_text_size_base) as? Float
                ?: view.textSize.also { view.setTag(R.id.tag_text_size_base, it) }
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * scale)
        }
    }
}
