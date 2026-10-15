package app.fluide.core.playback

import app.fluide.core.net.Backoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryLadderTest {

    private val ladder = RecoveryLadder(RecoveryLadder.Config(giveUpRetry = Backoff(5_000, 60_000)))

    @Test
    fun escalatesWhenIncidentsFollowEachOther() {
        val actions = (0 until 4).map { ladder.onSignal(StallSignal.BUFFERING_TOO_LONG, it * 5_000L).action }
        assertEquals(
            listOf(RecoveryAction.REPREPARE, RecoveryAction.DEGRADE, RecoveryAction.FAILOVER, RecoveryAction.GIVE_UP),
            actions,
        )
    }

    @Test
    fun lateIncidentStartsAgainFromTheGentlestStep() {
        assertEquals(RecoveryAction.REPREPARE, ladder.onSignal(StallSignal.POSITION_FROZEN, 0).action)
        assertEquals(RecoveryAction.DEGRADE, ladder.onSignal(StallSignal.POSITION_FROZEN, 10_000).action)
        // 60 s later: the degrade worked for a while, so a new incident restarts at N1.
        assertEquals(RecoveryAction.REPREPARE, ladder.onSignal(StallSignal.POSITION_FROZEN, 70_000).action)
    }

    @Test
    fun overloadSignalsGoStraightToDegrade() {
        assertEquals(RecoveryAction.DEGRADE, ladder.onSignal(StallSignal.FRAME_DROPPING, 0).action)
        assertEquals(RecoveryAction.FAILOVER, ladder.onSignal(StallSignal.AUDIO_STARVING, 5_000).action)
    }

    @Test
    fun healthyPlaybackResetsTheLadder() {
        ladder.onSignal(StallSignal.NETWORK_SILENT, 0)
        ladder.onSignal(StallSignal.NETWORK_SILENT, 5_000)
        assertEquals(2, ladder.level)
        for (t in 6_000L..40_000L step 500) ladder.onHealthy(t)
        assertEquals(0, ladder.level)
    }

    @Test
    fun escalationBudgetEndsInExplicitGiveUp() {
        // Escalations spread out so each one is "late", but many of them within ten minutes.
        var t = 0L
        val actions = ArrayList<RecoveryAction>()
        repeat(5) {
            actions += ladder.onSignal(StallSignal.FRAME_DROPPING, t).action
            t += 60_000
        }
        assertEquals(RecoveryAction.DEGRADE, actions[0])
        assertEquals(RecoveryAction.GIVE_UP, actions[3])
    }

    @Test
    fun giveUpRetryDelayGrows() {
        val delays = (0 until 6).map { ladder.onSignal(StallSignal.BUFFERING_TOO_LONG, it * 1_000L) }
            .filter { it.action == RecoveryAction.GIVE_UP }
            .map { it.retryInMs }
        assertTrue(delays.size >= 2)
        assertTrue(delays.zipWithNext().all { (a, b) -> b >= a })
    }
}

class AntiStallSpeedPolicyTest {

    private val policy = AntiStallSpeedPolicy(comfortMs = 6_000, criticalMs = 1_500, minSpeed = 0.95f)

    @Test
    fun normalSpeedWhenBufferIsComfortable() {
        assertEquals(1f, policy.maxSpeedFor(6_000), 0f)
        assertEquals(1f, policy.maxSpeedFor(30_000), 0f)
    }

    @Test
    fun minimumSpeedWhenBufferIsCritical() {
        assertEquals(0.95f, policy.maxSpeedFor(1_500), 1e-6f)
        assertEquals(0.95f, policy.maxSpeedFor(0), 1e-6f)
    }

    @Test
    fun speedGrowsWithTheBufferAndStaysQuantized() {
        val speeds = (1_500L..6_000L step 250).map { policy.maxSpeedFor(it) }
        assertTrue(speeds.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(speeds.all { s -> kotlin.math.abs(s * 100 - Math.round(s * 100)) < 1e-3 })
    }

    @Test
    fun hysteresisAvoidsWobble() {
        val current = policy.maxSpeedFor(3_000)
        assertEquals(current, policy.nextSpeed(current, 3_100), 0f)
        assertEquals(1f, policy.nextSpeed(current, 7_000), 0f)
    }
}

class SourceHealthTrackerTest {

    private val minute = 60_000L

    @Test
    fun newSourceIsStable() {
        assertEquals(StabilityTier.STABLE, SourceHealthTracker().tierFor("host", 0))
    }

    @Test
    fun repeatedRebuffersDowngradeImmediately() {
        val tracker = SourceHealthTracker()
        tracker.recordRebuffer("host", 2_000, 0)
        tracker.recordRebuffer("host", 2_000, minute)
        assertEquals(StabilityTier.UNSTABLE, tracker.tierFor("host", minute))
        tracker.recordFailure("host", 2 * minute)
        tracker.recordFailure("host", 3 * minute)
        assertEquals(StabilityTier.HOSTILE, tracker.tierFor("host", 3 * minute))
    }

    @Test
    fun upgradeNeedsLongCleanPeriodOneTierAtATime() {
        val tracker = SourceHealthTracker()
        repeat(4) { tracker.recordFailure("host", it * minute) }
        assertEquals(StabilityTier.HOSTILE, tracker.tierFor("host", 4 * minute))
        assertEquals(StabilityTier.HOSTILE, tracker.tierFor("host", 10 * minute))
        assertEquals(StabilityTier.UNSTABLE, tracker.tierFor("host", 200 * minute))
        assertEquals(StabilityTier.STABLE, tracker.tierFor("host", 400 * minute))
    }

    @Test
    fun sourcesAreIndependentAndSnapshotRoundTrips() {
        val tracker = SourceHealthTracker()
        repeat(3) { tracker.recordFailure("bad", it * minute) }
        assertEquals(StabilityTier.STABLE, tracker.tierFor("good", 3 * minute))
        val restored = SourceHealthTracker().apply { restore(tracker.snapshot()) }
        assertEquals(tracker.tierFor("bad", 3 * minute), restored.tierFor("bad", 3 * minute))
    }

    @Test
    fun profilesKeepLoadControlInvariants() {
        for (tier in StabilityTier.entries) {
            val p = StabilityProfile.forTier(tier)
            assertTrue(p.minBufferMs >= p.startBufferMs && p.minBufferMs >= p.resumeBufferMs && p.maxBufferMs >= p.minBufferMs)
        }
        assertTrue(StabilityProfile.HOSTILE.liveExtraOffsetMs > StabilityProfile.UNSTABLE.liveExtraOffsetMs)
    }
}
