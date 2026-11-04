package com.shadowpheonix.tradingdashboard

internal data class AlertNotificationContent(val headline: String, val preview: String, val body: String)

// The source heading is displayed once as Android's title. Keep the stored
// message and in-app presentation untouched; only shade/banner layout changes.
internal fun notificationContent(value: AlertPresentation): AlertNotificationContent {
    val lines = value.details.lines()
    val heading = lines.first()
    val body = lines.drop(1).joinToString("\n").trimStart('\n')
    val details = lines.drop(1).filter { it.isNotBlank() }
    fun field(label: String): String? = details.firstOrNull {
        it.removePrefix("• ").startsWith("$label:", ignoreCase = true)
    }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }

    val milestone = heading in setOf("📈 Combined open P&L", "📉 Combined open P&L")
    val money = "[+−-]\\$(?:[0-9]{1,3}(?:,[0-9]{3})*|[0-9]+)"
    val crossed = details.singleOrNull {
        Regex("^Crossed: $money [↑↓](?: \\([0-9]+ levels\\))?$").matches(it)
    }
    val current = details.singleOrNull { Regex("^Current P&L: $money\\.[0-9]{2}$").matches(it) }
    if (milestone && crossed != null && current != null) {
        return AlertNotificationContent("${heading.substringBefore(' ')} Crossed ${crossed.removePrefix("Crossed: ")}",
            current, lines.drop(1).filterNot { it == crossed }.joinToString("\n").trimStart('\n'))
    }

    val pnl = field("PnL")
    val action = field("Action")
    val reason = field("Reason")
    val preview = when {
        pnl != null -> listOfNotNull("PnL: $pnl", reason?.let { "Reason: $it" }).joinToString(" · ")
        value.category == AlertCategory.Trade && action != null -> listOfNotNull(action,
            field("Execution Price")?.let { "Entry $it" }, field("Stop Loss")?.let { "SL $it" }).joinToString(" · ")
        reason != null -> "Reason: $reason"
        else -> details.firstOrNull().orEmpty()
    }
    return AlertNotificationContent(heading, preview.take(240), body)
}

// BigText keeps its own title above this body. Show the latest details first,
// then older source headings/messages once each, within the existing six limit.
internal fun notificationExpandedText(messages: List<AlertNotificationContent>): String {
    val latest = messages.lastOrNull() ?: return ""
    val older = messages.dropLast(1).asReversed().map { listOf(it.headline, it.body).filter(String::isNotBlank).joinToString("\n") }
    return (listOf(latest.body) + older).filter(String::isNotBlank).joinToString("\n\n")
}
