package org.example.foodblob.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlobColorTest {
    @Test
    fun emptyCountsHaveNoMixedColor() {
        assertNull(BlobColor.mix(FoodCounts()))
    }

    @Test
    fun foodPigmentsStayRichAndInsideTheGamut() {
        for (color in listOf(BlobColor.FOOD_GREEN, BlobColor.FOOD_YELLOW, BlobColor.FOOD_RED)) {
            val channels = listOf(color.red, color.green, color.blue)
            assertTrue(channels.max() - channels.min() > .65)
            assertTrue(channels.min() > 0 && channels.max() < 1)
        }
    }

    @Test
    fun mixesUsingTheIosOklabFixture() {
        val mixed = requireNotNull(BlobColor.mix(FoodCounts(green = 4, yellow = 2, red = 1)))

        assertEquals(0.6543721688640014, mixed.red, 1e-12)
        assertEquals(0.7598411653121486, mixed.green, 1e-12)
        assertEquals(0.3777161751835682, mixed.blue, 1e-12)
        assertTrue(mixed.red in 0.0..1.0)
        assertTrue(mixed.green in 0.0..1.0)
        assertTrue(mixed.blue in 0.0..1.0)
    }
}
