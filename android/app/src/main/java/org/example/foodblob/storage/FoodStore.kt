package org.example.foodblob.storage

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import org.example.foodblob.domain.DayRecord
import org.example.foodblob.domain.FoodColor
import org.example.foodblob.domain.FoodCounts
import org.example.foodblob.domain.FoodDateKeys
import org.example.foodblob.domain.LedgerProjection
import org.example.foodblob.domain.SkinId
import org.example.foodblob.domain.SnapshotRevision
import org.example.foodblob.domain.WidgetEvent
import org.example.foodblob.domain.WidgetLayoutId
import java.io.File
import java.io.FileOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class EffectiveSnapshot(
    val days: List<DayRecord>,
    val selectedSkin: SkinId,
    val selectedWidgetLayout: WidgetLayoutId,
    val revision: String,
    val widgetEpoch: Long,
) {
    fun counts(dateKey: String): FoodCounts = days.firstOrNull { it.dateKey == dateKey }?.counts ?: FoodCounts()
}

internal data class StoreCommitImpact(
    val updateWidgets: Boolean,
    val notifyCountReader: Boolean,
) {
    companion object {
        val APPEARANCE = StoreCommitImpact(updateWidgets = false, notifyCountReader = false)
        val DELETE = StoreCommitImpact(updateWidgets = true, notifyCountReader = true)
        fun counts(isToday: Boolean) = StoreCommitImpact(updateWidgets = isToday, notifyCountReader = true)
    }
}

private data class ReconcileResult(
    val count: Int,
    val widgetEpochRotated: Boolean,
)

internal enum class IdempotentMutationResult {
    COMMITTED,
    ALREADY_COMMITTED,
}

