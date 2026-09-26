package org.example.foodblob.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotRevisionTest {
    @Test
    fun revisionIsStableOpaqueAndChangesWithEffectiveRows() {
        val first = SnapshotRevision.forRows(mapOf("2026-08-11" to FoodCounts(green = 1)))
        val same = SnapshotRevision.forRows(mapOf("2026-08-11" to FoodCounts(green = 1)))
        val changed = SnapshotRevision.forRows(mapOf("2026-08-11" to FoodCounts(green = 2)))

        assertEquals(first, same)
        assertNotEquals(first, changed)
        assertTrue(first.isNotBlank())
        assertTrue(first.all { it.isDigit() || it in 'a'..'f' })
    }
}
