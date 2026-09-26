package org.example.foodblob.integration

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Bundle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.example.foodblob.domain.DayRecord
import org.example.foodblob.domain.FoodColor
import org.example.foodblob.domain.FoodCounts
import org.example.foodblob.domain.SkinId
import org.example.foodblob.domain.WidgetLayoutId
import org.example.foodblob.storage.EffectiveSnapshot
import org.example.foodblob.storage.FoodBlobDatabase
import org.example.foodblob.storage.FoodStore
import java.io.FileNotFoundException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthExportProviderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun manifestProtectsTheExactExportWithASignaturePermission() {
        val packageManager = context.packageManager
        val permission = packageManager.getPermissionInfo(HealthExportProvider.READ_PERMISSION, 0)
        assertEquals(
            PermissionInfo.PROTECTION_SIGNATURE,
            permission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE,
        )

        val provider = requireNotNull(
            packageManager.resolveContentProvider(
                HealthExportProvider.AUTHORITY,
                PackageManager.ComponentInfoFlags.of(0),
            ),
        )
        assertTrue(provider.exported)
        assertFalse(provider.grantUriPermissions)
        assertEquals(HealthExportProvider.READ_PERMISSION, provider.readPermission)
        assertNull(provider.writePermission)
    }

    @Test
    fun queryReturnsOneAbsoluteSnapshotWithRequiredExtras() {
        val generatedAt = 1_786_446_000_000L
        val provider = provider(
            snapshot = snapshot(
                revision = "opaque-revision",
                days = listOf(
                    day("2026-08-11", green = 2, yellow = 1, red = 0),
                    day("2026-08-10", green = 0, yellow = 3, red = 1),
                ),
            ),
            generatedAt = generatedAt,
        )

        provider.queryExact().use { cursor ->
            assertArrayEquals(HealthExportProvider.COLUMNS, cursor.columnNames)
            assertEquals(2, cursor.count)
            assertEquals(
                HealthExportProvider.SCHEMA_VERSION,
                cursor.extras.getInt("schema_version"),
            )
            assertEquals("opaque-revision", cursor.extras.getString("snapshot_revision"))
            assertEquals(generatedAt, cursor.extras.getLong("generated_at_epoch_ms"))

            assertTrue(cursor.moveToFirst())
            assertEquals("2026-08-10", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(3, cursor.getInt(2))
            assertEquals(1, cursor.getInt(3))
            assertTrue(cursor.moveToNext())
            assertEquals("2026-08-11", cursor.getString(0))
            assertEquals(2, cursor.getInt(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals(0, cursor.getInt(3))
        }
    }

    @Test
    fun validEmptySnapshotStillCarriesTheVersionedEnvelope() {
        val provider = provider(snapshot(revision = "empty-revision", days = emptyList()))

        provider.queryExact().use { cursor ->
            assertEquals(0, cursor.count)
            assertEquals(1, cursor.extras.getInt(HealthExportProvider.EXTRA_SCHEMA_VERSION))
            assertEquals(
                "empty-revision",
                cursor.extras.getString(HealthExportProvider.EXTRA_SNAPSHOT_REVISION),
            )
            assertTrue(cursor.extras.containsKey(HealthExportProvider.EXTRA_GENERATED_AT_EPOCH_MS))
        }
    }

    @Test
    fun nullProjectionReturnsCanonicalColumns() {
        val provider = provider(snapshot(revision = "revision", days = emptyList()))

        provider.query(HealthExportProvider.CONTENT_URI, null, null, null, null).use { cursor ->
            assertArrayEquals(HealthExportProvider.COLUMNS, cursor.columnNames)
        }
    }

    @Test
    fun queryRejectsEveryShapeOutsideTheExactContract() {
        val provider = provider(snapshot(revision = "revision", days = emptyList()))

        assertThrows(IllegalArgumentException::class.java) {
            provider.query(
                Uri.parse("content://${HealthExportProvider.AUTHORITY}/v1/other"),
                HealthExportProvider.COLUMNS,
                null,
                null,
                null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.query(
                HealthExportProvider.CONTENT_URI,
                arrayOf("date", "red", "yellow", "green"),
                null,
                null,
                null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.query(
                HealthExportProvider.CONTENT_URI,
                HealthExportProvider.COLUMNS,
                "green > 0",
                null,
                null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.query(
                HealthExportProvider.CONTENT_URI,
                HealthExportProvider.COLUMNS,
                null,
                emptyArray(),
                null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.query(
                HealthExportProvider.CONTENT_URI,
                HealthExportProvider.COLUMNS,
                null,
                null,
                "date DESC",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.query(
                HealthExportProvider.CONTENT_URI,
                HealthExportProvider.COLUMNS,
                Bundle().apply { putInt("limit", 1) },
                null,
            )
        }
    }

    @Test
    fun everyNonQuerySurfaceIsRejected() {
        val provider = provider(snapshot(revision = "revision", days = emptyList()))

        assertThrows(UnsupportedOperationException::class.java) {
            provider.insert(HealthExportProvider.CONTENT_URI, ContentValues())
        }
        assertThrows(UnsupportedOperationException::class.java) {
            provider.bulkInsert(HealthExportProvider.CONTENT_URI, arrayOf(ContentValues()))
        }
        assertThrows(UnsupportedOperationException::class.java) {
            provider.update(HealthExportProvider.CONTENT_URI, ContentValues(), null, null)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            provider.delete(HealthExportProvider.CONTENT_URI, null, null)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            provider.applyBatch(arrayListOf<ContentProviderOperation>())
        }
        assertThrows(UnsupportedOperationException::class.java) {
            provider.applyBatch(
                HealthExportProvider.AUTHORITY,
                arrayListOf<ContentProviderOperation>(),
            )
        }
        assertThrows(UnsupportedOperationException::class.java) {
            provider.call("anything", null, null)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            provider.call(HealthExportProvider.AUTHORITY, "anything", null, null)
        }
        assertThrows(FileNotFoundException::class.java) {
            provider.openFile(HealthExportProvider.CONTENT_URI, "r")
        }
    }

    @Test
    fun readsOverlayPendingWidgetEventsWithoutConsumingOrDoubleApplyingThem() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, FoodBlobDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val instant = Instant.parse("2026-08-11T12:00:00Z")
            val store = FoodStore(
                database = database,
                clock = Clock.fixed(instant, ZoneId.of("UTC")),
                zoneProvider = { ZoneId.of("UTC") },
            )
            store.ensureInitialized()
            assertTrue(store.increment(FoodColor.GREEN, "2026-08-11"))
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000011")
            assertTrue(
                store.appendWidgetAction(FoodColor.YELLOW, 1, eventId, instant, ZoneId.of("UTC")),
            )
            assertFalse(
                store.appendWidgetAction(FoodColor.YELLOW, 1, eventId, instant, ZoneId.of("UTC")),
            )

            val provider = provider(snapshotReader = { store.effectiveSnapshot() })
            val first = provider.readSingleRow()
            val second = provider.readSingleRow()

            assertEquals(ExportedRow("2026-08-11", 1, 1, 0), first.row)
            assertEquals(first, second)
            assertEquals(1, database.dao().pendingWidgetEvents(Int.MAX_VALUE).size)

            assertEquals(1, store.reconcilePending())
            assertTrue(database.dao().pendingWidgetEvents(Int.MAX_VALUE).isEmpty())
            assertEquals(first, provider.readSingleRow())
        } finally {
            database.close()
        }
    }

    private fun provider(
        snapshot: EffectiveSnapshot? = null,
        generatedAt: Long = 1_786_446_000_000L,
        snapshotReader: (suspend () -> EffectiveSnapshot)? = null,
    ): HealthExportProvider {
        val resolvedReader = snapshotReader ?: { requireNotNull(snapshot) }
        return object : HealthExportProvider() {
            override suspend fun readSnapshot(context: Context): EffectiveSnapshot = resolvedReader()

            override fun generatedAtEpochMs(): Long = generatedAt
        }.also { provider ->
            provider.attachInfo(
                context,
                ProviderInfo().apply {
                    authority = HealthExportProvider.AUTHORITY
                    exported = true
                    readPermission = HealthExportProvider.READ_PERMISSION
                },
            )
        }
    }

    private fun HealthExportProvider.queryExact() = requireNotNull(
        query(HealthExportProvider.CONTENT_URI, HealthExportProvider.COLUMNS, null, null, null),
    )

    private fun HealthExportProvider.readSingleRow(): ExportedSnapshot = queryExact().use { cursor ->
        assertTrue(cursor.moveToFirst())
        val row = ExportedRow(
            date = cursor.getString(0),
            green = cursor.getInt(1),
            yellow = cursor.getInt(2),
            red = cursor.getInt(3),
        )
        assertFalse(cursor.moveToNext())
        ExportedSnapshot(
            row = row,
            revision = requireNotNull(
                cursor.extras.getString(HealthExportProvider.EXTRA_SNAPSHOT_REVISION),
            ),
        )
    }

    private fun snapshot(revision: String, days: List<DayRecord>): EffectiveSnapshot = EffectiveSnapshot(
        days = days,
        selectedSkin = SkinId.SKY_MEADOW,
        selectedWidgetLayout = WidgetLayoutId.BUBBLE_STACK,
        revision = revision,
        widgetEpoch = 0,
    )

    private fun day(date: String, green: Int, yellow: Int, red: Int): DayRecord = DayRecord(
        dateKey = date,
        counts = FoodCounts(green, yellow, red),
        updatedAt = Instant.parse("2026-08-11T12:00:00Z"),
    )

    private data class ExportedRow(
        val date: String,
        val green: Int,
        val yellow: Int,
        val red: Int,
    )

    private data class ExportedSnapshot(val row: ExportedRow, val revision: String)
}
