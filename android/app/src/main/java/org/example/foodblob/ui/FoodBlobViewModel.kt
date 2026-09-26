package org.example.foodblob.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.example.foodblob.domain.FoodColor
import org.example.foodblob.domain.FoodCounts
import org.example.foodblob.domain.FoodStreak
import org.example.foodblob.domain.SkinId
import org.example.foodblob.storage.EffectiveSnapshot
import org.example.foodblob.storage.FoodBlobServices
import org.example.foodblob.storage.FoodStore
import org.example.foodblob.storage.FoodImportPlan
import org.example.foodblob.storage.FoodImportRecoveryPlan
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class FoodBlobUiState(
    val snapshot: EffectiveSnapshot = EffectiveSnapshot(
        emptyList(),
        SkinId.SKY_MEADOW,
        org.example.foodblob.domain.WidgetLayoutId.BUBBLE_STACK,
        "",
        0,
    ),
    val selectedDateKey: String = LocalDate.now().toString(),
    val canUndo: Boolean = false,
    val onboardingComplete: Boolean = false,
    val busy: Boolean = false,
    val recoveredLocalStore: Boolean = false,
    val importPreview: FoodImportPreview? = null,
    val importRecoveryPreview: FoodImportRecoveryPreview? = null,
    val hasStoreError: Boolean = false,
) {
    val selectedDate: LocalDate get() = LocalDate.parse(selectedDateKey)
    val counts: FoodCounts get() = snapshot.counts(selectedDateKey)
    val selectedSkin: SkinId get() = snapshot.selectedSkin
    val recordsByDate: Map<String, FoodCounts> get() = snapshot.days.associate { it.dateKey to it.counts }
    val streak: Int get() = FoodStreak.consecutiveDays(selectedDate, recordsByDate)
    val canMoveBackward: Boolean
        get() = canMoveBackwardWithinRetention(
            selectedDateKey = selectedDateKey,
            retainedDateKeys = snapshot.days.map { it.dateKey },
        )
}

internal fun canMoveBackwardWithinRetention(
    selectedDateKey: String,
    retainedDateKeys: List<String>,
): Boolean {
    if (retainedDateKeys.size < FoodStore.MAX_HISTORY_DAYS) return true
    val oldestRetainedDateKey = retainedDateKeys.minOrNull() ?: return true
    return selectedDateKey > oldestRetainedDateKey
}

data class FoodImportPreview(
    val dayCount: Int,
    val undoActionCount: Int,
    val oldestDateKey: String?,
    val newestDateKey: String?,
    val total: Int,
    val duplicateDaysCollapsed: Int,
    val skin: SkinId,
)

data class FoodImportRecoveryPreview(
    val dayCount: Int,
    val pendingWidgetActionCount: Int,
    val skin: SkinId,
)

enum class HapticCue { ADD, REMOVE, UNDO, SUCCESS, ERROR }

enum class Notice { SAVED, REMOVED, UNDONE, NOTHING_TO_UNDO, EXPORTED, IMPORTED, ROLLED_BACK, DELETED, FAILED }

sealed interface FoodBlobEffect {
    data class Haptic(val cue: HapticCue) : FoodBlobEffect
    data class Message(
        val notice: Notice,
        val undoDateKey: String? = null,
    ) : FoodBlobEffect
    data class ExportReady(val json: String) : FoodBlobEffect
}

internal fun mutationEffects(
    dateKey: String,
    haptic: HapticCue,
    notice: Notice,
): List<FoodBlobEffect> {
    require(notice == Notice.SAVED || notice == Notice.REMOVED) {
        "Only reversible mutation notices can expose snackbar undo"
    }
    return listOf(
        FoodBlobEffect.Haptic(haptic),
        FoodBlobEffect.Message(notice, undoDateKey = dateKey),
    )
}

internal fun resolveDateSelectionAfterRefresh(
    selectedDateKey: String,
    currentToday: String,
    followsToday: Boolean,
): String =
    if (followsToday) currentToday else selectedDateKey

