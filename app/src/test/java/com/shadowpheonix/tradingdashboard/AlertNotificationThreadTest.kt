package com.shadowpheonix.tradingdashboard

import org.junit.Assert.*
import org.junit.Test

class AlertNotificationThreadTest {
    private fun event(index: Int, message: String = "Trade opened\nAction: LONG") =
        DashboardAlert("event-$index", 1000L + index, message)

    @Test fun onlyMessagesInTheActiveCardAreCountedRatherThanAllHistory() {
        val history = (1..1000).map(::event)
        val thread = notificationThread(history, listOf("event-998", "event-999"), history.last())
        assertEquals(listOf("event-998", "event-999", "event-1000"), thread.events.map { it.id })
        assertEquals("3 new alerts", notificationCount(thread.events.size))
        assertTrue(thread.isNew)
    }

    @Test fun clearingTheShadeStartsTheNextNotificationFresh() {
        val history = (1..10).map(::event)
        val thread = notificationThread(history, emptyList(), event(11))
        assertEquals(listOf(event(11)), thread.events)
        assertEquals("1 new alert", notificationCount(thread.events.size))
    }

    @Test fun aReplayUpdatesTheSameMessageWithoutSoundingAgain() {
        val history = listOf(event(1), event(2))
        val thread = notificationThread(history, listOf("event-1", "event-1", "event-2"), event(2))
        assertEquals(history, thread.events)
        assertFalse(thread.isNew)
        val next = notificationThread(history, thread.events.map { it.id }, event(3))
        assertTrue(next.isNew)
        assertEquals(3, next.events.size)
    }

    @Test fun missingHistoryAndSuppressedRoutineDiagnosticsDoNotInflateCounts() {
        val quiet = event(2, "🔍 [M1-Fast-B] Evaluation outcome\nStage: no_cross\nReason: no MACD cross\nEvaluated: synthetic time")
        val thread = notificationThread(listOf(event(1), quiet), listOf("missing", "event-1", "event-2"), event(3))
        assertEquals(listOf(event(1), event(3)), thread.events)
    }

    @Test fun metadataAndFullMessagesAreBoundedSeparately() {
        val history = (1..150).map(::event)
        val thread = notificationThread(history, history.dropLast(1).map { it.id }, history.last())
        assertEquals(MAX_PENDING_NOTIFICATION_EVENTS, thread.events.size)
        assertEquals(MAX_DISPLAYED_NOTIFICATION_EVENTS, thread.displayed.size)
        assertEquals(history.last(), thread.displayed.last())
        assertEquals("100+ recent alerts", notificationCount(thread.events.size))
    }

    @Test fun criticalRiskNeverSharesTheRoutineReportTopic() {
        val urgent = presentAlert("Main", event(1, "POSITION IS NAKED"))
        val report = presentAlert("Main", event(2, "Daily report\nAll models flat"))
        assertEquals("Urgent risk", notificationTopic(urgent))
        assertEquals("Bot reports", notificationTopic(report))
        assertNotEquals(alertChannel(urgent), alertChannel(report))
    }

    @Test fun plainTextEmphasisStylesTheHeaderAndLabelsWithoutParsingLinks() {
        val text = "🚀 Trade opened\n\nExecution Price: $80,000\n• Stop Loss: $79,000\nhttps://example.invalid\nNote: <b>literal</b>"
        val bold = notificationBoldRanges(text).map { text.substring(it) }
        assertEquals(listOf("🚀 Trade opened", "Execution Price:", "• Stop Loss:", "Note:"), bold)
        assertTrue(notificationBoldRanges("").isEmpty())
    }

    @Test fun aBurstKeepsSubmittedMessagesWhileAndroidStillReportsThePreviousCard() {
        val cards = PendingNotificationCards<List<String>> { it }
        cards.posted("main:trades", listOf("a"))
        assertEquals(listOf("a"), cards.snapshot(emptyMap())["main:trades"])
        cards.posted("main:trades", listOf("a", "b"))
        assertEquals(listOf("a", "b"), cards.snapshot(mapOf("main:trades" to listOf("a")))["main:trades"])
        cards.posted("main:trades", listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), cards.snapshot(emptyMap())["main:trades"])
    }

    @Test fun acknowledgedCardsFollowAndroidDismissalsWithoutResurrectingHistory() {
        val cards = PendingNotificationCards<List<String>> { it }
        cards.posted("main:trades", listOf("a", "b"))
        assertEquals(listOf("a", "b"), cards.snapshot(mapOf("main:trades" to listOf("a", "b")))["main:trades"])
        assertTrue(cards.snapshot(emptyMap()).isEmpty())
    }

    @Test fun dismissalAlsoClearsCardsWaitingForAndroidAcknowledgement() {
        val cards = PendingNotificationCards<List<String>> { it }
        cards.posted("main:trades", listOf("a"))
        cards.posted("main:urgent", listOf("critical"))
        cards.posted("vinay:trades", listOf("b"))
        cards.remove("main:trades")
        val remaining = cards.snapshot(emptyMap())
        assertFalse(remaining.containsKey("main:trades"))
        assertEquals(listOf("critical"), remaining["main:urgent"])
        assertEquals(listOf("b"), remaining["vinay:trades"])
    }
}
