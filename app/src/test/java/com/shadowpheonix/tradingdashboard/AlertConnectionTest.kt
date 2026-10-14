package com.shadowpheonix.tradingdashboard

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class AlertConnectionTest {
    @Test fun offlineDoesNoConnectionOrRetryWorkAndRecoveryReplaysImmediately() = runBlocking {
        withTimeout(3000) {
            val network = MutableStateFlow(AlertNetworkState(false))
            val waiting = Channel<Unit>(Channel.UNLIMITED)
            val started = Channel<Unit>(Channel.UNLIMITED)
            val closed = Channel<Unit>(Channel.UNLIMITED)
            var attempts = 0
            var retries = 0
            val job = launch {
                receiveAlerts(network, { waiting.trySend(Unit) }, {}, pause = { retries++ }) {
                    attempts++; started.send(Unit)
                    try { awaitCancellation() } finally { closed.trySend(Unit) }
                }
            }
            try {
                waiting.receive()
                assertEquals(0, attempts); assertEquals(0, retries)
                network.value = AlertNetworkState(true, 1); started.receive()
                network.value = AlertNetworkState(false); closed.receive(); waiting.receive()
                assertEquals(1, attempts); assertEquals(0, retries)
                network.value = AlertNetworkState(true, 2); started.receive()
                assertEquals(2, attempts)
            } finally { job.cancelAndJoin() }
            closed.receive(); assertEquals(0, retries)
        }
    }

    @Test fun defaultNetworkChangeReconnectsEvenWhileInternetStaysAvailable() = runBlocking {
        withTimeout(3000) {
            val network = MutableStateFlow(AlertNetworkState(true, 1))
            val started = Channel<Unit>(Channel.UNLIMITED)
            var attempts = 0; var closed = 0
            val job = launch {
                receiveAlerts(network, {}, {}, pause = { error("No retries during healthy network switch") }) {
                    attempts++; started.send(Unit)
                    try { awaitCancellation() } finally { closed++ }
                }
            }
            try {
                started.receive()
                network.value = AlertNetworkState(true, 2); started.receive()
                assertEquals(2, attempts); assertEquals(1, closed)
                network.value = AlertNetworkState(true, 2); yield()
                assertEquals(2, attempts)
            } finally { job.cancelAndJoin() }
            assertEquals(2, closed)
        }
    }

    @Test fun failuresBackOffAndHealthyDeliveryResetsTheDelay() = runBlocking {
        withTimeout(3000) {
            val network = MutableStateFlow(AlertNetworkState(true, 1))
            val pauses = Channel<Long>(Channel.UNLIMITED)
            val ticks = Channel<Unit>()
            var attempts = 0
            val job = launch {
                receiveAlerts(network, {}, {}, pause = { pauses.send(it); ticks.receive() }) { healthy ->
                    attempts++
                    if (attempts == 3) healthy()
                    error("Synthetic connection failure")
                }
            }
            try {
                assertTrue(pauses.receive() in 1600L..2000L); ticks.send(Unit)
                assertTrue(pauses.receive() in 3200L..4000L); ticks.send(Unit)
                assertTrue(pauses.receive() in 1600L..2000L)
                assertEquals(3, attempts)
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun goingOfflineCancelsRetryTimerAndReturningDoesNotWaitForIt() = runBlocking {
        withTimeout(3000) {
            val network = MutableStateFlow(AlertNetworkState(true, 1))
            val paused = Channel<Unit>(Channel.UNLIMITED)
            val waiting = Channel<Unit>(Channel.UNLIMITED)
            var attempts = 0; var cancelledPauses = 0
            val job = launch {
                receiveAlerts(network, { waiting.trySend(Unit) }, {}, pause = {
                    paused.send(Unit)
                    try { awaitCancellation() } finally { cancelledPauses++ }
                }) { attempts++; error("Synthetic connection failure") }
            }
            try {
                paused.receive()
                network.value = AlertNetworkState(false); waiting.receive()
                assertEquals(1, cancelledPauses); assertEquals(1, attempts)
                network.value = AlertNetworkState(true, 2); paused.receive()
                assertEquals(2, attempts)
            } finally { job.cancelAndJoin() }
            assertEquals(2, cancelledPauses)
        }
    }

    @Test fun retryJitterHasABoundedTwoMinuteCeiling() {
        for (failures in 1..20) {
            assertTrue(alertRetryDelay(failures, 0.0) >= 1600)
            assertTrue(alertRetryDelay(failures, 1.0) <= 120000)
            assertTrue(alertRetryDelay(failures, 0.0) <= alertRetryDelay(failures, 1.0))
        }
        assertEquals(96000L, alertRetryDelay(20, 0.0))
        assertEquals(120000L, alertRetryDelay(20, 1.0))
        assertThrows(IllegalArgumentException::class.java) { alertRetryDelay(0) }
    }

    @Test fun bootAndUpgradeOnlyResumeEnabledPermittedSavedConnections() {
        for (enabled in listOf(false, true)) for (permission in listOf(false, true)) for (saved in listOf(false, true))
            assertEquals(enabled && permission && saved, shouldResumeAlerts(enabled, permission, saved))
    }
}
