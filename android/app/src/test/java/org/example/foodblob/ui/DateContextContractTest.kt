package org.example.foodblob.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DateContextContractTest {
    @Test
    fun `same-day refresh preserves an explicitly selected past date`() {
        assertEquals(
            "2026-08-20",
            resolveDateSelectionAfterRefresh(
                selectedDateKey = "2026-08-20",
                currentToday = "2026-08-23",
                followsToday = false,
            ),
        )
    }

    @Test
    fun `real rollover advances a selection that still follows today`() {
        assertEquals(
            "2026-08-24",
            resolveDateSelectionAfterRefresh(
                selectedDateKey = "2026-08-23",
                currentToday = "2026-08-24",
                followsToday = true,
            ),
        )
    }

    @Test
    fun `explicitly selecting the old today after midnight survives a delayed refresh`() {
        assertEquals(
            "2026-08-23",
            resolveDateSelectionAfterRefresh(
                selectedDateKey = "2026-08-23",
                currentToday = "2026-08-24",
                followsToday = false,
            ),
        )
    }

    @Test
    fun `mutation feedback binds snackbar undo to the tapped date`() {
        val effects = mutationEffects(
            dateKey = "2026-08-22",
            haptic = HapticCue.ADD,
            notice = Notice.SAVED,
        )

        assertEquals(FoodBlobEffect.Haptic(HapticCue.ADD), effects[0])
        assertEquals(
            FoodBlobEffect.Message(Notice.SAVED, undoDateKey = "2026-08-22"),
            effects[1],
        )
    }

    @Test
    fun `ordinary notices never expose a snackbar undo target`() {
        assertNull(FoodBlobEffect.Message(Notice.UNDONE).undoDateKey)
        assertNull(FoodBlobEffect.Message(Notice.IMPORTED).undoDateKey)
    }

    @Test
    fun `date navigation stops at the oldest day of a full retention window`() {
        val retainedDates = (0 until 180).map { offset ->
            java.time.LocalDate.parse("2026-08-23").minusDays(offset.toLong()).toString()
        }

        assertTrue(canMoveBackwardWithinRetention("2026-08-23", retainedDates))
        assertFalse(canMoveBackwardWithinRetention(retainedDates.last(), retainedDates))
        assertTrue(canMoveBackwardWithinRetention(retainedDates.last(), retainedDates.dropLast(1)))
    }
}