class FoodStore internal constructor(
    private val database: FoodBlobDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneProvider: () -> ZoneId = ZoneId::systemDefault,
    private val afterCommit: suspend (StoreCommitImpact) -> Unit = {},
    private val beforeDeleteAll: suspend () -> Unit = {},
    private val importRecoveryStore: ImportRecoveryStore? = null,
    private val beforeImportMutation: suspend () -> Unit = {},
) {
    private val dao = database.dao()

    val snapshots: Flow<EffectiveSnapshot> = database.invalidationTracker.createFlow(
        "days",
        "widget_events",
        "consumed_widget_events",
        "settings",
        emitInitialState = true,
    ).map { effectiveSnapshot() }.distinctUntilChanged()

    suspend fun ensureInitialized() = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.insertInitialSettings(SettingsEntity())
        }
    }

    suspend fun increment(
        color: FoodColor,
        dateKey: String = currentDateKey(),
    ): Boolean = mutate(color, 1, dateKey)

    suspend fun decrement(
        color: FoodColor,
        dateKey: String = currentDateKey(),
    ): Boolean = mutate(color, -1, dateKey)

    private suspend fun mutate(color: FoodColor, requestedDelta: Int, dateKey: String): Boolean {
        require(FoodDateKeys.isCanonical(dateKey)) { "dateKey must be canonical" }
        val now = clock.instant()
        var changed = false
        var widgetEpochRotated = false
        withContext(Dispatchers.IO) {
            database.withTransaction {
                widgetEpochRotated = reconcilePendingLocked(now).widgetEpochRotated
                val existingDay = dao.day(dateKey)
                if (existingDay == null) {
                    val retainedDays = dao.allDays()
                    val oldestRetainedDateKey = retainedDays.lastOrNull()?.dateKey
                    if (
                        retainedDays.size >= MAX_HISTORY_DAYS &&
                        oldestRetainedDateKey != null &&
                        dateKey <= oldestRetainedDateKey
                    ) {
                        return@withTransaction
                    }
                }
                val current = existingDay?.toCounts() ?: FoodCounts()
                val next = current.applyDelta(color, requestedDelta)
                val actualDelta = next.count(color) - current.count(color)
                if (actualDelta != 0) {
                    dao.upsertDay(next.toEntity(dateKey, now))
                    dao.insertUndo(
                        UndoEntity(
                            dateKey = dateKey,
                            color = color.storageId,
                            delta = actualDelta,
                            createdAtEpochMs = now.toEpochMilli(),
                        ),
                    )
                    dao.trimUndo(MAX_UNDO_ACTIONS)
                    dao.trimDays(MAX_HISTORY_DAYS)
                    bumpGenerationLocked(now)
                    changed = true
                }
            }
        }
        if (changed || widgetEpochRotated) {
            afterCommit(
                StoreCommitImpact(
                    updateWidgets = widgetEpochRotated || dateKey == currentDateKey(),
                    notifyCountReader = true,
                ),
            )
        }
        return changed
    }

    suspend fun undo(dateKey: String? = null): Boolean {
        val now = clock.instant()
        var changed = false
        var changedDateKey: String? = null
        var widgetEpochRotated = false
        withContext(Dispatchers.IO) {
            database.withTransaction {
                widgetEpochRotated = reconcilePendingLocked(now).widgetEpochRotated
                val action = if (dateKey == null) dao.latestUndo() else dao.latestUndo(dateKey)
                if (action != null) {
                    val color = FoodColor.fromStorage(action.color)
                    if (color != null && FoodDateKeys.isCanonical(action.dateKey) && action.delta in -1..1 && action.delta != 0) {
                        val current = dao.day(action.dateKey)?.toCounts() ?: FoodCounts()
                        dao.upsertDay(current.applyDelta(color, -action.delta).toEntity(action.dateKey, now))
                        changed = true
                        changedDateKey = action.dateKey
                    }
                    dao.deleteUndo(action.id)
                    bumpGenerationLocked(now)
                }
            }
        }
        if (changed || widgetEpochRotated) {
            afterCommit(
                StoreCommitImpact(
                    updateWidgets = widgetEpochRotated || changedDateKey == currentDateKey(),
                    notifyCountReader = true,
                ),
            )
        }
        return changed
    }

    fun canUndo(dateKey: String): Flow<Boolean> = dao.observeCanUndo(dateKey).distinctUntilChanged()

    internal suspend fun appendWidgetAction(
        color: FoodColor,
        delta: Int,
        id: UUID = UUID.randomUUID(),
        instant: Instant = clock.instant(),
        zoneId: ZoneId = zoneProvider(),
    ): Boolean {
        val event = appendLedgerAction(color, delta, id, instant, zoneId) ?: return false
        afterCommit(StoreCommitImpact.counts(event.dateKey == currentDateKey()))
        return true
    }

    private suspend fun appendLedgerAction(
        color: FoodColor,
        delta: Int,
        id: UUID,
        instant: Instant,
        zoneId: ZoneId,
    ): WidgetEventEntity? {
        require(delta == -1 || delta == 1) { "Widget delta must be -1 or 1" }
        val event = WidgetEventEntity(
            eventId = id.toString(),
            occurredAtEpochMs = instant.toEpochMilli(),
            dateKey = FoodDateKeys.forInstant(instant, zoneId),
            zoneId = zoneId.id,
            color = color.storageId,
            delta = delta,
        )
        val inserted = withContext(Dispatchers.IO) {
            database.withTransaction {
                !dao.wasWidgetEventConsumed(event.eventId) && dao.appendWidgetEvent(event) != -1L
            }
        }
        return event.takeIf { inserted }
    }

    internal suspend fun appendQuickLogAction(
        color: FoodColor,
        id: UUID,
        instant: Instant = clock.instant(),
        zoneId: ZoneId = zoneProvider(),
    ): IdempotentMutationResult {
        val event = WidgetEventEntity(
            eventId = id.toString(),
            occurredAtEpochMs = instant.toEpochMilli(),
            dateKey = FoodDateKeys.forInstant(instant, zoneId),
            zoneId = zoneId.id,
            color = color.storageId,
            delta = 1,
        )
        var committed = false
        var changesToday = false
        var widgetEpochRotated = false
        withContext(Dispatchers.IO) {
            database.withTransaction {
                if (dao.wasWidgetEventConsumed(event.eventId)) return@withTransaction
                committed = dao.appendWidgetEvent(event) != -1L
                if (!committed) return@withTransaction
                changesToday = dao.pendingWidgetEvents(MAX_RECONCILE_BATCH).any {
                    it.dateKey == currentDateKey()
                }
                widgetEpochRotated = reconcilePendingLocked(instant, MAX_RECONCILE_BATCH).widgetEpochRotated
            }
        }
        if (!committed) return IdempotentMutationResult.ALREADY_COMMITTED
        try {
            afterCommit(
                StoreCommitImpact(
                    updateWidgets = changesToday || widgetEpochRotated,
                    notifyCountReader = true,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The ledger commit is authoritative. A later refresh must not be reported as a failed write.
        }
        return IdempotentMutationResult.COMMITTED
    }

    /** Atomically validates the rendered widget epoch, appends, and reconciles one bounded batch. */
    suspend fun commitWidgetAction(
        color: FoodColor,
        delta: Int,
        id: UUID,
        widgetEpoch: Long,
        instant: Instant = clock.instant(),
        zoneId: ZoneId = zoneProvider(),
    ): Boolean {
        require(delta == -1 || delta == 1) { "Widget delta must be -1 or 1" }
        val event = WidgetEventEntity(
            eventId = id.toString(),
            occurredAtEpochMs = instant.toEpochMilli(),
            dateKey = FoodDateKeys.forInstant(instant, zoneId),
            zoneId = zoneId.id,
            color = color.storageId,
            delta = delta,
        )
        var inserted = false
        var changesToday = false
        var widgetEpochRotated = false
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val settings = settingsLocked()
                if (settings.widgetEpoch != widgetEpoch || dao.wasWidgetEventConsumed(event.eventId)) {
                    return@withTransaction
                }
                inserted = dao.appendWidgetEvent(event) != -1L
                if (!inserted) return@withTransaction
                changesToday = dao.pendingWidgetEvents(MAX_RECONCILE_BATCH).any {
                    it.dateKey == currentDateKey()
                }
                widgetEpochRotated = reconcilePendingLocked(instant, MAX_RECONCILE_BATCH).widgetEpochRotated
            }
        }
        if (inserted) {
            afterCommit(
                StoreCommitImpact(
                    updateWidgets = changesToday || widgetEpochRotated,
                    notifyCountReader = true,
                ),
            )
        }
        return inserted
    }

    suspend fun reconcilePending(maxBatchSize: Int = DEFAULT_RECONCILE_BATCH): Int {
        require(maxBatchSize in 1..MAX_RECONCILE_BATCH)
        val now = clock.instant()
        var count = 0
        var changesToday = false
        var widgetEpochRotated = false
        val today = currentDateKey()
        withContext(Dispatchers.IO) {
            database.withTransaction {
                changesToday = dao.pendingWidgetEvents(maxBatchSize).any { it.dateKey == today }
                reconcilePendingLocked(now, maxBatchSize).also { result ->
                    count = result.count
                    widgetEpochRotated = result.widgetEpochRotated
                }
            }
        }
        if (count > 0) {
            afterCommit(
                StoreCommitImpact(
                    updateWidgets = changesToday || widgetEpochRotated,
                    notifyCountReader = true,
                ),
            )
        }
        return count
    }

    suspend fun setSkin(skin: SkinId) {
        val now = clock.instant()
        var changed = false
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val settings = settingsLocked()
                if (settings.selectedSkin != skin.storageId) {
                    dao.upsertSettings(
                        settings.copy(
                            selectedSkin = skin.storageId,
                            generation = settings.generation + 1,
                            lastMutationEpochMs = now.toEpochMilli(),
                        ),
                    )
                    changed = true
                }
            }
        }
        if (changed) afterCommit(StoreCommitImpact.APPEARANCE)
    }

    suspend fun deleteAll() {
        val now = clock.instant()
        withContext(Dispatchers.IO) {
            beforeDeleteAll()
            importRecoveryStore?.delete()
            database.withTransaction {
                val settings = settingsLocked()
                dao.deleteUndoActions()
                dao.deleteConsumedWidgetEvents()
                dao.deleteWidgetEvents()
                dao.deleteDays()
                dao.upsertSettings(
                    settings.copy(
                        widgetEpoch = settings.widgetEpoch + 1,
                        generation = settings.generation + 1,
                        lastMutationEpochMs = now.toEpochMilli(),
                    ),
                )
            }
        }
        afterCommit(StoreCommitImpact.DELETE)
    }

    suspend fun effectiveSnapshot(): EffectiveSnapshot = withContext(Dispatchers.IO) {
        database.withTransaction {
            project(
                days = dao.allDays(),
                pending = dao.pendingWidgetEvents(Int.MAX_VALUE),
                settings = dao.settings() ?: SettingsEntity(),
            )
        }
    }

    suspend fun exportJson(): String {
        val export = withContext(Dispatchers.IO) {
            database.withTransaction {
                ExportState(
                    snapshot = project(
                        days = dao.allDays(),
                        pending = dao.pendingWidgetEvents(Int.MAX_VALUE),
                        settings = dao.settings() ?: SettingsEntity(),
                    ),
                    undo = dao.allUndoActions(),
                    pending = dao.pendingWidgetEvents(Int.MAX_VALUE),
                    consumed = dao.allConsumedWidgetEvents(),
                )
            }
        }
        val snapshot = export.snapshot
        return buildString {
            append("{\n  \"schema_version\": 1,\n")
            append("  \"selected_skin\": \"")
            append(snapshot.selectedSkin.storageId)
            append("\",\n  \"selected_widget_layout\": \"")
            append(snapshot.selectedWidgetLayout.storageId)
            append("\",\n  \"days\": [")
            snapshot.days.forEachIndexed { index, day ->
                if (index > 0) append(',')
                append("\n    {\"date\":\"")
                append(day.dateKey)
                append("\",\"green\":")
                append(day.counts.green)
                append(",\"yellow\":")
                append(day.counts.yellow)
                append(",\"red\":")
                append(day.counts.red)
                append(",\"updated_at_epoch_ms\":")
                append(day.updatedAt.toEpochMilli())
                append('}')
            }
            if (snapshot.days.isNotEmpty()) append('\n')
            append("  ],\n  \"undo_stack\": [")
            export.undo.forEachIndexed { index, undo ->
                if (index > 0) append(',')
                append("\n    {\"date\":\"").append(undo.dateKey)
                append("\",\"color\":\"").append(undo.color)
                append("\",\"delta\":").append(undo.delta)
                append(",\"created_at_epoch_ms\":").append(undo.createdAtEpochMs).append('}')
            }
            if (export.undo.isNotEmpty()) append('\n')
            append("  ],\n  \"pending_widget_ids\": [")
            export.pending.forEachIndexed { index, event ->
                if (index > 0) append(',')
                append('"').append(event.eventId).append('"')
            }
            append("],\n  \"consumed_widget_ids\": [")
            export.consumed.forEachIndexed { index, event ->
                if (index > 0) append(',')
                append('"').append(event.eventId).append('"')
            }
            append("]\n}\n")
        }
    }

    fun inspectImport(json: String): FoodImportPlan = FoodImportParser.inspect(json)

    fun inspectLatestImportRecovery(): FoodImportRecoveryPlan? = importRecoveryStore?.inspect()

    suspend fun restoreImport(json: String, inspectedPlan: FoodImportPlan): FoodImportResult {
        val plan = FoodImportParser.validatedPayload(inspectedPlan, json)
        val recoveryStore = importRecoveryStore ?: throw FoodImportException(
            FoodImportErrorCode.RECOVERY_UNAVAILABLE,
            "Private import recovery storage is unavailable.",
        )
        val now = clock.instant()
        lateinit var restoredSnapshot: EffectiveSnapshot
        lateinit var artifact: File
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val settings = settingsLocked()
                artifact = recoveryStore.write(
                    FoodImportRollbackState(
                        settings = settings,
                        days = dao.allDays(),
                        undo = dao.allUndoActions(),
                        widgetEvents = dao.allWidgetEvents(),
                        consumedWidgetEvents = dao.allConsumedWidgetEvents(),
                    ),
                )
                beforeImportMutation()

                dao.deleteUndoActions()
                dao.deleteConsumedWidgetEvents()
                dao.deleteWidgetEvents()
                dao.deleteDays()
                plan.document.days.forEach { day ->
                    dao.upsertDay(
                        DayEntity(
                            dateKey = day.dateKey,
                            green = day.counts.green,
                            yellow = day.counts.yellow,
                            red = day.counts.red,
                            updatedAtEpochMs = day.updatedAtEpochMs,
                        ),
                    )
                }
                plan.document.undo.forEach { undo ->
                    dao.insertUndo(
                        UndoEntity(
                            dateKey = undo.dateKey,
                            color = undo.color.storageId,
                            delta = undo.delta,
                            createdAtEpochMs = undo.createdAtEpochMs,
                        ),
                    )
                }
                plan.document.consumedWidgetIds.forEach { id ->
                    dao.markWidgetEventConsumed(
                        ConsumedWidgetEventEntity(
                            eventId = id.toString(),
                            consumedAtEpochMs = now.toEpochMilli(),
                            rejected = false,
                        ),
                    )
                }
                val restoredSettings = settings.copy(
                    schemaVersion = 2,
                    selectedSkin = plan.document.selectedSkin.storageId,
                    selectedWidgetLayout = plan.document.selectedWidgetLayout.storageId,
                    widgetEpoch = settings.widgetEpoch + 1,
                    generation = settings.generation + 1,
                    lastMutationEpochMs = now.toEpochMilli(),
                )
                dao.upsertSettings(restoredSettings)
                restoredSnapshot = project(
                    days = dao.allDays(),
                    pending = emptyList(),
                    settings = restoredSettings,
                )
            }
        }
        val observersNotified = runCatching { afterCommit(StoreCommitImpact.DELETE) }.isSuccess
        return FoodImportResult(
            restoredDayCount = plan.dayCount,
            restoredUndoActionCount = plan.undoActionCount,
            restoredConsumedWidgetReceiptCount = plan.consumedWidgetReceiptCount,
            duplicateDaysCollapsed = plan.duplicateDaysCollapsed,
            snapshotRevision = restoredSnapshot.revision,
            widgetEpoch = restoredSnapshot.widgetEpoch,
            rollbackArtifactCreated = artifact.isFile,
            observersNotified = observersNotified,
        )
    }

    suspend fun restoreLatestImportRecovery(inspectedPlan: FoodImportRecoveryPlan): FoodImportResult {
        val recoveryStore = importRecoveryStore ?: throw FoodImportException(
            FoodImportErrorCode.RECOVERY_UNAVAILABLE,
            "Private import recovery storage is unavailable.",
        )
        val plan = withContext(Dispatchers.IO) { recoveryStore.verify(inspectedPlan) }
        val now = clock.instant()
        lateinit var restoredSnapshot: EffectiveSnapshot
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val currentSettings = settingsLocked()
                beforeImportMutation()
                dao.deleteUndoActions()
                dao.deleteConsumedWidgetEvents()
                dao.deleteWidgetEvents()
                dao.deleteDays()
                plan.state.days.forEach { dao.upsertDay(it) }
                plan.state.undo.forEach { dao.insertUndo(it) }
                plan.state.widgetEvents.forEach { dao.appendWidgetEvent(it) }
                plan.state.consumedWidgetEvents.forEach { dao.markWidgetEventConsumed(it) }
                val restoredSettings = plan.state.settings.copy(
                    id = SettingsEntity.SINGLETON_ID,
                    schemaVersion = 2,
                    widgetEpoch = currentSettings.widgetEpoch + 1,
                    generation = currentSettings.generation + 1,
                    lastMutationEpochMs = now.toEpochMilli(),
                )
                dao.upsertSettings(restoredSettings)
                restoredSnapshot = project(
                    days = dao.allDays(),
                    pending = dao.pendingWidgetEvents(Int.MAX_VALUE),
                    settings = restoredSettings,
                )
            }
        }
        val observersNotified = runCatching { afterCommit(StoreCommitImpact.DELETE) }.isSuccess
        return FoodImportResult(
            restoredDayCount = plan.dayCount,
            restoredUndoActionCount = plan.undoActionCount,
            restoredConsumedWidgetReceiptCount = plan.consumedWidgetReceiptCount,
            duplicateDaysCollapsed = 0,
            snapshotRevision = restoredSnapshot.revision,
            widgetEpoch = restoredSnapshot.widgetEpoch,
            rollbackArtifactCreated = true,
            observersNotified = observersNotified,
        )
    }

    fun currentDateKey(): String = FoodDateKeys.forInstant(clock.instant(), zoneProvider())

    private suspend fun reconcilePendingLocked(
        now: Instant,
        limit: Int = DEFAULT_RECONCILE_BATCH,
    ): ReconcileResult {
        val pending = dao.pendingWidgetEvents(limit)
        if (pending.isEmpty()) return ReconcileResult(0, widgetEpochRotated = false)

        pending.forEach { event ->
            val id = runCatching { UUID.fromString(event.eventId) }.getOrNull()
            val color = FoodColor.fromStorage(event.color)
            val eventInstant = Instant.ofEpochMilli(event.occurredAtEpochMs)
            val eventZone = runCatching { ZoneId.of(event.zoneId) }.getOrNull()
            val valid = id != null &&
                FoodDateKeys.isCanonical(event.dateKey) &&
                eventZone != null &&
                FoodDateKeys.forInstant(eventInstant, requireNotNull(eventZone)) == event.dateKey &&
                color != null &&
                event.delta in -1..1 &&
                event.delta != 0
            if (valid) {
                val current = dao.day(event.dateKey)?.toCounts() ?: FoodCounts()
                dao.upsertDay(current.applyDelta(requireNotNull(color), event.delta).toEntity(event.dateKey, eventInstant))
            }
            dao.markWidgetEventConsumed(
                ConsumedWidgetEventEntity(
                    eventId = event.eventId,
                    consumedAtEpochMs = now.toEpochMilli(),
                    rejected = !valid,
                ),
            )
        }
        dao.deleteReconciledWidgetEventPayloads()
        val widgetEpochRotated = dao.consumedWidgetEventCount() > MAX_CONSUMED_WIDGET_EVENTS
        if (widgetEpochRotated) {
            val settings = settingsLocked()
            // Once a receipt is pruned, invalidate every previously rendered token so the
            // removed UUID cannot be replayed. All current receipts belong to the retired
            // epoch, so clearing them together avoids rotating on every later action.
            dao.upsertSettings(settings.copy(widgetEpoch = settings.widgetEpoch + 1))
            dao.deleteConsumedWidgetEvents()
        }
        dao.trimDays(MAX_HISTORY_DAYS)
        bumpGenerationLocked(now)
        return ReconcileResult(pending.size, widgetEpochRotated)
    }

    private suspend fun settingsLocked(): SettingsEntity {
        val current = dao.settings()
        if (current != null) return current
        dao.insertInitialSettings(SettingsEntity())
        return dao.settings() ?: SettingsEntity()
    }

    private suspend fun bumpGenerationLocked(now: Instant) {
        val settings = settingsLocked()
        dao.upsertSettings(
            settings.copy(
                generation = settings.generation + 1,
                lastMutationEpochMs = now.toEpochMilli(),
            ),
        )
    }

    private fun project(
        days: List<DayEntity>,
        pending: List<WidgetEventEntity>,
        settings: SettingsEntity,
    ): EffectiveSnapshot {
        val validDays = days.filter { FoodDateKeys.isCanonical(it.dateKey) }
        val canonical = validDays.associate { it.dateKey to it.toCounts() }
        val events = pending.mapNotNull(WidgetEventEntity::toDomainOrNull)
        val effective = LedgerProjection.effectiveCounts(canonical, events, consumedIds = emptySet())
            .toSortedMap(compareByDescending<String> { it })
            .entries
            .take(MAX_HISTORY_DAYS)
            .associate { it.toPair() }
        val updatedByDay = validDays.associate { it.dateKey to it.updatedAtEpochMs }
        val pendingUpdatedByDay = events.groupBy { it.dateKey }
            .mapValues { (_, values) -> values.maxOfOrNull { it.occurredAt.toEpochMilli() } ?: 0L }
        val records = effective.map { (dateKey, counts) ->
            DayRecord(
                dateKey = dateKey,
                counts = counts,
                updatedAt = Instant.ofEpochMilli(maxOf(updatedByDay[dateKey] ?: 0L, pendingUpdatedByDay[dateKey] ?: 0L)),
            )
        }
        return EffectiveSnapshot(
            days = records,
            selectedSkin = SkinId.fromStorage(settings.selectedSkin),
            selectedWidgetLayout = WidgetLayoutId.fromStorage(settings.selectedWidgetLayout),
            revision = SnapshotRevision.forRows(effective),
            widgetEpoch = settings.widgetEpoch,
        )
    }

    companion object {
        const val MAX_HISTORY_DAYS = 180
        const val MAX_UNDO_ACTIONS = 20
        const val MAX_CONSUMED_WIDGET_EVENTS = 2_048
        const val DEFAULT_RECONCILE_BATCH = 512
        const val MAX_RECONCILE_BATCH = 2_048
    }
}

