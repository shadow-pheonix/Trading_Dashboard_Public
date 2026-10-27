package com.shadowpheonix.tradingdashboard

import android.content.Context
import android.content.SharedPreferences

/** Installation-wide acknowledgement, independent of saved connections. */
class ChangelogStore internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("app_changelog", Context.MODE_PRIVATE))

    fun shouldShow(version: Int, firstInstallTime: Long, lastUpdateTime: Long): Boolean = try {
        val acknowledged = prefs.getInt("acknowledged_version", 0)
        when {
            version <= 0 -> false
            acknowledged > 0 -> version > acknowledged
            // Migrates installations from before changelog tracking existed.
            firstInstallTime > 0 && lastUpdateTime > firstInstallTime -> true
            else -> { acknowledge(version); false }
        }
    } catch (_: Exception) { false } // Optional release notes must never prevent opening the app.

    fun acknowledge(version: Int): Boolean = try {
        val previous = prefs.getInt("acknowledged_version", 0)
        version > 0 && (previous >= version ||
            prefs.edit().putInt("acknowledged_version", version).commit())
    } catch (_: Exception) { false }
}
