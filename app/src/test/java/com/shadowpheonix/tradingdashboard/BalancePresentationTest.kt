package com.shadowpheonix.tradingdashboard

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class BalancePresentationTest {
    private fun model(vararg allocations: WalletAllocation) =
        ModelBalance("M1", 2005.0, "ok", 950, allocations.toList())
    private fun balance(model: ModelBalance) = BalanceSnapshot(2, "Main", 1000, 900, 950,
        "ok", 2005.0, listOf(model, ModelBalance("M2", null, "not_configured", null),
            ModelBalance("M3", null, "not_configured", null)))

    @Test fun singleWalletOmitsLabelForBothAAndB() {
        for (wallet in listOf("A", "B")) {
            assertEquals("(40%)", walletAllocationLabel(model(WalletAllocation(wallet, 40.0))))
            assertEquals("(0%)", walletAllocationLabel(model(WalletAllocation(wallet, 0.0))))
            assertEquals("(—)", walletAllocationLabel(model(WalletAllocation(wallet, null))))
        }
    }

    @Test fun dualWalletsAreSortedRoundedAndExplicitEvenForSparseModels() {
        assertEquals("(A: 40%, B: 38%)", walletAllocationLabel(model(
            WalletAllocation("B", 38.2), WalletAllocation("A", 39.8))))
        assertEquals("(A: —, B: 40%)", walletAllocationLabel(model(
            WalletAllocation("A", null), WalletAllocation("B", 40.0))))
        assertEquals("(A: 40%)", walletAllocationLabel(model(WalletAllocation("A", 40.0)), showWallets = true))
    }

    @Test fun olderBackendAndMissingSamplesKeepDollarOnlyPresentation() {
        assertEquals("", walletAllocationLabel(null))
        assertEquals("", walletAllocationLabel(model()))
        val old = dashboardJson.decodeFromString<ModelBalance>(
            """{"id":"M1","equity":2005,"status":"ok","sampledAt":950}""")
        assertTrue(old.walletAllocations.isEmpty())
        assertEquals("", walletAllocationLabel(old))
    }

    @Test fun exactHalfPercentagesMatchTelegramRounding() {
        assertEquals("(40%)", walletAllocationLabel(model(WalletAllocation("A", 40.5))))
        assertEquals("(42%)", walletAllocationLabel(model(WalletAllocation("A", 41.5))))
    }

    @Test fun allocationsRoundTripWithoutChangingExistingTotals() {
        val value = balance(model(WalletAllocation("A", 40.0), WalletAllocation("B", 45.0)))
        validateBalances(value)
        assertEquals(value, dashboardJson.decodeFromString<BalanceSnapshot>(dashboardJson.encodeToString(value)))
        validateBalances(balance(model(WalletAllocation("A", null))))
    }

    @Test fun invalidWalletLabelsDuplicateWalletsAndNonfiniteValuesAreRejected() {
        for (allocations in listOf(listOf(WalletAllocation("C", 40.0)),
            listOf(WalletAllocation("A", 40.0), WalletAllocation("A", 40.0)),
            listOf(WalletAllocation("A", Double.NaN)), listOf(WalletAllocation("A", Double.POSITIVE_INFINITY)))) {
            assertThrows(IllegalArgumentException::class.java) { validateBalances(balance(model(*allocations.toTypedArray()))) }
        }
        val failed = balance(model(WalletAllocation("A", 40.0))).copy(status = "failed", equity = null, sampledAt = null)
        assertThrows(IllegalArgumentException::class.java) { validateBalances(failed) }
        validateBalances(failed.copy(models = failed.models.map { it.copy(walletAllocations = emptyList()) }))
    }
}
