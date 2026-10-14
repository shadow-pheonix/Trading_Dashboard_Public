package com.shadowpheonix.tradingdashboard

import kotlinx.serialization.Serializable

@Serializable
data class DashboardAlert(val id: String, val timestamp: Long, val message: String) {
    fun validate(): DashboardAlert {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
        require(timestamp > 0)
        require(message.isNotBlank() && message.length <= 16000)
        require(message.none { it.code < 32 && it != '\n' && it != '\t' })
        return this
    }
}

enum class AlertCategory { Trade, Risk, Status }

// Only the bot's routine no-signal diagnostic is quiet. Blocked actual signals,
// execution failures, warm-up failures and risk alerts must still reach Android.
fun isRoutineEvaluation(alert: DashboardAlert): Boolean {
    val lines = alert.message.lines().map { it.replace("*", "").replace("`", "").trim() }
    return lines.size == 4 &&
        Regex("^🔍 \\[M[123]-(?:Fast|Med|Slow)(?:-B)?] Evaluation outcome$").matches(lines[0]) &&
        lines[1] == "Stage: no_cross" && lines[2].startsWith("Reason: no MACD cross") &&
        lines[3].startsWith("Evaluated: ")
}
data class AlertPresentation(val title: String, val preview: String, val details: String,
                             val category: AlertCategory, val urgent: Boolean)

// Keep the complete alert available on expansion; compact only the collapsed preview.
// Messages are treated as plain text. Never create actions from URLs in bot messages.
fun presentAlert(instance: String, alert: DashboardAlert): AlertPresentation {
    alert.validate()
    val lines = alert.message.replace("\r", "").lines().map { line ->
        line.replace("*", "").replace("`", "").trim()
    }.filter { line -> line.isNotBlank() && !line.all { it in "━─—-=" } }
    val body = lines.joinToString("\n")
    val upper = body.uppercase(java.util.Locale.ROOT)
    val models = Regex("\\bM([123])(?:-Fast)?(?:-B)?\\b", RegexOption.IGNORE_CASE)
        .findAll(body).map { "M${it.groupValues[1]}" }.distinct().toList()
    val model = models.singleOrNull()
    val urgent = listOf("POSITION IS NAKED", "POSITION NAKED", "UNPROTECTED", "FATAL", "CRITICAL EXECUTION FAILURE",
        "COULD NOT RE-ARM STOP", "STOP LOSS MISSING", "CIRCUIT BREAKER", "BREAKER HALT", "POSITION HAS NO STOP-LOSS", "NO RESTING STOP").any { it in upper }
    val entry = "MACRO MACD SIGNAL" in upper || "TRADE OPENED" in upper
    val exit = "TRADE CLOSED" in upper
    val risk = urgent || listOf("WARNING", "ORDER NOT SENT", "STOP LOSS", "STOP-LOSS", "RECOVERY", "DISABLED",
        "AGENT KEY", "EXECUTION FAILURE", "ERROR", "HALTED").any { it in upper }
    val category = when { urgent -> AlertCategory.Risk; entry || exit -> AlertCategory.Trade; risk -> AlertCategory.Risk; else -> AlertCategory.Status }
    val action = when {
        urgent -> "Urgent risk alert"
        entry -> when { Regex("\\bLONG\\b").containsMatchIn(upper) -> "LONG entered";
            Regex("\\bSHORT\\b").containsMatchIn(upper) -> "SHORT entered"; else -> "Trade entered" }
        exit -> "Trade closed"
        risk -> "Risk update"
        else -> lines.firstOrNull()?.replace(Regex("^\\[[^]]+]\\s*"), "")?.take(60) ?: "Bot update"
    }
    val title = listOfNotNull(instance.take(40), model, action).joinToString(" · ")
    val keys = if (entry) listOf("Execution Price:", "Position Size (notional):", "Stop Loss:")
        else if (exit) listOf("Reason:", "PnL:", "Price:") else emptyList()
    val highlights = keys.mapNotNull { key -> lines.find { key in it }?.removePrefix("• ") }
    val preview = (if (highlights.isNotEmpty()) highlights.joinToString(" · ") else lines.drop(1)
        .firstOrNull() ?: lines.firstOrNull().orEmpty()).take(240)
    return AlertPresentation(title, preview, body, category, urgent)
}

// Source IDs, not message text, identify events. Same text can describe different trades.
class AlertDeduplicator(private val capacity: Int = 1000) {
    init { require(capacity in 1..10000) }
    private val seen = LinkedHashSet<String>()
    fun restore(ids: Collection<String>) { ids.toList().takeLast(capacity).forEach { seen.add(it) } }
    fun accept(id: String): Boolean {
        if (!seen.add(id)) return false
        while (seen.size > capacity) seen.remove(seen.first())
        return true
    }
    fun savedIds(): List<String> = seen.toList()
}
