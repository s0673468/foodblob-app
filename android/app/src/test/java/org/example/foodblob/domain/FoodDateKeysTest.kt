package org.example.foodblob.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodDateKeysTest {
    @Test
    fun attributesTheSameInstantToTheActiveZone() {
        val instant = Instant.parse("2026-01-01T01:30:00Z")

        assertEquals("2025-12-31", FoodDateKeys.forInstant(instant, ZoneId.of("America/Sao_Paulo")))
        assertEquals("2026-01-01", FoodDateKeys.forInstant(instant, ZoneId.of("Europe/London")))
    }

    @Test
    fun validatesRealCalendarDatesAndCanonicalizesPadding() {
        assertEquals(LocalDate.of(2027, 1, 5), FoodDateKeys.parse("2027-1-5"))
        assertEquals("2027-01-05", FoodDateKeys.canonical("2027-1-5"))
        assertTrue(FoodDateKeys.isCanonical("2027-01-05"))
        assertFalse(FoodDateKeys.isCanonical("2027-1-5"))
        assertEquals(null, FoodDateKeys.parse("2027-02-29"))
        assertEquals(null, FoodDateKeys.parse("not-a-day"))
    }
}
