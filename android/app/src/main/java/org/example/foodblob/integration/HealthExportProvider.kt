package org.example.foodblob.integration

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentValues
import android.content.Context
import android.content.OperationApplicationException
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import org.example.foodblob.domain.FoodDateKeys
import org.example.foodblob.storage.EffectiveSnapshot
import org.example.foodblob.storage.FoodBlobServices
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking

/**
 * Signature-protected, query-only projection consumed by Android Track.
 *
 * The provider exposes one bounded absolute snapshot. It never exposes the
 * underlying Room database, event ledger, undo state, settings, or files.
 */
open class HealthExportProvider : ContentProvider() {
    override fun onCreate(): Boolean = context != null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        require(selection == null) { "Selections are not supported." }
        require(selectionArgs == null) { "Selection arguments are not supported." }
        require(sortOrder == null) { "Sort orders are not supported." }
        return querySnapshot(uri, projection, cancellationSignal = null)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        queryArgs: Bundle?,
        cancellationSignal: CancellationSignal?,
    ): Cursor {
        require(queryArgs == null || queryArgs.keySet().isEmpty()) {
            "Query arguments are not supported."
        }
        return querySnapshot(uri, projection, cancellationSignal)
    }

    private fun querySnapshot(
        uri: Uri,
        projection: Array<out String>?,
        cancellationSignal: CancellationSignal?,
    ): Cursor {
        require(uri == CONTENT_URI) { "Unsupported Health export URI." }
        require(projection == null || projection.contentEquals(COLUMNS)) {
            "Only the canonical Health export projection is supported."
        }
        enforceSameSignatureCaller()
        cancellationSignal?.throwIfCanceled()

        val providerContext = requireNotNull(context) { "Provider is not attached." }
        val snapshot = runBlocking { readSnapshot(providerContext) }
        cancellationSignal?.throwIfCanceled()
        validateSnapshot(snapshot)

        val generatedAt = generatedAtEpochMs()
        require(generatedAt >= 0) { "Snapshot timestamp is invalid." }
        return MatrixCursor(COLUMNS, snapshot.days.size).apply {
            snapshot.days.sortedBy { it.dateKey }.forEach { day ->
                addRow(
                    arrayOf<Any?>(
                        day.dateKey,
                        day.counts.green,
                        day.counts.yellow,
                        day.counts.red,
                    ),
                )
            }
            extras = Bundle(3).apply {
                putInt(EXTRA_SCHEMA_VERSION, SCHEMA_VERSION)
                putString(EXTRA_SNAPSHOT_REVISION, snapshot.revision)
                putLong(EXTRA_GENERATED_AT_EPOCH_MS, generatedAt)
            }
            setNotificationUri(providerContext.contentResolver, CONTENT_URI)
        }
    }

    protected open suspend fun readSnapshot(context: Context): EffectiveSnapshot =
        FoodBlobServices.get(context).store.effectiveSnapshot()

    protected open fun generatedAtEpochMs(): Long = System.currentTimeMillis()

    private fun enforceSameSignatureCaller() {
        val providerContext = requireNotNull(context) { "Provider is not attached." }
        val result = providerContext.packageManager.checkSignatures(
            Binder.getCallingUid(),
            Process.myUid(),
        )
        if (result != PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("Health export requires the Food Blob signing identity.")
        }
    }

    private fun validateSnapshot(snapshot: EffectiveSnapshot) {
        require(snapshot.revision.isNotBlank()) { "Snapshot revision is missing." }
        require(snapshot.days.size <= MAX_HISTORY_DAYS) { "Snapshot exceeds the retention contract." }
        val dates = HashSet<String>(snapshot.days.size)
        snapshot.days.forEach { day ->
            require(FoodDateKeys.isCanonical(day.dateKey) && dates.add(day.dateKey)) {
                "Snapshot contains an invalid or duplicate day."
            }
            require(day.counts.green >= 0 && day.counts.yellow >= 0 && day.counts.red >= 0) {
                "Snapshot contains a negative count."
            }
        }
    }

    override fun getType(uri: Uri): String {
        require(uri == CONTENT_URI) { "Unsupported Health export URI." }
        return MIME_TYPE
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = rejectMutation()

    override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int = rejectMutation()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = rejectMutation()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        rejectMutation()

    @Throws(OperationApplicationException::class)
    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> =
        rejectMutation()

    @Throws(OperationApplicationException::class)
    override fun applyBatch(
        authority: String,
        operations: ArrayList<ContentProviderOperation>,
    ): Array<ContentProviderResult> = rejectMutation()

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = rejectMutation()

    override fun call(authority: String, method: String, arg: String?, extras: Bundle?): Bundle? =
        rejectMutation()

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = throw FileNotFoundException(
        "Health export does not expose files.",
    )

    private fun rejectMutation(): Nothing = throw UnsupportedOperationException(
        "Health export is query-only.",
    )

    companion object {
        const val AUTHORITY = "org.example.foodblob.health_export"
        const val READ_PERMISSION = "org.example.foodblob.permission.READ_HEALTH_EXPORT"
        const val PATH_DAILY_COUNTS = "v1/daily_counts"

        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_DAILY_COUNTS")

        const val COLUMN_DATE = "date"
        const val COLUMN_GREEN = "green"
        const val COLUMN_YELLOW = "yellow"
        const val COLUMN_RED = "red"
        val COLUMNS: Array<String> = arrayOf(COLUMN_DATE, COLUMN_GREEN, COLUMN_YELLOW, COLUMN_RED)

        const val EXTRA_SCHEMA_VERSION = "schema_version"
        const val EXTRA_SNAPSHOT_REVISION = "snapshot_revision"
        const val EXTRA_GENERATED_AT_EPOCH_MS = "generated_at_epoch_ms"
        const val SCHEMA_VERSION = 1

        private const val MAX_HISTORY_DAYS = 180
        private const val MIME_TYPE = "vnd.android.cursor.dir/vnd.org.example.foodblob.daily-count"
    }
}
