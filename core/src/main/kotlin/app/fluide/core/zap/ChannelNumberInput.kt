package app.fluide.core.zap

/**
 * Direct channel-number entry on the remote ("1", "0", "4" → channel 104).
 *
 * Commits after [commitDelayMs] without a new digit, at [maxDigits], or as soon as no longer channel
 * number can start with what was typed (so "7" commits instantly when there is no channel 70-79).
 */
class ChannelNumberInput(
    private val commitDelayMs: Long = 1_500,
    private val maxDigits: Int = 4,
) {
    private val digits = StringBuilder(maxDigits)
    private var lastDigitAt = Long.MIN_VALUE

    val pending: String get() = digits.toString()

    /**
     * Adds a digit. [hasLongerNumberStartingWith] tells whether some channel number is longer than and
     * starts with the given prefix. Returns the committed number, or null while waiting for more digits.
     */
    fun onDigit(digit: Int, nowMs: Long, hasLongerNumberStartingWith: (String) -> Boolean): Int? {
        require(digit in 0..9)
        if (digits.isEmpty() && digit == 0) return null // a leading zero is never a channel
        digits.append(('0' + digit))
        lastDigitAt = nowMs
        if (digits.length >= maxDigits || !hasLongerNumberStartingWith(digits.toString())) return commitNow()
        return null
    }

    /** Commits when the delay has elapsed since the last digit. Call it from a timer. */
    fun commitIfDue(nowMs: Long): Int? =
        if (digits.isNotEmpty() && nowMs - lastDigitAt >= commitDelayMs) commitNow() else null

    fun commitNow(): Int? {
        if (digits.isEmpty()) return null
        val number = digits.toString().toIntOrNull()
        clear()
        return number
    }

    fun clear() {
        digits.setLength(0)
        lastDigitAt = Long.MIN_VALUE
    }
}
