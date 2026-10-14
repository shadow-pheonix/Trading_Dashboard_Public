package com.shadowpheonix.tradingdashboard

import org.junit.Assert.*
import org.junit.Test

class AlertInboxTest {
    private val main = Connection("main", "Main", DASHBOARD_URL, "x".repeat(43))
    private val other = main.copy(id = "vinay", label = "Vinay")
    private fun alert(id: String = "event", time: Long = 100, message: String = "Report\nBalance: 123.45") = DashboardAlert(id, time, message)
    @Test fun onlySavedInstancesAndSelectedScopeAreShownWithDistinctSourceIds() {
        val history = mapOf("main" to listOf(alert()), "vinay" to listOf(alert(time = 200)), "forgotten" to listOf(alert(time = 300)))
        val rows = inboxAlerts(listOf(main, other), history)
        assertEquals(listOf("vinay:event", "main:event"), rows.map { it.key })
        assertEquals(listOf("main:event"), inboxAlerts(listOf(main, other), history, "main").map { it.key })
        assertTrue(inboxAlerts(listOf(main), history, "forgotten").isEmpty())
    }
    @Test fun routineDiagnosticsRemainHiddenButRealRejectionIsPreserved() {
        val routine = alert("quiet", message = "🔍 [M3-Slow] Evaluation outcome\nStage: no_cross\nReason: no MACD cross (hist -5)\nEvaluated: 2026-10-09 UTC")
        val rejection = alert("reject", message = "Order not sent\nReason: insufficient equity")
        assertEquals(listOf("reject"), inboxAlerts(listOf(main), mapOf("main" to listOf(routine, rejection))).map { it.event.id })
    }
    @Test fun testMetadataIsCollapsedAndFullOriginalIsPreserved() {
        val event = alert(message = "TEST NOTIFICATION — native delivery check\nSent at: 2026-10-09 UTC\nTest ID: notification_test_abc\nThis is a test only.")
        assertEquals(listOf(AlertDetail(null, "This is a test only.")), alertDetails(event))
        assertTrue(event.message.contains("Test ID: notification_test_abc"))
    }
    @Test fun financialFieldsAndUrgentWarningAreNotLost() {
        val event = alert(message = "POSITION IS NAKED\n• Execution Price: 82,123.45\nReason: exchange: stop rejected\nhttps://example.com/anything")
        assertEquals(listOf(AlertDetail(null, "POSITION IS NAKED"), AlertDetail("Execution Price", "82,123.45"),
            AlertDetail("Reason", "exchange: stop rejected"), AlertDetail(null, "https://example.com/anything")), alertDetails(event))
    }
    @Test fun inboxBackAndNotificationTargetsDoNotChangePortfolioTab() {
        val nav = AlertInboxNavigation()
        assertNull(nav.route.value)
        nav.open(AlertTarget("main", "event"))
        assertEquals("event", nav.route.value?.eventId)
        nav.back(); assertNull(nav.route.value)
        nav.open(); assertEquals(AlertTarget(), nav.route.value)
    }
}
