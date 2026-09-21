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

data class ShelfEndpoint(
    val classNumber: String,
    val authorStart: String? = null,
    val authorEnd: String? = null
)

data class ParsedShelfRange(
    val section: String,
    val start: ShelfEndpoint,
    val end: ShelfEndpoint?
) {
    val prefix: String get() = "$section${start.classNumber}"
}

data class ParsedShelfTarget(
    val section: String,
    val classNumber: String,
    val authorKey: String
) {
    val prefix: String get() = "$section$classNumber"
    val key: String get() = authorKey
}

/**
 * Normalises OUTI/Finna shelf text and resolves it against physical shelf
 * intervals. The important distinction is:
 *
 *   Finna value  = a target, e.g. AIK84.2ANA or AIK82.2KYR
 *   admin value  = an interval, e.g. AIK84.2A, AIK84.2CON-D, AIK81-82.2, or AIK81-82.2A-M
 *
 * We NEVER look for the complete target as an exact database key.
 */
object ShelfRangeParser {

    /** Finnish alphabetical order used for author shelf keys. */
    private const val FINNISH_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZÅÄÖ"

    private val targetRegex = Regex(
        "^([A-ZÅÄÖ]{2,8})(\\d{1,3}(?:\\.\\d{1,3})?)([A-ZÅÄÖ]{1,16})?$",
        RegexOption.IGNORE_CASE
    )

    private val classNumberRegex = Regex("(\\d{1,3}(?:\\.\\d{1,3})?)")

    /**
     * Parses a shelf range configured by library staff, supporting:
     *   - Single-class author bucket: AIK84.2A
     *   - Single-class author range:  AIK84.2A-CAN, AIK84.2CON-D, AIK84.2E-H
     *   - Single-class full bucket:   AIK84.2
     *   - Multi-class range:          AIK81-82.2
     *   - Multi-class + author bound: AIK81-82.2A-M, AIK81-82.2M, AIK82.2N-83, AIK82.2N-83A-M
     */
    fun parseRange(raw: String): ParsedShelfRange? {
        val value = normalizeRaw(raw) ?: return null
        val sectionMatch = Regex("^([A-ZÅÄÖ]{2,8})").find(value) ?: return null
        val section = sectionMatch.groupValues[1]

        var remainder = value.substring(section.length)
        // If section is repeated after hyphen (e.g. AIK81-AIK82.2), clean it
        remainder = remainder.replace("-$section", "-")

        val classMatches = classNumberRegex.findAll(remainder).toList()
        if (classMatches.isEmpty() || classMatches.size > 2) return null

        if (classMatches.size == 1) {
            val classNumber = classMatches[0].groupValues[1]
            val afterClass = remainder.substring(classMatches[0].range.last + 1).trim()

            return if (afterClass.isBlank()) {
                ParsedShelfRange(
                    section = section,
                    start = ShelfEndpoint(classNumber = classNumber),
                    end = null
                )
            } else if (afterClass.contains('-')) {
                val parts = afterClass.split('-')
                val startAuthor = parts.getOrNull(0)?.ifBlank { null }
                val endAuthor = parts.getOrNull(1)?.ifBlank { null }
                if (startAuthor == null && endAuthor == null) return null
                ParsedShelfRange(
                    section = section,
                    start = ShelfEndpoint(classNumber = classNumber, authorStart = startAuthor),
                    end = ShelfEndpoint(classNumber = classNumber, authorEnd = endAuthor)
                )
            } else {
                ParsedShelfRange(
                    section = section,
                    start = ShelfEndpoint(classNumber = classNumber, authorStart = afterClass),
                    end = null
                )
            }
        }

        // classMatches.size == 2
        val startClass = classMatches[0].groupValues[1]
        val endClass = classMatches[1].groupValues[1]

        val between = remainder.substring(classMatches[0].range.last + 1, classMatches[1].range.first)
        val startAuthor = between.trimEnd('-').ifBlank { null }

        val afterEnd = remainder.substring(classMatches[1].range.last + 1).trim()
        val (endAuthorStart, endAuthorEnd) = if (afterEnd.contains('-')) {
            val parts = afterEnd.split('-')
            Pair(parts.getOrNull(0)?.ifBlank { null }, parts.getOrNull(1)?.ifBlank { null })
        } else {
            Pair(null, afterEnd.ifBlank { null })
        }

        return ParsedShelfRange(
            section = section,
            start = ShelfEndpoint(classNumber = startClass, authorStart = startAuthor),
            end = ShelfEndpoint(
                classNumber = endClass,
                authorStart = endAuthorStart,
                authorEnd = endAuthorEnd
            )
        )
    }

