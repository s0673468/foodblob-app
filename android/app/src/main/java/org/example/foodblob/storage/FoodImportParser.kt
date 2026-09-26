package org.example.foodblob.storage

import org.example.foodblob.domain.FoodColor
import org.example.foodblob.domain.FoodCounts
import org.example.foodblob.domain.FoodDateKeys
import org.example.foodblob.domain.SkinId
import org.example.foodblob.domain.WidgetLayoutId
import java.security.MessageDigest
import java.util.UUID

enum class FoodImportErrorCode {
    MALFORMED_JSON,
    DUPLICATE_OBJECT_KEY,
    INVALID_STRUCTURE,
    UNSUPPORTED_SCHEMA,
    INVALID_DATE_KEY,
    INVALID_COUNT,
    INVALID_ENUM,
    INVALID_UUID,
    BOUNDS_EXCEEDED,
    UNSAFE_PENDING_WIDGET_ACTIONS,
    RECOVERY_UNAVAILABLE,
    STALE_OR_TAMPERED_PLAN,
    INVALID_ROLLBACK_ARTIFACT,
}

class FoodImportException(
    val code: FoodImportErrorCode,
    message: String,
) : IllegalArgumentException(message)

interface FoodImportPlan {
    val schemaVersion: Int
    val selectedSkin: SkinId
    val selectedWidgetLayout: WidgetLayoutId
    val dayCount: Int
    val undoActionCount: Int
    val consumedWidgetReceiptCount: Int
    val duplicateDaysCollapsed: Int
    val duplicateConsumedWidgetIdsCollapsed: Int
    val oldestDateKey: String?
    val newestDateKey: String?
    val totalCounts: FoodCounts
    val sourceDigest: String
}

private class ValidatedFoodImportPlan(
    override val schemaVersion: Int,
    override val selectedSkin: SkinId,
    override val selectedWidgetLayout: WidgetLayoutId,
    override val dayCount: Int,
    override val undoActionCount: Int,
    override val consumedWidgetReceiptCount: Int,
    override val duplicateDaysCollapsed: Int,
    override val duplicateConsumedWidgetIdsCollapsed: Int,
    override val oldestDateKey: String?,
    override val newestDateKey: String?,
    override val totalCounts: FoodCounts,
    override val sourceDigest: String,
    val document: FoodImportDocument,
    val integrityDigest: String,
) : FoodImportPlan

internal data class ValidatedFoodImport(
    val document: FoodImportDocument,
    val dayCount: Int,
    val undoActionCount: Int,
    val consumedWidgetReceiptCount: Int,
    val duplicateDaysCollapsed: Int,
)

data class FoodImportResult(
    val restoredDayCount: Int,
    val restoredUndoActionCount: Int,
    val restoredConsumedWidgetReceiptCount: Int,
    val duplicateDaysCollapsed: Int,
    val snapshotRevision: String,
    val widgetEpoch: Long,
    val rollbackArtifactCreated: Boolean,
    val observersNotified: Boolean,
)

internal data class FoodImportDocument(
    val days: List<FoodImportDay>,
    val selectedSkin: SkinId,
    val selectedWidgetLayout: WidgetLayoutId,
    val undo: List<FoodImportUndo>,
    val consumedWidgetIds: List<UUID>,
)

internal data class FoodImportDay(
    val dateKey: String,
    val counts: FoodCounts,
    val updatedAtEpochMs: Long,
)

internal data class FoodImportUndo(
    val dateKey: String,
    val color: FoodColor,
    val delta: Int,
    val createdAtEpochMs: Long,
)

object FoodImportParser {
    private const val EXPORT_SCHEMA_VERSION = 1
    private const val MAX_INPUT_CHARS = 2 * 1_024 * 1_024
    private const val MAX_RAW_DAYS = FoodStore.MAX_HISTORY_DAYS * 4
    private const val MAX_RAW_RECEIPTS = FoodStore.MAX_CONSUMED_WIDGET_EVENTS * 2

    private val topLevelKeys = setOf(
        "schema_version",
        "selected_skin",
        "selected_widget_layout",
        "days",
        "undo_stack",
        "pending_widget_ids",
        "consumed_widget_ids",
    )
    private val dayKeys = setOf("date", "green", "yellow", "red", "updated_at_epoch_ms")
    private val undoKeys = setOf("date", "color", "delta", "created_at_epoch_ms")

