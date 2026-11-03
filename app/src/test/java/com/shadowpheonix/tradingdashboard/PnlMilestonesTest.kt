package com.shadowpheonix.tradingdashboard

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class PnlMilestonesTest {
    private val key = "a".repeat(64)
    private fun sample(pnl: Double, at: Long = 100_000, positions: String = key, count: Int = 3) =
        AlertPnlSample(positions, pnl, count, at, if (count > 0) at else null)
    private fun batch(value: AlertPnlSample?) = AlertBatch(1, AlertSource("main", "Main"), "ready",
        emptyList(), 0, 0, 0, false, false, value)
    private fun next(saved: AlertLedger, value: AlertPnlSample?, now: Long = value?.sampledAt ?: 100_000) = replayAlerts(saved, batch(value), now)
    private fun seeded(value: Double = 950.0) = next(AlertLedger(), sample(value)).ledger

    @Test fun crossingsIncludeBothSignsExactLevelsRecoveryAndNoZeroAlert() {
        val cases = listOf(
            Triple(999.99,1000.0,1000L), Triple(1000.0,999.99,1000L),
            Triple(-999.99,-1000.0,-1000L), Triple(-1000.0,-999.99,-1000L),
            Triple(2001.0,1999.0,2000L), Triple(-2001.0,-1999.0,-2000L),
            Triple(1500.0,-2500.0,-2000L), Triple(-1500.0,2500.0,2000L))
        cases.forEach { (before,after,level) -> assertEquals("$before to $after",level.toDouble(),pnlCrossing(before,after)!!.last,0.0) }
        listOf(0.0 to 999.99, 999.0 to -999.0, 1000.0 to 1999.99, -1000.0 to -1999.99)
            .forEach { (before,after) -> assertNull(pnlCrossing(before,after)) }
    }
    @Test fun initialValueIsSilentEvenWhenAlreadyPastManyLevels() {
        val initial=next(AlertLedger(),sample(8900.0))
        assertTrue(initial.notifications.isEmpty()); assertEquals(8900.0,initial.ledger.pnl!!.value,0.0)
        assertTrue(initial.ledger.history.isEmpty())
    }
    @Test fun cooldownSuppressesRecrossingsButNewPositiveAndNegativeLevelsAlertImmediately() {
        val first=next(seeded(),sample(2045.61,103_000))
        val event=first.notifications.single()
        assertTrue(event.message.contains("Current P&L: +$2,045.61"))
        assertTrue(event.message.contains("Crossed: +$2,000 ↑ (2 levels)"))
        assertTrue(event.message.contains("All models · All wallets"))
        val down=next(first.ledger,sample(1999.99,106_000))
        assertTrue(down.notifications.isEmpty())
        val up=next(down.ledger,sample(2000.0,109_000))
        assertTrue(up.notifications.isEmpty())
        val newProfit=next(up.ledger,sample(3000.0,112_000))
        assertTrue(newProfit.notifications.single().message.contains("+$3,000 ↑"))
        val newLoss=next(newProfit.ledger,sample(-1000.0,115_000))
        assertTrue(newLoss.notifications.single().message.contains("−$1,000 ↓"))
        assertEquals(3,(first.notifications+newProfit.notifications+newLoss.notifications).map { it.id }.distinct().size)
    }
    @Test fun cooldownExpiresAtFifteenMinutesAndQuietRecrossingsDoNotExtendIt() {
        val first=next(seeded(),sample(1050.0,103_000))
        val down=next(first.ledger,sample(950.0,106_000))
        assertTrue(down.notifications.isEmpty())
        val before=next(down.ledger,sample(1050.0,103_000+PNL_MILESTONE_COOLDOWN_MS-1))
        assertTrue(before.notifications.isEmpty())
        val expired=next(before.ledger,sample(950.0,103_000+PNL_MILESTONE_COOLDOWN_MS))
        assertTrue(expired.notifications.single().message.contains("+$1,000 ↓"))
        val again=next(expired.ledger,sample(1050.0,103_000+PNL_MILESTONE_COOLDOWN_MS+1))
        assertTrue(again.notifications.isEmpty())
        assertEquals(15*60_000L,PNL_MILESTONE_COOLDOWN_MS)
    }
    @Test fun levelsKeepIndependentExpiryTimesAcrossRestart() {
        val one=next(seeded(),sample(1050.0,103_000))
        val two=next(one.ledger,sample(2050.0,106_000))
        val down=next(two.ledger,sample(950.0,109_000))
        assertTrue(down.notifications.isEmpty())
        val restored=dashboardJson.decodeFromString<AlertLedger>(dashboardJson.encodeToString(down.ledger))
        val oneExpired=next(restored,sample(1050.0,103_000+PNL_MILESTONE_COOLDOWN_MS))
        assertEquals(1,oneExpired.notifications.size)
        val twoNotExpired=next(oneExpired.ledger,sample(2050.0,103_001+PNL_MILESTONE_COOLDOWN_MS))
        assertTrue(twoNotExpired.notifications.isEmpty())
        val twoExpired=next(twoNotExpired.ledger,sample(1950.0,106_000+PNL_MILESTONE_COOLDOWN_MS))
        assertTrue(twoExpired.notifications.single().message.contains("+$2,000 ↓"))
    }
    @Test fun jumpsOnlyNotifyEligibleLevelsAndStoreHugeWindowsAsCompactRanges() {
        val one=next(seeded(),sample(1050.0,103_000))
        val down=next(one.ledger,sample(950.0,106_000))
        val jump=next(down.ledger,sample(3450.0,109_000))
        assertTrue(jump.notifications.single().message.contains("+$3,000 ↑ (2 levels)"))
        val dropped=next(jump.ledger,sample(950.0,112_000))
        assertTrue(dropped.notifications.isEmpty())
        val huge=next(dropped.ledger,sample(1e20,115_000))
        assertEquals(1,huge.notifications.size)
        assertEquals(3,huge.ledger.pnl!!.recentLevels.size)
        assertTrue(huge.ledger.pnl!!.recentLevels.any { it.last==100000000000000000L })
        val reversed=next(huge.ledger,sample(-1e20,118_000))
        assertEquals(1,reversed.notifications.size)
        assertTrue(reversed.notifications.single().message.contains("−$100,000,000,000,000,000,000 ↓"))
        assertTrue(reversed.ledger.pnl!!.recentLevels.size<=4)
    }
    @Test fun cooldownOnlyTracksAcceptedCrossingsAndNeverNotifiesOnATimerAlone() {
        val first=next(seeded(),sample(1050.0,103_000))
        val elapsed=next(first.ledger,sample(1200.0,103_000+PNL_MILESTONE_COOLDOWN_MS))
        assertTrue(elapsed.notifications.isEmpty())
        assertTrue(elapsed.ledger.pnl!!.recentLevels.isEmpty())
        assertTrue(alertCheckpointChanged(first.ledger,elapsed.ledger))
        val crossed=next(elapsed.ledger,sample(950.0,103_001+PNL_MILESTONE_COOLDOWN_MS))
        assertEquals(1,crossed.notifications.size)
    }
    @Test fun lossesAndLargeJumpsProduceOneBoundedMessagePerSample() {
        val delivery=next(seeded(-900.0),sample(-3425.12,103_000))
        val event=delivery.notifications.single()
        assertTrue(event.message.startsWith("📉"));assertTrue(event.message.contains("−$3,425.12"))
        assertTrue(event.message.contains("−$3,000 ↓ (3 levels)"))
        assertEquals(1,next(seeded(),sample(1e20,103_000)).notifications.size)
    }
    @Test fun supportedLargePnlValuesCannotOverflowTheCrossedLevelOrFlipItsSign() {
        val profit=next(seeded(),sample(1e20,103_000)).notifications.single()
        assertTrue(profit.message,profit.message.contains("Crossed: +$100,000,000,000,000,000,000 ↑"))
        val overflowSign=next(seeded(),sample(1e19,103_000)).notifications.single()
        assertTrue(overflowSign.message,overflowSign.message.contains("Crossed: +$10,000,000,000,000,000,000 ↑"))
        val loss=next(seeded(-950.0),sample(-1e20,103_000)).notifications.single()
        assertTrue(loss.message,loss.message.contains("Crossed: −$100,000,000,000,000,000,000 ↓"))
    }

    @Test fun reconnectSerializationAndUncommittedRetriesKeepStableIds() {
        val saved=seeded()
        val sample=sample(1050.0,103_000)
        val first=next(saved,sample)
        assertEquals(first.notifications.single().id,next(saved,sample.copy(sampledAt=106_000,priceUpdatedAt=106_000)).notifications.single().id)
        val restored=dashboardJson.decodeFromString<AlertLedger>(dashboardJson.encodeToString(first.ledger))
        assertTrue(next(restored,sample).notifications.isEmpty())
        assertTrue(next(restored,sample.copy(sampledAt=106_000,priceUpdatedAt=106_000)).notifications.isEmpty())
        assertEquals(first.ledger,restored)
    }
    @Test fun sameBandQuotesKeepTheDurableCrossingBaselineWithoutRewritingHistory() {
        val saved=seeded(1100.0)
        val sameBand=next(saved,sample(1800.0,103_000))
        assertFalse(alertCheckpointChanged(saved,sameBand.ledger))
        assertFalse(alertCheckpointChanged(saved,next(saved,sample(1500.0,106_000)).ledger))
        // Reconnect may use the older persisted quote: its band still gives the same crossing.
        val live=next(sameBand.ledger,sample(2050.0,106_000))
        val restarted=next(saved,sample(2050.0,106_000))
        assertEquals(live.notifications,restarted.notifications)
        assertTrue(alertCheckpointChanged(saved,restarted.ledger))
        assertTrue(alertCheckpointChanged(AlertLedger(),saved))
        assertTrue(alertCheckpointChanged(saved,saved.copy(cursor=1)))
        assertTrue(alertCheckpointChanged(saved,saved.copy(gap=true)))
        assertTrue(alertCheckpointChanged(saved,saved.copy(pendingNotifications=live.notifications)))
        assertTrue(alertCheckpointChanged(saved,next(saved,sample(1100.0,103_000,"b".repeat(64))).ledger))
    }

    @Test fun pendingDeliverySurvivesProcessDeathEvenWhenTheNextPriceNoLongerCrosses() {
        val first=next(seeded(),sample(1050.0,103_000))
        // This is the service's atomic pre-post commit. The process may stop
        // before Android sees the event or before the post-ack commit.
        val staged=first.ledger.copy(pendingNotifications=first.notifications)
        val restored=dashboardJson.decodeFromString<AlertLedger>(dashboardJson.encodeToString(staged))
        val retried=next(restored,sample(1200.0,106_000))
        assertEquals(first.notifications,retried.notifications)
        assertEquals(first.ledger.history,retried.ledger.history)
        assertTrue(retried.ledger.pendingNotifications.isEmpty())
        // Once posting and the acknowledgement commit complete, reconnect is quiet.
        assertTrue(next(retried.ledger,sample(1200.0,109_000)).notifications.isEmpty())
        // A new level during recovery delivers the pending original and the new event.
        val recrossed=next(restored,sample(2050.0,106_000))
        assertEquals(2,recrossed.notifications.size)
        assertEquals(first.notifications.single(),recrossed.notifications.first())
        assertNotEquals(recrossed.notifications[0].id,recrossed.notifications[1].id)
        val unavailable=batch(null).copy(status="unavailable")
        assertEquals(first.notifications,replayAlerts(restored,unavailable,106_000).notifications)
    }

    @Test fun positionChangesAndFlatTransitionsEstablishFreshSilentBaselines() {
        val delivered=next(seeded(),sample(1050.0,103_000)).ledger
        val replaced=next(delivered,sample(-5000.0,106_000,"b".repeat(64)))
        assertTrue(replaced.notifications.isEmpty())
        val flat=next(replaced.ledger,sample(0.0,109_000,"c".repeat(64),0))
        assertTrue(flat.notifications.isEmpty())
        val reopened=next(flat.ledger,sample(5000.0,112_000))
        assertTrue(reopened.notifications.isEmpty())
        val crossed=next(reopened.ledger,sample(6000.0,115_000))
        assertEquals(2L,crossed.ledger.pnl!!.sequence)
        assertNotEquals(delivered.history.single().id,crossed.notifications.single().id)
    }
    @Test fun missingStaleFutureAndReorderedSamplesCannotMoveTheBaseline() {
        val saved=seeded()
        assertEquals(saved,next(saved,null).ledger)
        val value=sample(1050.0,103_000)
        assertEquals(saved,next(saved,value,133_001).ledger)
        assertEquals(saved,next(saved,value,102_999).ledger)
        assertEquals(saved,next(saved,sample(1050.0,99_000)).ledger)
        // Recovery compares the last accepted sample; it does not invent an outage history.
        assertEquals(1,next(saved,sample(2150.0,150_000)).notifications.size)
    }
    @Test fun botOutboxFailureCannotBlockPnlAndLegacyBotReplayRemainsIndependent() {
        val unavailable=batch(sample(1050.0,103_000)).copy(status="unavailable")
        val delivery=replayAlerts(seeded(),unavailable,103_000)
        assertEquals(1,delivery.notifications.size);assertEquals(0L,delivery.ledger.cursor)
        val bot=batch(null).copy(events=listOf(AlertEvent(1,"bot1",104_000,"Trade opened")),cursor=1,oldest=1,newest=1)
        val combined=replayAlerts(delivery.ledger,bot,104_000)
        assertEquals(listOf("bot1"),combined.notifications.map { it.id });assertEquals(delivery.ledger.pnl,combined.ledger.pnl)
        assertTrue(replayAlerts(combined.ledger,bot,104_000).notifications.isEmpty())
    }
    @Test fun oldSavedLedgersAndServersAreCompatibleAndNewSamplesAreStrictlyValidated() {
        val legacy=dashboardJson.decodeFromString<AlertLedger>("{\"sourceId\":\"main\",\"cursor\":0}")
        assertNull(legacy.pnl);assertEquals(legacy,next(legacy,null).ledger)
        val valid=sample(1200.0)
        listOf(valid.copy(value=Double.NaN),valid.copy(value=Double.POSITIVE_INFINITY),valid.copy(positionsKey="private"),
            valid.copy(activeCount=7),valid.copy(priceUpdatedAt=null),valid.copy(priceUpdatedAt=100_001),
            valid.copy(priceUpdatedAt=84_999),valid.copy(activeCount=0),valid.copy(sampledAt=0))
            .forEach { assertThrows(IllegalArgumentException::class.java) { batch(it).validate() } }
        batch(sample(0.0,count=0)).validate()
    }
    @Test fun milestonesUseFloatingReportsAndKeepTheFullAmountInPreviewAndHistory() {
        val event=next(seeded(),sample(1050.25,103_000)).notifications.single()
        val presentation=presentAlert("Main",event)
        assertEquals("Main · 📈 Combined open P&L",presentation.title)
        assertEquals("Current P&L: +$1,050.25",presentation.preview)
        assertEquals("alerts_reports_floating",alertChannel(presentation))
        assertEquals(AlertCategory.Status,presentation.category)
        assertFalse(isRoutineEvaluation(event)); assertFalse(presentation.urgent)
    }
}
