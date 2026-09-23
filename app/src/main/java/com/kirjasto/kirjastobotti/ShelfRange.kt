package com.kirjasto.kirjastobotti

/** A physical shelf interval entered by the library staff, e.g. AIK84.2CON-D. */
data class ShelfRange(
    val id: String,
    val text: String,
    val mapX: Double? = null,
    val mapY: Double? = null,
    val yaw: Double? = null,
    /** Optional genre / esiluokka such as "Jännitys". Null means none. */
    val preclass: String? = null
) {
    val parsed: ParsedShelfRange?
        get() = ShelfRangeParser.parseRange(text)

    val hasLocation: Boolean
        get() = mapX != null && mapY != null && yaw != null

    val normalizedPreclass: String?
        get() = preclass?.trim()?.takeIf { it.isNotEmpty() }
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
 *   admin value  = an interval, e.g. AIK84.2A, AIK84.2CON-D, AIK81-82.2,
 *                  AIKMYC14-17, or AIKMYC14-15JUS
 *
 * We NEVER look for the complete target as an exact database key.
 */
object ShelfRangeParser {

    /** Finnish alphabetical order used for author shelf keys. */
    private const val FINNISH_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZÅÄÖ"

    /**
     * Age/department codes used at the start of a compact shelf mark.
     * Longer codes first so AIK wins over a hypothetical shorter prefix.
     */
    private val COMPACT_SECTIONS = listOf("AIK", "NUO", "LAP")

    private val SECTION_LABELS = setOf(
        "AIK", "AIKUISET", "NUO", "NUORET", "LAP", "LAPSET"
    )

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
     *   - Author letters before class (start bound): AIKMYC14-17, AIKMYC14-15JUS
     */
    fun parseRange(raw: String): ParsedShelfRange? {
        val value = normalizeRaw(raw) ?: return null
        val firstClass = classNumberRegex.find(value) ?: return null
        val head = value.substring(0, firstClass.range.first)
        if (head.isBlank() || !head.all { it in 'A'..'Z' || it == 'Å' || it == 'Ä' || it == 'Ö' }) {
            return null
        }

        val section = parseSection(head) ?: return null
        var remainder = value.substring(section.length)
        remainder = remainder.replace("-$section", "-")

        val classMatches = classNumberRegex.findAll(remainder).toList()
        if (classMatches.isEmpty() || classMatches.size > 2) return null

        val prefixAuthor = remainder.substring(0, classMatches[0].range.first)
            .ifBlank { null }

        if (classMatches.size == 1) {
            val classNumber = classMatches[0].groupValues[1]
            val afterClass = remainder.substring(classMatches[0].range.last + 1).trim()

            if (prefixAuthor != null) {
                val endAuthor = when {
                    afterClass.isBlank() -> null
                    afterClass.contains('-') -> afterClass.trim('-').ifBlank { null }
                    else -> afterClass
                }
                return ParsedShelfRange(
                    section = section,
                    start = ShelfEndpoint(classNumber = classNumber, authorStart = prefixAuthor),
                    end = ShelfEndpoint(classNumber = classNumber, authorEnd = endAuthor)
                )
            }

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

        val startClass = classMatches[0].groupValues[1]
        val endClass = classMatches[1].groupValues[1]

        val between = remainder.substring(classMatches[0].range.last + 1, classMatches[1].range.first)
        val betweenAuthor = between.trimEnd('-').ifBlank { null }
        val startAuthor = prefixAuthor ?: betweenAuthor

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
     * Section is the age/department code. Author letters immediately before the
     * first class number are a start bound (AIKMYC14), not part of the section.
     */
    internal fun parseSection(head: String): String? {
        val known = COMPACT_SECTIONS.filter { head.startsWith(it) }.maxByOrNull { it.length }
        if (known != null) return known
        return when {
            head.length in 4..8 -> head.take(3)
            head.length in 2..3 -> head
            else -> null
        }
    }

    /**
     * Converts e.g.
     *   "Jännitys Aikuiset 84.2 ANA" -> "AIK84.2ANA"
     *   "Aikuiset, 82.2 KYR"        -> "AIK82.2KYR"
     *   "Aikuiset, 81.04 KAN"       -> "AIK81.04KAN"
     *   "AIK Aikuiset 84.2 CÖN"      -> "AIK84.2CÖN"
     *
     * Genre / esiluokka words are omitted from the compact key; use
     * [extractPreclass] to read them separately.
     */
    fun normalizeFinnaShelf(raw: String): String? {
        val tokens = tokenizeFinna(raw) ?: return null

        val aiKIndex = tokens.indexOfFirst { it == "AIK" }
        val adultLabelIndex = tokens.indexOfFirst { it == "AIKUISET" }
        val sectionIndex = if (aiKIndex >= 0) aiKIndex else adultLabelIndex

        if (sectionIndex >= 0) {
            val classIndex = (sectionIndex + 1 until tokens.size).firstOrNull { index ->
                isClassToken(tokens[index])
            }

            if (classIndex != null) {
                val classification = tokens[classIndex].replace(',', '.')
                val authorKey = authorKeyAfter(tokens, classIndex)
                return compactTarget("AIK", classification, authorKey)
            }
        }

        val fallbackClassIndex = tokens.indexOfFirst { isClassToken(it) }
        if (fallbackClassIndex >= 0) {
            val classification = tokens[fallbackClassIndex].replace(',', '.')
            val authorKey = authorKeyAfter(tokens, fallbackClassIndex)
            val prefix = when {
                tokens.any { it == "NUO" || it == "NUORET" } -> "NUO"
                tokens.any { it == "LAP" || it == "LAPSET" } -> "LAP"
                else -> "AIK"
            }
            return compactTarget(prefix, classification, authorKey)
        }

        return normalizeCompact(tokens.joinToString(""))
    }

    /**
     * Leading genre / esiluokka from a Finna call number, e.g.
     * "Jännitys Aikuiset 84.2 MYC" -> "JÄNNITYS".
     * Returns null when there is none.
     */
    fun extractPreclass(raw: String): String? {
        val tokens = tokenizeFinna(raw) ?: return null
        val collected = mutableListOf<String>()
        for (token in tokens) {
            if (token == "HYLLY") continue
            if (token in SECTION_LABELS) break
            if (isClassToken(token)) break
            collected.add(token)
        }
        return collected.joinToString(" ").ifBlank { null }
    }

    fun preclassEquals(a: String?, b: String?): Boolean {
        val left = a?.trim().orEmpty()
        val right = b?.trim().orEmpty()
        if (left.isEmpty() || right.isEmpty()) return false
        return left.equals(right, ignoreCase = true)
    }

    /**
     * Among configured intervals that contain [targetRaw], prefer a shelf whose
     * esiluokka matches the book, then a shelf with no esiluokka. When several
     * still match, the tightest author/class bound wins.
     */
    fun selectShelf(
        targetRaw: String,
        bookPreclass: String?,
        ranges: List<ShelfRange>
    ): ShelfRange? {
        val matching = ranges.filter { range ->
            range.hasLocation && matches(targetRaw, range)
        }
        if (matching.isEmpty()) return null

        val preclassHits = if (!bookPreclass.isNullOrBlank()) {
            matching.filter { preclassEquals(it.normalizedPreclass, bookPreclass) }
        } else {
            emptyList()
        }

        val pool = if (preclassHits.isNotEmpty()) {
            preclassHits
        } else {
            matching.filter { it.normalizedPreclass == null }
        }

        return pool.minWithOrNull(specificShelfComparator)
    }

    private val specificShelfComparator = Comparator<ShelfRange> { a, b ->
        specificityRank(a.parsed).compareTo(specificityRank(b.parsed))
    }

    /**
     * Lower rank = more specific. Author bounds beat open class buckets;
     * a single class beats a multi-class span.
     */
    private fun specificityRank(parsed: ParsedShelfRange?): Int {
        if (parsed == null) return Int.MAX_VALUE
        val startAuthor = if (parsed.start.authorStart != null) 0 else 4
        val endAuthor = if (parsed.end?.authorStart != null || parsed.end?.authorEnd != null) 0 else 2
        val multiClass = if (parsed.end != null && parsed.end.classNumber != parsed.start.classNumber) 1 else 0
        return startAuthor + endAuthor + multiClass
    }

    private fun tokenizeFinna(raw: String): List<String>? {
        val cleaned = raw
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .uppercase()
            .replace(Regex("^HYLLY:\\s*"), "")

        if (cleaned.isBlank()) return null

        return cleaned.split(' ')
            .map { it.trim().trimEnd(',', ';', ':') }
            .filter { it.isNotBlank() }
    }

    private fun isClassToken(token: String): Boolean {
        return token.matches(Regex("\\d{1,3}(?:[.,]\\d{1,3})?"))
    }

    private fun authorKeyAfter(tokens: List<String>, classIndex: Int): String? {
        return tokens.drop(classIndex + 1)
            .asSequence()
            .map { it.filter { c -> FINNISH_ALPHABET.contains(c) } }
            .firstOrNull { it.isNotBlank() }
    }

    private fun compactTarget(section: String, classification: String, authorKey: String?): String {
        return if (authorKey.isNullOrBlank()) {
            "$section$classification"
        } else {
            "$section$classification${authorKey.uppercase()}"
        }
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
     * Compares YKL classifications as hierarchical digit strings, not as
     * integers. The first digit is the main class, then each following digit
     * (and digits after the decimal point) is a finer subdivision:
     *   29 < 3
     *   10 < 2
     *   81 < 81.04 < 81.2 < 82 < 82.2 < 84.11 < 84.2 < 84.21
     */
    fun compareClassification(aRaw: String, bRaw: String): Int {
        val aClean = aRaw.trim().replace(',', '.')
        val bClean = bRaw.trim().replace(',', '.')

        val aParts = aClean.split('.', limit = 2)
        val bParts = bClean.split('.', limit = 2)

        val mainCmp = compareDigitPrefix(aParts[0], bParts[0])
        if (mainCmp != 0) return mainCmp

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

    private fun compareDigitPrefix(a: String, b: String): Int {
        val minLen = minOf(a.length, b.length)
        for (i in 0 until minLen) {
            val cmp = a[i].compareTo(b[i])
            if (cmp != 0) return cmp
        }
        return a.length.compareTo(b.length)
    }

    /** Returns negative/zero/positive according to Finnish shelf alphabet order. */
    fun compareFinnish(aRaw: String, bRaw: String): Int {
        val a = aRaw.trim().uppercase()
        val b = bRaw.trim().uppercase()
        val length = minOf(a.length, b.length)

        for (i in 0 until length) {
            val ai = FINNISH_ALPHABET.indexOf(a[i])
            val bi = FINNISH_ALPHABET.indexOf(b[i])

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
     *   - Author letters before class: AIKMYC14-17, AIKMYC14-15JUS
     */
    fun matches(targetRaw: String, range: ShelfRange): Boolean {
        val parsed = range.parsed ?: return false
        val target = parseTarget(targetRaw) ?: return false

        if (target.section != parsed.section) return false

        val startClassCmp = compareClassification(target.classNumber, parsed.start.classNumber)
        if (startClassCmp < 0) return false

        if (startClassCmp == 0) {
            val startAuthor = parsed.start.authorStart
            if (startAuthor != null) {
                if (compareFinnish(target.authorKey, startAuthor) < 0) return false
            }
        }

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

        val endAuthorStart = end.authorStart
        if (endAuthorStart != null) {
            if (compareFinnish(target.authorKey, endAuthorStart) < 0) return false
        }

        val endAuthor = end.authorEnd
        if (endAuthor == null) return true

        return compareFinnish(target.authorKey, endAuthor) < 0 || target.authorKey.startsWith(endAuthor)
    }
}
