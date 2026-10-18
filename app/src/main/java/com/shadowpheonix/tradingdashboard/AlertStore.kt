package com.shadowpheonix.tradingdashboard

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString

class AlertStore internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("native_alerts", Context.MODE_PRIVATE))
    fun enabled() = prefs.getBoolean("enabled", true)
    fun enabled(value: Boolean) { check(prefs.edit().putBoolean("enabled", value).commit()) }
    fun notificationsEnabled(id: String) = prefs.getBoolean("notifications_$id", true)
    fun notificationsEnabled(id: String, value: Boolean) {
        check(prefs.edit().putBoolean("notifications_$id", value).commit())
    }
    fun canNotify(id: String) = enabled() && notificationsEnabled(id)
    fun prompted() = prefs.getBoolean("prompted", false)
    fun prompted(value: Boolean) { prefs.edit().putBoolean("prompted", value).apply() }
    fun load(id: String): AlertLedger = prefs.getString("ledger_$id", null)?.let {
        dashboardJson.decodeFromString<AlertLedger>(it)
    } ?: AlertLedger()
    fun save(id: String, ledger: AlertLedger) {
        check(prefs.edit().putString("ledger_$id", dashboardJson.encodeToString(ledger)).commit())
    }
    fun status(id: String) = prefs.getString("status_$id", "Waiting for connection").orEmpty()
    fun status(id: String, value: String) { prefs.edit().putString("status_$id", value).apply() }
    fun forget(id: String) {
        prefs.edit().remove("ledger_$id").remove("status_$id").remove("notifications_$id").apply()
    }
}
