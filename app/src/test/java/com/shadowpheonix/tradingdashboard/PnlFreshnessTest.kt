package com.shadowpheonix.tradingdashboard

import org.junit.Assert.*
import org.junit.Test

class PnlFreshnessTest {
    private val key = "a".repeat(64)
    private fun batch(value: Double?, at: Long) = AlertBatch(1, AlertSource("main", "Main"), "ready",
        emptyList(), 0, 0, 0, false, false, value?.let { AlertPnlSample(key, it, 3, at, at) })
    private fun baseline() = replayAlerts(AlertLedger(), batch(4100.0, 100_000), 100_000).ledger
    private val milestone = DashboardAlert("pnl_${key.take(16)}_1", 103_000,
        "📈 Combined open P&L\nCurrent P&L: +$2,530.62\nCrossed: +$3,000 ↓ (2 levels)\nScope: All models · All wallets")

    @Test fun firstFrameAfterReconnectDoesNotInferACrossingDuringTheGap() {
        val delivery = replayAlerts(baseline(), batch(2530.62, 300_000), 300_000)
        assertTrue("Reconnect must establish a quiet baseline", delivery.notifications.isEmpty())
        assertEquals(2530.62, delivery.ledger.pnl!!.value, 0.0)
    }

    @Test fun oldPendingMilestoneIsNotReplayedButCriticalBotEventIs() {
        val risk = DashboardAlert("bot_risk", 103_000, "⚠️ POSITION IS NAKED\nReason: Protection failure")
        val saved = baseline().copy(history = listOf(milestone, risk), pendingNotifications = listOf(milestone, risk))
        val delivery = replayAlerts(saved, batch(4100.0, 300_000), 300_000)
        assertEquals(listOf(risk), delivery.notifications)
        assertEquals(saved.history, delivery.ledger.history)
    }

    @Test fun evenRecentPendingMilestoneIsNotAnnouncedAsFreshAfterReconnect() {
        val saved = baseline().copy(history = listOf(milestone), pendingNotifications = listOf(milestone))
        assertTrue(replayAlerts(saved, batch(4100.0, 106_000), 106_000).notifications.isEmpty())
    }

    @Test fun downwardPositiveLevelUsesDirectionalWordingInInboxAndShade() {
        val presentation = presentAlert("Main", milestone)
        assertEquals("Main · 📉 Dropped below +$3,000 (2 levels)", presentation.title)
        assertEquals("Current P&L: +$2,530.62", presentation.preview)
        assertEquals("📉 Dropped below +$3,000 (2 levels)", notificationContent(presentation).headline)
        assertFalse(presentation.details.contains("Crossed:"))
        assertEquals(listOf("Current P&L", "Scope"), alertDetails(milestone).map { it.label })
    }

    @Test fun continuousCrossingAfterLongUnchangedTotalStillAlerts() {
        // The server sends changed totals, not timestamp heartbeats. A long
        // interval between frames does not imply a disconnected socket.
        val delivery = replayAlerts(baseline(), batch(2530.62, 1_500_000), 1_500_000, pnlContinuous = true)
        val event = delivery.notifications.single()
        assertTrue(event.message.startsWith("📉"))
        assertTrue(event.message.contains("+$3,000 ↓ (2 levels)"))
    }

    @Test fun milestoneLikeMessageCannotDowngradeAnUrgentRiskWarning() {
        val warning = milestone.copy(message = milestone.message + "\nPOSITION IS NAKED")
        val presentation = presentAlert("Main", warning)
        assertTrue(presentation.urgent)
        assertEquals(AlertCategory.Risk, presentation.category)
        assertTrue(presentation.details.contains("POSITION IS NAKED"))
    }

