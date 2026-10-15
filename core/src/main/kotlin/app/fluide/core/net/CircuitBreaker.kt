package app.fluide.core.net

/**
 * Per-server circuit breaker. After [failureThreshold] failures within [windowMs] the circuit opens:
 * no more requests are sent (we stop hammering a dead server and avoid account bans). After the open
 * duration a single probe is allowed (half-open); success closes the circuit, failure reopens it longer.
 */
class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val windowMs: Long = 60_000,
    private val openDurationsMs: LongArray = longArrayOf(30_000, 120_000, 300_000),
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    private class Circuit {
        val failures = ArrayDeque<Long>()
        var state = State.CLOSED
        var openedAt = 0L
        var openCount = 0
        var probeInFlight = false
    }

    private val circuits = HashMap<String, Circuit>()

    fun state(key: String, nowMs: Long): State {
        val circuit = circuits[key] ?: return State.CLOSED
        refresh(circuit, nowMs)
        return circuit.state
    }

    /** True if a request to [key] may be sent now. In HALF_OPEN, only one probe is allowed. */
    fun allow(key: String, nowMs: Long): Boolean {
        val circuit = circuits[key] ?: return true
        refresh(circuit, nowMs)
        return when (circuit.state) {
            State.CLOSED -> true
            State.OPEN -> false
            State.HALF_OPEN -> if (circuit.probeInFlight) false else {
                circuit.probeInFlight = true
                true
            }
        }
    }

    fun onSuccess(key: String) {
        val circuit = circuits[key] ?: return
        circuit.failures.clear()
        circuit.state = State.CLOSED
        circuit.openCount = 0
        circuit.probeInFlight = false
    }

    fun onFailure(key: String, nowMs: Long) {
        val circuit = circuits.getOrPut(key) { Circuit() }
        refresh(circuit, nowMs)
        if (circuit.state == State.HALF_OPEN) {
            open(circuit, nowMs)
            return
        }
        circuit.failures.addLast(nowMs)
        while (circuit.failures.isNotEmpty() && nowMs - circuit.failures.first() > windowMs) {
            circuit.failures.removeFirst()
        }
        if (circuit.failures.size >= failureThreshold) open(circuit, nowMs)
    }

    /** Milliseconds until the next probe is allowed; 0 when requests may be sent. */
    fun remainingOpenMs(key: String, nowMs: Long): Long {
        val circuit = circuits[key] ?: return 0
        refresh(circuit, nowMs)
        if (circuit.state != State.OPEN) return 0
        return (circuit.openedAt + currentOpenDuration(circuit) - nowMs).coerceAtLeast(0)
    }

    private fun open(circuit: Circuit, nowMs: Long) {
        circuit.state = State.OPEN
        circuit.openedAt = nowMs
        circuit.openCount++
        circuit.probeInFlight = false
        circuit.failures.clear()
    }

    private fun refresh(circuit: Circuit, nowMs: Long) {
        if (circuit.state == State.OPEN && nowMs - circuit.openedAt >= currentOpenDuration(circuit)) {
            circuit.state = State.HALF_OPEN
            circuit.probeInFlight = false
        }
    }

    private fun currentOpenDuration(circuit: Circuit): Long =
        openDurationsMs[(circuit.openCount - 1).coerceIn(0, openDurationsMs.size - 1)]
}