    fun inspect(json: String): FoodImportPlan {
        if (json.length > MAX_INPUT_CHARS) {
            fail(FoodImportErrorCode.BOUNDS_EXCEEDED, "Import document is too large.")
        }
        val root = StrictJsonParser(json).parse().asObject("document")
        root.requireExactKeys(topLevelKeys, "document")

        val schemaVersion = root.requiredLong("schema_version")
        if (schemaVersion != EXPORT_SCHEMA_VERSION.toLong()) {
            fail(FoodImportErrorCode.UNSUPPORTED_SCHEMA, "Import schema is not supported.")
        }
        val skin = SkinId.entries.firstOrNull { it.storageId == root.requiredString("selected_skin") }
            ?: fail(FoodImportErrorCode.INVALID_ENUM, "Selected skin is not supported.")
        val layout = WidgetLayoutId.entries.firstOrNull {
            it.storageId == root.requiredString("selected_widget_layout")
        } ?: fail(FoodImportErrorCode.INVALID_ENUM, "Selected widget layout is not supported.")

        val rawDays = root.requiredArray("days")
        if (rawDays.size > MAX_RAW_DAYS) {
            fail(FoodImportErrorCode.BOUNDS_EXCEEDED, "Import has too many day records.")
        }
        val latestDayByKey = linkedMapOf<String, IndexedValue<FoodImportDay>>()
        rawDays.forEachIndexed { index, value ->
            val item = value.asObject("day")
            item.requireExactKeys(dayKeys, "day")
            val dateKey = item.requiredString("date")
            if (!FoodDateKeys.isCanonical(dateKey)) {
                fail(FoodImportErrorCode.INVALID_DATE_KEY, "Import contains an invalid day key.")
            }
            val candidate = FoodImportDay(
                dateKey = dateKey,
                counts = FoodCounts(
                    green = item.requiredCount("green"),
                    yellow = item.requiredCount("yellow"),
                    red = item.requiredCount("red"),
                ),
                updatedAtEpochMs = item.requiredLong("updated_at_epoch_ms"),
            )
            val current = latestDayByKey[dateKey]
            if (current == null || candidate.updatedAtEpochMs >= current.value.updatedAtEpochMs) {
                latestDayByKey[dateKey] = IndexedValue(index, candidate)
            }
        }
        if (latestDayByKey.size > FoodStore.MAX_HISTORY_DAYS) {
            fail(FoodImportErrorCode.BOUNDS_EXCEEDED, "Import exceeds history retention.")
        }
        val days = latestDayByKey.values.map { it.value }.sortedByDescending { it.dateKey }

        val undoValues = root.requiredArray("undo_stack")
        if (undoValues.size > FoodStore.MAX_UNDO_ACTIONS) {
            fail(FoodImportErrorCode.BOUNDS_EXCEEDED, "Import has too many undo actions.")
        }
        val undo = undoValues.map { value ->
            val item = value.asObject("undo action")
            item.requireExactKeys(undoKeys, "undo action")
            val dateKey = item.requiredString("date")
            if (!FoodDateKeys.isCanonical(dateKey)) {
                fail(FoodImportErrorCode.INVALID_DATE_KEY, "Import contains an invalid undo day key.")
            }
            val color = FoodColor.fromStorage(item.requiredString("color"))
                ?: fail(FoodImportErrorCode.INVALID_ENUM, "Undo color is not supported.")
            val delta = item.requiredLong("delta")
            if (delta != -1L && delta != 1L) {
                fail(FoodImportErrorCode.INVALID_COUNT, "Undo delta must be one step.")
            }
            FoodImportUndo(
                dateKey = dateKey,
                color = color,
                delta = delta.toInt(),
                createdAtEpochMs = item.requiredLong("created_at_epoch_ms"),
            )
        }

        val pendingIds = root.requiredUuidArray("pending_widget_ids", FoodStore.MAX_RECONCILE_BATCH * 4)
        if (pendingIds.isNotEmpty()) {
            fail(
                FoodImportErrorCode.UNSAFE_PENDING_WIDGET_ACTIONS,
                "Pending widget actions cannot be restored without their action payloads.",
            )
        }
        val rawConsumed = root.requiredUuidArray("consumed_widget_ids", MAX_RAW_RECEIPTS)
        val consumed = rawConsumed.distinct()
        if (consumed.size > FoodStore.MAX_CONSUMED_WIDGET_EVENTS) {
            fail(FoodImportErrorCode.BOUNDS_EXCEEDED, "Import has too many widget receipts.")
        }

        val totals = days.fold(longArrayOf(0, 0, 0)) { result, day ->
            result[0] += day.counts.green.toLong()
            result[1] += day.counts.yellow.toLong()
            result[2] += day.counts.red.toLong()
            result
        }
        val document = FoodImportDocument(days, skin, layout, undo, consumed)
        val sourceDigest = sha256(json)
        return ValidatedFoodImportPlan(
            schemaVersion = EXPORT_SCHEMA_VERSION,
            selectedSkin = skin,
            selectedWidgetLayout = layout,
            dayCount = days.size,
            undoActionCount = undo.size,
            consumedWidgetReceiptCount = consumed.size,
            duplicateDaysCollapsed = rawDays.size - days.size,
            duplicateConsumedWidgetIdsCollapsed = rawConsumed.size - consumed.size,
            oldestDateKey = days.lastOrNull()?.dateKey,
            newestDateKey = days.firstOrNull()?.dateKey,
            totalCounts = FoodCounts(
                green = totals[0].coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                yellow = totals[1].coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                red = totals[2].coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            ),
            sourceDigest = sourceDigest,
            document = document,
            integrityDigest = integrityDigest(sourceDigest, document),
        )
    }

