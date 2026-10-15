package app.fluide.core.xmltv

/**
 * Allocation-free parser for XMLTV timestamps such as `20261015193000 +0200`.
 *
 * Date-formatting APIs are slow and allocate on every call; an EPG holds hundreds of thousands of
 * timestamps, so they are parsed digit by digit here.
 */
object XmltvTime {
    const val INVALID = Long.MIN_VALUE

    /**
     * Returns epoch seconds (UTC), or [INVALID].
     *
     * Accepts `YYYYMMDD[hh[mm[ss]]]` followed by an optional offset `+hhmm`, `-hh:mm`, `+hh`,
     * or a zone name (`UTC`, `GMT`, `Z`, treated as UTC).
     */
    fun parseEpochSeconds(text: CharSequence, start: Int = 0, end: Int = text.length): Long {
        var i = start
        while (i < end && text[i] == ' ') i++
        val digitsStart = i
        while (i < end && text[i] in '0'..'9') i++
        val digitCount = i - digitsStart
        if (digitCount < 8) return INVALID

        val year = number(text, digitsStart, 4)
        val month = number(text, digitsStart + 4, 2)
        val day = number(text, digitsStart + 6, 2)
        val hour = if (digitCount >= 10) number(text, digitsStart + 8, 2) else 0
        val minute = if (digitCount >= 12) number(text, digitsStart + 10, 2) else 0
        val second = if (digitCount >= 14) number(text, digitsStart + 12, 2) else 0
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return INVALID

        while (i < end && text[i] == ' ') i++
        var offsetSeconds = 0
        if (i < end && (text[i] == '+' || text[i] == '-')) {
            val sign = if (text[i] == '-') -1 else 1
            i++
            var hours = 0
            var hourDigits = 0
            while (i < end && hourDigits < 2 && text[i] in '0'..'9') {
                hours = hours * 10 + (text[i] - '0')
                i++
                hourDigits++
            }
            if (hourDigits != 2) return INVALID
            if (i < end && text[i] == ':') i++
            var minutes = 0
            var minuteDigits = 0
            while (i < end && minuteDigits < 2 && text[i] in '0'..'9') {
                minutes = minutes * 10 + (text[i] - '0')
                i++
                minuteDigits++
            }
            if (minuteDigits == 1 || hours > 18 || minutes > 59) return INVALID
            offsetSeconds = sign * (hours * 3_600 + minutes * 60)
        }

        val days = daysFromCivil(year, month, day)
        return days * 86_400L + hour * 3_600L + minute * 60L + second - offsetSeconds
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val shiftedMonth = (month + 9) % 12 // March = 0
        val dayOfYear = (153 * shiftedMonth + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }

    private fun number(text: CharSequence, from: Int, length: Int): Int {
        var value = 0
        for (k in 0 until length) value = value * 10 + (text[from + k] - '0')
        return value
    }
}
