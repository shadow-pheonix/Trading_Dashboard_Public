package com.shadowpheonix.tradingdashboard

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AppUnlockSessionTest {
    private class Preferences(var value: Boolean? = null, var succeeds: Boolean = true) : AppLockPreferences {
        override fun read() = value
        override fun write(enabled: Boolean): Boolean {
            if (succeeds) value = enabled
            return succeeds
        }
    }

    @Test fun fingerprintPreferenceDefaultsEnabledAndPersistsBothChoices() {
        val preferences = Preferences()
        assertTrue(AppLockStore(preferences).load())
        for (enabled in listOf(false, true, false)) {
            AppLockStore(preferences).save(enabled)
            assertEquals(enabled, AppLockStore(preferences).load())
            val reopened = AppUnlockSession()
            reopened.configure(AppLockStore(preferences).load())
            assertEquals(!enabled, reopened.state.value.unlocked)
        }
        preferences.succeeds = false
        assertThrows(IllegalStateException::class.java) { AppLockStore(preferences).save(true) }
        assertFalse(AppLockStore(preferences).load())
    }

    @Test fun disablingLockSurvivesCloseAndTaskRemovalButEnablingLocksOnNextClose() {
        val session = AppUnlockSession()
        session.opened(12)
        val pending = session.begin()!!
        session.configure(false)
        session.failed(pending, "Late cancellation")
        assertTrue(session.state.value.unlocked)
        assertNull(session.begin())
        session.close()
        session.opened(13)
        assertTrue(session.state.value.unlocked)
        session.configure(true)
        assertTrue(session.state.value.unlocked)
        session.activityDestroyed(finishing = false, changingConfiguration = true)
        assertTrue(session.state.value.unlocked)
        session.close()
        assertFalse(session.state.value.unlocked)
        session.succeeded(pending)
        assertFalse(session.state.value.unlocked)
        session.succeeded(session.begin()!!)
        assertTrue(session.state.value.unlocked)
        session.opened(14)
        assertFalse(session.state.value.unlocked)
    }

    @Test fun authenticationMustSucceedBeforeUnlock() {
        val session = AppUnlockSession()
        assertFalse(session.state.value.unlocked)
        val attempt = session.begin()!!
        assertNull(session.begin())
        session.rejected(attempt)
        assertTrue(session.state.value.authenticating)
        assertFalse(session.state.value.unlocked)
        session.succeeded(attempt)
        assertTrue(session.state.value.unlocked)
        assertNull(session.begin())
    }

    @Test fun cancelledAttemptAndLateCallbacksCannotUnlockRetry() {
        val session = AppUnlockSession()
        val cancelled = session.begin()!!
        session.failed(cancelled, "Cancelled")
        assertFalse(session.state.value.unlocked)
        val retry = session.begin()!!
        assertNotEquals(cancelled, retry)
        session.succeeded(cancelled)
        session.failed(cancelled, "Late error")
        assertTrue(session.state.value.authenticating)
        assertFalse(session.state.value.unlocked)
        session.succeeded(retry)
        assertTrue(session.state.value.unlocked)
    }

    @Test fun rotationRetainsPromptAndSuccessfulUnlock() {
        val session = AppUnlockSession()
        session.opened(12)
        val attempt = session.begin()!!
        session.activityDestroyed(finishing = false, changingConfiguration = true)
        session.opened(12)
        assertNull(session.begin())
        session.succeeded(attempt)
        session.activityDestroyed(finishing = false, changingConfiguration = true)
        assertTrue(session.state.value.unlocked)
    }

    @Test fun backgroundActivityDestructionPreservesUnlockButAbandonsLostPrompt() {
        val session = AppUnlockSession()
        val lost = session.begin()!!
        session.activityDestroyed(finishing = false, changingConfiguration = false)
        assertFalse(session.state.value.authenticating)
        session.succeeded(lost)
        assertFalse(session.state.value.unlocked)
        session.succeeded(session.begin()!!)
        session.activityDestroyed(finishing = false, changingConfiguration = false)
        assertTrue(session.state.value.unlocked)
    }

    @Test fun closingAndProcessRestartRelock() {
        val session = AppUnlockSession()
        val attempt = session.begin()!!
        session.succeeded(attempt)
        session.activityDestroyed(finishing = true, changingConfiguration = false)
        session.succeeded(attempt)
        assertFalse(session.state.value.unlocked)
        session.succeeded(session.begin()!!)
        assertFalse(AppUnlockSession().state.value.unlocked)
        session.close()
        assertFalse(session.state.value.unlocked)
    }

    @Test fun newTaskRelocksEvenWithoutDestroyCallback() {
        val session = AppUnlockSession()
        session.opened(12)
        session.succeeded(session.begin()!!)
        session.opened(12)
        assertTrue(session.state.value.unlocked)
        session.opened(13)
        assertFalse(session.state.value.unlocked)
    }

    @Test fun nativeMethodSupportsCredentialOnlyPhonesAndOlderAndroid() {
        for (api in listOf(26, 28, 29, 30, 35)) {
            assertEquals(DeviceUnlockMethod.Setup, deviceUnlockMethod(api, false, true))
            assertEquals(DeviceUnlockMethod.Setup, deviceUnlockMethod(api, false, false))
            assertEquals(if (api < 30) DeviceUnlockMethod.Biometric else DeviceUnlockMethod.Combined, deviceUnlockMethod(api, true, true))
            assertEquals(if (api < 30) DeviceUnlockMethod.Credential else DeviceUnlockMethod.Combined, deviceUnlockMethod(api, true, false))
        }
    }

    @Test fun networkIsGatedAndBackgroundingDoesNotRelock() = runBlocking {
        val session = AppUnlockSession()
        var starts = 0
        var stops = 0
        suspend fun stream() {
            starts++
            try { awaitCancellation() } finally { stops++ }
        }
        var foreground = launch { session.whileUnlocked { stream() } }
        delay(25)
        assertEquals(0, starts)
        val failed = session.begin()!!
        session.rejected(failed)
        session.failed(failed, "Cancelled")
        delay(25)
        assertEquals(0, starts)
        val accepted = session.begin()!!
        session.succeeded(accepted)
        withTimeout(2000) { while (starts != 1) delay(1) }
        session.failed(accepted, "Late error")
        session.explain("No state change")
        delay(25)
        assertEquals(1, starts)
        foreground.cancelAndJoin()
        assertEquals(1, stops)
        assertTrue(session.state.value.unlocked)
        foreground = launch { session.whileUnlocked { stream() } }
        withTimeout(2000) { while (starts != 2) delay(1) }
        session.close()
        withTimeout(2000) { while (stops != 2) delay(1) }
        assertFalse(session.state.value.unlocked)
        foreground.cancelAndJoin()
    }
}