private data class ExportState(
    val snapshot: EffectiveSnapshot,
    val undo: List<UndoEntity>,
    val pending: List<WidgetEventEntity>,
    val consumed: List<ConsumedWidgetEventEntity>,
)

private fun DayEntity.toCounts(): FoodCounts = FoodCounts(green, yellow, red)

private fun FoodCounts.toEntity(dateKey: String, instant: Instant): DayEntity = DayEntity(
    dateKey = dateKey,
    green = green,
    yellow = yellow,
    red = red,
    updatedAtEpochMs = instant.toEpochMilli(),
)

private fun WidgetEventEntity.toDomainOrNull(): WidgetEvent? {
    val id = runCatching { UUID.fromString(eventId) }.getOrNull() ?: return null
    val parsedColor = FoodColor.fromStorage(color) ?: return null
    if (!FoodDateKeys.isCanonical(dateKey) || delta !in -1..1 || delta == 0) return null
    val parsedZone = runCatching { ZoneId.of(zoneId) }.getOrNull() ?: return null
    val instant = Instant.ofEpochMilli(occurredAtEpochMs)
    if (FoodDateKeys.forInstant(instant, parsedZone) != dateKey) return null
    return WidgetEvent(
        id = id,
        occurredAt = instant,
        dateKey = dateKey,
        zoneId = zoneId,
        color = parsedColor,
        delta = delta,
    )
}

