package org.example.foodblob.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class LiquidFeedbackMotionTest {
    @Test fun `controls yield under pressure and release without changing their resting geometry`() {
        val rest = LiquidFeedbackMotion.frame(0f, 1f, true)
        assertEquals(1f, rest.scaleX, 0f)
        assertEquals(1f, rest.scaleY, 0f)
        val held = LiquidFeedbackMotion.frame(1f, 1f, true)
        assertTrue(held.scaleY < held.scaleX)
        for (step in 0..1000) {
            val frame = LiquidFeedbackMotion.frame(0f, step / 1000f, true)
            assertTrue(frame.scaleX in .97f..1.03f)
            assertTrue(frame.scaleY in .97f..1.03f)
            assertTrue(abs(frame.scaleX * frame.scaleY - 1f) < .002f)
        }
        assertEquals(rest, LiquidFeedbackMotion.frame(0f, 2f, true))
    }

    @Test fun `release glints rise softly and vanish instead of flashing at either endpoint`() {
        assertEquals(0f, LiquidFeedbackMotion.releaseLight(0f), 0f)
        assertEquals(0f, LiquidFeedbackMotion.releaseLight(1f), 0f)
        assertTrue(LiquidFeedbackMotion.releaseLight(.16f) > .4f)
        assertTrue(LiquidFeedbackMotion.releaseLight(.999f) < .00001f)
    }

    @Test fun `reduced motion keeps fixed geometry for any interaction phase`() {
        for (pressure in listOf(0f, .5f, 1f)) for (step in 0..100) {
            val frame = LiquidFeedbackMotion.frame(pressure, step / 100f, false)
            assertEquals(1f, frame.scaleX, 0f)
            assertEquals(1f, frame.scaleY, 0f)
            assertEquals(0f, frame.lift, 0f)
            assertEquals(0f, frame.light, 0f)
        }
    }

    @Test fun `landing beads emerge then return to the blob before disappearing`() {
        assertEquals(0f, LiquidFeedbackMotion.landingBeadTravel(0f), 0f)
        assertEquals(0f, LiquidFeedbackMotion.landingBeadTravel(1f), .00001f)
        assertTrue(LiquidFeedbackMotion.landingBeadTravel(.45f) > .9f)
        assertTrue(LiquidFeedbackMotion.landingBeadTravel(.85f) < .5f)
        assertEquals(0f, LiquidFeedbackMotion.landingLight(-1f), 0f)
        assertEquals(0f, LiquidFeedbackMotion.landingLight(1f), 0f)
    }
}