internal suspend fun writeExportDocument(
    json: String,
    dispatcher: CoroutineDispatcher,
    openOutputStream: () -> java.io.OutputStream?,
) {
    withContext(dispatcher) {
        requireNotNull(openOutputStream()).bufferedWriter().use { writer ->
            writer.write(json)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FoodBlobViewModel(application: Application) : AndroidViewModel(application) {
    private val services = FoodBlobServices.get(application)
    private val store = services.store
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, 0)
    private val selectedDateKey = MutableStateFlow(store.currentDateKey())
    private var selectedDateFollowsToday = true
    private val onboardingComplete = MutableStateFlow(preferences.getBoolean(KEY_ONBOARDING_COMPLETE, false))
    private val busy = MutableStateFlow(false)
    private val hasStoreError = MutableStateFlow(false)
    private val importPreview = MutableStateFlow<FoodImportPreview?>(null)
    private val importRecoveryPreview = MutableStateFlow<FoodImportRecoveryPreview?>(null)
    private var inspectedImport: Pair<String, FoodImportPlan>? = null
    private var inspectedRecovery: FoodImportRecoveryPlan? = null
    private val effectsMutable = MutableSharedFlow<FoodBlobEffect>(extraBufferCapacity = 8)
    private val mutationMutex = Mutex()
    val effects = effectsMutable.asSharedFlow()

    private val canUndo = selectedDateKey
        .flatMapLatest(store::canUndo)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val uiFlags = combine(
        onboardingComplete,
        busy,
        hasStoreError,
        importPreview,
        importRecoveryPreview,
    ) { onboarding, isBusy, storeError, pendingImport, pendingRecovery ->
        UiFlags(onboarding, isBusy, storeError, pendingImport, pendingRecovery)
    }

    val uiState: StateFlow<FoodBlobUiState> = combine(
        store.snapshots,
        selectedDateKey,
        canUndo,
        uiFlags,
    ) { snapshot, dateKey, undoAvailable, flags ->
        FoodBlobUiState(
            snapshot = snapshot,
            selectedDateKey = dateKey,
            canUndo = undoAvailable,
            onboardingComplete = flags.onboarding,
            busy = flags.busy,
            recoveredLocalStore = services.recoveredDatabase != null,
            importPreview = flags.importPreview,
            importRecoveryPreview = flags.importRecoveryPreview,
            hasStoreError = flags.storeError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoodBlobUiState())

    init {
        viewModelScope.launch {
            runCatching {
                store.ensureInitialized()
                store.reconcilePending()
                refreshImportRecoveryPreview()
            }.onSuccess { hasStoreError.value = false }.onFailure { fail() }
        }
    }

    fun selectDate(dateKey: String) {
        selectedDateFollowsToday = false
        selectedDateKey.value = dateKey
    }

    fun selectToday() {
        val currentToday = store.currentDateKey()
        selectedDateFollowsToday = true
        selectedDateKey.value = currentToday
    }

    fun refreshLocalDay() {
        val currentToday = store.currentDateKey()
        selectedDateKey.value = resolveDateSelectionAfterRefresh(
            selectedDateKey = selectedDateKey.value,
            currentToday = currentToday,
            followsToday = selectedDateFollowsToday,
        )
    }

    fun increment(color: FoodColor) = increment(color, {}, {})

    fun increment(color: FoodColor, onAccepted: (FoodCounts) -> Unit, onFinished: () -> Unit) {
        val tappedDateKey = selectedDateKey.value
        mutate(onFinished = onFinished) {
            if (store.increment(color, tappedDateKey)) {
                val acceptedCounts = store.snapshots.first().counts(tappedDateKey)
                onAccepted(acceptedCounts)
                mutationEffects(tappedDateKey, HapticCue.ADD, Notice.SAVED)
                    .forEach { effectsMutable.emit(it) }
            }
        }
    }

    fun decrement(color: FoodColor) {
        val tappedDateKey = selectedDateKey.value
        mutate {
            if (store.decrement(color, tappedDateKey)) {
                mutationEffects(tappedDateKey, HapticCue.REMOVE, Notice.REMOVED)
                    .forEach { effectsMutable.emit(it) }
            }
        }
    }

    fun undo() {
        undoOnDate(selectedDateKey.value)
    }

    fun undoSnackbarAction(dateKey: String) {
        undoOnDate(dateKey)
    }

    private fun undoOnDate(dateKey: String) {
        mutate {
            if (store.undo(dateKey)) {
                effectsMutable.emit(FoodBlobEffect.Haptic(HapticCue.UNDO))
                effectsMutable.emit(FoodBlobEffect.Message(Notice.UNDONE))
            } else {
                effectsMutable.emit(FoodBlobEffect.Message(Notice.NOTHING_TO_UNDO))
            }
        }
    }

    fun selectSkin(skin: SkinId) {
        if (uiState.value.selectedSkin == skin) return
        mutate {
            store.setSkin(skin)
            effectsMutable.emit(FoodBlobEffect.Haptic(HapticCue.SUCCESS))
        }
    }

    fun requestExport() = exclusive {
        effectsMutable.emit(FoodBlobEffect.ExportReady(store.exportJson()))
    }

    fun writeExport(uri: Uri, json: String) = exclusive {
        val resolver = getApplication<Application>().contentResolver
        writeExportDocument(json, Dispatchers.IO) {
            resolver.openOutputStream(uri, "wt")
        }
        effectsMutable.emit(FoodBlobEffect.Haptic(HapticCue.SUCCESS))
        effectsMutable.emit(FoodBlobEffect.Message(Notice.EXPORTED))
    }

    fun inspectImport(uri: Uri) = exclusive {
        val resolver = getApplication<Application>().contentResolver
        val (payload, plan) = withContext(Dispatchers.IO) {
            val selectedPayload = requireNotNull(resolver.openInputStream(uri)).use(::readBoundedUtf8)
            selectedPayload to store.inspectImport(selectedPayload)
        }
        inspectedImport = payload to plan
        importPreview.value = plan.toPreview()
    }

    fun cancelImport() {
        inspectedImport = null
        importPreview.value = null
    }

    fun confirmImport() = exclusive {
        val (payload, plan) = requireNotNull(inspectedImport) { "Import preview is unavailable" }
        store.restoreImport(payload, plan)
        inspectedImport = null
        importPreview.value = null
        refreshImportRecoveryPreview()
        selectedDateKey.value = store.currentDateKey()
        effectsMutable.emit(FoodBlobEffect.Haptic(HapticCue.SUCCESS))
        effectsMutable.emit(FoodBlobEffect.Message(Notice.IMPORTED))
    }

    fun restorePreviousState() = exclusive {
        val plan = requireNotNull(inspectedRecovery) { "Import recovery preview is unavailable" }
        store.restoreLatestImportRecovery(plan)
        refreshImportRecoveryPreview()
        selectedDateKey.value = store.currentDateKey()
        effectsMutable.emit(FoodBlobEffect.Haptic(HapticCue.SUCCESS))
        effectsMutable.emit(FoodBlobEffect.Message(Notice.ROLLED_BACK))
    }

    fun deleteAll() = exclusive {
        store.deleteAll()
        inspectedImport = null
        inspectedRecovery = null
        importPreview.value = null
        importRecoveryPreview.value = null
        effectsMutable.emit(FoodBlobEffect.Haptic(HapticCue.SUCCESS))
        effectsMutable.emit(FoodBlobEffect.Message(Notice.DELETED))
    }

    fun completeOnboarding() {
        preferences.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
        onboardingComplete.value = true
    }

    fun replayOnboarding() {
        onboardingComplete.value = false
    }

    private fun mutate(onFinished: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                mutationMutex.withLock {
                    runCatching { block() }
                        .onSuccess { hasStoreError.value = false }
                        .onFailure { fail() }
                }
            } finally {
                onFinished()
            }
        }
    }

    private fun exclusive(block: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            mutationMutex.withLock {
                busy.value = true
                runCatching { block() }
                    .onSuccess { hasStoreError.value = false }
                    .onFailure { fail() }
                busy.value = false
            }
        }
    }

    private fun fail() {
        hasStoreError.value = true
        effectsMutable.tryEmit(FoodBlobEffect.Haptic(HapticCue.ERROR))
        effectsMutable.tryEmit(FoodBlobEffect.Message(Notice.FAILED))
    }

    private suspend fun refreshImportRecoveryPreview() {
        inspectedRecovery = withContext(Dispatchers.IO) { store.inspectLatestImportRecovery() }
        importRecoveryPreview.value = inspectedRecovery?.let { plan ->
            FoodImportRecoveryPreview(
                dayCount = plan.dayCount,
                pendingWidgetActionCount = plan.pendingWidgetActionCount,
                skin = plan.selectedSkin,
            )
        }
    }

    private fun readBoundedUtf8(stream: java.io.InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_IMPORT_BYTES) error("Import document is too large")
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    companion object {
        private const val PREFERENCES_NAME = "food_blob_ui"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val MAX_IMPORT_BYTES = 2 * 1_024 * 1_024
    }
}

private data class UiFlags(
    val onboarding: Boolean,
    val busy: Boolean,
    val storeError: Boolean,
    val importPreview: FoodImportPreview?,
    val importRecoveryPreview: FoodImportRecoveryPreview?,
)

private fun FoodImportPlan.toPreview() = FoodImportPreview(
    dayCount = dayCount,
    undoActionCount = undoActionCount,
    oldestDateKey = oldestDateKey,
    newestDateKey = newestDateKey,
    total = totalCounts.total,
    duplicateDaysCollapsed = duplicateDaysCollapsed,
    skin = selectedSkin,
)
