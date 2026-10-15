package app.fluide.core.text

/**
 * Normalises names for search: lower case, accents removed, punctuation turned into spaces.
 * "Télé-Matin" and "tele matin" both become "tele matin". Pure Kotlin table, no java.text.
 */
object SearchNormalizer {

    // Base letters for U+00C0..U+017F (Latin-1 Supplement and Latin Extended-A), lower case.
    private const val LATIN =
        "aaaaaaaceeeeiiii" + // C0-CF (C6 Æ handled separately)
            "dnooooo ouuuuyts" + // D0-DF (D7 ×, DE Þ, DF ß handled separately)
            "aaaaaaaceeeeiiii" + // E0-EF
            "dnooooo ouuuuyty" + // F0-FF
            "aaaaaaccccccccdd" + // 100-10F
            "ddeeeeeeeeeegggg" + // 110-11F
            "gggghhhhiiiiiiii" + // 120-12F
            "iijjjjkkklllllll" + // 130-13F
            "lllnnnnnnnnnoooo" + // 140-14F
            "oooorrrrrrssssss" + // 150-15F
            "ssttttttuuuuuuuu" + // 160-16F
            "uuuuwwyyyzzzzzzs"   // 170-17F

    fun normalize(input: String): String {
        val out = StringBuilder(input.length)
        var pendingSpace = false
        for (ch in input) {
            val mapped: String? = when {
                ch in 'a'..'z' || ch in '0'..'9' -> null
                ch in 'A'..'Z' -> null
                ch == 'Æ' || ch == 'æ' -> "ae"
                ch == 'Œ' || ch == 'œ' -> "oe"
                ch == 'ß' -> "ss"
                ch == 'Þ' || ch == 'þ' -> "th"
                ch.code in 0xC0..0x17F -> LATIN[ch.code - 0xC0].toString() // × and ÷ map to a space
                ch.isLetterOrDigit() -> ch.lowercaseChar().toString()
                else -> " "
            }
            if (mapped == null) {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                out.append(ch.lowercaseChar())
            } else if (mapped == " ") {
                pendingSpace = true
            } else if (mapped.isNotEmpty()) {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                out.append(mapped)
            }
        }
        return out.toString()
    }
}
