package com.shadowpheonix.tradingdashboard

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class ChangelogStoreTest {
    @Test fun freshInstallIsBaselinedWithoutPopupButNextUpgradeShowsIt() {
        val memory = MemoryPreferences()
        val store = ChangelogStore(memory.prefs)
        assertFalse(store.shouldShow(58, 100, 100))
        assertFalse(ChangelogStore(memory.prefs).shouldShow(58, 100, 100))
        assertTrue(ChangelogStore(memory.prefs).shouldShow(59, 100, 200))
    }

    @Test fun upgradeFromVersionWithoutTrackingShowsUntilAcknowledged() {
        val memory = MemoryPreferences()
        assertTrue(ChangelogStore(memory.prefs).shouldShow(58, 100, 200))
        // Opening or rotating without dismissing does not consume the notes.
        assertTrue(ChangelogStore(memory.prefs).shouldShow(58, 100, 200))
        assertTrue(ChangelogStore(memory.prefs).acknowledge(58))
        assertFalse(ChangelogStore(memory.prefs).shouldShow(58, 100, 200))
    }

    @Test fun acknowledgementSurvivesReopenAndNewerVersionShowsAgain() {
        val memory = MemoryPreferences()
        ChangelogStore(memory.prefs).acknowledge(58)
        val reopened = ChangelogStore(memory.prefs)
        assertFalse(reopened.shouldShow(58, 100, 200))
        assertTrue(reopened.shouldShow(60, 100, 300))
        assertTrue(reopened.acknowledge(60))
        assertFalse(ChangelogStore(memory.prefs).shouldShow(60, 100, 300))
    }

    @Test fun downgradeCannotLowerAcknowledgedVersion() {
        val store = ChangelogStore(MemoryPreferences().prefs)
        store.acknowledge(60)
        assertFalse(store.shouldShow(58, 100, 300))
        assertTrue(store.acknowledge(58))
        assertFalse(store.shouldShow(60, 100, 400))
        assertTrue(store.shouldShow(61, 100, 500))
    }

    @Test fun invalidPackageMetadataDoesNotCreateSpuriousUpdatePopup() {
        val store = ChangelogStore(MemoryPreferences().prefs)
        assertFalse(store.shouldShow(0, 100, 200))
        assertFalse(store.shouldShow(58, 0, 200))
        assertFalse(store.acknowledge(-1))
        assertTrue(store.shouldShow(59, 100, 300))
    }

    @Test fun failedWriteKeepsUpdatePendingWithoutThrowing() {
        val store = ChangelogStore(MemoryPreferences(commitSucceeds = false).prefs)
        assertTrue(store.shouldShow(58, 100, 200))
        assertFalse(store.acknowledge(58))
        assertTrue(store.shouldShow(58, 100, 200))
        assertFalse(store.shouldShow(58, 100, 100))
    }

    @Test fun unreadablePreferencesDoNotBlockDashboardStartupOrDismissal() {
        val store = ChangelogStore(MemoryPreferences(readFails = true).prefs)
        assertFalse(store.shouldShow(58, 100, 200))
        assertFalse(store.acknowledge(58))
    }

    private class MemoryPreferences(
        private val commitSucceeds: Boolean = true,
        private val readFails: Boolean = false,
    ) {
        private val values = mutableMapOf<String, Int>()
        val prefs: SharedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getInt" -> {
                    if (readFails) throw ClassCastException("Invalid stored value")
                    values[args!![0]] ?: args[1]
                }
                "edit" -> editor()
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Int>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putInt" -> { pending[args!![0] as String] = args[1] as Int; proxy }
                    "commit" -> {
                        if (commitSucceeds) values.putAll(pending)
                        commitSucceeds
                    }
                    else -> error("Unexpected editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
