package app.fluide.core.text

/**
 * Recognises adult categories from their name, for the parental control: providers name them
 * "XXX", "Adult", "Adultes +18", "FR| ADULTS 18+", "For Adults"... Matching works on whole words of the
 * normalised name so that "Sussex News" or "Essex" are not caught.
 */
object AdultFilter {

    private val words = setOf("xxx", "adult", "adults", "adulte", "adultes", "porn", "porno", "erotic", "erotique", "sexy", "hot18", "x rated")

    fun isAdult(category: String?): Boolean {
        if (category.isNullOrBlank()) return false
        val raw = category.lowercase()
        if ("18+" in raw || "+18" in raw || "(18)" in raw) return true
        val normalized = SearchNormalizer.normalize(category)
        if (normalized.split(' ').any { it in words }) return true
        return "x rated" in normalized || "for adults" in normalized
    }
}
