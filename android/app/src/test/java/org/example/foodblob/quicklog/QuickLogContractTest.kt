package org.example.foodblob.quicklog

import org.example.foodblob.domain.FoodColor
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickLogContractTest {
    @Test
    fun `shortcuts preserve stable green yellow red order and identifiers`() {
        assertEquals(
            listOf(
                "log_green" to FoodColor.GREEN,
                "log_yellow" to FoodColor.YELLOW,
                "log_red" to FoodColor.RED,
            ),
            QuickLogContract.definitions.map { it.id to it.color },
        )
    }

    @Test
    fun `only the exact private action and a known color can log`() {
        QuickLogContract.definitions.forEach { definition ->
            assertEquals(
                definition.color,
                QuickLogContract.colorForIntent(
                    QuickLogContract.ACTION_LOG,
                    definition.color.storageId,
                ),
            )
        }

        assertNull(QuickLogContract.colorForIntent(null, FoodColor.GREEN.storageId))
        assertNull(QuickLogContract.colorForIntent("com.example.LOG", FoodColor.GREEN.storageId))
        assertNull(QuickLogContract.colorForIntent(QuickLogContract.ACTION_LOG, "blue"))
        assertNull(QuickLogContract.colorForIntent(QuickLogContract.ACTION_LOG, null))
    }

    @Test
    fun `saved execution id is reused after recreation and malformed state is replaced`() {
        val saved = UUID.fromString("00000000-0000-0000-0000-000000000901")
        val generated = UUID.fromString("00000000-0000-0000-0000-000000000902")

        assertEquals(saved, QuickLogContract.resolveEventId(saved.toString()) { generated })
        assertEquals(generated, QuickLogContract.resolveEventId(null) { generated })
        assertEquals(generated, QuickLogContract.resolveEventId("not-a-uuid") { generated })
    }
}
