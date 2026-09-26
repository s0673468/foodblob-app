package org.example.foodblob.storage

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "days")
internal data class DayEntity(
    @PrimaryKey @ColumnInfo(name = "date_key") val dateKey: String,
    val green: Int,
    val yellow: Int,
    val red: Int,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

@Entity(tableName = "settings")
internal data class SettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int = 2,
    @ColumnInfo(name = "selected_skin") val selectedSkin: String = "sky_meadow",
    @ColumnInfo(name = "selected_widget_layout") val selectedWidgetLayout: String = "bubble_stack",
    val generation: Long = 0,
    @ColumnInfo(name = "last_mutation_epoch_ms") val lastMutationEpochMs: Long = 0,
    @ColumnInfo(name = "widget_epoch") val widgetEpoch: Long = 0,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(
    tableName = "undo_actions",
    indices = [Index(value = ["date_key", "id"])],
)
internal data class UndoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "date_key") val dateKey: String,
    val color: String,
    val delta: Int,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
)

@Entity(
    tableName = "widget_events",
    indices = [
        Index(value = ["event_id"], unique = true),
        Index(value = ["date_key", "sequence"]),
    ],
)
internal data class WidgetEventEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "occurred_at_epoch_ms") val occurredAtEpochMs: Long,
    @ColumnInfo(name = "date_key") val dateKey: String,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    val color: String,
    val delta: Int,
)

@Entity(tableName = "consumed_widget_events")
internal data class ConsumedWidgetEventEntity(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "consumed_at_epoch_ms") val consumedAtEpochMs: Long,
    val rejected: Boolean,
)

@Dao
internal interface FoodBlobDao {
    @Query("SELECT * FROM days ORDER BY date_key DESC")
    fun observeDays(): Flow<List<DayEntity>>

    @Query("SELECT * FROM days ORDER BY date_key DESC")
    suspend fun allDays(): List<DayEntity>

    @Query("SELECT * FROM days WHERE date_key = :dateKey LIMIT 1")
    suspend fun day(dateKey: String): DayEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDay(day: DayEntity)

    @Query("DELETE FROM days WHERE date_key = :dateKey")
    suspend fun deleteDay(dateKey: String)

    @Query("DELETE FROM days WHERE date_key NOT IN (SELECT date_key FROM days ORDER BY date_key DESC LIMIT :limit)")
    suspend fun trimDays(limit: Int)

    @Query("SELECT * FROM settings WHERE id = 1 LIMIT 1")
    fun observeSettings(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = 1 LIMIT 1")
    suspend fun settings(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInitialSettings(settings: SettingsEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: SettingsEntity)

    @Insert
    suspend fun insertUndo(undo: UndoEntity): Long

    @Query("SELECT * FROM undo_actions ORDER BY id DESC LIMIT 1")
    suspend fun latestUndo(): UndoEntity?

    @Query("SELECT * FROM undo_actions WHERE date_key = :dateKey ORDER BY id DESC LIMIT 1")
    suspend fun latestUndo(dateKey: String): UndoEntity?

    @Query("SELECT * FROM undo_actions ORDER BY id ASC")
    suspend fun allUndoActions(): List<UndoEntity>

    @Query("SELECT MAX(id) FROM undo_actions")
    suspend fun latestUndoId(): Long?

    @Query("SELECT EXISTS(SELECT 1 FROM undo_actions WHERE date_key = :dateKey)")
    fun observeCanUndo(dateKey: String): Flow<Boolean>

    @Query("DELETE FROM undo_actions WHERE id = :id")
    suspend fun deleteUndo(id: Long)

    @Query("DELETE FROM undo_actions WHERE id > :id")
    suspend fun deleteUndoAfter(id: Long)

    @Query("DELETE FROM undo_actions WHERE id NOT IN (SELECT id FROM undo_actions ORDER BY id DESC LIMIT :limit)")
    suspend fun trimUndo(limit: Int)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun appendWidgetEvent(event: WidgetEventEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM consumed_widget_events WHERE event_id = :eventId)")
    suspend fun wasWidgetEventConsumed(eventId: String): Boolean

    @Query(
        """
        SELECT e.* FROM widget_events e
        LEFT JOIN consumed_widget_events c ON c.event_id = e.event_id
        WHERE c.event_id IS NULL
        ORDER BY e.sequence ASC
        """,
    )
    fun observePendingWidgetEvents(): Flow<List<WidgetEventEntity>>

    @Query(
        """
        SELECT e.* FROM widget_events e
        LEFT JOIN consumed_widget_events c ON c.event_id = e.event_id
        WHERE c.event_id IS NULL
        ORDER BY e.sequence ASC
        LIMIT :limit
        """,
    )
    suspend fun pendingWidgetEvents(limit: Int): List<WidgetEventEntity>

    @Query("SELECT * FROM widget_events ORDER BY sequence ASC")
    suspend fun allWidgetEvents(): List<WidgetEventEntity>

    @Query("SELECT * FROM consumed_widget_events ORDER BY consumed_at_epoch_ms ASC, event_id ASC")
    suspend fun allConsumedWidgetEvents(): List<ConsumedWidgetEventEntity>

    @Query("SELECT COUNT(*) FROM consumed_widget_events")
    suspend fun consumedWidgetEventCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markWidgetEventConsumed(event: ConsumedWidgetEventEntity): Long

    @Query(
        """
        DELETE FROM consumed_widget_events
        WHERE event_id NOT IN (
            SELECT event_id FROM consumed_widget_events
            ORDER BY consumed_at_epoch_ms DESC, event_id DESC
            LIMIT :limit
        )
        """,
    )
    suspend fun trimConsumedWidgetEvents(limit: Int)

    @Query("DELETE FROM widget_events WHERE event_id IN (SELECT event_id FROM consumed_widget_events)")
    suspend fun deleteReconciledWidgetEventPayloads()

    @Query("DELETE FROM days")
    suspend fun deleteDays()

    @Query("DELETE FROM undo_actions")
    suspend fun deleteUndoActions()

    @Query("DELETE FROM consumed_widget_events")
    suspend fun deleteConsumedWidgetEvents()

    @Query("DELETE FROM widget_events")
    suspend fun deleteWidgetEvents()
}

@Database(
    entities = [
        DayEntity::class,
        SettingsEntity::class,
        UndoEntity::class,
        WidgetEventEntity::class,
        ConsumedWidgetEventEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
internal abstract class FoodBlobDatabase : RoomDatabase() {
    abstract fun dao(): FoodBlobDao

    companion object {
        const val DATABASE_NAME = "food_blob.db"

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN widget_epoch INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE settings SET schema_version = 2")
            }
        }

        fun create(
            context: Context,
            databaseName: String = DATABASE_NAME,
        ): FoodBlobDatabase =
            Room.databaseBuilder(context, FoodBlobDatabase::class.java, databaseName)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
