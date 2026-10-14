package com.shadowpheonix.tradingdashboard

import java.util.Locale

// Allocation is supplied from each wallet's own equity pool, never the combined total.
internal fun walletAllocationLabel(sample: ModelBalance?, showWallets: Boolean = sample?.walletAllocations.orEmpty().size > 1): String {
    val allocations = sample?.walletAllocations.orEmpty().sortedBy { it.wallet }
    if (allocations.isEmpty()) return ""
    return allocations.joinToString(prefix = "(", postfix = ")", separator = ", ") {
        val pct = it.pct?.takeIf { value -> value.isFinite() }?.let { value ->
            // Telegram's Python formatter rounds exact halves to the nearest even integer.
            String.format(Locale.US, "%.0f%%", kotlin.math.round(value))
        } ?: "—"
        if (showWallets) "${it.wallet}: $pct" else pct
    }
}
