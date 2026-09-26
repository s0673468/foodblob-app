package org.example.foodblob.ui

import org.example.foodblob.domain.BlobPoint
import org.example.foodblob.domain.FoodColor
import org.example.foodblob.domain.FoodCounts
import org.example.foodblob.domain.SkinId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlobMotionContractTest {
    @Test
    fun `transient paint is limited to interactive same-day full-motion changes`() {
        assertTrue(BlobMotionPolicy.showsTransientPaint(true, true, "2026-08-11", "2026-08-11"))
        assertFalse(BlobMotionPolicy.showsTransientPaint(false, true, "2026-08-11", "2026-08-11"))
        assertFalse(BlobMotionPolicy.showsTransientPaint(true, false, "2026-08-11", "2026-08-11"))
        assertFalse(BlobMotionPolicy.showsTransientPaint(true, true, "2026-08-10", "2026-08-11"))
    }

    @Test
    fun `paint settles whenever an addition cannot finish its transient animation`() {
        val addition = FoodMutation(FoodColor.GREEN, 1)
        val removal = FoodMutation(FoodColor.GREEN, -1)

        assertEquals(BlobPaintAction.START, BlobMotionPolicy.paintAction(addition, transientAllowed = true))
        assertEquals(BlobPaintAction.SETTLE, BlobMotionPolicy.paintAction(removal, transientAllowed = true))
        assertEquals(BlobPaintAction.SETTLE, BlobMotionPolicy.paintAction(addition, transientAllowed = false))
        assertEquals(BlobPaintAction.SETTLE, BlobMotionPolicy.paintAction(null, transientAllowed = true))
    }

    @Test
    fun `food mutation detects the changed colour and signed delta`() {
        assertEquals(
            FoodMutation(FoodColor.YELLOW, 1),
            FoodMutation.detect(FoodCounts(green = 2), FoodCounts(green = 2, yellow = 1)),
        )
        assertEquals(
            FoodMutation(FoodColor.RED, -1),
            FoodMutation.detect(FoodCounts(red = 2), FoodCounts(red = 1)),
        )
        assertNull(FoodMutation.detect(FoodCounts(green = 1), FoodCounts(green = 1)))
    }

    @Test
    fun `counter jelly is disabled with system reduced motion`() {
        assertEquals(1f, CounterMotionPolicy.impactScaleX(adding = true, motionAllowed = false))
        assertEquals(1f, CounterMotionPolicy.impactScaleY(adding = true, motionAllowed = false))
        assertTrue(CounterMotionPolicy.impactScaleX(adding = true, motionAllowed = true) > 1f)
        assertTrue(CounterMotionPolicy.impactScaleY(adding = true, motionAllowed = true) < 1f)
    }

    @Test
    fun `counter jelly follows the iOS tactile keyframes`() {
        assertEquals(
            listOf(
                CounterJellyFrame(1.11f, .87f, 130),
                CounterJellyFrame(.94f, 1.06f, 120),
                CounterJellyFrame(1.03f, .98f, 110),
                CounterJellyFrame(1f, 1f, 100),
            ),
            CounterMotionPolicy.frames,
        )
    }

    @Test
    fun `blob outline interpolates between old and new deterministic shapes`() {
        val previous = listOf(BlobPoint(10.0, 20.0), BlobPoint(30.0, 40.0))
        val current = listOf(BlobPoint(20.0, 40.0), BlobPoint(50.0, 80.0))

        assertEquals(previous, BlobMotionPolicy.interpolatePoints(previous, current, 0f))
        assertEquals(
            listOf(BlobPoint(15.0, 30.0), BlobPoint(40.0, 60.0)),
            BlobMotionPolicy.interpolatePoints(previous, current, .5f),
        )
        assertEquals(current, BlobMotionPolicy.interpolatePoints(previous, current, 1f))
    }

    @Test
    fun `living blob has no persistent hard-edged halo`() {
        SkinId.entries.forEach { skin ->
            assertEquals(0f, BlobMotionPolicy.persistentHaloAlpha(skin))
        }
    }

    @Test
    fun `living blob idle breath matches each iOS skin tempo`() {
        assertEquals(4_500, BlobMotionPolicy.idleBreathDurationMs(SkinId.SKY_MEADOW))
        assertEquals(5_500, BlobMotionPolicy.idleBreathDurationMs(SkinId.SHRINE))
    }
}
