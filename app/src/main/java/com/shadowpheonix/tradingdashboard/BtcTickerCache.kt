package com.shadowpheonix.tradingdashboard

import android.content.Context
import kotlinx.serialization.encodeToString

// Public market data only. Never persist a portfolio, connection or access key here.
interface BtcTickerPreferences {
    fun read(): String?
    fun write(value: String)
}

internal class BtcTickerCache(private val preferences: BtcTickerPreferences,
                              private val now: () -> Long = System::currentTimeMillis) {
    constructor(context: Context) : this(object : BtcTickerPreferences {
        private val prefs = context.getSharedPreferences("btc_ticker", Context.MODE_PRIVATE)
        override fun read(): String? = prefs.getString("last", null)
        override fun write(value: String) { prefs.edit().putString("last", value).apply() }
    })
    private var savedAt: Long? = null
    private var newestAt: Long? = null

    private fun usable(value: BtcTicker): Boolean = value.symbol == "BTCUSDT" &&
        value.price.isFinite() && value.price > 0 && value.price <= 1e20 &&
        value.change24hPct.isFinite() && value.change24hPct >= -100 && value.change24hPct <= 1e20 &&
        value.updatedAt > 0 && now() - value.updatedAt in 0L..86_400_000L

    fun load(): BtcTicker? = try {
        preferences.read()?.takeIf { it.length <= 512 }?.let {
            dashboardJson.decodeFromString<BtcTicker>(it).takeIf(::usable)?.also { value -> newestAt = value.updatedAt }
        }
    } catch (_: Exception) { null }

    fun save(value: BtcTicker) {
        if (!usable(value) || (newestAt ?: 0) > value.updatedAt) return
        newestAt = value.updatedAt
        val time = now()
        if (savedAt?.let { time - it in 0L..14_999L } == true) return
        try {
            preferences.write(dashboardJson.encodeToString(value))
            savedAt = time
        } catch (_: Exception) { /* A cache failure must never interrupt the live feed. */ }
    }
}
