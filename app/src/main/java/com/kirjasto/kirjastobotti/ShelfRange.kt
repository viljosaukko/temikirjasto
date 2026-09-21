package com.kirjasto.kirjastobotti

/** A physical shelf interval entered by the library staff, e.g. AIK84.2CON-D. */
data class ShelfRange(
    val id: String,
    val text: String,
    val mapX: Double? = null,
    val mapY: Double? = null,
    val yaw: Double? = null
) {
    val parsed: ParsedShelfRange?
        get() = ShelfRangeParser.parseRange(text)

    val hasLocation: Boolean
        get() = mapX != null && mapY != null && yaw != null
}

data class ParsedShelfRange(
    val prefix: String,
    val start: String,
    val end: String?
)

data class ParsedShelfTarget(
    val prefix: String,
    val key: String
)

/**
 * Normalises OUTI/Finna shelf text and resolves it against physical shelf
 * intervals. The important distinction is:
 *
 *   Finna value  = a target, e.g. AIK84.2ANA
 *   admin value  = an interval, e.g. AIK84.2A or AIK84.2CON-D
 *
 * We NEVER look for the complete target as an exact database key.
 */
object ShelfRangeParser {

    /** Finnish alphabetical order used for author shelf keys. */
    private const val FINNISH_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZÅÄÖ"

    /* Prefix ends in the classification number, e.g. AIK84.2. */
    private val rangeRegex = Regex(
        "^(.+?\\d(?:\\.\\d+)?)([A-ZÅÄÖ]{1,16})(?:-([A-ZÅÄÖ]{1,16}))?$",
        RegexOption.IGNORE_CASE
    )

    private val targetRegex = Regex(
        "^(.+?\\d(?:\\.\\d+)?)([A-ZÅÄÖ]{1,16})$",
        RegexOption.IGNORE_CASE
    )

    fun parseRange(raw: String): ParsedShelfRange? {
        val value = normalizeRaw(raw) ?: return null
        val match = rangeRegex.matchEntire(value) ?: return null
        val prefix = match.groupValues[1].uppercase()
        val start = match.groupValues[2].uppercase()
        val end = match.groupValues[3].takeIf { it.isNotBlank() }?.uppercase()
        if (prefix.isBlank() || start.isBlank()) return null
        return ParsedShelfRange(prefix, start, end)
    }

    /**
     * Converts e.g.
     *   "Jännitys Aikuiset 84.2 ANA" -> "AIK84.2ANA"
     *   "AIK Aikuiset 84.2 CÖN"      -> "AIK84.2CÖN"
     *
     * Genre labels are intentionally discarded. The only data retained after
     * the classification number is the author/cutter key used for shelving.
     */
    fun normalizeFinnaShelf(raw: String): String? {
        val cleaned = raw
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .uppercase()
            .replace(Regex("^HYLLY:\\s*"), "")

        if (cleaned.isBlank()) return null

        val tokens = cleaned.split(' ').filter { it.isNotBlank() }
        val aiKIndex = tokens.indexOfFirst { it == "AIK" }
        val adultLabelIndex = tokens.indexOfFirst { it == "AIKUISET" }
        val sectionIndex = if (aiKIndex >= 0) aiKIndex else adultLabelIndex

        if (sectionIndex >= 0) {
            val classIndex = (sectionIndex + 1 until tokens.size).firstOrNull { index ->
                tokens[index].matches(Regex("\\d{1,3}(?:[.,]\\d{1,3})?"))
            }

            if (classIndex != null) {
                val classification = tokens[classIndex].replace(',', '.')
                val authorKey = tokens.drop(classIndex + 1)
                    .asSequence()
                    .map { it.filter { c -> FINNISH_ALPHABET.contains(c) } }
                    .firstOrNull { it.isNotBlank() }

                return if (authorKey.isNullOrBlank()) {
                    "AIK$classification"
                } else {
                    "AIK$classification${authorKey.uppercase()}"
                }
            }
        }

        // Fallback for cases where AIK/AIKUISET label is omitted, e.g. "Jännitys 84.2 ANA" or "84.2 ANA".
        val fallbackClassIndex = tokens.indexOfFirst { it.matches(Regex("\\d{1,3}(?:[.,]\\d{1,3})?")) }
        if (fallbackClassIndex >= 0) {
            val classification = tokens[fallbackClassIndex].replace(',', '.')
            val authorKey = tokens.drop(fallbackClassIndex + 1)
                .asSequence()
                .map { it.filter { c -> FINNISH_ALPHABET.contains(c) } }
                .firstOrNull { it.isNotBlank() }

            val prefix = when {
                tokens.any { it == "NUO" || it == "NUORET" } -> "NUO"
                tokens.any { it == "LAP" || it == "LAPSET" } -> "LAP"
                else -> "AIK"
            }

            return if (authorKey.isNullOrBlank()) {
                "$prefix$classification"
            } else {
                "$prefix$classification${authorKey.uppercase()}"
            }
        }

        // Fallback for already compact values such as AIK84.2CON.
        return normalizeCompact(cleaned)
    }