    internal fun validatedPayload(plan: FoodImportPlan, json: String): ValidatedFoodImport {
        val inspected = plan as? ValidatedFoodImportPlan
            ?: fail(FoodImportErrorCode.STALE_OR_TAMPERED_PLAN, "Import preview is not authentic.")
        val verified = inspect(json) as ValidatedFoodImportPlan
        val planIsIntact = inspected.integrityDigest == integrityDigest(inspected.sourceDigest, inspected.document)
        val samePayload = inspected.sourceDigest == verified.sourceDigest &&
            inspected.integrityDigest == verified.integrityDigest
        if (!planIsIntact || !samePayload) {
            fail(FoodImportErrorCode.STALE_OR_TAMPERED_PLAN, "Import preview no longer matches the selected document.")
        }
        return ValidatedFoodImport(
            document = verified.document,
            dayCount = verified.dayCount,
            undoActionCount = verified.undoActionCount,
            consumedWidgetReceiptCount = verified.consumedWidgetReceiptCount,
            duplicateDaysCollapsed = verified.duplicateDaysCollapsed,
        )
    }

    private fun integrityDigest(sourceDigest: String, document: FoodImportDocument): String = sha256(
        buildString {
            append(sourceDigest).append('|')
            append(document.selectedSkin.storageId).append('|')
            append(document.selectedWidgetLayout.storageId).append('|')
            document.days.forEach { day ->
                append(day.dateKey).append(':')
                append(day.counts.green).append(',').append(day.counts.yellow).append(',').append(day.counts.red)
                append('@').append(day.updatedAtEpochMs).append(';')
            }
            append('|')
            document.undo.forEach { undo ->
                append(undo.dateKey).append(':').append(undo.color.storageId).append(':')
                append(undo.delta).append('@').append(undo.createdAtEpochMs).append(';')
            }
            append('|')
            document.consumedWidgetIds.forEach { append(it).append(';') }
        },
    )

    private fun Map<String, JsonValue>.requiredCount(key: String): Int {
        val value = requiredLong(key)
        if (value !in 0..Int.MAX_VALUE.toLong()) {
            fail(FoodImportErrorCode.INVALID_COUNT, "Import contains an invalid count.")
        }
        return value.toInt()
    }

    private fun Map<String, JsonValue>.requiredUuidArray(key: String, rawLimit: Int): List<UUID> {
        val values = requiredArray(key)
        if (values.size > rawLimit) {
            fail(FoodImportErrorCode.BOUNDS_EXCEEDED, "Import contains too many widget identifiers.")
        }
        return values.map { value ->
            val raw = (value as? JsonValue.Text)?.value
                ?: fail(FoodImportErrorCode.INVALID_STRUCTURE, "Widget identifier must be a string.")
            runCatching { UUID.fromString(raw) }.getOrNull()
                ?: fail(FoodImportErrorCode.INVALID_UUID, "Import contains an invalid widget identifier.")
        }
    }

    private fun Map<String, JsonValue>.requiredString(key: String): String =
        (this[key] as? JsonValue.Text)?.value
            ?: fail(FoodImportErrorCode.INVALID_STRUCTURE, "Import field is missing or has the wrong type.")

    private fun Map<String, JsonValue>.requiredLong(key: String): Long {
        val raw = (this[key] as? JsonValue.Number)?.raw
            ?: fail(FoodImportErrorCode.INVALID_STRUCTURE, "Import field is missing or has the wrong type.")
        if (!INTEGER.matches(raw)) {
            fail(FoodImportErrorCode.INVALID_STRUCTURE, "Import integer field is malformed.")
        }
        return raw.toLongOrNull()
            ?: fail(FoodImportErrorCode.BOUNDS_EXCEEDED, "Import integer is outside the supported range.")
    }

    private fun Map<String, JsonValue>.requiredArray(key: String): List<JsonValue> =
        (this[key] as? JsonValue.Array)?.values
            ?: fail(FoodImportErrorCode.INVALID_STRUCTURE, "Import array is missing or has the wrong type.")

