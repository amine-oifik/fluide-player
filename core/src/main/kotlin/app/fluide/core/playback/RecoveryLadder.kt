package app.fluide.core.playback

import app.fluide.core.net.Backoff

/** Recovery steps, from the least to the most disruptive. */
enum class RecoveryAction {
    NONE,

    /** N1: re-prepare at the live edge on the same URL, keeping the last picture on screen. */
    REPREPARE,

    /** N2: lower the quality cap by one step and disable tunneling for this session. */
    DEGRADE,

    /** N3: switch to the backup URL, another CDN or the fallback engine. */
    FAILOVER,

    /** N4: show an actionable error and retry automatically after [RecoveryDecision.retryInMs]. */
    GIVE_UP,
}

data class RecoveryDecision(
    val action: RecoveryAction,
    val level: Int,
    val reason: StallSignal,
    val retryInMs: Long = 0,
)

/**
 * Bounded escalation ladder. Each incident that happens soon after the previous recovery means the
 * previous step did not work, so the next one is stronger. Clean playback resets the ladder. Budgets
 * guarantee that a broken stream ends in an explicit state instead of an endless silent loop.
 */
class RecoveryLadder(private val config: Config = Config()) {

    data class Config(
        /** An incident within this delay after the last action escalates to the next level. */
        val escalationWindowMs: Long = 20_000,
        /** Continuous healthy playback that resets the ladder to zero. */
        val healthyResetMs: Long = 30_000,
        val budgetWindowMs: Long = 600_000,
        /** Escalations (level 2 and above) allowed per budget window before giving up. */
        val maxEscalations: Int = 3,
        /** Hard cap on actions of any kind per budget window: anti-loop guard. */
        val maxActions: Int = 12,
        val giveUpRetry: Backoff = Backoff(baseMs = 5_000, maxMs = 60_000),
    )

    var level = 0
        private set

    private var lastActionAt = Long.MIN_VALUE
    private var healthySince = Long.MIN_VALUE
    private var giveUps = 0
    private val actionTimes = ArrayDeque<Long>()
    private val escalationTimes = ArrayDeque<Long>()

    fun reset() {
        level = 0
        lastActionAt = Long.MIN_VALUE
        healthySince = Long.MIN_VALUE
        giveUps = 0
        actionTimes.clear()
        escalationTimes.clear()
    }

    /** Called on every sample where playback is healthy (playing, no signal). */
    fun onHealthy(nowMs: Long) {
        if (healthySince == Long.MIN_VALUE) healthySince = nowMs
        if (nowMs - healthySince >= config.healthyResetMs) {
            level = 0
            giveUps = 0
        }
    }

    fun onSignal(signal: StallSignal, nowMs: Long): RecoveryDecision {
        healthySince = Long.MIN_VALUE
        prune(actionTimes, nowMs)
        prune(escalationTimes, nowMs)

        // An incident soon after the last action means that action did not work: go one step higher.
        // A late incident means the last action worked: start again from the gentlest step.
        val recent = lastActionAt != Long.MIN_VALUE && nowMs - lastActionAt <= config.escalationWindowMs
        var next = when (signal) {
            // The device is overloaded: re-preparing would not help, lowering the load does.
            StallSignal.FRAME_DROPPING, StallSignal.AUDIO_STARVING -> if (recent) maxOf(level + 1, 2) else 2
            else -> if (recent) level + 1 else 1
        }
        if (next >= 2) escalationTimes.addLast(nowMs)
        if (escalationTimes.size > config.maxEscalations || actionTimes.size >= config.maxActions) next = 4
        next = next.coerceIn(1, 4)

        level = next
        lastActionAt = nowMs
        actionTimes.addLast(nowMs)

        val action = when (next) {
            1 -> RecoveryAction.REPREPARE
            2 -> RecoveryAction.DEGRADE
            3 -> RecoveryAction.FAILOVER
            else -> RecoveryAction.GIVE_UP
        }
        val retryIn = if (action == RecoveryAction.GIVE_UP) config.giveUpRetry.delayFor(++giveUps) else 0
        return RecoveryDecision(action, next, signal, retryIn)
    }

    /** After a GIVE_UP retry succeeds, the ladder starts over at the bottom. */
    fun onRetryStarted() {
        level = 0
        lastActionAt = Long.MIN_VALUE
    }

    private fun prune(times: ArrayDeque<Long>, nowMs: Long) {
        while (times.isNotEmpty() && nowMs - times.first() > config.budgetWindowMs) times.removeFirst()
    }
}
