package com.shadowpheonix.tradingdashboard

import org.junit.Assert.*
import org.junit.Test

class AlertNotificationContentTest {
    private fun content(message: String) = notificationContent(presentAlert("Main", DashboardAlert("sample", 1760000000000, message)))

    @Test fun crossedLevelLeadsCollapsedAndExpandedNotificationWithoutRepeatingHeading() {
        val value = content("📈 Combined open P&L\nCurrent P&L: +$3,393.84\nCrossed: +$3,000 ↑\nScope: All models · All wallets")
        assertEquals("📈 Crossed +$3,000 ↑", value.headline)
        assertEquals("Current P&L: +$3,393.84", value.preview)
        assertEquals("Current P&L: +$3,393.84\nScope: All models · All wallets", value.body)
        assertFalse(value.body.contains("Crossed")); assertFalse(value.body.contains("Combined open P&L"))
    }

    @Test fun negativeAndRecrossMilestonesKeepSignedLevelDirectionAndCount() {
        val value = content("📉 Combined open P&L\nCurrent P&L: −$3,393.84\nCrossed: −$3,000 ↓ (2 levels)\nScope: All models · All wallets")
        assertEquals("📉 Crossed −$3,000 ↓ (2 levels)", value.headline)
        assertEquals("Current P&L: −$3,393.84", value.preview)
        val recovery = content("📉 Combined open P&L\nCurrent P&L: −$2,990.00\nCrossed: −$3,000 ↑\nScope: All models · All wallets")
        assertEquals("📉 Crossed −$3,000 ↑", recovery.headline)
    }

    @Test fun tradeEntryUsesOriginalHeadingOnceAndDirectionAndPriceLeadPreview() {
        val header = "🚀 [M1-Fast-B] MACRO MACD SIGNAL 🚀"
        val value = content("$header\n\nAction: SHORT\nExecution Price: $82,000\n• Position Size (notional): $10,000\n• Stop Loss: $84,000\nLeverage: 4x")
        assertEquals(header, value.headline)
        assertEquals("SHORT · Entry $82,000 · SL $84,000", value.preview)
        assertFalse(value.body.contains(header))
        assertTrue(value.body.startsWith("Action: SHORT"))
        assertTrue(value.body.contains("Position Size (notional): $10,000"))
        assertTrue(value.body.contains("Leverage: 4x"))
    }

    @Test fun tradeClosedKeepsSourceHeadingAndPnlBeforeLongReason() {
        val value = content("🛑 TRADE CLOSED\nReason: M2-Fast Stop Loss\nPrice: $80,000\n• PnL: −$40.00 (−0.8%)")
        assertEquals("🛑 TRADE CLOSED", value.headline)
        assertTrue(value.preview.startsWith("PnL: −$40.00 (−0.8%)"))
        assertTrue(value.body.contains("Reason: M2-Fast Stop Loss"))
        assertFalse(value.body.contains("TRADE CLOSED"))
    }

    @Test fun skippedAndRiskAlertsHaveNoGeneratedHeadingAndKeepReasons() {
        val skipped = content("🔍 [M1-Fast] Evaluation outcome\nStage: gated_fuel\nReason: LONG cross but fuel 42 < threshold 50\nEvaluated: synthetic time")
        assertEquals("🔍 [M1-Fast] Evaluation outcome", skipped.headline)
        assertEquals("Reason: LONG cross but fuel 42 < threshold 50", skipped.preview)
        assertTrue(skipped.body.contains("Stage: gated_fuel"))
        val risk = content("⚠️ [M3-Slow] POSITION HAS NO STOP-LOSS\nReason: Protection pending\nRetry: In progress")
        assertEquals("⚠️ [M3-Slow] POSITION HAS NO STOP-LOSS", risk.headline)
        assertEquals("Reason: Protection pending", risk.preview)
        assertFalse(risk.body.contains("Urgent risk alert"))
        assertTrue(risk.body.contains("Retry: In progress"))
    }

    @Test fun headerOnlyAndUnknownAlertsDoNotRepeatTheirTitleAsBody() {
        assertEquals(AlertNotificationContent("⚠️ POSITION IS NAKED", "", ""), content("⚠️ POSITION IS NAKED"))
        val value = content("🔔 Custom report\n\nFirst detail\n\nSecond detail")
        assertEquals("🔔 Custom report", value.headline)
        assertEquals("First detail", value.preview)
        assertEquals("First detail\n\nSecond detail", value.body)
    }

    @Test fun malformedMilestoneFieldsArePreservedRatherThanSilentlyDropped() {
        val value = content("📈 Combined open P&L\nCurrent P&L: unavailable\nCrossed: unknown\nScope: All models")
        assertEquals("📈 Combined open P&L", value.headline)
        assertTrue(value.body.contains("Current P&L: unavailable"))
        assertTrue(value.body.contains("Crossed: unknown"))
    }

    @Test fun sourceEmojiParagraphsAndLiteralMarkupRemainPlainText() {
        val value = content("🚀 *[M1-Fast] MACRO MACD SIGNAL*\n\n*Action:* LONG\n\nNote: <b>literal text</b>\nLink: https://example.invalid")
        assertEquals("🚀 [M1-Fast] MACRO MACD SIGNAL", value.headline)
        assertTrue(value.body.contains("Action: LONG\n\nNote: <b>literal text</b>"))
        assertTrue(value.body.contains("Link: https://example.invalid"))
    }

    @Test fun expandedThreadShowsLatestDetailsFirstAndEachOriginalHeadingOnce() {
        val earlier = content("📊 Daily report\nM1-Fast +$10\nM2-Fast −$5")
        val latest = content("📈 Combined open P&L\nCurrent P&L: +$3,393.84\nCrossed: +$3,000 ↑\nScope: All models · All wallets")
        val body = notificationExpandedText(listOf(earlier, latest))
        assertTrue(body.startsWith("Current P&L: +$3,393.84"))
        assertFalse(body.contains(latest.headline))
        assertEquals(1, Regex(Regex.escape(earlier.headline)).findAll(body).count())
        assertTrue(body.contains("M1-Fast +$10\nM2-Fast −$5"))
        assertEquals("", notificationExpandedText(emptyList()))
    }

    @Test fun separateEventsWithSameHeadingRemainDistinctAndLatestIsNotHiddenBehindOldText() {
        val earlier = content("⚠️ Risk update\nReason: " + "old detail ".repeat(1000))
        val latest = content("⚠️ Risk update\nReason: New protection failure")
        val body = notificationExpandedText(listOf(earlier, latest))
        assertTrue(body.startsWith("Reason: New protection failure\n\n⚠️ Risk update"))
        assertTrue(body.contains("old detail"))
        assertEquals(1, Regex("⚠️ Risk update").findAll(body).count()) // Other occurrence is Android's title.
    }

    @Test fun replayingAnOlderPendingEventKeepsTheMostRecentCardContentAndTimestamp() {
        val older = DashboardAlert("older", 1000, "📊 Daily report\nOld totals")
        val newer = DashboardAlert("newer", 2000, "📊 Daily report\nNew totals")
        val thread = notificationThread(listOf(older, newer), listOf(older.id, newer.id), older)
        val focused = thread.displayed.last()
        val messages = thread.displayed.map { notificationContent(presentAlert("Main", it)) }
        assertFalse(thread.isNew)
        assertEquals(newer.id, focused.id)
        assertEquals(2000L, focused.timestamp)
        assertEquals("New totals", messages.last().preview)
        assertTrue(notificationExpandedText(messages).startsWith("New totals"))
    }
}
