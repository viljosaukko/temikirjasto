package com.kirjasto.kirjastobotti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfRangeTest {
    @Test
    fun normalizesFinnaGenreAndAdultLabels() {
        assertEquals("AIK84.2ANA", ShelfRangeParser.normalizeFinnaShelf("Jännitys Aikuiset 84.2 ANA"))
        assertEquals("AIK84.2CON", ShelfRangeParser.normalizeFinnaShelf("AIK Aikuiset 84.2 CON"))
    }

    @Test
    fun singleAIntervalMatchesAna() {
        val shelf = ShelfRange("1", "AIK84.2A", 1.0, 2.0, 3.0)
        val target = ShelfRangeParser.normalizeFinnaShelf("Jännitys Aikuiset 84.2 ANA")!!
        assertTrue(ShelfRangeParser.matches(target, shelf))
    }

    @Test
    fun finnishAlphabetPlacesOWithDiaeresisAfterO() {
        assertTrue(ShelfRangeParser.compareFinnish("CON", "CÖN") < 0)
        assertTrue(ShelfRangeParser.compareFinnish("CÖN", "D") < 0)
        assertTrue(ShelfRangeParser.compareFinnish("Z", "Å") < 0)
        assertTrue(ShelfRangeParser.compareFinnish("Å", "Ä") < 0)
        assertTrue(ShelfRangeParser.compareFinnish("Ä", "Ö") < 0)
    }

    @Test
    fun conToDIntervalMatchesConAndConWithDiaeresisAndDAuthors() {
        val shelf = ShelfRange("1", "AIK84.2CON-D", 1.0, 2.0, 3.0)
        assertTrue(ShelfRangeParser.matches("AIK84.2CON", shelf))
        assertTrue(ShelfRangeParser.matches("AIK84.2CÖN", shelf))
        assertTrue(ShelfRangeParser.matches("AIK84.2CÖNIG", shelf))
        assertTrue(ShelfRangeParser.matches("AIK84.2D", shelf))
        assertTrue(ShelfRangeParser.matches("AIK84.2DOY", shelf))
        assertFalse(ShelfRangeParser.matches("AIK84.2E", shelf))
    }

    @Test
    fun shelfIntervalsMatchAsExpectedByLibrarians() {
        val shelf1 = ShelfRange("1", "AIK84.2A-CAN", 1.0, 2.0, 3.0)
        val shelf2 = ShelfRange("2", "AIK84.2CON-D", 4.0, 5.0, 6.0)
        val shelf3 = ShelfRange("3", "AIK84.2E-H", 7.0, 8.0, 9.0)

        // Shelf 1 (A - CAN): includes Canth
        assertTrue(ShelfRangeParser.matches("AIK84.2AAL", shelf1))
        assertTrue(ShelfRangeParser.matches("AIK84.2CAN", shelf1))
        assertTrue(ShelfRangeParser.matches("AIK84.2CANTH", shelf1))
        assertFalse(ShelfRangeParser.matches("AIK84.2CON", shelf1))

        // Shelf 2 (CON - D): includes Connelly, Cön, and Doyle
        assertTrue(ShelfRangeParser.matches("AIK84.2CON", shelf2))
        assertTrue(ShelfRangeParser.matches("AIK84.2CÖN", shelf2))
        assertTrue(ShelfRangeParser.matches("AIK84.2DOY", shelf2))
        assertFalse(ShelfRangeParser.matches("AIK84.2ERK", shelf2))

        // Shelf 3 (E - H): includes Erkko, Heikkinen, Huovinen
        assertTrue(ShelfRangeParser.matches("AIK84.2ERK", shelf3))
        assertTrue(ShelfRangeParser.matches("AIK84.2HEI", shelf3))
        assertTrue(ShelfRangeParser.matches("AIK84.2HUU", shelf3))
        assertFalse(ShelfRangeParser.matches("AIK84.2IKÄ", shelf3))
    }

    @Test
    fun normalizesFinnaWithoutExplicitAdultWord() {
        assertEquals("AIK84.2ANA", ShelfRangeParser.normalizeFinnaShelf("Jännitys 84.2 ANA"))
        assertEquals("AIK84.2ANA", ShelfRangeParser.normalizeFinnaShelf("84.2 ANA"))
    }

    @Test
    fun normalizesFinnaWithCommas() {
        assertEquals("AIK82.2KYR", ShelfRangeParser.normalizeFinnaShelf("Aikuiset, 82.2 KYR"))
        assertEquals("AIK81.04KAN", ShelfRangeParser.normalizeFinnaShelf("Aikuiset, 81.04 KAN"))
    }

    @Test
    fun comparesLibraryClassificationsCorrectly() {
        assertTrue(ShelfRangeParser.compareClassification("81", "81.04") < 0)
        assertTrue(ShelfRangeParser.compareClassification("81.04", "81.2") < 0)
        assertTrue(ShelfRangeParser.compareClassification("81.2", "82") < 0)
        assertTrue(ShelfRangeParser.compareClassification("82", "82.2") < 0)
        assertTrue(ShelfRangeParser.compareClassification("84.11", "84.2") < 0)
        assertTrue(ShelfRangeParser.compareClassification("84.2", "84.21") < 0)
        assertEquals(0, ShelfRangeParser.compareClassification("82.2", "82.2"))
    }

    @Test
    fun multiClassRangeWithoutAuthorsMatchesInsideClasses() {
        val shelf = ShelfRange("1", "AIK81-82.2", 1.0, 2.0, 3.0)

        assertTrue(ShelfRangeParser.matches("AIK81", shelf))
        assertTrue(ShelfRangeParser.matches("AIK81.04KAN", shelf))
        assertTrue(ShelfRangeParser.matches("AIK82AAL", shelf))
        assertTrue(ShelfRangeParser.matches("AIK82.2KYR", shelf))
        assertFalse(ShelfRangeParser.matches("AIK80.9AAL", shelf))
        assertFalse(ShelfRangeParser.matches("AIK82.3AAL", shelf))
    }

    @Test
    fun multiClassWithAuthorBoundAtEndMatchesAsExpected() {
        val shelf = ShelfRange("1", "AIK81-82.2A-M", 1.0, 2.0, 3.0)

        // Class 81.04 is strictly inside 81..82.2, so it fits automatically without author check
        val target81 = ShelfRangeParser.normalizeFinnaShelf("Aikuiset, 81.04 KAN")!!
        assertTrue(ShelfRangeParser.matches(target81, shelf))

        // Class 82.2 with author KYR: K <= M -> fits!
        val target82Kyr = ShelfRangeParser.normalizeFinnaShelf("Aikuiset, 82.2 KYR")!!
        assertTrue(ShelfRangeParser.matches(target82Kyr, shelf))

        // Class 82.2 with author NIS: N > M -> does not fit!
        val target82Nis = ShelfRangeParser.normalizeFinnaShelf("Aikuiset, 82.2 NIS")!!
        assertFalse(ShelfRangeParser.matches(target82Nis, shelf))

        // Out of class range bounds
        assertFalse(ShelfRangeParser.matches("AIK80.9AAL", shelf))
        assertFalse(ShelfRangeParser.matches("AIK82.3AAL", shelf))
    }

    @Test
    fun continuationShelfWithAuthorStartMatchesAsExpected() {
        val shelf = ShelfRange("1", "AIK82.2N-83", 1.0, 2.0, 3.0)

        // Class 82.2 with author K < N -> does not fit
        assertFalse(ShelfRangeParser.matches("AIK82.2KYR", shelf))

        // Class 82.2 with author N >= N -> fits!
        assertTrue(ShelfRangeParser.matches("AIK82.2NIS", shelf))
        assertTrue(ShelfRangeParser.matches("AIK82.2ÖST", shelf))

        // Class 83 with any author -> fits!
        assertTrue(ShelfRangeParser.matches("AIK83AAL", shelf))
        assertFalse(ShelfRangeParser.matches("AIK83.1AAL", shelf))
    }
}
