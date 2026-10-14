package com.shadowpheonix.tradingdashboard

import org.junit.Assert.*
import org.junit.Test

class AlertPresentationTest {
    private fun alert(message: String, id: String = "event1") = DashboardAlert(id, 1760000000000, message)

    @Test fun entryNotificationHasModelDirectionAndCompactTradeDetails() {
        val value = presentAlert("Main", alert("🚀 *[M1-Fast-B] MACRO MACD SIGNAL* 🚀\n\n*Action:* SHORT\nExecution Price: $82,000\n━━━━━━━━━━━━━━\n• Position Size (notional): $10,000\n• Stop Loss: $84,000\nModel Capital at Entry: $5,000"))
        assertEquals("Main · M1 · SHORT entered", value.title)
        assertEquals(AlertCategory.Trade, value.category)
        assertEquals("alerts_trades_floating", alertChannel(value))
        assertTrue(value.preview.contains("$82,000"))
        assertTrue(value.preview.contains("$10,000"))
        assertTrue(value.preview.contains("$84,000"))
        assertTrue(value.details.contains("Model Capital at Entry"))
        assertFalse(value.details.contains("━")); assertFalse(value.details.contains("*"))
    }

    @Test fun closedTradeIncludesPnlAndReasonWithoutBecomingAnUrgentRiskAlert() {
        val value = presentAlert("Vinay", alert("🛑 TRADE CLOSED\nReason: M2-Fast Stop Loss\nPrice: $80,000\n• PnL: −$40.00 (−0.8%)"))
        assertEquals("Vinay · M2 · Trade closed", value.title)
        assertTrue(value.preview.contains("−$40.00"))
        assertFalse(value.urgent); assertEquals(AlertCategory.Trade, value.category)
    }

    @Test fun unprotectedPositionAndExecutionFailuresAreUrgent() {
        for (text in listOf("☠️ [M3-Fast] FATAL: Could not re-arm Stop Loss! Position is NAKED.",
            "Critical Execution Failure\nFunction failed after 3 attempts.", "[M1] Circuit breaker HALT", "[M2] POSITION HAS NO STOP-LOSS")) {
            val value = presentAlert("Madhu", alert(text))
            assertTrue(value.urgent); assertEquals(AlertCategory.Risk, value.category)
            assertEquals("alerts_critical", alertChannel(value))
        }
    }

    @Test fun statusReportsRetainFullDetailsAndDoNotPickAnArbitraryModel() {
        val value = presentAlert("Main", alert("Daily report\nM1-Fast +$10\nM2-Fast −$5\nM3-Fast flat"))
        assertEquals("Main · Daily report", value.title)
        assertEquals(AlertCategory.Status, value.category)
        assertEquals("alerts_reports_floating", alertChannel(value))
        assertTrue(value.details.contains("M3-Fast flat"))
    }

    @Test fun repeatedTextIsAllowedButSourceIdIsPersistentlyDeduplicated() {
        val first = AlertDeduplicator(3)
        assertTrue(first.accept("a")); assertFalse(first.accept("a"))
        assertTrue(first.accept("b"))
        val next = AlertDeduplicator(3); next.restore(first.savedIds())
        assertFalse(next.accept("b")); assertTrue(next.accept("c")); assertTrue(next.accept("d"))
        assertEquals(listOf("b", "c", "d"), next.savedIds())
    }

    @Test fun invalidEventsAreRejectedBeforeNotificationDisplay() {
        for (bad in listOf(alert(""), alert("x".repeat(16001)), alert("message", "../bad"),
            alert("message").copy(timestamp = -1), alert("bad\u0000message")))
            assertThrows(IllegalArgumentException::class.java) { bad.validate() }
    }

    @Test fun floatingChannelMigrationPreservesExplicitlyBlockedCategories() {
        assertEquals(0, floatingAlertImportance(0))
        for (importance in listOf(null, 1, 2, 3, 4)) assertEquals(4, floatingAlertImportance(importance))
        assertEquals("alerts_risk_floating", alertChannel(presentAlert("Main", alert("Risk recovery update"))
            .copy(category = AlertCategory.Risk, urgent = false)))
        assertNotEquals(ALERT_SUMMARY_CHANNEL, alertChannel(presentAlert("Main", alert("Daily report"))))
        assertNotEquals(ALERT_CONNECTION_CHANNEL, ALERT_SUMMARY_CHANNEL)
    }
}
