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
}
