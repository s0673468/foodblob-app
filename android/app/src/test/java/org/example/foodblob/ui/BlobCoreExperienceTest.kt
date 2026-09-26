package org.example.foodblob.ui

import org.example.foodblob.domain.BlobPoint
import org.example.foodblob.domain.FoodColor
import org.example.foodblob.domain.FoodCounts
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class BlobCoreExperienceTest {
    private val outline = listOf(BlobPoint(0.0,0.0),BlobPoint(100.0,0.0),BlobPoint(100.0,100.0),BlobPoint(0.0,100.0))
    @Test fun `drag commits only once inside current rendered silhouette`() {
        val drag = BlobColorDrag()
        drag.begin(FoodColor.GREEN, 3, BlobPoint(50.0,150.0))
        drag.move(BlobPoint(50.0,50.0))
        assertEquals(FoodColor.GREEN, drag.finish(3, outline))
        assertNull(drag.finish(3, outline))
    }
    @Test fun `cancel outside drop and interrupted day never log`() {
        val drag = BlobColorDrag()
        drag.begin(FoodColor.RED, 0, BlobPoint(150.0,150.0))
        assertNull(drag.finish(0, outline))
        drag.begin(FoodColor.RED, 0, BlobPoint(50.0,50.0))
        assertNull(drag.finish(1, outline))
        drag.begin(FoodColor.RED, 1, BlobPoint(50.0,50.0))
        drag.cancel()
        assertNull(drag.finish(1, outline))
    }
    @Test fun `undo bead follows only the actual accepted single-color removal`() {
        assertEquals(FoodColor.YELLOW, CoreBlobMotion.removedColor(FoodCounts(3,2,1),FoodCounts(3,1,1)))
        assertNull(CoreBlobMotion.removedColor(FoodCounts(3,2,1),FoodCounts(4,2,1)))
        assertNull(CoreBlobMotion.removedColor(FoodCounts(3,2,1),FoodCounts(2,1,1)))
        assertNull(CoreBlobMotion.removedColor(FoodCounts(3,2,1),FoodCounts(3,2,1)))
    }
    @Test fun `calendar retains month dates and aligns first day of week`() {
        for (weekday in DayOfWeek.entries) {
            val cells = CoreCalendar.cells(java.time.YearMonth.of(2026,9), weekday)
            assertEquals(30, cells.count { it != null })
            assertEquals(LocalDate.of(2026,9,30), cells.filterNotNull().last())
            assertEquals(weekday, cells.filterNotNull().first().minusDays(cells.indexOfFirst { it != null }.toLong()).dayOfWeek)
        }
    }
    @Test fun `fuller jelly settles with more weight and greeting is finite`() {
        assertTrue(CoreBlobMotion.massTime(16) > CoreBlobMotion.massTime(1))
        assertTrue(CoreBlobMotion.massTime(1000) <= 1.28)
        assertEquals(0.0, CoreBlobMotion.greeting(0), 0.0)
        assertEquals(0.0, CoreBlobMotion.greeting(1000), 0.0)
        assertTrue(kotlin.math.abs(CoreBlobMotion.greeting(200)) < .04)
    }
}