    private fun Map<String, JsonValue>.requireExactKeys(expected: Set<String>, label: String) {
        if (keys != expected) {
            fail(FoodImportErrorCode.INVALID_STRUCTURE, "$label fields do not match the supported schema.")
        }
    }

    private fun JsonValue.asObject(label: String): Map<String, JsonValue> =
        (this as? JsonValue.Object)?.values
            ?: fail(FoodImportErrorCode.INVALID_STRUCTURE, "$label must be an object.")

    internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private val INTEGER = Regex("-?(?:0|[1-9][0-9]*)")
}

private sealed interface JsonValue {
    data class Object(val values: LinkedHashMap<String, JsonValue>) : JsonValue
    data class Array(val values: List<JsonValue>) : JsonValue
    data class Text(val value: String) : JsonValue
    data class Number(val raw: String) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

private class StrictJsonParser(private val source: String) {
    private var index = 0

    fun parse(): JsonValue {
        skipWhitespace()
        val value = parseValue(depth = 0)
        skipWhitespace()
        if (index != source.length) malformed()
        return value
    }

    private fun parseValue(depth: Int): JsonValue {
        if (depth > 16 || index >= source.length) malformed()
        return when (source[index]) {
            '{' -> parseObject(depth + 1)
            '[' -> parseArray(depth + 1)
            '"' -> JsonValue.Text(parseString())
            't' -> parseLiteral("true", JsonValue.Bool(true))
            'f' -> parseLiteral("false", JsonValue.Bool(false))
            'n' -> parseLiteral("null", JsonValue.Null)
            '-', in '0'..'9' -> parseNumber()
            else -> malformed()
        }
    }

    private fun parseObject(depth: Int): JsonValue.Object {
        index += 1
        skipWhitespace()
        val values = linkedMapOf<String, JsonValue>()
        if (consume('}')) return JsonValue.Object(values)
        while (true) {
            if (index >= source.length || source[index] != '"') malformed()
            val key = parseString()
            if (values.containsKey(key)) {
                fail(FoodImportErrorCode.DUPLICATE_OBJECT_KEY, "Import object contains a duplicate field.")
            }
            skipWhitespace()
            expect(':')
            skipWhitespace()
            values[key] = parseValue(depth)
            skipWhitespace()
            if (consume('}')) break
            expect(',')
            skipWhitespace()
        }
        return JsonValue.Object(values)
    }

    private fun parseArray(depth: Int): JsonValue.Array {
        index += 1
        skipWhitespace()
        val values = mutableListOf<JsonValue>()
        if (consume(']')) return JsonValue.Array(values)
        while (true) {
            values += parseValue(depth)
            skipWhitespace()
            if (consume(']')) break
            expect(',')
            skipWhitespace()
        }
        return JsonValue.Array(values)
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            when {
                character == '"' -> return result.toString()
                character == '\\' -> {
                    if (index >= source.length) malformed()
                    when (val escaped = source[index++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> result.append(parseUnicodeEscape())
                        else -> malformed()
                    }
                }
                character.code < 0x20 -> malformed()
                else -> result.append(character)
            }
        }
        malformed()
    }

    private fun parseUnicodeEscape(): Char {
        if (index + 4 > source.length) malformed()
        val raw = source.substring(index, index + 4)
        index += 4
        return raw.toIntOrNull(16)?.toChar() ?: malformed()
    }

    private fun parseNumber(): JsonValue.Number {
        val start = index
        if (consume('-') && index >= source.length) malformed()
        if (consume('0')) {
            if (index < source.length && source[index].isDigit()) malformed()
        } else {
            if (index >= source.length || source[index] !in '1'..'9') malformed()
            while (index < source.length && source[index].isDigit()) index += 1
        }
        if (consume('.')) {
            if (index >= source.length || !source[index].isDigit()) malformed()
            while (index < source.length && source[index].isDigit()) index += 1
        }
        if (index < source.length && source[index] in "eE") {
            index += 1
            if (index < source.length && source[index] in "+-") index += 1
            if (index >= source.length || !source[index].isDigit()) malformed()
            while (index < source.length && source[index].isDigit()) index += 1
        }
        return JsonValue.Number(source.substring(start, index))
    }

    private fun <T : JsonValue> parseLiteral(literal: String, value: T): T {
        if (!source.startsWith(literal, index)) malformed()
        index += literal.length
        return value
    }

    private fun expect(character: Char) {
        if (!consume(character)) malformed()
    }

    private fun consume(character: Char): Boolean {
        if (index >= source.length || source[index] != character) return false
        index += 1
        return true
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index] in " \t\r\n") index += 1
    }

    private fun malformed(): Nothing = fail(FoodImportErrorCode.MALFORMED_JSON, "Import JSON is malformed.")
}

private fun fail(code: FoodImportErrorCode, message: String): Nothing = throw FoodImportException(code, message)
