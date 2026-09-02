package org.strickland.japa

import android.content.Context

/**
 * Which prayer set the user is currently working through, and where they are in it.
 *
 * Shared between the two screens: [PrayerSetActivity] chooses the set, [UserPrayerActivity] shows
 * it. Kept in preferences rather than passed between them so the choice survives leaving the app.
 */
object PrayerSelection {

    /** No set chosen — every prayer, ordered by name. No real set id is negative. */
    const val ALL_PRAYERS = -1L

    private const val PREFS = "UserPrayerPrefs"
    private const val KEY_SET = "userPrayerSetId"
    private const val KEY_RECORD = "userPrayerRecordId"

    fun setId(context: Context): Long = prefs(context).getLong(KEY_SET, ALL_PRAYERS)

    fun setSetId(context: Context, setId: Long) {
        prefs(context).edit().putLong(KEY_SET, setId).apply()
    }

    fun recordId(context: Context): Long = prefs(context).getLong(KEY_RECORD, -1L)

    fun setRecordId(context: Context, recordId: Long) {
        prefs(context).edit().putLong(KEY_RECORD, recordId).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
