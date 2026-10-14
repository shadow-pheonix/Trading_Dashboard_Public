package com.shadowpheonix.tradingdashboard

import android.content.Context

interface AppLockPreferences {
    fun read(): Boolean?
    fun write(enabled: Boolean): Boolean
}

// Independent of encrypted dashboard credentials; existing installs stay locked by default.
class AppLockStore(private val preferences: AppLockPreferences) {
    constructor(context: Context) : this(object : AppLockPreferences {
        private val prefs = context.getSharedPreferences("dashboard_app_lock", Context.MODE_PRIVATE)
        override fun read(): Boolean? = if (prefs.contains("enabled")) prefs.getBoolean("enabled", true) else null
        override fun write(enabled: Boolean): Boolean = prefs.edit().putBoolean("enabled", enabled).commit()
    })

    fun load(): Boolean = preferences.read() ?: true
    fun save(enabled: Boolean) { check(preferences.write(enabled)) }
}
