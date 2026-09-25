package com.kirjasto.kirjastobotti

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun isbnValidation_acceptsValidIsbn10And13AndRejectsOthers() {
        val isbnRegex = Regex("(?:97[89])?\\d{9}[\\dXx]")

        // Valid ISBN-13
        assertTrue("9789510478349".matches(isbnRegex))
        assertTrue("9791234567890".matches(isbnRegex))

        // Valid ISBN-10 (numbers and X)
        assertTrue("0306406152".matches(isbnRegex))
        assertTrue("080442957X".matches(isbnRegex))
        assertTrue("080442957x".matches(isbnRegex))

        // Invalid: non-ISBN barcodes
        assertFalse("1234567".matches(isbnRegex)) // too short
        assertFalse("6418800012345".matches(isbnRegex)) // non-ISBN EAN-13 (starts with 641)
        assertFalse("ABC123456789".matches(isbnRegex))
    }
}