internal class FoodBlobServices private constructor(
    val database: FoodBlobDatabase,
    val store: FoodStore,
    private val recoveredArtifact: File?,
) {
    val recoveredDatabase: File? get() = recoveredArtifact?.takeIf(File::exists)

    companion object {
        private const val DEFAULT_RECOVERY_DIRECTORY = "database-recovery"
        private const val PENDING_MARKER = ".pending"
        private const val COMPLETE_MARKER = ".complete"

        @Volatile private var instance: FoodBlobServices? = null

        fun get(context: Context): FoodBlobServices = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(context: Context): FoodBlobServices = createServices(
            context = context,
            databaseName = FoodBlobDatabase.DATABASE_NAME,
            recoveryDirectoryName = DEFAULT_RECOVERY_DIRECTORY,
        )

        internal fun createForTesting(
            context: Context,
            databaseName: String,
            recoveryDirectoryName: String,
        ): FoodBlobServices = createServices(
            context = context.applicationContext,
            databaseName = databaseName,
            recoveryDirectoryName = recoveryDirectoryName,
        )

        private fun createServices(
            context: Context,
            databaseName: String,
            recoveryDirectoryName: String,
        ): FoodBlobServices {
            var recovered = resumeInterruptedQuarantine(context, databaseName, recoveryDirectoryName)
                ?: latestCompletedRecovery(context, databaseName, recoveryDirectoryName)
            try {
                verifyExistingDatabase(context, databaseName)
            } catch (_: SQLiteDatabaseCorruptException) {
                recovered = quarantineDatabase(context, databaseName, recoveryDirectoryName)
            } catch (_: VerifiedDatabaseCorruptionException) {
                recovered = quarantineDatabase(context, databaseName, recoveryDirectoryName)
            } catch (error: SQLiteException) {
                if (!isVerifiedCorruption(error)) throw error
                recovered = quarantineDatabase(context, databaseName, recoveryDirectoryName)
            }
            val database = FoodBlobDatabase.create(context, databaseName)
            lateinit var store: FoodStore
            store = FoodStore(
                database = database,
                afterCommit = { impact ->
                    if (impact.updateWidgets) WidgetUpdateBridge.update(context)
                    if (impact.notifyCountReader) {
                        context.contentResolver.notifyChange(
                            android.net.Uri.parse("content://org.example.foodblob.health_export/v1/daily_counts"),
                            null,
                        )
                    }
                },
                beforeDeleteAll = {
                    cleanupRecoveryArtifacts(context, recoveryDirectoryName, databaseName)
                },
                importRecoveryStore = ImportRecoveryStore(
                    File(context.noBackupFilesDir, "import-recovery"),
                ),
            )
            return FoodBlobServices(database, store, recovered)
        }

        private fun verifyExistingDatabase(context: Context, databaseName: String) {
            val source = context.getDatabasePath(databaseName)
            if (!source.isFile) return
            val database = SQLiteDatabase.openDatabase(
                source.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
                DatabaseErrorHandler { /* Preserve the bundle; recovery is handled below. */ },
            )
            try {
                database.rawQuery("PRAGMA quick_check", null).use { cursor ->
                    if (!cursor.moveToFirst() || cursor.getString(0) != "ok") {
                        throw VerifiedDatabaseCorruptionException()
                    }
                }
            } finally {
                database.close()
            }
        }

        private fun quarantineDatabase(
            context: Context,
            databaseName: String,
            recoveryDirectoryName: String,
        ): File? {
            val source = context.getDatabasePath(databaseName)
            if (!source.exists()) return null
            val recoveryDirectory = recoveryDirectory(context, recoveryDirectoryName)
            var suffix = 0
            var bundle: File
            do {
                val collisionSuffix = if (suffix == 0) "" else "-$suffix"
                bundle = File(recoveryDirectory, "food_blob-${System.currentTimeMillis()}$collisionSuffix.bundle")
                suffix += 1
            } while (bundle.exists())
            check(bundle.mkdir()) { "Unable to create a Food Blob recovery bundle" }
            val marker = File(bundle, PENDING_MARKER)
            FileOutputStream(marker).use { output ->
                output.write("pending\n".toByteArray())
                output.fd.sync()
            }
            return completePendingBundle(context, databaseName, bundle)
        }

        internal fun resumeInterruptedQuarantine(
            context: Context,
            databaseName: String,
            recoveryDirectoryName: String,
        ): File? {
            val recoveryDirectory = File(context.noBackupFilesDir, recoveryDirectoryName)
            if (!recoveryDirectory.exists()) return null
            var recovered: File? = null
            recoveryDirectory.listFiles().orEmpty()
                .filter { it.isDirectory && isKnownBundle(it) && File(it, PENDING_MARKER).isFile }
                .sortedBy { it.name }
                .forEach { bundle -> recovered = completePendingBundle(context, databaseName, bundle) }
            return recovered
        }

        private fun completePendingBundle(
            context: Context,
            databaseName: String,
            bundle: File,
        ): File {
            val pending = File(bundle, PENDING_MARKER)
            check(pending.isFile) { "Food Blob recovery bundle is missing its pending marker" }
            val source = context.getDatabasePath(databaseName)
            listOf("-wal", "-shm", "").forEach { suffix ->
                val live = File(source.path + suffix)
                val preserved = File(bundle, databaseName + suffix)
                check(!(live.exists() && preserved.exists())) {
                    "Food Blob recovery found conflicting live and preserved files"
                }
                if (live.exists()) {
                    check(live.renameTo(preserved)) { "Unable to preserve a Food Blob database file" }
                }
            }
            val preservedMain = File(bundle, databaseName)
            check(preservedMain.isFile) { "Food Blob recovery bundle has no database" }
            val complete = File(bundle, COMPLETE_MARKER)
            check(!complete.exists()) { "Food Blob recovery bundle has conflicting markers" }
            check(pending.renameTo(complete)) { "Unable to complete a Food Blob recovery bundle" }
            return preservedMain
        }

        private fun latestCompletedRecovery(
            context: Context,
            databaseName: String,
            recoveryDirectoryName: String,
        ): File? = File(context.noBackupFilesDir, recoveryDirectoryName)
            .listFiles().orEmpty()
            .filter { it.isDirectory && isKnownBundle(it) && File(it, COMPLETE_MARKER).isFile }
            .map { File(it, databaseName) }
            .filter(File::isFile)
            .maxByOrNull(File::lastModified)

        internal fun cleanupRecoveryArtifacts(
            context: Context,
            recoveryDirectoryName: String = DEFAULT_RECOVERY_DIRECTORY,
            databaseName: String = FoodBlobDatabase.DATABASE_NAME,
        ) {
            val recoveryDirectory = File(context.noBackupFilesDir, recoveryDirectoryName)
            if (!recoveryDirectory.exists()) return
            val knownBundleContents = setOf(
                databaseName,
                "$databaseName-wal",
                "$databaseName-shm",
                PENDING_MARKER,
                COMPLETE_MARKER,
            )
            recoveryDirectory.listFiles().orEmpty()
                .filter { it.isDirectory && isKnownBundle(it) }
                .filter { bundle -> bundle.listFiles().orEmpty().all { it.isFile && it.name in knownBundleContents } }
                .forEach { bundle ->
                    bundle.listFiles().orEmpty().forEach { artifact ->
                        check(artifact.delete()) { "Unable to delete a Food Blob recovery artifact" }
                    }
                    check(bundle.delete()) { "Unable to delete a Food Blob recovery bundle" }
                }
            val knownLegacyArtifact = Regex("food_blob-[0-9]+\\.db(?:-wal|-shm)?")
            recoveryDirectory.listFiles().orEmpty()
                .filter { it.isFile && knownLegacyArtifact.matches(it.name) }
                .forEach { artifact ->
                    check(artifact.delete()) { "Unable to delete a Food Blob recovery artifact" }
                }
            recoveryDirectory.listFiles()?.takeIf { it.isEmpty() }?.let {
                check(recoveryDirectory.delete()) { "Unable to remove the empty recovery directory" }
            }
        }

        private fun recoveryDirectory(context: Context, name: String): File {
            val directory = File(context.noBackupFilesDir, name)
            check(directory.isDirectory || directory.mkdirs()) {
                "Unable to create the Food Blob recovery directory"
            }
            return directory
        }

        private fun isKnownBundle(file: File): Boolean =
            Regex("food_blob-[0-9]+(?:-[0-9]+)?\\.bundle").matches(file.name)

        internal fun isVerifiedCorruption(error: SQLiteException): Boolean {
            val messages = generateSequence<Throwable>(error) { it.cause }
                .mapNotNull(Throwable::message)
                .joinToString(" ")
                .lowercase()
            return "sqlite_notadb" in messages ||
                "file is not a database" in messages ||
                "database disk image is malformed" in messages
        }
    }
}

private class VerifiedDatabaseCorruptionException : RuntimeException()

internal object WidgetUpdateBridge {
    @Volatile var updater: suspend (Context) -> Unit = {}

    suspend fun update(context: Context) = updater(context)
}