    @Test fun reconnectBaselineAllowsOnlySubsequentLiveCrossingsAndPreservesCooldown() {
        val first = replayAlerts(baseline(), batch(2900.0, 103_000), 103_000, pnlContinuous = true)
        val reconnect = replayAlerts(first.ledger, batch(4100.0, 106_000), 106_000)
        assertTrue(reconnect.notifications.isEmpty())
        assertEquals(first.ledger.pnl!!.recentLevels, reconnect.ledger.pnl!!.recentLevels)
        val recross = replayAlerts(reconnect.ledger, batch(2900.0, 109_000), 109_000, pnlContinuous = true)
        assertTrue(recross.notifications.isEmpty())
        val fresh = replayAlerts(recross.ledger, batch(1900.0, 112_000), 112_000, pnlContinuous = true)
        assertTrue(fresh.notifications.single().message.contains("+$2,000 ↓"))
        assertEquals(2L, fresh.ledger.pnl!!.sequence)
    }

    @Test fun missingStaleAndFutureTotalsBreakBaselineWithoutErasingSequenceOrCooldowns() {
        val first = replayAlerts(baseline(), batch(2900.0, 103_000), 103_000, pnlContinuous = true).ledger
        val invalid = listOf(batch(null, 106_000) to 106_000L,
            batch(4100.0, 106_000) to 136_001L, batch(4100.0, 106_000) to 105_999L)
        invalid.forEach { (batch, now) ->
            val broken = replayAlerts(first, batch, now, pnlContinuous = true)
            assertFalse(broken.ledger.pnl!!.baselineValid)
            assertTrue(alertCheckpointChanged(first, broken.ledger))
            assertEquals(first.pnl!!.recentLevels, broken.ledger.pnl!!.recentLevels)
            val recovery = replayAlerts(broken.ledger, this.batch(1900.0, 140_000), 140_000, pnlContinuous = true)
            assertTrue(recovery.notifications.isEmpty())
            assertEquals(first.pnl!!.sequence, recovery.ledger.pnl!!.sequence)
            val fresh = replayAlerts(recovery.ledger, this.batch(900.0, 143_000), 143_000, pnlContinuous = true)
            assertEquals(2L, fresh.ledger.pnl!!.sequence)
        }
    }

    @Test fun pendingLocalRetriesExpireAfterThirtySecondsOrOnPositionChange() {
        val saved = baseline().copy(history = listOf(milestone), pendingNotifications = listOf(milestone))
        fun retry(at: Long, value: AlertBatch = batch(4100.0, at)) =
            replayAlerts(saved, value, at, pnlContinuous = true).notifications
        assertEquals(listOf(milestone), retry(133_000))
        assertTrue(retry(133_001).isEmpty())
        assertTrue(retry(102_999).isEmpty())
        assertTrue(retry(106_000, batch(null, 106_000)).isEmpty())
        val changed = batch(4100.0, 106_000).let { it.copy(openPnl = it.openPnl!!.copy(positionsKey = "b".repeat(64))) }
        assertTrue(retry(106_000, changed).isEmpty())
        val broken = saved.copy(pnl = saved.pnl!!.copy(baselineValid = false))
        assertTrue(replayAlerts(broken, batch(4100.0, 106_000), 106_000, pnlContinuous = true).notifications.isEmpty())
    }

    @Test fun legacyStateAndRepeatedFirstQuoteCannotArmAnOldBaseline() {
        val legacy = dashboardJson.decodeFromString<AlertLedger>(
            """{"sourceId":"main","pnl":{"positionsKey":"$key","value":4100.0,"sampledAt":100000,"sequence":7}}""")
        assertTrue(replayAlerts(legacy, batch(2530.62, 106_000), 106_000, pnlContinuous = true).notifications.isEmpty())
        val repeated = replayAlerts(baseline(), batch(4100.0, 100_000), 100_000)
        assertFalse(repeated.ledger.pnl!!.baselineValid)
        val next = replayAlerts(repeated.ledger, batch(2530.62, 103_000), 103_000, pnlContinuous = true)
        assertTrue(next.notifications.isEmpty())
    }
}
