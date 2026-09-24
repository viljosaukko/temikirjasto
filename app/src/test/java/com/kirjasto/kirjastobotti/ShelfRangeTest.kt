package com.kirjasto.kirjastobotti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

        // Class 83 with any author -> fits, and 83.1 is an alaluokka of 83
        assertTrue(ShelfRangeParser.matches("AIK83AAL", shelf))
        assertTrue(ShelfRangeParser.matches("AIK83.1AAL", shelf))
    }

    @Test
    fun yklOrdersByDigitsNotByIntegerValue() {
        assertTrue(ShelfRangeParser.compareClassification("29", "3") < 0)
        assertTrue(ShelfRangeParser.compareClassification("10", "2") < 0)
        assertTrue(ShelfRangeParser.compareClassification("3", "30") < 0)
        assertTrue(ShelfRangeParser.compareClassification("14", "17") < 0)
        assertTrue(ShelfRangeParser.compareClassification("84.2", "9") < 0)
    }

    @Test
    fun extractsPreclassFromFinnaCallNumber() {
        assertEquals("JÄNNITYS", ShelfRangeParser.extractPreclass("Jännitys Aikuiset 84.2 MYC"))
        assertEquals("JÄNNITYS", ShelfRangeParser.extractPreclass("Jännitys 84.2 ANA"))
        assertNull(ShelfRangeParser.extractPreclass("Aikuiset, 82.2 KYR"))
        assertNull(ShelfRangeParser.extractPreclass("AIK Aikuiset 84.2 CON"))
        assertNull(ShelfRangeParser.extractPreclass("84.2 ANA"))
    }

    @Test
    fun parsesAuthorLettersBeforeClassAsStartBound() {
        val parsed = ShelfRangeParser.parseRange("AIKMYC14-17")
        assertEquals("AIK", parsed!!.section)
        assertEquals("14", parsed.start.classNumber)
        assertEquals("MYC", parsed.start.authorStart)
        assertEquals("17", parsed.end!!.classNumber)
        assertNull(parsed.end.authorEnd)
    }

    @Test
    fun parsesAuthorLettersBeforeClassAndEndAuthor() {
        val parsed = ShelfRangeParser.parseRange("AIKMYC14-15JUS")
        assertEquals("AIK", parsed!!.section)
        assertEquals("14", parsed.start.classNumber)
        assertEquals("MYC", parsed.start.authorStart)
        assertEquals("15", parsed.end!!.classNumber)
        assertEquals("JUS", parsed.end.authorEnd)
    }

    @Test
    fun existingCompactFictionRangesStillParseAsBefore() {
        val conToD = ShelfRangeParser.parseRange("AIK84.2CON-D")!!
        assertEquals("AIK", conToD.section)
        assertEquals("84.2", conToD.start.classNumber)
        assertEquals("CON", conToD.start.authorStart)
        assertEquals("D", conToD.end!!.authorEnd)

        val multi = ShelfRangeParser.parseRange("AIK82.2N-83")!!
        assertEquals("82.2", multi.start.classNumber)
        assertEquals("N", multi.start.authorStart)
        assertEquals("83", multi.end!!.classNumber)
    }

    @Test
    fun authorBeforeClassRangeMatchesFromStartAuthorThroughLaterClasses() {
        val shelf = ShelfRange("1", "AIKMYC14-17", 1.0, 2.0, 3.0)

        assertFalse(ShelfRangeParser.matches("AIK14LYN", shelf))
        assertTrue(ShelfRangeParser.matches("AIK14MYC", shelf))
        assertTrue(ShelfRangeParser.matches("AIK14NAD", shelf))
        assertTrue(ShelfRangeParser.matches("AIK15AAL", shelf))
        assertTrue(ShelfRangeParser.matches("AIK17ZZZ", shelf))
        assertFalse(ShelfRangeParser.matches("AIK13AAL", shelf))
        assertFalse(ShelfRangeParser.matches("AIK18AAL", shelf))
    }

    @Test
    fun authorBeforeClassRangeHonorsEndAuthor() {
        val shelf = ShelfRange("1", "AIKMYC14-15JUS", 1.0, 2.0, 3.0)

        assertTrue(ShelfRangeParser.matches("AIK14MYC", shelf))
        assertTrue(ShelfRangeParser.matches("AIK15AAL", shelf))
        assertTrue(ShelfRangeParser.matches("AIK15JUS", shelf))
        assertTrue(ShelfRangeParser.matches("AIK15JUST", shelf))
        assertFalse(ShelfRangeParser.matches("AIK15KAA", shelf))
        assertFalse(ShelfRangeParser.matches("AIK14LYN", shelf))
        assertFalse(ShelfRangeParser.matches("AIK16AAL", shelf))
    }

    @Test
    fun prefersPreclassShelfOverGenericWhenBothMatch() {
        val generic = ShelfRange("g", "AIK84.2MYC-Z", 1.0, 1.0, 0.0)
        val jannitys = ShelfRange("j", "AIK84.2A-Z", 2.0, 2.0, 0.0, preclass = "Jännitys")

        val chosen = ShelfRangeParser.selectShelf(
            "AIK84.2MYC",
            "JÄNNITYS",
            listOf(generic, jannitys)
        )
        assertEquals("j", chosen!!.id)
    }

    @Test
    fun booksWithoutPreclassDoNotGoToPreclassShelves() {
        val generic = ShelfRange("g", "AIK84.2MYC-Z", 1.0, 1.0, 0.0)
        val jannitys = ShelfRange("j", "AIK84.2A-Z", 2.0, 2.0, 0.0, preclass = "Jännitys")

        val chosen = ShelfRangeParser.selectShelf(
            "AIK84.2MYC",
            null,
            listOf(generic, jannitys)
        )
        assertEquals("g", chosen!!.id)
    }

    @Test
    fun doesNotFallBackToGenericShelfWhenPreclassRangeDoesNotMatch() {
        val generic = ShelfRange("g", "AIK84.2MYC-Z", 1.0, 1.0, 0.0)
        val jannitysEarly = ShelfRange("j", "AIK84.2A-L", 2.0, 2.0, 0.0, preclass = "Jännitys")

        val chosen = ShelfRangeParser.selectShelf(
            "AIK84.2MYC",
            "JÄNNITYS",
            listOf(generic, jannitysEarly)
        )
        assertNull(chosen)
    }

    @Test
    fun prefersAuthorBoundShelfOverOpenClassShelf() {
        val openClass = ShelfRange("open", "AIK14", 1.0, 1.0, 0.0)
        val fromMyc = ShelfRange("myc", "AIKMYC14-17", 2.0, 2.0, 0.0)

        val chosen = ShelfRangeParser.selectShelf(
            "AIK14MYC",
            null,
            listOf(openClass, fromMyc)
        )
        assertEquals("myc", chosen!!.id)

        val earlierAuthor = ShelfRangeParser.selectShelf(
            "AIK14AAL",
            null,
            listOf(openClass, fromMyc)
        )
        assertEquals("open", earlierAuthor!!.id)
    }

    @Test
    fun overlappingTaggedAuthorRangesChooseTheNearestShelfBounds() {
        val broad = ShelfRange("broad", "AIK84.2A-Z", 1.0, 1.0, 0.0, preclass = "Jännitys")
        val precise = ShelfRange("precise", "AIK84.2V-Z", 2.0, 2.0, 0.0, preclass = "Jännitys")

        val chosen = ShelfRangeParser.selectShelf(
            "AIK84.2VYN",
            "JÄNNITYS",
            listOf(broad, precise)
        )

        assertEquals("precise", chosen?.id)
    }

    @Test
    fun taggedPrefixIsNotTreatedAsAnAuthorBound() {
        val ranges = listOf(
            ShelfRange("a-cle", "AIKJÄN84.2A-CLE", 1.0, 1.0, 0.0, preclass = "Jännitys"),
            ShelfRange("jon-lin", "AIKJÄN84.2JON-LIN", 2.0, 2.0, 0.0, preclass = "Jännitys"),
            ShelfRange("liu-ohl", "AIKJÄN84.2LIU-OHL", 3.0, 3.0, 0.0, preclass = "Jännitys")
        )

        assertEquals("jon-lin", ShelfRangeParser.selectShelf(
            ShelfRangeParser.normalizeFinnaShelf("Jännitys, Aikuiset, 84.2 LAG")!!,
            ShelfRangeParser.resolveExistingPreclass("Jännitys, Aikuiset, 84.2 LAG", ranges),
            ranges
        )?.id)
        assertEquals("a-cle", ShelfRangeParser.selectShelf(
            ShelfRangeParser.normalizeFinnaShelf("Jännitys, Aikuiset, 84.2 BÖR")!!,
            ShelfRangeParser.resolveExistingPreclass("Jännitys, Aikuiset, 84.2 BÖR", ranges),
            ranges
        )?.id)
    }

    @Test
    fun shelfPreclassMatchingPrefersJannitysOverGenericForFinnaCallNumber() {
        val generic = ShelfRange("generic", "AIK84.2MYC-Z", 1.0, 1.0, 0.0)
        val jannitys = ShelfRange("jannitys", "AIK84.2A-Z", 2.0, 2.0, 0.0, preclass = "Jännitys")
        val shelves = listOf(generic, jannitys)

        // When book has preclass "Jännitys", it directs to jannitys shelf, not generic AIK84.2MYC-Z
        val finnaCallNumberWithGenre = "Jännitys Aikuiset 84.2 MYC"
        val targetWithGenre = ShelfRangeParser.normalizeFinnaShelf(finnaCallNumberWithGenre)!!
        val preclassWithGenre = ShelfRangeParser.extractPreclass(finnaCallNumberWithGenre)
        val chosenWithGenre = ShelfRangeParser.selectShelf(targetWithGenre, preclassWithGenre, shelves)
        assertEquals("jannitys", chosenWithGenre?.id)

        // When book has no preclass, it directs to generic AIK84.2MYC-Z, not jannitys shelf
        val finnaCallNumberWithoutGenre = "Aikuiset 84.2 MYC"
        val targetWithoutGenre = ShelfRangeParser.normalizeFinnaShelf(finnaCallNumberWithoutGenre)!!
        val preclassWithoutGenre = ShelfRangeParser.extractPreclass(finnaCallNumberWithoutGenre)
        val chosenWithoutGenre = ShelfRangeParser.selectShelf(targetWithoutGenre, preclassWithoutGenre, shelves)
        assertEquals("generic", chosenWithoutGenre?.id)
    }

    @Test
    fun authorLettersBeforeClassNonfictionRangesWorkAsExplained() {
        // Shelf AIKMYC14-17: starting from MYC 14, but also from that MYC 14-17
        val shelf14to17 = ShelfRange("1", "AIKMYC14-17", 1.0, 1.0, 0.0)
        assertFalse(ShelfRangeParser.matches("AIK14AAL", shelf14to17))
        assertFalse(ShelfRangeParser.matches("AIK14LYN", shelf14to17))
        assertTrue(ShelfRangeParser.matches("AIK14MYC", shelf14to17))
        assertTrue(ShelfRangeParser.matches("AIK14ZZZ", shelf14to17))
        assertTrue(ShelfRangeParser.matches("AIK15AAL", shelf14to17))
        assertTrue(ShelfRangeParser.matches("AIK16KAN", shelf14to17))
        assertTrue(ShelfRangeParser.matches("AIK17ZZZ", shelf14to17))
        assertFalse(ShelfRangeParser.matches("AIK18AAL", shelf14to17))

        // Shelf AIKMYC14-15JUS: from MYC 14 all the way to 15 JUS
        val shelf14to15Jus = ShelfRange("2", "AIKMYC14-15JUS", 2.0, 2.0, 0.0)
        assertFalse(ShelfRangeParser.matches("AIK14AAL", shelf14to15Jus))
        assertTrue(ShelfRangeParser.matches("AIK14MYC", shelf14to15Jus))
        assertTrue(ShelfRangeParser.matches("AIK14ZZZ", shelf14to15Jus))
        assertTrue(ShelfRangeParser.matches("AIK15AAL", shelf14to15Jus))
        assertTrue(ShelfRangeParser.matches("AIK15JUS", shelf14to15Jus))
        assertTrue(ShelfRangeParser.matches("AIK15JUST", shelf14to15Jus))
        assertFalse(ShelfRangeParser.matches("AIK15KAA", shelf14to15Jus))
        assertFalse(ShelfRangeParser.matches("AIK16AAL", shelf14to15Jus))
    }

    @Test
    fun yklClassNumbersDoNotGoLikeNormalNumbers() {
        // "29 is not bigger than 3. Only the first number matters. but also the second and the one after the dot."
        assertTrue(ShelfRangeParser.compareClassification("29", "3") < 0)
        assertTrue(ShelfRangeParser.compareClassification("3", "29") > 0)
        assertTrue(ShelfRangeParser.compareClassification("10", "2") < 0)
        assertTrue(ShelfRangeParser.compareClassification("2", "10") > 0)
        assertTrue(ShelfRangeParser.compareClassification("3", "30") < 0)
        assertTrue(ShelfRangeParser.compareClassification("14", "17") < 0)
        assertTrue(ShelfRangeParser.compareClassification("84.2", "9") < 0)
        assertTrue(ShelfRangeParser.compareClassification("9", "84.2") > 0)

        // Decimal parts
        assertTrue(ShelfRangeParser.compareClassification("84.11", "84.2") < 0)
        assertTrue(ShelfRangeParser.compareClassification("84.2", "84.21") < 0)
        assertTrue(ShelfRangeParser.compareClassification("81.04", "81.2") < 0)
    }

    @Test
    fun yklSubclassFallsBackToParentClassShelf() {
        val parent = ShelfRange("14", "AIK14", 1.0, 1.0, 0.0)
        val parentFromTal = ShelfRange("14tal", "AIK14TAL", 2.0, 2.0, 0.0)
        val sibling = ShelfRange("14.5", "AIK14.5", 3.0, 3.0, 0.0)
        val nextClass = ShelfRange("15", "AIK15", 4.0, 4.0, 0.0)
        val specific = ShelfRange("14.4", "AIK14.4", 5.0, 5.0, 0.0)

        // 14.4 is an alaluokka of 14, so it belongs on a 14 shelf
        assertTrue(ShelfRangeParser.matches("AIK14.4YRJ", parent))
        assertTrue(ShelfRangeParser.matches("AIK14.4YRJ", parentFromTal))
        assertFalse(ShelfRangeParser.matches("AIK14.4YRJ", sibling))
        assertFalse(ShelfRangeParser.matches("AIK14.4YRJ", nextClass))

        // A book classified only as 14 does not go onto a more specific 14.4 shelf
        assertFalse(ShelfRangeParser.matches("AIK14YRJ", specific))
        assertTrue(ShelfRangeParser.matches("AIK14.4YRJ", specific))
    }

    @Test
    fun yklSubclassPrefersDedicatedShelfOverParent() {
        val parent = ShelfRange("14", "AIK14TAL", 1.0, 1.0, 0.0)
        val specific = ShelfRange("14.4", "AIK14.4", 2.0, 2.0, 0.0)

        val withSpecific = ShelfRangeParser.selectShelf(
            "AIK14.4YRJ",
            null,
            listOf(parent, specific)
        )
        assertEquals("14.4", withSpecific!!.id)

        val onlyParent = ShelfRangeParser.selectShelf(
            "AIK14.4YRJ",
            null,
            listOf(parent)
        )
        assertEquals("14", onlyParent!!.id)
    }

    @Test
    fun yklNestedSubclassFollowsDigitHierarchy() {
        assertTrue(ShelfRangeParser.isSameOrSubclass("14.4", "14"))
        assertTrue(ShelfRangeParser.isSameOrSubclass("14.4", "1"))
        assertTrue(ShelfRangeParser.isSameOrSubclass("14.14", "14.1"))
        assertFalse(ShelfRangeParser.isSameOrSubclass("14.14", "14.4"))
        assertTrue(ShelfRangeParser.isSameOrSubclass("38.552", "38.55"))
        assertFalse(ShelfRangeParser.isSameOrSubclass("14", "14.4"))
        assertFalse(ShelfRangeParser.isSameOrSubclass("14", "1.4"))
        assertFalse(ShelfRangeParser.isSameOrSubclass("1.4", "14"))
    }

    @Test
    fun rangeEndIncludesSubclassesOnlyWhenWholeEndClassIsCovered() {
        val wholeEnd = ShelfRange("1", "AIK81-82.2", 1.0, 1.0, 0.0)
        assertTrue(ShelfRangeParser.matches("AIK82.21KAN", wholeEnd))
        assertFalse(ShelfRangeParser.matches("AIK82.3AAL", wholeEnd))

        val cutAtAuthor = ShelfRange("2", "AIK81-82.2A-M", 2.0, 2.0, 0.0)
        assertFalse(ShelfRangeParser.matches("AIK82.21KAN", cutAtAuthor))
    }
}
