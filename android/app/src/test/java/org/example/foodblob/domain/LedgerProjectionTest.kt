package org.example.foodblob.domain

import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerProjectionTest {
    private val day = "2026-08-11"

    @Test
    fun pendingActionsAreDeduplicatedInEncounterOrderAndFloorAtZero() {
        val duplicateId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val events = listOf(
            event(duplicateId, FoodColor.GREEN, 1),
            event(duplicateId, FoodColor.GREEN, 1),
            event(UUID.fromString("00000000-0000-0000-0000-000000000002"), FoodColor.RED, -1),
            event(UUID.fromString("00000000-0000-0000-0000-000000000003"), FoodColor.YELLOW, 2),
        )

        val projected = LedgerProjection.effectiveCounts(
            canonical = mapOf(day to FoodCounts(red = 0)),
            events = events,
            consumedIds = emptySet(),
        )

        assertEquals(FoodCounts(green = 1, yellow = 2, red = 0), projected.getValue(day))
    }

    @Test
    fun consumedAndInvalidActionsDoNotChangeTheSnapshot() {
        val consumed = UUID.fromString("00000000-0000-0000-0000-000000000004")
        val events = listOf(
            event(consumed, FoodColor.GREEN, 1),
            event(UUID.fromString("00000000-0000-0000-0000-000000000005"), FoodColor.GREEN, 0),
            event(UUID.fromString("00000000-0000-0000-0000-000000000006"), FoodColor.GREEN, 1, "bad-day"),
        )

        val projected = LedgerProjection.effectiveCounts(
            canonical = mapOf(day to FoodCounts(yellow = 1)),
            events = events,
            consumedIds = setOf(consumed),
        )

        assertEquals(mapOf(day to FoodCounts(yellow = 1)), projected)
    }

    private fun event(
        id: UUID,
        color: FoodColor,
        delta: Int,
        dateKey: String = day,
    ) = WidgetEvent(
        id = id,
        occurredAt = Instant.parse("2026-08-11T12:00:00Z"),
        dateKey = dateKey,
        zoneId = "America/Sao_Paulo",
        color = color,
        delta = delta,
    )
}
