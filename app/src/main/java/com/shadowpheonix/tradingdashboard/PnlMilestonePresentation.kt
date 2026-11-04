package com.shadowpheonix.tradingdashboard

private val milestoneHeadings = setOf("📈 Combined open P&L", "📉 Combined open P&L")

internal fun isLocalPnlMilestone(event: DashboardAlert): Boolean =
    event.id.matches(Regex("pnl_[a-f0-9]{16}_[0-9]+")) && event.message.lineSequence().firstOrNull() in milestoneHeadings

/** One projection for both saved inbox rows and Android notifications. The
 * sign describes the level; the verb and icon describe movement across it. */
internal fun pnlMilestonePresentation(instance: String, lines: List<String>): AlertPresentation? {
    if (lines.firstOrNull() !in milestoneHeadings) return null
    val money = "[+−-]\\$(?:[0-9]{1,3}(?:,[0-9]{3})*|[0-9]+)"
    val crossingPattern = Regex("^Crossed: ($money) ([↑↓])( \\([0-9]+ levels\\))?$")
    val crossed = lines.singleOrNull { crossingPattern.matches(it) } ?: return null
    val current = lines.singleOrNull { Regex("^Current P&L: $money\\.[0-9]{2}$").matches(it) } ?: return null
    val match = crossingPattern.matchEntire(crossed)!!
    val rising = match.groupValues[2] == "↑"
    val heading = "${if (rising) "📈 Rose above" else "📉 Dropped below"} ${match.groupValues[1]}${match.groupValues[3]}"
    val title = listOf(instance.take(40), heading).filter(String::isNotBlank).joinToString(" · ")
    val details = (listOf(heading, current) + lines.drop(1).filterNot { it == crossed || it == current }).joinToString("\n")
    return AlertPresentation(title, current, details, AlertCategory.Status, false)
}
