package org.example.foodblob.storage

import org.example.foodblob.domain.FoodCounts
import org.example.foodblob.domain.SkinId
import org.example.foodblob.domain.WidgetLayoutId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FoodImportParserTest {
    @Test
    fun `inspection validates and deterministically collapses duplicate records`() {
        val plan = FoodImportParser.inspect(
            exportJson(
                days = """
                    {"date":"2026-08-10","green":9,"yellow":0,"red":0,"updated_at_epoch_ms":10},
                    {"date":"2026-08-11","green":1,"yellow":2,"red":3,"updated_at_epoch_ms":20},
                    {"date":"2026-08-10","green":4,"yellow":5,"red":6,"updated_at_epoch_ms":30}
                """.trimIndent(),
                consumed = """
                    "00000000-0000-0000-0000-000000000001",
                    "00000000-0000-0000-0000-000000000001",
                    "00000000-0000-0000-0000-000000000002"
                """.trimIndent(),
            ),
        )

        assertEquals(1, plan.schemaVersion)
        assertEquals(SkinId.SHRINE, plan.selectedSkin)
        assertEquals(WidgetLayoutId.PUDDLE_DOCK, plan.selectedWidgetLayout)
        assertEquals(2, plan.dayCount)
        assertEquals(1, plan.duplicateDaysCollapsed)
        assertEquals(1, plan.duplicateConsumedWidgetIdsCollapsed)
        assertEquals("2026-08-10", plan.oldestDateKey)
        assertEquals("2026-08-11", plan.newestDateKey)
        assertEquals(FoodCounts(green = 5, yellow = 7, red = 9), plan.totalCounts)
        assertEquals(2, plan.consumedWidgetReceiptCount)
        assertEquals(64, plan.sourceDigest.length)
    }

    @Test
    fun `inspection rejects torn json unknown schema and ambiguous object keys`() {
        assertCode(FoodImportErrorCode.MALFORMED_JSON, "{\"schema_version\":1")
        assertCode(FoodImportErrorCode.UNSUPPORTED_SCHEMA, exportJson(schemaVersion = 2))
        assertCode(
            FoodImportErrorCode.DUPLICATE_OBJECT_KEY,
            exportJson().replace("\"selected_skin\": \"shrine\"", "\"selected_skin\":\"shrine\",\"selected_skin\":\"sky_meadow\""),
        )
    }

    @Test
    fun `inspection rejects invalid dates counts enums uuids and bounds`() {
        assertCode(FoodImportErrorCode.INVALID_DATE_KEY, exportJson(days = day(date = "2026-8-1")))
        assertCode(FoodImportErrorCode.INVALID_COUNT, exportJson(days = day(green = -1)))
        assertCode(FoodImportErrorCode.INVALID_ENUM, exportJson().replace("\"shrine\"", "\"retired\""))
        assertCode(FoodImportErrorCode.INVALID_UUID, exportJson(consumed = "\"not-a-uuid\""))

        val tooManyDays = (0..FoodStore.MAX_HISTORY_DAYS).joinToString(",") { index ->
            day(date = java.time.LocalDate.parse("2026-01-01").plusDays(index.toLong()).toString())
        }
        assertCode(FoodImportErrorCode.BOUNDS_EXCEEDED, exportJson(days = tooManyDays))
    }

    @Test
    fun `current exports with pending ids fail closed because action payloads are absent`() {
        assertCode(
            FoodImportErrorCode.UNSAFE_PENDING_WIDGET_ACTIONS,
            exportJson(pending = "\"00000000-0000-0000-0000-000000000003\""),
        )
    }

    @Test
    fun `preview cannot authorize a different payload`() {
        val inspectedPayload = exportJson(days = day(green = 1))
        val swappedPayload = exportJson(days = day(green = 2))
        val plan = FoodImportParser.inspect(inspectedPayload)

        val error = assertThrows(FoodImportException::class.java) {
            FoodImportParser.validatedPayload(plan, swappedPayload)
        }

        assertEquals(FoodImportErrorCode.STALE_OR_TAMPERED_PLAN, error.code)
    }

    private fun assertCode(expected: FoodImportErrorCode, payload: String) {
        val error = assertThrows(FoodImportException::class.java) {
            FoodImportParser.inspect(payload)
        }
        assertEquals(expected, error.code)
    }

    private fun day(
        date: String = "2026-08-11",
        green: Int = 1,
        yellow: Int = 2,
        red: Int = 3,
    ): String = """{"date":"$date","green":$green,"yellow":$yellow,"red":$red,"updated_at_epoch_ms":20}"""

    private fun exportJson(
        schemaVersion: Int = 1,
        days: String = day(),
        pending: String = "",
        consumed: String = "",
    ): String = """
        {
          "schema_version": $schemaVersion,
          "selected_skin": "shrine",
          "selected_widget_layout": "puddle_dock",
          "days": [$days],
          "undo_stack": [
            {"date":"2026-08-11","color":"green","delta":1,"created_at_epoch_ms":20}
          ],
          "pending_widget_ids": [$pending],
          "consumed_widget_ids": [$consumed]
        }
    """.trimIndent()
}
