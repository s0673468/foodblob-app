package org.example.foodblob.storage

import android.system.Os
import android.system.OsConstants
import org.example.foodblob.domain.FoodColor
import org.example.foodblob.domain.FoodDateKeys
import org.example.foodblob.domain.SkinId
import org.example.foodblob.domain.WidgetLayoutId
import java.io.File
import java.io.FileOutputStream
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal data class FoodImportRollbackState(
    val settings: SettingsEntity,
    val days: List<DayEntity>,
    val undo: List<UndoEntity>,
    val widgetEvents: List<WidgetEventEntity>,
    val consumedWidgetEvents: List<ConsumedWidgetEventEntity>,
)

class FoodImportRecoveryPlan internal constructor(
    val dayCount: Int,
    val undoActionCount: Int,
    val pendingWidgetActionCount: Int,
    val consumedWidgetReceiptCount: Int,
    val selectedSkin: SkinId,
    val selectedWidgetLayout: WidgetLayoutId,
    val artifactCreatedAtEpochMs: Long,
    val artifactDigest: String,
    internal val state: FoodImportRollbackState,
)

internal class ImportRecoveryStore(
    val directory: File,
) {
    fun write(state: FoodImportRollbackState): File {
        ensurePrivateDirectory()
        val target = File(directory, ARTIFACT_NAME)
        val temporary = File(directory, "$ARTIFACT_NAME.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(FoodImportRollbackCodec.encode(state).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            makeOwnerOnly(temporary)
            java.nio.file.Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            makeOwnerOnly(target)
            fsyncDirectory(directory)
            return target
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun inspect(): FoodImportRecoveryPlan? {
        val artifact = File(directory, ARTIFACT_NAME)
        if (!artifact.exists()) return null
        if (!artifact.isFile || artifact.length() !in 1..MAX_ARTIFACT_BYTES) invalidArtifact()
        val payload = try {
            artifact.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            invalidArtifact()
        }
        val state = FoodImportRollbackCodec.decode(payload)
        return FoodImportRecoveryPlan(
            dayCount = state.days.size,
            undoActionCount = state.undo.size,
            pendingWidgetActionCount = state.widgetEvents.size,
            consumedWidgetReceiptCount = state.consumedWidgetEvents.size,
            selectedSkin = SkinId.entries.first { it.storageId == state.settings.selectedSkin },
            selectedWidgetLayout = WidgetLayoutId.entries.first {
                it.storageId == state.settings.selectedWidgetLayout
            },
            artifactCreatedAtEpochMs = artifact.lastModified(),
            artifactDigest = FoodImportParser.sha256(payload),
            state = state,
        )
    }

    fun verify(plan: FoodImportRecoveryPlan): FoodImportRecoveryPlan {
        val current = inspect() ?: throw FoodImportException(
            FoodImportErrorCode.RECOVERY_UNAVAILABLE,
            "Import recovery artifact is unavailable.",
        )
        if (current.artifactDigest != plan.artifactDigest || current.state != plan.state) {
            throw FoodImportException(
                FoodImportErrorCode.STALE_OR_TAMPERED_PLAN,
                "Import recovery preview no longer matches the private artifact.",
            )
        }
        return current
    }

    fun delete() {
        if (!directory.exists()) return
        check(directory.isDirectory && !java.nio.file.Files.isSymbolicLink(directory.toPath())) {
            "Import recovery path is not a private directory"
        }
        var directoryChanged = false
        val artifact = File(directory, ARTIFACT_NAME)
        if (artifact.exists()) {
            check(artifact.isFile && artifact.delete()) {
                "Unable to delete import recovery artifact"
            }
            directoryChanged = true
        }
        directory.listFiles().orEmpty()
            .filter { candidate ->
                candidate.name.startsWith("$ARTIFACT_NAME.") &&
                    candidate.name.endsWith(".tmp") &&
                    candidate.isFile &&
                    !java.nio.file.Files.isSymbolicLink(candidate.toPath())
            }
            .forEach { temporary ->
                check(temporary.delete()) { "Unable to delete interrupted import recovery artifact" }
                directoryChanged = true
            }
        if (directoryChanged) {
            fsyncDirectory(directory)
        }
        if (directory.listFiles().orEmpty().isEmpty()) {
            val parent = directory.parentFile
            check(directory.delete()) { "Unable to remove empty import recovery directory" }
            if (parent?.isDirectory == true) fsyncDirectory(parent)
        }
    }

    private fun ensurePrivateDirectory() {
        if (!directory.exists()) check(directory.mkdirs()) { "Unable to create import recovery directory" }
        check(directory.isDirectory && !java.nio.file.Files.isSymbolicLink(directory.toPath())) {
            "Import recovery path is not a private directory"
        }
        Os.chmod(directory.path, PRIVATE_DIRECTORY_MODE)
    }

    private fun makeOwnerOnly(file: File) {
        Os.chmod(file.path, PRIVATE_FILE_MODE)
    }

    private fun fsyncDirectory(value: File) {
        val descriptor = Os.open(value.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    companion object {
        private const val ARTIFACT_NAME = "latest-import.rollback.json"
        private const val MAX_ARTIFACT_BYTES = 8L * 1_024 * 1_024
        private const val PRIVATE_DIRECTORY_MODE = 0x1C0 // 0700
        private const val PRIVATE_FILE_MODE = 0x180 // 0600
    }
}

private object FoodImportRollbackCodec {
    fun encode(state: FoodImportRollbackState): String = buildString {
        append("{\"rollback_schema_version\":1,\"settings\":{")
        field("schema_version", state.settings.schemaVersion.toLong())
        append(',')
        field("selected_skin", state.settings.selectedSkin)
        append(',')
        field("selected_widget_layout", state.settings.selectedWidgetLayout)
        append(',')
        field("generation", state.settings.generation)
        append(',')
        field("last_mutation_epoch_ms", state.settings.lastMutationEpochMs)
        append(',')
        field("widget_epoch", state.settings.widgetEpoch)
        append("},\"days\":[")
        state.days.forEachIndexed { index, day ->
            if (index > 0) append(',')
            append('{')
            field("date", day.dateKey)
            append(',')
            field("green", day.green.toLong())
            append(',')
            field("yellow", day.yellow.toLong())
            append(',')
            field("red", day.red.toLong())
            append(',')
            field("updated_at_epoch_ms", day.updatedAtEpochMs)
            append('}')
        }
        append("],\"undo_stack\":[")
        state.undo.forEachIndexed { index, undo ->
            if (index > 0) append(',')
            append('{')
            field("id", undo.id)
            append(',')
            field("date", undo.dateKey)
            append(',')
            field("color", undo.color)
            append(',')
            field("delta", undo.delta.toLong())
            append(',')
            field("created_at_epoch_ms", undo.createdAtEpochMs)
            append('}')
        }
        append("],\"widget_events\":[")
        state.widgetEvents.forEachIndexed { index, event ->
            if (index > 0) append(',')
            append('{')
            field("sequence", event.sequence)
            append(',')
            field("event_id", event.eventId)
            append(',')
            field("occurred_at_epoch_ms", event.occurredAtEpochMs)
            append(',')
            field("date", event.dateKey)
            append(',')
            field("zone_id", event.zoneId)
            append(',')
            field("color", event.color)
            append(',')
            field("delta", event.delta.toLong())
            append('}')
        }
        append("],\"consumed_widget_events\":[")
        state.consumedWidgetEvents.forEachIndexed { index, event ->
            if (index > 0) append(',')
            append('{')
            field("event_id", event.eventId)
            append(',')
            field("consumed_at_epoch_ms", event.consumedAtEpochMs)
            append(',')
            append("\"rejected\":").append(event.rejected)
            append('}')
        }
        append("]}\n")
    }

    fun decode(payload: String): FoodImportRollbackState {
        try {
            val root = JSONObject(payload).requireKeys(
                setOf(
                    "rollback_schema_version",
                    "settings",
                    "days",
                    "undo_stack",
                    "widget_events",
                    "consumed_widget_events",
                ),
            )
            if (root.strictLong("rollback_schema_version") != 1L) invalidArtifact()
            val settingsObject = root.strictObject("settings").requireKeys(
                setOf(
                    "schema_version",
                    "selected_skin",
                    "selected_widget_layout",
                    "generation",
                    "last_mutation_epoch_ms",
                    "widget_epoch",
                ),
            )
            val selectedSkin = settingsObject.strictString("selected_skin")
            if (SkinId.entries.none { it.storageId == selectedSkin }) invalidArtifact()
            val selectedLayout = settingsObject.strictString("selected_widget_layout")
            if (WidgetLayoutId.entries.none { it.storageId == selectedLayout }) invalidArtifact()
            val settings = SettingsEntity(
                schemaVersion = settingsObject.strictLong("schema_version").toSupportedInt(1, 2),
                selectedSkin = selectedSkin,
                selectedWidgetLayout = selectedLayout,
                generation = settingsObject.strictLong("generation"),
                lastMutationEpochMs = settingsObject.strictLong("last_mutation_epoch_ms"),
                widgetEpoch = settingsObject.strictLong("widget_epoch"),
            )

            val days = root.strictArray("days").mapObjects(MAX_ROLLBACK_DAYS) { item ->
                item.requireKeys(setOf("date", "green", "yellow", "red", "updated_at_epoch_ms"))
                val date = item.strictString("date")
                if (!FoodDateKeys.isCanonical(date)) invalidArtifact()
                DayEntity(
                    dateKey = date,
                    green = item.strictLong("green").toSupportedInt(0, Int.MAX_VALUE),
                    yellow = item.strictLong("yellow").toSupportedInt(0, Int.MAX_VALUE),
                    red = item.strictLong("red").toSupportedInt(0, Int.MAX_VALUE),
                    updatedAtEpochMs = item.strictLong("updated_at_epoch_ms"),
                )
            }
            if (days.map { it.dateKey }.toSet().size != days.size) invalidArtifact()

            val undo = root.strictArray("undo_stack").mapObjects(FoodStore.MAX_UNDO_ACTIONS) { item ->
                item.requireKeys(setOf("id", "date", "color", "delta", "created_at_epoch_ms"))
                val date = item.strictString("date")
                if (!FoodDateKeys.isCanonical(date)) invalidArtifact()
                val color = item.strictString("color")
                if (FoodColor.fromStorage(color) == null) invalidArtifact()
                val delta = item.strictLong("delta")
                if (delta != -1L && delta != 1L) invalidArtifact()
                UndoEntity(
                    id = item.strictLong("id").also { if (it <= 0) invalidArtifact() },
                    dateKey = date,
                    color = color,
                    delta = delta.toInt(),
                    createdAtEpochMs = item.strictLong("created_at_epoch_ms"),
                )
            }
            if (undo.map { it.id }.toSet().size != undo.size) invalidArtifact()

            val widgetEvents = root.strictArray("widget_events").mapObjects(MAX_ROLLBACK_EVENTS) { item ->
                item.requireKeys(
                    setOf("sequence", "event_id", "occurred_at_epoch_ms", "date", "zone_id", "color", "delta"),
                )
                val id = item.strictString("event_id")
                if (runCatching { UUID.fromString(id) }.isFailure) invalidArtifact()
                val instantMs = item.strictLong("occurred_at_epoch_ms")
                val date = item.strictString("date")
                val zone = runCatching { ZoneId.of(item.strictString("zone_id")) }.getOrNull() ?: invalidArtifact()
                if (!FoodDateKeys.isCanonical(date) || FoodDateKeys.forInstant(Instant.ofEpochMilli(instantMs), zone) != date) {
                    invalidArtifact()
                }
                val color = item.strictString("color")
                if (FoodColor.fromStorage(color) == null) invalidArtifact()
                val delta = item.strictLong("delta")
                if (delta != -1L && delta != 1L) invalidArtifact()
                WidgetEventEntity(
                    sequence = item.strictLong("sequence").also { if (it <= 0) invalidArtifact() },
                    eventId = id,
                    occurredAtEpochMs = instantMs,
                    dateKey = date,
                    zoneId = zone.id,
                    color = color,
                    delta = delta.toInt(),
                )
            }
            if (widgetEvents.map { it.sequence }.toSet().size != widgetEvents.size ||
                widgetEvents.map { it.eventId }.toSet().size != widgetEvents.size
            ) {
                invalidArtifact()
            }

            val consumed = root.strictArray("consumed_widget_events")
                .mapObjects(FoodStore.MAX_CONSUMED_WIDGET_EVENTS) { item ->
                    item.requireKeys(setOf("event_id", "consumed_at_epoch_ms", "rejected"))
                    val id = item.strictString("event_id")
                    if (runCatching { UUID.fromString(id) }.isFailure) invalidArtifact()
                    ConsumedWidgetEventEntity(
                        eventId = id,
                        consumedAtEpochMs = item.strictLong("consumed_at_epoch_ms"),
                        rejected = item.strictBoolean("rejected"),
                    )
                }
            if (consumed.map { it.eventId }.toSet().size != consumed.size) invalidArtifact()
            return FoodImportRollbackState(settings, days, undo, widgetEvents, consumed)
        } catch (error: FoodImportException) {
            throw error
        } catch (_: JSONException) {
            invalidArtifact()
        } catch (_: RuntimeException) {
            invalidArtifact()
        }
    }

    private fun StringBuilder.field(name: String, value: String) {
        append('"').append(name).append("\":\"").append(value.jsonEscaped()).append('"')
    }

    private fun StringBuilder.field(name: String, value: Long) {
        append('"').append(name).append("\":").append(value)
    }

    private fun String.jsonEscaped(): String = buildString(length) {
        this@jsonEscaped.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }

    private fun JSONObject.requireKeys(expected: Set<String>): JSONObject {
        val actual = keys().asSequence().toSet()
        if (actual != expected) invalidArtifact()
        return this
    }

    private fun JSONObject.strictObject(name: String): JSONObject =
        opt(name) as? JSONObject ?: invalidArtifact()

    private fun JSONObject.strictArray(name: String): JSONArray =
        opt(name) as? JSONArray ?: invalidArtifact()

    private fun JSONObject.strictString(name: String): String =
        opt(name) as? String ?: invalidArtifact()

    private fun JSONObject.strictLong(name: String): Long = when (val value = opt(name)) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> invalidArtifact()
    }

    private fun JSONObject.strictBoolean(name: String): Boolean =
        opt(name) as? Boolean ?: invalidArtifact()

    private fun <T> JSONArray.mapObjects(limit: Int, block: (JSONObject) -> T): List<T> {
        if (length() > limit) invalidArtifact()
        return (0 until length()).map { index -> block(opt(index) as? JSONObject ?: invalidArtifact()) }
    }

    private fun Long.toSupportedInt(minimum: Int, maximum: Int): Int {
        if (this !in minimum.toLong()..maximum.toLong()) invalidArtifact()
        return toInt()
    }

    private const val MAX_ROLLBACK_DAYS = FoodStore.MAX_HISTORY_DAYS
    private const val MAX_ROLLBACK_EVENTS = FoodStore.MAX_RECONCILE_BATCH * 4
}

private fun invalidArtifact(): Nothing = throw FoodImportException(
    FoodImportErrorCode.INVALID_ROLLBACK_ARTIFACT,
    "Private import recovery artifact is invalid.",
)
