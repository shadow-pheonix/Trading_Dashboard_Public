package com.shadowpheonix.tradingdashboard

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class AlertStoreTest {
    @Test fun existingAndNewConnectionsDefaultToNotificationsOn() {
        val store = AlertStore(MemoryPreferences().prefs)
        assertTrue(store.notificationsEnabled("main"))
        assertTrue(store.notificationsEnabled("new-connection"))
        assertTrue(store.canNotify("main"))
    }

    @Test fun mutingOneInstancePersistsAcrossRecreationWithoutMutingOthers() {
        val memory = MemoryPreferences()
        AlertStore(memory.prefs).notificationsEnabled("main", false)
        val reopened = AlertStore(memory.prefs)
        assertFalse(reopened.canNotify("main"))
        assertTrue(reopened.canNotify("vinay"))
        assertTrue(reopened.canNotify("madhu"))
        reopened.notificationsEnabled("main", true)
        assertTrue(AlertStore(memory.prefs).canNotify("main"))
    }

    @Test fun masterSwitchPausesEveryoneAndPreservesIndividualChoices() {
        val store = AlertStore(MemoryPreferences().prefs)
        store.notificationsEnabled("main", false)
        store.enabled(false)
        assertFalse(store.canNotify("main"))
        assertFalse(store.canNotify("vinay"))
        store.notificationsEnabled("madhu", true)
        assertFalse(store.canNotify("madhu"))
        store.enabled(true)
        assertFalse(store.canNotify("main"))
        assertTrue(store.canNotify("vinay"))
        assertTrue(store.canNotify("madhu"))
    }

    @Test fun mutedEventsAdvanceHistoryAndAreNotNotifiedOnReenable() {
        val memory = MemoryPreferences()
        val store = AlertStore(memory.prefs)
        fun batch(sequence: Long) = AlertBatch(1, AlertSource("main", "Main"), "ready",
            listOf(AlertEvent(sequence, "event-$sequence", sequence * 1000, "Bot status update")),
            sequence, 1, sequence, false, false)
        store.save("main", replayAlerts(AlertLedger(), batch(1)).ledger)
        store.notificationsEnabled("main", false)
        val delivery = replayAlerts(store.load("main"), batch(2))
        assertEquals(1, delivery.notifications.size)
        assertFalse(store.canNotify("main"))
        store.save("main", delivery.ledger)
        val reopened = AlertStore(memory.prefs)
        reopened.notificationsEnabled("main", true)
        assertEquals(2L, reopened.load("main").cursor)
        assertEquals(listOf("event-1", "event-2"), reopened.load("main").history.map { it.id })
        assertTrue(replayAlerts(reopened.load("main"), batch(2)).notifications.isEmpty())
        assertEquals(listOf("event-3"), replayAlerts(reopened.load("main"), batch(3)).notifications.map { it.id })
    }

    @Test fun forgettingRemovesOnlyThatInstancesHistoryAndPreference() {
        val store = AlertStore(MemoryPreferences().prefs)
        store.notificationsEnabled("main", false)
        store.notificationsEnabled("vinay", false)
        store.save("main", AlertLedger("main", 5))
        store.save("vinay", AlertLedger("vinay", 7))
        store.status("main", "Connected")
        store.forget("main")
        assertTrue(store.notificationsEnabled("main"))
        assertEquals(AlertLedger(), store.load("main"))
        assertEquals("Waiting for connection", store.status("main"))
        assertFalse(store.notificationsEnabled("vinay"))
        assertEquals(7L, store.load("vinay").cursor)
    }

    @Test fun failedPreferenceWriteIsReported() {
        val memory = MemoryPreferences(commitSucceeds = false)
        assertThrows(IllegalStateException::class.java) {
            AlertStore(memory.prefs).notificationsEnabled("main", false)
        }
    }

    // Only the SharedPreferences interface is needed; no Android runtime or
    // production credentials are used. Each editor stages writes until commit.
    private class MemoryPreferences(private val commitSucceeds: Boolean = true) {
        private val values = mutableMapOf<String, Any>()
        val prefs: SharedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getBoolean", "getString" -> values[args!![0]] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putBoolean", "putString" -> { pending[args!![0] as String] = args[1]; proxy }
                    "remove" -> { pending[args!![0] as String] = null; proxy }
                    "commit", "apply" -> {
                        if (commitSucceeds) pending.forEach { (key, value) ->
                            if (value == null) values.remove(key) else values[key] = value
                        }
                        if (method.name == "commit") commitSucceeds else null
                    }
                    else -> error("Unexpected editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
