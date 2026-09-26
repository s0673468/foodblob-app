package org.example.foodblob.storage

import android.content.Context
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.example.foodblob.domain.FoodColor
import org.example.foodblob.domain.FoodCounts
import org.example.foodblob.domain.SkinId
import java.io.File
import java.io.RandomAccessFile
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoodStoreInstrumentedTest {
    private lateinit var database: FoodBlobDatabase
    private lateinit var store: FoodStore
    private lateinit var importRecoveryDirectory: File
    private val instant = Instant.parse("2026-08-11T12:00:00Z")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FoodBlobDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = FoodStore(
            database = database,
            clock = Clock.fixed(instant, ZoneId.of("UTC")),
            zoneProvider = { ZoneId.of("America/Sao_Paulo") },
            importRecoveryStore = ImportRecoveryStore(
                File(context.noBackupFilesDir, "food-import-test-${UUID.randomUUID()}"),
            ).also { importRecoveryDirectory = it.directory },
        )
        runBlocking { store.ensureInitialized() }
    }

    @After
    fun tearDown() {
        database.close()
        importRecoveryDirectory.deleteRecursively()
    }

    @Test
    fun duplicateWidgetUuidProjectsAndReconcilesExactlyOnce() = runBlocking {
        val eventId = UUID.fromString("00000000-0000-0000-0000-000000000001")

        assertTrue(store.appendWidgetAction(FoodColor.GREEN, 1, eventId))
        assertFalse(store.appendWidgetAction(FoodColor.GREEN, 1, eventId))
        assertEquals(FoodCounts(green = 1), store.effectiveSnapshot().counts("2026-08-11"))

        assertEquals(1, store.reconcilePending())
        assertEquals(0, store.reconcilePending())
        assertEquals(FoodCounts(green = 1), store.effectiveSnapshot().counts("2026-08-11"))
        assertFalse(store.appendWidgetAction(FoodColor.GREEN, 1, eventId))
        assertEquals(FoodCounts(green = 1), store.effectiveSnapshot().counts("2026-08-11"))
    }

    @Test
    fun undoReconcilesPendingWidgetActionsBeforeReversingLatestAppAction() = runBlocking {
        store.appendWidgetAction(FoodColor.YELLOW, 1)
        store.increment(FoodColor.RED)

        assertEquals(FoodCounts(yellow = 1, red = 1), store.effectiveSnapshot().counts("2026-08-11"))
        assertTrue(store.undo("2026-08-11"))
        assertEquals(FoodCounts(yellow = 1), store.effectiveSnapshot().counts("2026-08-11"))
    }

    @Test
    fun concurrentWidgetAppendsRemainLosslessAndDeduplicated() = runBlocking {
        val ids = (1..40).map { UUID.nameUUIDFromBytes("event-$it".toByteArray()) }

        ids.map { id -> async { store.appendWidgetAction(FoodColor.GREEN, 1, id) } }.awaitAll()
        assertEquals(40, store.effectiveSnapshot().counts("2026-08-11").green)

        store.reconcilePending()
        assertEquals(40, store.effectiveSnapshot().counts("2026-08-11").green)
    }

    @Test
    fun undoHistoryRetainsOnlyTheNewestTwentyActions() = runBlocking {
        repeat(21) { store.increment(FoodColor.GREEN) }

        repeat(20) { assertTrue(store.undo("2026-08-11")) }

        assertFalse(store.undo("2026-08-11"))
        assertEquals(1, store.effectiveSnapshot().counts("2026-08-11").green)
    }

    @Test
    fun widgetActionKeepsTheLocalDayAndZoneFromMutationTime() = runBlocking {
        val nearMidnight = Instant.parse("2026-08-12T02:30:00Z")

        store.appendWidgetAction(
            color = FoodColor.RED,
            delta = 1,
            instant = nearMidnight,
            zoneId = ZoneId.of("America/Sao_Paulo"),
        )

        assertEquals(FoodCounts(red = 1), store.effectiveSnapshot().counts("2026-08-11"))
        assertEquals(FoodCounts(), store.effectiveSnapshot().counts("2026-08-12"))
    }

    @Test
    fun malformedStoredEventIsRejectedWithoutChangingCounts() = runBlocking {
        database.dao().appendWidgetEvent(
            WidgetEventEntity(
                eventId = "not-a-uuid",
                occurredAtEpochMs = instant.toEpochMilli(),
                dateKey = "2026-08-11",
                zoneId = "UTC",
                color = "green",
                delta = 1,
            ),
        )

        assertEquals(FoodCounts(), store.effectiveSnapshot().counts("2026-08-11"))
        assertEquals(1, store.reconcilePending())
        assertEquals(FoodCounts(), store.effectiveSnapshot().counts("2026-08-11"))
    }

    @Test
    fun eventRejectedByReconciliationIsNeverShownOptimistically() = runBlocking {
        database.dao().appendWidgetEvent(
            WidgetEventEntity(
                eventId = UUID.randomUUID().toString(),
                occurredAtEpochMs = instant.toEpochMilli(),
                dateKey = "2026-08-11",
                zoneId = "not-a-zone",
                color = "yellow",
                delta = 1,
            ),
        )

        assertEquals(FoodCounts(), store.effectiveSnapshot().counts("2026-08-11"))
        assertEquals(1, store.reconcilePending())
        assertEquals(FoodCounts(), store.effectiveSnapshot().counts("2026-08-11"))
    }

    @Test
    fun eventWhoseDayDoesNotMatchItsInstantAndZoneIsRejected() = runBlocking {
        database.dao().appendWidgetEvent(
            WidgetEventEntity(
                eventId = UUID.randomUUID().toString(),
                occurredAtEpochMs = instant.toEpochMilli(),
                dateKey = "2026-08-10",
                zoneId = "UTC",
                color = "red",
                delta = 1,
            ),
        )

        assertEquals(FoodCounts(), store.effectiveSnapshot().counts("2026-08-10"))
        store.reconcilePending()
        assertEquals(FoodCounts(), store.effectiveSnapshot().counts("2026-08-10"))
    }

    @Test
    fun malformedCanonicalRowsCannotEscapeThroughTheEffectiveSnapshot() = runBlocking {
        database.dao().upsertDay(
            DayEntity(
                dateKey = "not-a-day",
                green = 9,
                yellow = 9,
                red = 9,
                updatedAtEpochMs = instant.toEpochMilli(),
            ),
        )
        database.dao().upsertDay(
            DayEntity(
                dateKey = "2026-08-11",
                green = -9,
                yellow = 2,
                red = -1,
                updatedAtEpochMs = instant.toEpochMilli(),
            ),
        )

        val snapshot = store.effectiveSnapshot()
        assertFalse(snapshot.days.any { it.dateKey == "not-a-day" })
        assertEquals(FoodCounts(yellow = 2), snapshot.counts("2026-08-11"))
    }

    @Test
    fun effectiveHistoryIsBoundedToTheNewestOneHundredEightyDays() = runBlocking {
        val first = LocalDate.parse("2026-01-01")
        repeat(181) { offset ->
            database.dao().upsertDay(
                DayEntity(
                    dateKey = first.plusDays(offset.toLong()).toString(),
                    green = 1,
                    yellow = 0,
                    red = 0,
                    updatedAtEpochMs = instant.toEpochMilli(),
                ),
            )
        }

        val snapshot = store.effectiveSnapshot()
        assertEquals(180, snapshot.days.size)
        assertFalse(snapshot.days.any { it.dateKey == "2026-01-01" })
    }

    @Test
    fun mutationOlderThanFullRetentionWindowIsRejectedWithoutUndo() = runBlocking {
        val newest = LocalDate.parse("2026-08-11")
        repeat(FoodStore.MAX_HISTORY_DAYS) { offset ->
            database.dao().upsertDay(
                DayEntity(
                    dateKey = newest.minusDays(offset.toLong()).toString(),
                    green = 1,
                    yellow = 0,
                    red = 0,
                    updatedAtEpochMs = instant.toEpochMilli(),
                ),
            )
        }
        val tooOld = newest.minusDays(FoodStore.MAX_HISTORY_DAYS.toLong()).toString()

        assertFalse(store.increment(FoodColor.RED, tooOld))

        val snapshot = store.effectiveSnapshot()
        assertEquals(FoodStore.MAX_HISTORY_DAYS, snapshot.days.size)
        assertEquals(FoodCounts(), snapshot.counts(tooOld))
        assertFalse(store.canUndo(tooOld).first())
    }

    @Test
    fun deleteAllPreservesTheSelectedSkinAndProducesAnEmptyRevision() = runBlocking {
        store.setSkin(SkinId.SHRINE)
        store.increment(FoodColor.YELLOW)

        store.deleteAll()

        val snapshot = store.effectiveSnapshot()
        assertEquals(SkinId.SHRINE, snapshot.selectedSkin)
        assertTrue(snapshot.days.isEmpty())
        assertTrue(snapshot.revision.isNotBlank())
    }

    @Test
    fun deleteAllRejectsConsumedAndNeverFiredTokensFromThePreviousWidgetEpoch() = runBlocking {
        val consumed = UUID.fromString("00000000-0000-0000-0000-000000000201")
        val neverFired = UUID.fromString("00000000-0000-0000-0000-000000000202")
        val oldEpoch = store.effectiveSnapshot().widgetEpoch

        assertTrue(store.commitWidgetAction(FoodColor.GREEN, 1, consumed, oldEpoch))
        store.deleteAll()

        assertFalse(store.commitWidgetAction(FoodColor.GREEN, 1, consumed, oldEpoch))
        assertFalse(store.commitWidgetAction(FoodColor.YELLOW, 1, neverFired, oldEpoch))
        assertTrue(store.effectiveSnapshot().days.isEmpty())

        val newEpoch = store.effectiveSnapshot().widgetEpoch
        assertTrue(newEpoch > oldEpoch)
        assertTrue(store.commitWidgetAction(FoodColor.RED, 1, UUID.randomUUID(), newEpoch))
        assertEquals(FoodCounts(red = 1), store.effectiveSnapshot().counts("2026-08-11"))
    }

    @Test
    fun widgetOnlyCommitsStayReconciledAndBounded() = runBlocking {
        val total = FoodStore.MAX_RECONCILE_BATCH * 2 + 7
        val firstEpoch = store.effectiveSnapshot().widgetEpoch
        val firstId = UUID.nameUUIDFromBytes("widget-only-0".toByteArray())
        repeat(total) { index ->
            val snapshot = store.effectiveSnapshot()
            assertTrue(
                store.commitWidgetAction(
                    FoodColor.GREEN,
                    1,
                    UUID.nameUUIDFromBytes("widget-only-$index".toByteArray()),
                    snapshot.widgetEpoch,
                ),
            )
            assertTrue(database.dao().pendingWidgetEvents(Int.MAX_VALUE).isEmpty())
        }

        assertEquals(total, store.effectiveSnapshot().counts("2026-08-11").green)
        assertTrue(database.dao().consumedWidgetEventCount() <= FoodStore.MAX_CONSUMED_WIDGET_EVENTS)
        assertTrue(store.effectiveSnapshot().widgetEpoch > firstEpoch)
        assertFalse(store.commitWidgetAction(FoodColor.GREEN, 1, firstId, firstEpoch))
    }

    @Test
    fun versionOneDatabaseMigratesWithoutLosingCountsOrSettings() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "food-blob-v1-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
            sqlite.execSQL("CREATE TABLE IF NOT EXISTS `days` (`date_key` TEXT NOT NULL, `green` INTEGER NOT NULL, `yellow` INTEGER NOT NULL, `red` INTEGER NOT NULL, `updated_at_epoch_ms` INTEGER NOT NULL, PRIMARY KEY(`date_key`))")
            sqlite.execSQL("CREATE TABLE IF NOT EXISTS `settings` (`id` INTEGER NOT NULL, `schema_version` INTEGER NOT NULL, `selected_skin` TEXT NOT NULL, `selected_widget_layout` TEXT NOT NULL, `generation` INTEGER NOT NULL, `last_mutation_epoch_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            sqlite.execSQL("CREATE TABLE IF NOT EXISTS `undo_actions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date_key` TEXT NOT NULL, `color` TEXT NOT NULL, `delta` INTEGER NOT NULL, `created_at_epoch_ms` INTEGER NOT NULL)")
            sqlite.execSQL("CREATE INDEX IF NOT EXISTS `index_undo_actions_date_key_id` ON `undo_actions` (`date_key`, `id`)")
            sqlite.execSQL("CREATE TABLE IF NOT EXISTS `widget_events` (`sequence` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `event_id` TEXT NOT NULL, `occurred_at_epoch_ms` INTEGER NOT NULL, `date_key` TEXT NOT NULL, `zone_id` TEXT NOT NULL, `color` TEXT NOT NULL, `delta` INTEGER NOT NULL)")
            sqlite.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_widget_events_event_id` ON `widget_events` (`event_id`)")
            sqlite.execSQL("CREATE INDEX IF NOT EXISTS `index_widget_events_date_key_sequence` ON `widget_events` (`date_key`, `sequence`)")
            sqlite.execSQL("CREATE TABLE IF NOT EXISTS `consumed_widget_events` (`event_id` TEXT NOT NULL, `consumed_at_epoch_ms` INTEGER NOT NULL, `rejected` INTEGER NOT NULL, PRIMARY KEY(`event_id`))")
            sqlite.execSQL("INSERT INTO days VALUES ('2026-08-11', 2, 1, 0, ${instant.toEpochMilli()})")
            sqlite.execSQL("INSERT INTO settings VALUES (1, 1, 'shrine', 'bubble_stack', 4, ${instant.toEpochMilli()})")
            sqlite.version = 1
        }

        val migrated = FoodBlobDatabase.create(context, name)
        try {
            val migratedStore = FoodStore(migrated, Clock.fixed(instant, ZoneId.of("UTC")), { ZoneId.of("UTC") })
            val snapshot = migratedStore.effectiveSnapshot()
            assertEquals(FoodCounts(green = 2, yellow = 1), snapshot.counts("2026-08-11"))
            assertEquals(SkinId.SHRINE, snapshot.selectedSkin)
            assertEquals(0L, snapshot.widgetEpoch)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun deleteAllRunsRecoveryCleanupBeforeRemovingActiveState() = runBlocking {
        var cleanupCalled = false
        val protectedStore = FoodStore(
            database = database,
            clock = Clock.fixed(instant, ZoneId.of("UTC")),
            zoneProvider = { ZoneId.of("UTC") },
            beforeDeleteAll = { cleanupCalled = true },
        )
        protectedStore.increment(FoodColor.GREEN)

        protectedStore.deleteAll()

        assertTrue(cleanupCalled)
        assertTrue(protectedStore.effectiveSnapshot().days.isEmpty())
    }

    @Test
    fun widgetRefreshesOnlyForTodayCountsAndDeletion() = runBlocking {
        val impacts = mutableListOf<StoreCommitImpact>()
        val trackedStore = FoodStore(
            database = database,
            clock = Clock.fixed(instant, ZoneId.of("UTC")),
            zoneProvider = { ZoneId.of("UTC") },
            afterCommit = { impacts += it },
        )

        trackedStore.increment(FoodColor.GREEN, "2026-08-10")
        trackedStore.increment(FoodColor.GREEN, "2026-08-11")
        trackedStore.setSkin(SkinId.SHRINE)
        trackedStore.deleteAll()

        assertEquals(
            listOf(
                StoreCommitImpact(updateWidgets = false, notifyCountReader = true),
                StoreCommitImpact(updateWidgets = true, notifyCountReader = true),
                StoreCommitImpact.APPEARANCE,
                StoreCommitImpact.DELETE,
            ),
            impacts,
        )
    }

    @Test
    fun exportIsAReadableVersionedAbsoluteStateDocument() = runBlocking {
        store.setSkin(SkinId.SHRINE)
        store.increment(FoodColor.GREEN)

        val exported = JSONObject(store.exportJson())

        assertEquals(1, exported.getInt("schema_version"))
        assertEquals("shrine", exported.getString("selected_skin"))
        assertEquals("bubble_stack", exported.getString("selected_widget_layout"))
        assertEquals(1, exported.getJSONArray("days").length())
        assertEquals(1, exported.getJSONArray("days").getJSONObject(0).getInt("green"))
        assertEquals(1, exported.getJSONArray("undo_stack").length())
        assertEquals(0, exported.getJSONArray("pending_widget_ids").length())
        assertEquals(0, exported.getJSONArray("consumed_widget_ids").length())
    }

    @Test
    fun exportInspectRestoreRoundTripIsAtomicAndRotatesTheWidgetEpoch() = runBlocking {
        store.setSkin(SkinId.SHRINE)
        store.increment(FoodColor.GREEN, "2026-08-10")
        val epochBeforeExport = store.effectiveSnapshot().widgetEpoch
        store.commitWidgetAction(
            FoodColor.YELLOW,
            1,
            UUID.fromString("00000000-0000-0000-0000-000000000301"),
            epochBeforeExport,
            instant,
            ZoneId.of("America/Sao_Paulo"),
        )
        val exported = store.exportJson()
        val expected = store.effectiveSnapshot()
        store.increment(FoodColor.RED, "2026-08-11")

        val plan = store.inspectImport(exported)
        val result = store.restoreImport(exported, plan)
        val restored = store.effectiveSnapshot()

        assertEquals(2, plan.dayCount)
        assertEquals(expected.days, restored.days)
        assertEquals(expected.selectedSkin, restored.selectedSkin)
        assertEquals(expected.selectedWidgetLayout, restored.selectedWidgetLayout)
        assertNotEquals(epochBeforeExport, restored.widgetEpoch)
        assertEquals(restored.revision, result.snapshotRevision)
        assertTrue(result.rollbackArtifactCreated)
        val artifact = importRecoveryDirectory.listFiles().orEmpty().single()
        assertTrue(artifact.isFile)
        assertEquals(0x180, android.system.Os.stat(artifact.path).st_mode and 0x1FF)
        assertEquals(0x1C0, android.system.Os.stat(importRecoveryDirectory.path).st_mode and 0x1FF)
    }

    @Test
    fun unsafePendingImportAndCommitFailureNeverReplaceLiveState() = runBlocking {
        store.increment(FoodColor.RED, "2026-08-11")
        val original = store.effectiveSnapshot()
        store.appendWidgetAction(
            FoodColor.YELLOW,
            1,
            UUID.fromString("00000000-0000-0000-0000-000000000302"),
        )
        val pendingExport = store.exportJson()

        val parseError = assertThrows(FoodImportException::class.java) {
            store.inspectImport(pendingExport)
        }
        assertEquals(FoodImportErrorCode.UNSAFE_PENDING_WIDGET_ACTIONS, parseError.code)
        assertEquals(FoodCounts(red = 1, yellow = 1), store.effectiveSnapshot().counts("2026-08-11"))

        store.reconcilePending()
        val safePlan = store.inspectImport(store.exportJson())
        val failingStore = FoodStore(
            database = database,
            clock = Clock.fixed(instant, ZoneId.of("UTC")),
            zoneProvider = { ZoneId.of("UTC") },
            importRecoveryStore = ImportRecoveryStore(importRecoveryDirectory),
            beforeImportMutation = { error("synthetic import failure") },
        )
        val beforeFailure = failingStore.effectiveSnapshot()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { failingStore.restoreImport(failingStore.exportJson(), safePlan) }
        }
        assertEquals(beforeFailure, failingStore.effectiveSnapshot())
        assertNotNull(importRecoveryDirectory.listFiles().orEmpty().singleOrNull())
        assertNotEquals(original.revision, beforeFailure.revision)
    }

    @Test
    fun restoreRejectsAPreviewFromAnotherPayloadBeforeCreatingRecovery() = runBlocking {
        store.increment(FoodColor.GREEN, "2026-08-11")
        val firstPayload = store.exportJson()
        val firstPlan = store.inspectImport(firstPayload)
        store.increment(FoodColor.RED, "2026-08-11")
        val secondPayload = store.exportJson()
        val liveBeforeAttempt = store.effectiveSnapshot()

        val error = assertThrows(FoodImportException::class.java) {
            runBlocking { store.restoreImport(secondPayload, firstPlan) }
        }

        assertEquals(FoodImportErrorCode.STALE_OR_TAMPERED_PLAN, error.code)
        assertEquals(liveBeforeAttempt, store.effectiveSnapshot())
        assertFalse(importRecoveryDirectory.exists())
    }

    @Test
    fun restoreRejectsAStaleRecoveryPreviewWithoutChangingLiveState() = runBlocking {
        store.increment(FoodColor.GREEN, "2026-08-11")
        val firstPayload = store.exportJson()
        store.increment(FoodColor.RED, "2026-08-11")
        store.restoreImport(firstPayload, store.inspectImport(firstPayload))
        val staleRecoveryPlan = requireNotNull(store.inspectLatestImportRecovery())

        store.increment(FoodColor.YELLOW, "2026-08-11")
        val secondPayload = store.exportJson()
        store.restoreImport(secondPayload, store.inspectImport(secondPayload))
        val liveBeforeAttempt = store.effectiveSnapshot()

        val error = assertThrows(FoodImportException::class.java) {
            runBlocking { store.restoreLatestImportRecovery(staleRecoveryPlan) }
        }

        assertEquals(FoodImportErrorCode.STALE_OR_TAMPERED_PLAN, error.code)
        assertEquals(liveBeforeAttempt, store.effectiveSnapshot())
    }

    @Test
    fun rollbackArtifactCapturesPendingPayloadBeforeRestoreClearsIt() = runBlocking {
        val pendingId = UUID.fromString("00000000-0000-0000-0000-000000000303")
        store.increment(FoodColor.GREEN, "2026-08-11")
        val safeExport = store.exportJson()
        val safePlan = store.inspectImport(safeExport)
        store.appendWidgetAction(FoodColor.RED, 1, pendingId, instant, ZoneId.of("UTC"))

        store.restoreImport(safeExport, safePlan)

        val artifact = importRecoveryDirectory.listFiles().orEmpty().single()
        val contents = artifact.readText()
        assertTrue(contents.contains(pendingId.toString()))
        assertTrue(contents.contains("\"zone_id\":\"UTC\""))
        assertTrue(database.dao().pendingWidgetEvents(Int.MAX_VALUE).isEmpty())

        val recoveryPlan = requireNotNull(store.inspectLatestImportRecovery())
        assertEquals(1, recoveryPlan.pendingWidgetActionCount)
        store.restoreLatestImportRecovery(recoveryPlan)
        assertEquals(FoodCounts(green = 1, red = 1), store.effectiveSnapshot().counts("2026-08-11"))
        assertEquals(1, database.dao().pendingWidgetEvents(Int.MAX_VALUE).size)
        assertEquals(1, store.reconcilePending())
        assertEquals(FoodCounts(green = 1, red = 1), store.effectiveSnapshot().counts("2026-08-11"))
    }

    @Test
    fun corruptRollbackArtifactFailsClosedAndDeleteAllRemovesPrivateRecovery() = runBlocking {
        store.increment(FoodColor.RED, "2026-08-11")
        val exported = store.exportJson()
        store.restoreImport(exported, store.inspectImport(exported))
        val beforeCorruption = store.effectiveSnapshot()
        val artifact = importRecoveryDirectory.listFiles().orEmpty().single()
        artifact.writeText("{\"rollback_schema_version\":1")

        val error = assertThrows(FoodImportException::class.java) {
            store.inspectLatestImportRecovery()
        }
        assertEquals(FoodImportErrorCode.INVALID_ROLLBACK_ARTIFACT, error.code)
        assertEquals(beforeCorruption, store.effectiveSnapshot())

        store.deleteAll()
        assertFalse(artifact.exists())
        assertFalse(importRecoveryDirectory.exists())
    }

    @Test
    fun restoredCalendarKeysRemainExactAcrossTimezoneAndProcessReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "food-blob-import-reopen-${UUID.randomUUID()}.db"
        val recovery = File(context.noBackupFilesDir, "food-import-reopen-${UUID.randomUUID()}")
        val payload = """
            {
              "schema_version":1,
              "selected_skin":"sky_meadow",
              "selected_widget_layout":"bubble_stack",
              "days":[{"date":"2026-08-11","green":2,"yellow":0,"red":1,"updated_at_epoch_ms":1786449600000}],
              "undo_stack":[],
              "pending_widget_ids":[],
              "consumed_widget_ids":[]
            }
        """.trimIndent()
        var onDisk = FoodBlobDatabase.create(context, name)
        try {
            var onDiskStore = FoodStore(
                onDisk,
                Clock.fixed(Instant.parse("2026-08-12T00:30:00Z"), ZoneId.of("UTC")),
                { ZoneId.of("Asia/Tokyo") },
                importRecoveryStore = ImportRecoveryStore(recovery),
            )
            onDiskStore.ensureInitialized()
            onDiskStore.restoreImport(payload, onDiskStore.inspectImport(payload))
            onDisk.close()

            onDisk = FoodBlobDatabase.create(context, name)
            onDiskStore = FoodStore(onDisk, zoneProvider = { ZoneId.of("America/Sao_Paulo") })
            assertEquals(FoodCounts(green = 2, red = 1), onDiskStore.effectiveSnapshot().counts("2026-08-11"))
            assertEquals(FoodCounts(), onDiskStore.effectiveSnapshot().counts("2026-08-12"))

            onDisk.close()
            onDisk = FoodBlobDatabase.create(context, name)
            onDiskStore = FoodStore(
                onDisk,
                zoneProvider = { ZoneId.of("America/Sao_Paulo") },
                importRecoveryStore = ImportRecoveryStore(recovery),
            )
            val recoveryPlan = requireNotNull(onDiskStore.inspectLatestImportRecovery())
            onDiskStore.restoreLatestImportRecovery(recoveryPlan)
            assertTrue(onDiskStore.effectiveSnapshot().days.isEmpty())
        } finally {
            onDisk.close()
            context.deleteDatabase(name)
            recovery.deleteRecursively()
        }
    }

    @Test
    fun readOnlySnapshotDoesNotCreateSettingsInAFreshStore() = runBlocking {
        database.dao().deleteUndoActions()
        database.dao().deleteConsumedWidgetEvents()
        database.dao().deleteWidgetEvents()
        database.dao().deleteDays()
        database.clearAllTables()
        assertEquals(null, database.dao().settings())

        store.effectiveSnapshot()

        assertEquals(null, database.dao().settings())
    }

    @Test
    fun snapshotFlowNeverDoubleCountsDuringWidgetReconciliation() = runBlocking {
        val observed = mutableListOf<Int>()
        val collector = launch { store.snapshots.collect { observed += it.counts("2026-08-11").green } }
        delay(50)
        store.appendWidgetAction(FoodColor.GREEN, 1)
        store.reconcilePending()
        delay(100)
        collector.cancel()

        assertTrue(observed.isNotEmpty())
        assertTrue(observed.all { it in 0..1 })
        assertEquals(1, observed.last())
    }

    @Test
    fun widgetIdempotencySurvivesDatabaseCloseAndReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "food-blob-reopen-${UUID.randomUUID()}.db"
        val eventId = UUID.fromString("00000000-0000-0000-0000-000000000099")
        fun open() = Room.databaseBuilder(context, FoodBlobDatabase::class.java, name).build()
        var onDisk = open()
        try {
            var onDiskStore = FoodStore(onDisk, Clock.fixed(instant, ZoneId.of("UTC")), { ZoneId.of("UTC") })
            onDiskStore.ensureInitialized()
            assertTrue(onDiskStore.appendWidgetAction(FoodColor.RED, 1, eventId))
            onDisk.close()

            onDisk = open()
            onDiskStore = FoodStore(onDisk, Clock.fixed(instant, ZoneId.of("UTC")), { ZoneId.of("UTC") })
            assertEquals(1, onDiskStore.effectiveSnapshot().counts("2026-08-11").red)
            assertEquals(1, onDiskStore.reconcilePending())
            onDisk.close()

            onDisk = open()
            onDiskStore = FoodStore(onDisk, Clock.fixed(instant, ZoneId.of("UTC")), { ZoneId.of("UTC") })
            assertFalse(onDiskStore.appendWidgetAction(FoodColor.RED, 1, eventId))
            assertEquals(1, onDiskStore.effectiveSnapshot().counts("2026-08-11").red)
        } finally {
            onDisk.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun consumedWidgetReceiptsStayBoundedAfterADeviceLifetimeSizedBurst() = runBlocking {
        val total = FoodStore.MAX_CONSUMED_WIDGET_EVENTS + 7
        repeat(total) { index ->
            database.dao().appendWidgetEvent(
                WidgetEventEntity(
                    eventId = UUID.nameUUIDFromBytes("bounded-$index".toByteArray()).toString(),
                    occurredAtEpochMs = instant.toEpochMilli(),
                    dateKey = "2026-08-11",
                    zoneId = "UTC",
                    color = FoodColor.GREEN.storageId,
                    delta = 1,
                ),
            )
        }

        while (store.reconcilePending(FoodStore.MAX_RECONCILE_BATCH) > 0) Unit

        assertTrue(database.dao().consumedWidgetEventCount() <= FoodStore.MAX_CONSUMED_WIDGET_EVENTS)
        assertTrue(
            JSONObject(store.exportJson()).getJSONArray("consumed_widget_ids").length() <=
                FoodStore.MAX_CONSUMED_WIDGET_EVENTS,
        )
        assertEquals(total, store.effectiveSnapshot().counts("2026-08-11").green)
    }

    @Test
    fun receiptCompactionRefreshesWidgetsEvenWhenOnlyHistoricalEventsWereReconciled() = runBlocking {
        val impacts = mutableListOf<StoreCommitImpact>()
        val trackedStore = FoodStore(
            database = database,
            clock = Clock.fixed(instant, ZoneId.of("UTC")),
            zoneProvider = { ZoneId.of("UTC") },
            afterCommit = { impacts += it },
        )
        repeat(FoodStore.MAX_CONSUMED_WIDGET_EVENTS + 1) { index ->
            database.dao().markWidgetEventConsumed(
                ConsumedWidgetEventEntity(
                    eventId = UUID.nameUUIDFromBytes("old-receipt-$index".toByteArray()).toString(),
                    consumedAtEpochMs = instant.minusSeconds(86_400).toEpochMilli(),
                    rejected = false,
                ),
            )
        }
        database.dao().appendWidgetEvent(
            WidgetEventEntity(
                eventId = UUID.nameUUIDFromBytes("historical-reconcile".toByteArray()).toString(),
                occurredAtEpochMs = instant.minusSeconds(86_400).toEpochMilli(),
                dateKey = "2026-08-10",
                zoneId = "UTC",
                color = FoodColor.GREEN.storageId,
                delta = 1,
            ),
        )
        val oldEpoch = trackedStore.effectiveSnapshot().widgetEpoch

        assertEquals(1, trackedStore.reconcilePending())

        assertTrue(trackedStore.effectiveSnapshot().widgetEpoch > oldEpoch)
        assertEquals(
            listOf(StoreCommitImpact(updateWidgets = true, notifyCountReader = true)),
            impacts,
        )
    }

    @Test
    fun corruptOnDiskDatabaseIsPreservedAsABundleBeforeStartingFresh() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "food-blob-corrupt-${UUID.randomUUID()}.db"
        val recoveryName = "database-recovery-${UUID.randomUUID()}"
        context.deleteDatabase(name)
        var onDisk = FoodBlobDatabase.create(context, name)
        FoodStore(onDisk).ensureInitialized()
        onDisk.close()
        RandomAccessFile(context.getDatabasePath(name), "rw").use { file ->
            file.seek(0)
            file.write(ByteArray(32) { 0x5A })
            file.fd.sync()
        }

        val services = FoodBlobServices.createForTesting(context, name, recoveryName)
        try {
            services.store.ensureInitialized()
            val recovered = services.recoveredDatabase
            assertTrue(recovered?.isFile == true)
            assertTrue(requireNotNull(recovered).parentFile?.name?.endsWith(".bundle") == true)
            assertTrue(File(requireNotNull(recovered).parentFile, ".complete").isFile)
            assertTrue(services.store.effectiveSnapshot().days.isEmpty())
        } finally {
            services.database.close()
            context.deleteDatabase(name)
            FoodBlobServices.cleanupRecoveryArtifacts(context, recoveryName, name)
        }
    }

    @Test
    fun interruptedDatabaseBundleMoveResumesBeforeRoomCanOpenTheLivePath() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "food-blob-interrupted-${UUID.randomUUID()}.db"
        val recoveryName = "database-recovery-${UUID.randomUUID()}"
        val source = context.getDatabasePath(name).apply {
            parentFile?.mkdirs()
            writeText("live-main")
        }
        File(source.path + "-wal").writeText("live-wal")
        File(source.path + "-shm").writeText("live-shm")
        val bundle = File(context.noBackupFilesDir, "$recoveryName/food_blob-123.bundle").apply { mkdirs() }
        val recoveredMain = File(bundle, name)
        assertTrue(source.renameTo(recoveredMain))
        File(bundle, ".pending").writeText("pending")

        try {
            val recovered = FoodBlobServices.resumeInterruptedQuarantine(context, name, recoveryName)

            assertEquals(recoveredMain, recovered)
            assertFalse(source.exists())
            assertFalse(File(source.path + "-wal").exists())
            assertFalse(File(source.path + "-shm").exists())
            assertEquals("live-wal", File(bundle, "$name-wal").readText())
            assertEquals("live-shm", File(bundle, "$name-shm").readText())
            assertTrue(File(bundle, ".complete").isFile)
            assertFalse(File(bundle, ".pending").exists())
        } finally {
            context.deleteDatabase(name)
            FoodBlobServices.cleanupRecoveryArtifacts(context, recoveryName, name)
        }
    }

    @Test
    fun ambiguousInterruptedRecoveryFailsClosedWithoutOverwritingEitherMainFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "food-blob-conflict-${UUID.randomUUID()}.db"
        val recoveryName = "database-recovery-${UUID.randomUUID()}"
        val source = context.getDatabasePath(name).apply {
            parentFile?.mkdirs()
            writeText("live")
        }
        val bundle = File(context.noBackupFilesDir, "$recoveryName/food_blob-456.bundle").apply { mkdirs() }
        val recoveredMain = File(bundle, name).apply { writeText("recovered") }
        File(bundle, ".pending").writeText("pending")

        try {
            val failure = runCatching {
                FoodBlobServices.resumeInterruptedQuarantine(context, name, recoveryName)
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("live", source.readText())
            assertEquals("recovered", recoveredMain.readText())
        } finally {
            source.delete()
            FoodBlobServices.cleanupRecoveryArtifacts(context, recoveryName, name)
        }
    }

    @Test
    fun nonCorruptionSqliteFailuresRemainFailClosed() {
        assertFalse(
            FoodBlobServices.isVerifiedCorruption(
                SQLiteException("disk I/O error"),
            ),
        )
        assertTrue(
            FoodBlobServices.isVerifiedCorruption(
                SQLiteException("database disk image is malformed"),
            ),
        )
    }

    @Test
    fun cleanupDeletesOnlyKnownRecoveryArtifacts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = java.io.File(context.noBackupFilesDir, "database-recovery").apply { mkdirs() }
        val known = java.io.File(directory, "food_blob-123456.db-wal").apply { writeText("test") }
        val unrelated = java.io.File(directory, "keep-me.txt").apply { writeText("test") }
        try {
            FoodBlobServices.cleanupRecoveryArtifacts(context)
            assertFalse(known.exists())
            assertTrue(unrelated.exists())
        } finally {
            unrelated.delete()
            directory.delete()
        }
    }

    @Test
    fun deleteImportRecoveryRemovesOnlyKnownInterruptedTemporaryFiles() {
        val recoveryStore = ImportRecoveryStore(importRecoveryDirectory)
        importRecoveryDirectory.mkdirs()
        val interrupted = File(
            importRecoveryDirectory,
            "latest-import.rollback.json.12345.tmp",
        ).apply { writeText("private interrupted payload") }
        val unrelated = File(importRecoveryDirectory, "keep-me.txt").apply { writeText("unrelated") }

        recoveryStore.delete()

        assertFalse(interrupted.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun benchmarkInteractionSequenceRestoresTheExistingCount() = runBlocking {
        repeat(3) { store.increment(FoodColor.GREEN) }
        val before = store.effectiveSnapshot().counts("2026-08-11")

        store.increment(FoodColor.GREEN)
        store.decrement(FoodColor.GREEN)
        store.undo("2026-08-11")
        store.undo("2026-08-11")

        assertEquals(before, store.effectiveSnapshot().counts("2026-08-11"))
    }
}
