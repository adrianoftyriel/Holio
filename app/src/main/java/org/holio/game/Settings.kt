package org.holio.game

import android.content.Context

/**
 * Persistent player settings, backed by [android.content.SharedPreferences].
 * Currently just the round length chosen on the main-menu Settings screen.
 */
class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("holio_settings", Context.MODE_PRIVATE)

    /** Round length in seconds. Always one of [DURATIONS]. */
    var roundSeconds: Int
        get() = prefs.getInt(KEY_ROUND, DEFAULT_ROUND)
        set(value) {
            prefs.edit().putInt(KEY_ROUND, value).apply()
        }

    /** The chosen round length in milliseconds, for [GameWorld]. */
    val roundMillis: Long get() = roundSeconds * 1000L

    companion object {
        const val DEFAULT_ROUND = 120

        /** Selectable round lengths, in seconds, shown on the Settings screen. */
        val DURATIONS = intArrayOf(60, 120, 180)

        private const val KEY_ROUND = "round_seconds"
    }
}
