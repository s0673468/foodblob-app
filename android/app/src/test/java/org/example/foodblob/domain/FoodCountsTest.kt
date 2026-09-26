package org.example.foodblob.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodCountsTest {
    @Test
    fun countsClampAtZeroAndApplyDeltas() {
        val counts = FoodCounts(green = -2, yellow = 1, red = 0)

        assertEquals(FoodCounts(green = 0, yellow = 1, red = 0), counts)
        assertEquals(0, counts.applyDelta(FoodColor.GREEN, -9).green)
        assertEquals(3, counts.applyDelta(FoodColor.RED, 3).red)
        assertEquals(1, counts.total)
    }

    @Test
    fun unknownSkinAndLayoutUseSafeDefaults() {
        assertEquals(SkinId.SKY_MEADOW, SkinId.fromStorage("retired_world"))
        assertEquals(WidgetLayoutId.BUBBLE_STACK, WidgetLayoutId.fromStorage("unknown"))
        assertEquals(11, WidgetLayoutId.entries.size)
        assertTrue(WidgetLayoutId.entries.any { it.storageId == "pop_columns" })
        assertTrue(WidgetLayoutId.entries.any { it.storageId == "palette_tray" })
        WidgetLayoutId.entries.forEach { layout ->
            assertEquals(layout, WidgetLayoutId.fromStorage(layout.storageId))
        }
    }

    @Test
    fun applyingADeltaCannotWrapAStoredCountBelowZero() {
        val counts = FoodCounts(green = Int.MAX_VALUE)

        assertEquals(Int.MAX_VALUE, counts.applyDelta(FoodColor.GREEN, 1).green)
        assertEquals(0, FoodCounts(red = 1).applyDelta(FoodColor.RED, Int.MIN_VALUE).red)
    }
}