    /**
     * Converts e.g.
     *   "Jännitys Aikuiset 84.2 ANA" -> "AIK84.2ANA"
     *   "Aikuiset, 82.2 KYR"        -> "AIK82.2KYR"
     *   "Aikuiset, 81.04 KAN"       -> "AIK81.04KAN"
     *   "AIK Aikuiset 84.2 CÖN"      -> "AIK84.2CÖN"
     *
     * Genre labels are intentionally discarded.
     */
    fun normalizeFinnaShelf(raw: String): String? {
        val cleaned = raw
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .uppercase()
            .replace(Regex("^HYLLY:\\s*"), "")

        if (cleaned.isBlank()) return null

        val tokens = cleaned.split(' ')
            .map { it.trim().trimEnd(',', ';', ':') }
            .filter { it.isNotBlank() }

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
            "^([A-ZÅÄÖ]{2,8})(\\d{1,3}(?:\\.\\d{1,3})?)([A-ZÅÄÖ]{1,16})?$"
        ).find(compact) ?: return null
        return match.groupValues[1] + match.groupValues[2] + match.groupValues[3]
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

    /**
     * Compares decimal library classifications (YKL/Dewey), e.g.:
     *   81 < 81.04 < 81.2 < 82 < 82.2 < 84.11 < 84.2 < 84.21
     */
    fun compareClassification(aRaw: String, bRaw: String): Int {
        val aClean = aRaw.trim().replace(',', '.')
        val bClean = bRaw.trim().replace(',', '.')

        val aParts = aClean.split('.')
        val bParts = bClean.split('.')

        val aMain = aParts[0].toIntOrNull() ?: 0
        val bMain = bParts[0].toIntOrNull() ?: 0
        if (aMain != bMain) {
            return aMain.compareTo(bMain)
        }

        val aSub = if (aParts.size > 1) aParts[1] else ""
        val bSub = if (bParts.size > 1) bParts[1] else ""

        val minLen = minOf(aSub.length, bSub.length)
        for (i in 0 until minLen) {
            if (aSub[i] != bSub[i]) {
                return aSub[i].compareTo(bSub[i])
            }
        }
        return aSub.length.compareTo(bSub.length)
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
            section = match.groupValues[1],
            classNumber = match.groupValues[2],
            authorKey = match.groupValues.getOrNull(3).orEmpty()
        )
    }

    /**
     * Checks whether a target belongs to this physical shelf interval.
     *
     * Handles:
     *   - Single-class author bucket: AIK84.2A
     *   - Single-class author range:  AIK84.2A-CAN, AIK84.2CON-D, AIK84.2E-H
     *   - Multi-class without author: AIK81-82.2
     *   - Multi-class + author bound: AIK81-82.2A-M, AIK82.2N-83
     */
    fun matches(targetRaw: String, range: ShelfRange): Boolean {
        val parsed = range.parsed ?: return false
        val target = parseTarget(targetRaw) ?: return false

        if (target.section != parsed.section) return false

        // Check start bound
        val startClassCmp = compareClassification(target.classNumber, parsed.start.classNumber)
        if (startClassCmp < 0) return false

        if (startClassCmp == 0) {
            val startAuthor = parsed.start.authorStart
            if (startAuthor != null) {
                if (compareFinnish(target.authorKey, startAuthor) < 0) return false
            }
        }

        // Check end bound
        val end = parsed.end
        if (end == null) {
            if (startClassCmp != 0) return false
            val startAuthor = parsed.start.authorStart
            if (startAuthor == null) return true
            return target.authorKey.startsWith(startAuthor)
        }

        val endClassCmp = compareClassification(target.classNumber, end.classNumber)
        if (endClassCmp > 0) return false
        if (endClassCmp < 0) return true

        // target.classNumber == end.classNumber
        val endAuthorStart = end.authorStart
        if (endAuthorStart != null) {
            if (compareFinnish(target.authorKey, endAuthorStart) < 0) return false
        }

        val endAuthor = end.authorEnd
        if (endAuthor == null) return true

        return compareFinnish(target.authorKey, endAuthor) < 0 || target.authorKey.startsWith(endAuthor)
    }
}
