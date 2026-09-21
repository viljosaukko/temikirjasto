package com.kirjasto.kirjastobotti

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryShelfOcrTest {

    @Test
    fun testNormalizeText_handlesAllDashVariants() {
        val input1 = "B-C"
        val input2 = "B–C" // en dash
        val input3 = "B—C" // em dash
        val input4 = "B−C" // minus sign

        assertEquals("B-C", LibraryShelfOcr.normalizeText(input1))
        assertEquals("B-C", LibraryShelfOcr.normalizeText(input2))
        assertEquals("B-C", LibraryShelfOcr.normalizeText(input3))
        assertEquals("B-C", LibraryShelfOcr.normalizeText(input4))
    }

    @Test
    fun testFinnishCharacters_preservedCorrectly() {
        val rangeVo = "V–Ö"
        val rangeAo = "Ä–Ö"
        val rangeAa = "Å–Ä"

        assertEquals("V-Ö", LibraryShelfOcr.normalizeText(rangeVo))
        assertEquals("Ä-Ö", LibraryShelfOcr.normalizeText(rangeAo))
        assertEquals("Å-Ä", LibraryShelfOcr.normalizeText(rangeAa))

        val parsedVo = LibraryShelfOcr.parseRangeToken(rangeVo)
        assertNotNull(parsedVo)
        assertEquals("V", parsedVo?.first)
        assertEquals("Ö", parsedVo?.second)
    }

    @Test
    fun testRangeParsing_distinguishesSingleAndRanges() {
        // Single letter vs range
        val singleB = LibraryShelfOcr.parseRangeToken("B")
        assertNotNull(singleB)
        assertEquals("B", singleB?.first)
        assertEquals("B", singleB?.second)

        val rangeBc = LibraryShelfOcr.parseRangeToken("B – C")
        assertNotNull(rangeBc)
        assertEquals("B", rangeBc?.first)
        assertEquals("C", rangeBc?.second)

        // Decimal classification range
        val rangeClass = LibraryShelfOcr.parseRangeToken("84.2 - 84.5")
        assertNotNull(rangeClass)
        assertEquals("84.2", rangeClass?.first)
        assertEquals("84.5", rangeClass?.second)
    }

    @Test
    fun testGenerateSuggestion_flagsMisreads() {
        // Misread V-O instead of V-Ö
        val (suggestionVo, penaltyVo) = LibraryShelfOcr.generateSuggestion("V-O")
        assertEquals("V-Ö", suggestionVo)
        assertTrue(penaltyVo > 0.0)

        // Misread V-0 instead of V-Ö
        val (suggestionV0, penaltyV0) = LibraryShelfOcr.generateSuggestion("V-0")
        assertEquals("V-Ö", suggestionV0)
        assertTrue(penaltyV0 > 0.0)

        // Correct range V-Ö should not generate penalty
        val (suggestionCorrect, penaltyCorrect) = LibraryShelfOcr.generateSuggestion("V-Ö")
        assertNull(suggestionCorrect)
        assertEquals(0.0, penaltyCorrect, 0.001)
    }

    @Test
    fun testShelfDetection_jsonSerialization() {
        val bb = ShelfBoundingBox(x = 100, y = 150, width = 200, height = 80)
        val detection = ShelfDetection(
            type = "shelf_label",
            rawText = "V–Ö",
            normalizedText = "V-Ö",
            rangeStart = "V",
            rangeEnd = "Ö",
            section = "Kaunokirjallisuus",
            confidence = 0.97,
            boundingBox = bb,
            suggestion = null,
            requiresVerification = false
        )

        val json = detection.toJson()
        assertEquals("shelf_label", json.getString("type"))
        assertEquals("V–Ö", json.getString("raw_text"))
        assertEquals("V-Ö", json.getString("normalized_text"))
        assertEquals("V", json.getString("range_start"))
        assertEquals("Ö", json.getString("range_end"))
        assertEquals(0.97, json.getDouble("confidence"), 0.001)

        val deserialized = ShelfDetection.fromJson(json)
        assertEquals(detection.rawText, deserialized.rawText)
        assertEquals(detection.normalizedText, deserialized.normalizedText)
        assertEquals(detection.rangeStart, deserialized.rangeStart)
        assertEquals(detection.rangeEnd, deserialized.rangeEnd)
        assertEquals(detection.boundingBox?.width, deserialized.boundingBox?.width)
    }
}
