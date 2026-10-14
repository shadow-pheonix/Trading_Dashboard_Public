package com.shadowpheonix.tradingdashboard

import org.junit.Assert.*
import org.junit.Test

class AlertReplayTest {
    private fun evaluation(id: String, stage: String, reason: String, suffix: String = "") = DashboardAlert(id, 1000,
        "🔍 *[M1-Fast$suffix] Evaluation outcome*\nStage: $stage\nReason: $reason\nEvaluated: 2026-10-09 14:00:00 UTC")
    @Test fun routineNoCrossIsQuietForBothWalletsWithoutLosingLaterAlerts() {
        val quietA = evaluation("quiet_a", "no_cross", "no MACD cross (hist -855.46, toward LONG cross)")
        val quietB = evaluation("quiet_b", "no_cross", "no MACD cross (hist -855.46, toward LONG cross)", "-B")
        val blocked = evaluation("blocked", "gate", "Heatmap gate blocked a real SHORT signal")
        val failure = DashboardAlert("failure", 1001, "CRITICAL EXECUTION FAILURE: no resting stop")
        assertTrue(isRoutineEvaluation(quietA)); assertTrue(isRoutineEvaluation(quietB))
        assertFalse(isRoutineEvaluation(blocked)); assertFalse(isRoutineEvaluation(failure))
        val events = listOf(quietA, quietB, blocked, failure).mapIndexed { i, alert ->
            AlertEvent(i + 2L, alert.id, alert.timestamp, alert.message) }
        val batch = AlertBatch(1, AlertSource("main", "Main"), "ready", events, 5, 1, 5, false, false)
        val saved = AlertLedger("main", 1, listOf(quietA))
        val delivered = replayAlerts(saved, batch)
        assertEquals(5L, delivered.ledger.cursor)
        assertEquals(listOf("blocked", "failure"), delivered.notifications.map { it.id })
        assertEquals(delivered.notifications, delivered.ledger.history)
        assertTrue(replayAlerts(delivered.ledger, batch).notifications.isEmpty())
        val onlyQuiet = batch.copy(events = events.take(2), cursor = 3, newest = 3)
        val quiet = replayAlerts(saved, onlyQuiet)
        assertEquals(3L, quiet.ledger.cursor); assertTrue(quiet.notifications.isEmpty()); assertTrue(quiet.ledger.history.isEmpty())
    }
    private fun batch(first: Long = 1, last: Long = 2, gap: Boolean = false) = AlertBatch(1,
        AlertSource("main", "Main"), "ready", (first..last).map { AlertEvent(it, "event_$it", 1000 + it, "Actual bot event") },
        last, 1, last, gap, false)
    @Test fun initialHistoryIsSilentAndReconnectReplaysOnlyNewIds() {
        val initial = replayAlerts(AlertLedger(), batch())
        assertTrue(initial.notifications.isEmpty()); assertEquals(2, initial.ledger.history.size)
        val next = replayAlerts(initial.ledger, batch(1,3))
        assertEquals(listOf("event_3"), next.notifications.map { it.id })
        assertTrue(replayAlerts(next.ledger, batch(1,3)).notifications.isEmpty())
    }
    @Test fun unavailableFeedNeverAdvancesCursor() {
        val saved = replayAlerts(AlertLedger(), batch()).ledger
        val failure = AlertBatch(1, AlertSource("main","Main"),"unavailable", emptyList(),2,0,0,false,false)
        assertEquals(saved, replayAlerts(saved, failure).ledger)
    }
    @Test fun gapReplaysRetainedEventsAndIsVisible() {
        val saved = replayAlerts(AlertLedger(), batch()).ledger
        val next = replayAlerts(saved,batch(5,6,true))
        assertTrue(next.ledger.gap); assertEquals(2,next.notifications.size)
    }
    @Test fun resetDoesNotNotifySameSourceIdAgain() {
        val saved = replayAlerts(AlertLedger(),batch()).ledger.copy(cursor = 999)
        val next = replayAlerts(saved,batch(gap = true))
        assertEquals(2L,next.ledger.cursor); assertTrue(next.notifications.isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectChangedInstance() {
        replayAlerts(AlertLedger(sourceId="other"),batch())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectOutOfOrderEvents() {
        batch().copy(events=batch().events.reversed()).validate()
    }
    @Test fun authenticatedStreamDoesNotHavePortfolioFiltersOrKeysInUrl() {
        val connection = Connection("test", "Main", DASHBOARD_URL, "k".repeat(40))
        val request = alertRequest(connection, 42)
        assertEquals("/v2/alerts/live", request.url.encodedPath)
        assertEquals("after=42",request.url.encodedQuery)
        assertEquals("Bearer ${connection.accessKey}",request.header("Authorization"))
        assertFalse(request.url.toString().contains(connection.accessKey))
    }
}