    private fun normalizeCompact(cleaned: String): String? {
        val compact = cleaned.replace(" ", "")
        val match = Regex(
            "^([A-ZÅÄÖ]{2,8}\\d{1,3}(?:\\.\\d{1,3})?)([A-ZÅÄÖ]{1,16})$"
        ).find(compact) ?: return null
        return match.groupValues[1] + match.groupValues[2]
    }

    private fun normalizeRaw(raw: String): String? {
        val value = raw
            .trim()
            .uppercase()
            .replace('–', '-')
            .replace('—', '-')
            .replace(Regex("\\s+"), "")
        return value.ifBlank { null }
    }

    /** Returns negative/zero/positive according to Finnish shelf alphabet order. */
    fun compareFinnish(aRaw: String, bRaw: String): Int {
        val a = aRaw.trim().uppercase()
        val b = bRaw.trim().uppercase()
        val length = minOf(a.length, b.length)

        for (i in 0 until length) {
            val ai = FINNISH_ALPHABET.indexOf(a[i])
            val bi = FINNISH_ALPHABET.indexOf(b[i])

            // Unknown characters are sorted after the Finnish alphabet rather
            // than silently being treated as the same character.
            val aRank = if (ai >= 0) ai else FINNISH_ALPHABET.length
            val bRank = if (bi >= 0) bi else FINNISH_ALPHABET.length
            if (aRank != bRank) return aRank.compareTo(bRank)
        }

        return a.length.compareTo(b.length)
    }

    fun parseTarget(raw: String): ParsedShelfTarget? {
        val value = raw.trim().uppercase().replace(Regex("\\s+"), "")
        val match = targetRegex.matchEntire(value) ?: return null
        return ParsedShelfTarget(
            prefix = match.groupValues[1],
            key = match.groupValues[2]
        )
    }

    /**
     * Checks whether a target belongs to this physical shelf interval.
     *
     * AIK84.2A means the whole A surname bucket.
     * AIK84.2A-CAN means A <= key <= CAN (inclusive, covers CAN / CANTH).
     * AIK84.2CON-D means CON <= key <= D (inclusive of D, covers CON, CÖN, and D authors like DOY).
     * AIK84.2E-H means E <= key <= H (inclusive of H, covers ERK, HEI, HUU).
     */
    fun matches(targetRaw: String, range: ShelfRange): Boolean {
        val parsed = range.parsed ?: return false
        val target = parseTarget(targetRaw) ?: return false

        if (target.prefix != parsed.prefix) return false
        if (compareFinnish(target.key, parsed.start) < 0) return false

        val end = parsed.end
        if (end == null) {
            return target.key.startsWith(parsed.start)
        }

        // Inclusive upper bound: either strictly before end, or starting with the end prefix
        return compareFinnish(target.key, end) < 0 || target.key.startsWith(end)
    }
}
