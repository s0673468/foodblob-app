package org.example.foodblob.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import org.example.foodblob.ui.rememberBlobTranslucency
import org.example.foodblob.R
import org.example.foodblob.domain.FoodColor
import org.example.foodblob.domain.FoodCounts
import org.example.foodblob.storage.EffectiveSnapshot
import org.example.foodblob.storage.FoodBlobServices
import org.example.foodblob.storage.FoodStore
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val colorKey = ActionParameters.Key<String>("food_color")
private val deltaKey = ActionParameters.Key<Int>("food_delta")
private val eventIdKey = ActionParameters.Key<String>("event_id")
private val variantKey = ActionParameters.Key<String>("widget_variant")
private val widgetEpochKey = ActionParameters.Key<Long>("widget_epoch")

internal object FoodWidgetActionIntent {
    const val ACTION = "org.example.foodblob.widget.CHANGE_COUNT"
    const val COLOR = "food_color"
    const val DELTA = "food_delta"
    const val WIDGET_EPOCH = "widget_epoch"

    fun uri(eventId: UUID): Uri = Uri.Builder()
        .scheme("foodblob-widget")
        .authority("change")
        .appendPath(eventId.toString())
        .build()
}

private data class WidgetContentState(
    val counts: FoodCounts,
    val available: Boolean,
    val revision: String,
    val widgetEpoch: Long,
)

private data class WidgetStateSource(
    val store: FoodStore?,
    val initialSnapshot: EffectiveSnapshot?,
)

internal object FoodBlobWidgetRefreshSignal {
    private val mutableTicks = MutableStateFlow(0L)
    val ticks: StateFlow<Long> = mutableTicks

    fun advance() {
        mutableTicks.update { tick -> tick + 1L }
    }
}

private data class WidgetAccessibility(
    val openBlob: String,
    val unavailable: String,
    val add: Map<FoodColor, String>,
    val remove: Map<FoodColor, String>,
)

internal abstract class FoodBlobWidget(
    private val variant: FoodWidgetVariant,
) : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val source = loadStateSource(context)
        val store = source.store
        val initialSnapshot = source.initialSnapshot
        if (store == null || initialSnapshot == null) {
            val unavailable = WidgetContentState(
                FoodCounts(),
                available = false,
                revision = "unavailable",
                widgetEpoch = 0,
            )
            provideContent {
                FoodBlobWidgetContent(
                    variant = variant,
                    state = unavailable,
                    accessibility = accessibility(context, unavailable),
                )
            }
            return
        }
        provideContent {
            val snapshot by store.snapshots.collectAsState(initialSnapshot)
            val refreshTick by FoodBlobWidgetRefreshSignal.ticks.collectAsState()
            val state = WidgetContentState(
                counts = snapshot.counts(store.currentDateKey()),
                available = true,
                // Refresh ticks make timezone/day-rollover updates observable even when
                // the database rows themselves have not changed.
                revision = "${snapshot.revision}:$refreshTick",
                widgetEpoch = snapshot.widgetEpoch,
            )
            FoodBlobWidgetContent(
                variant = variant,
                state = state,
                accessibility = accessibility(context, state),
            )
        }
    }

    private suspend fun loadStateSource(context: Context): WidgetStateSource = withContext(Dispatchers.IO) {
        try {
            val store = FoodBlobServices.get(context).store
            store.ensureInitialized()
            WidgetStateSource(store, store.effectiveSnapshot())
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            WidgetStateSource(store = null, initialSnapshot = null)
        }
    }
}

@Composable
private fun FoodBlobWidgetContent(
    variant: FoodWidgetVariant,
    state: WidgetContentState,
    accessibility: WidgetAccessibility,
) {
    val context = LocalContext.current
    val translucency by rememberBlobTranslucency(context)
    val size = LocalSize.current
    val geometry = FoodWidgetGeometry.resolve(
        widthDp = size.width.value,
        heightDp = size.height.value,
        presentation = variant.presentation,
    )
    val density = context.resources.displayMetrics.density
    val artwork = remember(state.counts, state.available, variant, geometry, density, translucency) {
        FoodWidgetArtworkRenderer.render(
            counts = state.counts,
            variant = variant,
            geometry = geometry,
            density = density,
            available = state.available,
            translucency = translucency,
        )
    }
    val openAction = remember(context.packageName) {
        actionStartActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("foodblob://today"))
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(artwork)),
    ) {
        when (variant.presentation) {
            WidgetPresentation.SMALL -> SmallInteractionOverlay(
                counts = state.counts,
                available = state.available,
                revision = state.revision,
                widgetEpoch = state.widgetEpoch,
                geometry = geometry,
                accessibility = accessibility,
                openAction = openAction,
            )
            WidgetPresentation.MEDIUM -> MediumInteractionOverlay(
                counts = state.counts,
                available = state.available,
                revision = state.revision,
                widgetEpoch = state.widgetEpoch,
                geometry = geometry,
                accessibility = accessibility,
                openAction = openAction,
            )
        }
    }
}

@Composable
private fun SmallInteractionOverlay(
    counts: FoodCounts,
    available: Boolean,
    revision: String,
    widgetEpoch: Long,
    geometry: ResolvedWidgetGeometry,
    accessibility: WidgetAccessibility,
    openAction: Action,
) {
    if (geometry.layout == WidgetLayout.OPEN_ONLY) {
        OpenRegion(GlanceModifier.fillMaxSize(), available, accessibility, openAction)
        return
    }
    Column(
        modifier = GlanceModifier.fillMaxSize(),
    ) {
        when (geometry.layout) {
            WidgetLayout.QUICK_WIDE -> {
                Spacer(GlanceModifier.height(geometry.actionTargets.first().rect.topDp.dp))
                WidgetActionRow(counts, available, revision, widgetEpoch, geometry, accessibility)
            }
            WidgetLayout.QUICK_FEATURED -> {
                val blob = requireNotNull(geometry.blobRect)
                Spacer(GlanceModifier.height(blob.topDp.dp))
                Row {
                    Spacer(GlanceModifier.width(blob.leftDp.dp))
                    OpenRegion(
                        GlanceModifier.width(blob.widthDp.dp).height(blob.heightDp.dp),
                        available,
                        accessibility,
                        openAction,
                    )
                }
                Spacer(GlanceModifier.height(geometry.rowGapDp.dp))
                WidgetActionRow(counts, available, revision, widgetEpoch, geometry, accessibility)
            }
            WidgetLayout.QUICK_TALL -> {
                val blob = requireNotNull(geometry.blobRect)
                Spacer(GlanceModifier.height(blob.topDp.dp))
                Row {
                    Spacer(GlanceModifier.width(blob.leftDp.dp))
                    OpenRegion(
                        GlanceModifier.width(blob.widthDp.dp).height(blob.heightDp.dp),
                        available,
                        accessibility,
                        openAction,
                    )
                }
                Spacer(GlanceModifier.height(geometry.rowGapDp.dp))
                geometry.actionTargets.forEachIndexed { index, target ->
                    if (index > 0) Spacer(GlanceModifier.height(geometry.rowGapDp.dp))
                    Row {
                        Spacer(GlanceModifier.width(target.rect.leftDp.dp))
                        WidgetAction(counts, available, revision, widgetEpoch, target, accessibility)
                    }
                }
            }
            WidgetLayout.QUICK_GRID -> {
                Spacer(GlanceModifier.height(geometry.outerPaddingDp.dp))
                WidgetGridRow(
                    geometry.actionTargets.take(2), counts, available, revision, widgetEpoch, geometry, accessibility,
                )
                Spacer(GlanceModifier.height(geometry.rowGapDp.dp))
                Row {
                    Spacer(GlanceModifier.width(geometry.outerPaddingDp.dp))
                    WidgetAction(counts, available, revision, widgetEpoch, geometry.actionTargets[2], accessibility)
                    Spacer(GlanceModifier.width(geometry.controlGapDp.dp))
                    val blob = requireNotNull(geometry.blobRect)
                    OpenRegion(
                        GlanceModifier.width(blob.widthDp.dp).height(blob.heightDp.dp),
                        available,
                        accessibility,
                        openAction,
                    )
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun MediumInteractionOverlay(
    counts: FoodCounts,
    available: Boolean,
    revision: String,
    widgetEpoch: Long,
    geometry: ResolvedWidgetGeometry,
    accessibility: WidgetAccessibility,
    openAction: Action,
) {
    if (geometry.layout == WidgetLayout.OPEN_ONLY) {
        OpenRegion(GlanceModifier.fillMaxSize(), available, accessibility, openAction)
        return
    }
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Spacer(GlanceModifier.height(geometry.outerPaddingDp.dp))
        Row {
            Spacer(GlanceModifier.width(geometry.outerPaddingDp.dp))
            Box(
                modifier = GlanceModifier
                    .width(geometry.blobWidthDp.dp)
                    .height(requireNotNull(geometry.blobRect).heightDp.dp)
                    .accessibleAction(openAction, if (available) accessibility.openBlob else accessibility.unavailable),
            ) {}
            Spacer(GlanceModifier.width(geometry.sectionGapDp.dp))
            if (geometry.layout == WidgetLayout.FULL_ROWS) {
                Column {
                    Spacer(GlanceModifier.height(
                        (geometry.actionTargets.first().rect.topDp - geometry.outerPaddingDp).dp,
                    ))
                    geometry.actionTargets.chunked(2).forEachIndexed { index, pair ->
                        if (index > 0) Spacer(GlanceModifier.height(geometry.rowGapDp.dp))
                        Row {
                            WidgetAction(counts, available, revision, widgetEpoch, pair[0], accessibility)
                            Spacer(GlanceModifier.width(geometry.controlGapDp.dp))
                            WidgetAction(counts, available, revision, widgetEpoch, pair[1], accessibility)
                        }
                    }
                }
            } else {
                geometry.actionTargets.chunked(2).forEachIndexed { index, pair ->
                    if (index > 0) Spacer(GlanceModifier.width(geometry.controlGapDp.dp))
                    Column {
                        WidgetAction(counts, available, revision, widgetEpoch, pair[0], accessibility)
                        Spacer(GlanceModifier.height(geometry.rowGapDp.dp))
                        WidgetAction(counts, available, revision, widgetEpoch, pair[1], accessibility)
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetActionRow(
    counts: FoodCounts,
    available: Boolean,
    revision: String,
    widgetEpoch: Long,
    geometry: ResolvedWidgetGeometry,
    accessibility: WidgetAccessibility,
) {
    Row {
        Spacer(GlanceModifier.width(geometry.actionTargets.first().rect.leftDp.dp))
        geometry.actionTargets.forEachIndexed { index, target ->
            if (index > 0) Spacer(GlanceModifier.width(geometry.controlGapDp.dp))
            WidgetAction(counts, available, revision, widgetEpoch, target, accessibility)
        }
    }
}

@Composable
private fun WidgetGridRow(
    targets: List<WidgetActionTarget>,
    counts: FoodCounts,
    available: Boolean,
    revision: String,
    widgetEpoch: Long,
    geometry: ResolvedWidgetGeometry,
    accessibility: WidgetAccessibility,
) {
    Row {
        Spacer(GlanceModifier.width(geometry.outerPaddingDp.dp))
        targets.forEachIndexed { index, target ->
            if (index > 0) Spacer(GlanceModifier.width(geometry.controlGapDp.dp))
            WidgetAction(counts, available, revision, widgetEpoch, target, accessibility)
        }
    }
}

@Composable
private fun WidgetAction(
    counts: FoodCounts,
    available: Boolean,
    revision: String,
    widgetEpoch: Long,
    target: WidgetActionTarget,
    accessibility: WidgetAccessibility,
) {
    ActionRegion(
        modifier = GlanceModifier.width(target.rect.widthDp.dp).height(target.rect.heightDp.dp),
        color = target.color,
        delta = target.delta,
        revision = revision,
        widgetEpoch = widgetEpoch,
        enabled = available && FoodWidgetContract.isActionEnabled(counts, target.color, target.delta),
        description = if (target.delta > 0) {
            accessibility.add.getValue(target.color)
        } else {
            accessibility.remove.getValue(target.color)
        },
    )
}

@Composable
private fun OpenRegion(
    modifier: GlanceModifier,
    available: Boolean,
    accessibility: WidgetAccessibility,
    openAction: Action,
) {
    Box(
        modifier = modifier.accessibleAction(
            openAction,
            if (available) accessibility.openBlob else accessibility.unavailable,
        ),
    ) {}
}

@Composable
private fun ActionRegion(
    modifier: GlanceModifier,
    color: FoodColor,
    delta: Int,
    revision: String,
    widgetEpoch: Long,
    enabled: Boolean,
    description: String,
) {
    val context = LocalContext.current
    // A committed store revision renders a new idempotency token. Retries of the same
    // launcher action remain one write; the first redraw makes the next tap a new write.
    val eventId = remember(color, delta, revision) { FoodWidgetContract.newEventId().toString() }
    val actionIntent = remember(context.packageName, color, delta, eventId, widgetEpoch) {
        Intent(context, FoodBlobWidgetActionReceiver::class.java)
            .setAction(FoodWidgetActionIntent.ACTION)
            .setPackage(context.packageName)
            // PendingIntent identity ignores extras. The event URI forces Android to
            // replace the action after every authoritative widget redraw.
            .setData(FoodWidgetActionIntent.uri(UUID.fromString(eventId)))
            .putExtra(FoodWidgetActionIntent.COLOR, color.storageId)
            .putExtra(FoodWidgetActionIntent.DELTA, delta)
            .putExtra(FoodWidgetActionIntent.WIDGET_EPOCH, widgetEpoch)
    }
    val action = actionSendBroadcast(actionIntent)
    val semantics = modifier.semantics { contentDescription = description }
    Box(modifier = if (enabled) {
        // RemoteViews runs this native ripple in the launcher; no animation loop
        // or speculative count update is needed in the widget process.
        semantics.clickable(action, rippleOverride = widgetPressRipple(color, delta))
    } else semantics) {}
}

private fun GlanceModifier.accessibleAction(action: Action, description: String): GlanceModifier =
    clickable(action, rippleOverride = R.drawable.widget_control_ripple)
        .semantics { contentDescription = description }

internal fun widgetPressRipple(color: FoodColor, delta: Int): Int = if (delta < 0) {
    // Undo is a quieter neutral response; adds carry the selected food color.
    R.drawable.widget_control_ripple
} else when (color) {
    FoodColor.GREEN -> R.drawable.widget_control_ripple_green
    FoodColor.YELLOW -> R.drawable.widget_control_ripple_yellow
    FoodColor.RED -> R.drawable.widget_control_ripple_red
}

class FoodBlobWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FoodWidgetActionIntent.ACTION) return
        val eventId = intent.data
            ?.takeIf { it.scheme == "foodblob-widget" && it.host == "change" }
            ?.lastPathSegment
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return
        val color = intent.getStringExtra(FoodWidgetActionIntent.COLOR)
            ?.let(FoodColor::fromStorage)
            ?: return
        val delta = intent.getIntExtra(FoodWidgetActionIntent.DELTA, 0)
        if (!FoodWidgetContract.isSupportedDelta(delta)) return
        if (!intent.hasExtra(FoodWidgetActionIntent.WIDGET_EPOCH)) {
            refreshLegacyWidgetWithoutMutation(context)
            return
        }
        val widgetEpoch = intent.getLongExtra(FoodWidgetActionIntent.WIDGET_EPOCH, -1)

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                FoodBlobServices.get(context).store.commitWidgetAction(
                    color = color,
                    delta = delta,
                    id = eventId,
                    widgetEpoch = widgetEpoch,
                )
                // Hold the broadcast lifetime until every pinned instance has received
                // authoritative state and fresh PendingIntent identities.
                FoodBlobWidgetUpdater.updateAll(context)
            } catch (error: CancellationException) {
                throw error
            } catch (_: RuntimeException) {
                // Leave the last authoritative snapshot unchanged; never imply a failed write.
            } finally {
                pendingResult.finish()
            }
        }
    }
}

private fun BroadcastReceiver.refreshLegacyWidgetWithoutMutation(context: Context) {
    val pendingResult = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            refreshLegacyWidgetSafely {
                FoodBlobWidgetUpdater.updateAll(context.applicationContext)
            }
        } finally {
            pendingResult.finish()
        }
    }
}

internal suspend fun refreshLegacyWidgetSafely(refresh: suspend () -> Unit) {
    try {
        refresh()
    } catch (error: CancellationException) {
        throw error
    } catch (_: RuntimeException) {
        // A legacy token must never mutate state or terminate the host process.
    }
}

/** Handles callback PendingIntents rendered by APKs before the unique-data broadcast contract. */
class ChangeFoodCountWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val color = parameters[colorKey]?.let(FoodColor::fromStorage) ?: return
        val delta = parameters[deltaKey] ?: return
        val eventId = parameters[eventIdKey]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return
        val variant = parameters[variantKey]
            ?.let { raw -> FoodWidgetVariant.entries.firstOrNull { it.name == raw } }
        if (!FoodWidgetContract.isSupportedDelta(delta)) return
        val widgetEpoch = parameters[widgetEpochKey]
        if (widgetEpoch == null) {
            // APKs before the epoch contract cannot safely mutate after Delete All.
            FoodBlobWidgetUpdater.updateAll(context)
            return
        }

        try {
            FoodBlobServices.get(context).store.commitWidgetAction(
                color = color,
                delta = delta,
                id = eventId,
                widgetEpoch = widgetEpoch,
            )
            // The callback must not finish before the launcher has received fresh RemoteViews.
            // The app-level bridge is intentionally asynchronous and can be stopped with the process.
            if (variant != null) {
                FoodBlobWidgetUpdater.updateAfterAction(context, glanceId, variant)
            } else {
                // A widget pinned by an older APK has no variant parameter. Preserve its
                // durable action, then replace every legacy RemoteViews instance in one pass.
                FoodBlobWidgetUpdater.updateAll(context)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            // Leave the last authoritative snapshot unchanged; never imply a failed write.
        }
    }
}

internal object FoodBlobWidgetUpdater {
    suspend fun updateAfterAction(context: Context, glanceId: GlanceId, variant: FoodWidgetVariant) {
        FoodBlobWidgetRefreshSignal.advance()
        val applicationContext = context.applicationContext
        val primary = widgetFor(variant)
        primary.update(applicationContext, glanceId)
        allWidgets
            .filterNot { it::class == primary::class }
            .forEach { widget -> widget.updateAll(applicationContext) }
        // Keep duplicate instances of the tapped kind consistent as well. The targeted
        // update above gives the pressed instance its authoritative redraw first.
        primary.updateAll(applicationContext)
    }

    suspend fun updateAll(context: Context) {
        FoodBlobWidgetRefreshSignal.advance()
        allWidgets.forEach { widget -> widget.updateAll(context.applicationContext) }
    }

    private fun widgetFor(variant: FoodWidgetVariant): GlanceAppWidget = when (variant) {
        FoodWidgetVariant.SKY_MEADOW_SMALL -> SkyMeadowSmallWidget()
        FoodWidgetVariant.SHRINE_SMALL -> ShrineSmallWidget()
        FoodWidgetVariant.SKY_MEADOW_MEDIUM -> SkyMeadowMediumWidget()
        FoodWidgetVariant.SHRINE_MEDIUM -> ShrineMediumWidget()
    }

    private val allWidgets: List<GlanceAppWidget> = listOf(
        SkyMeadowSmallWidget(),
        ShrineSmallWidget(),
        SkyMeadowMediumWidget(),
        ShrineMediumWidget(),
    )
}

internal class SkyMeadowSmallWidget : FoodBlobWidget(FoodWidgetVariant.SKY_MEADOW_SMALL)

internal class ShrineSmallWidget : FoodBlobWidget(FoodWidgetVariant.SHRINE_SMALL)

internal class SkyMeadowMediumWidget : FoodBlobWidget(FoodWidgetVariant.SKY_MEADOW_MEDIUM)

internal class ShrineMediumWidget : FoodBlobWidget(FoodWidgetVariant.SHRINE_MEDIUM)

abstract class FoodBlobWidgetReceiver : GlanceAppWidgetReceiver()

class SkyMeadowSmallWidgetReceiver : FoodBlobWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SkyMeadowSmallWidget()
}

class ShrineSmallWidgetReceiver : FoodBlobWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShrineSmallWidget()
}

class SkyMeadowMediumWidgetReceiver : FoodBlobWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SkyMeadowMediumWidget()
}

class ShrineMediumWidgetReceiver : FoodBlobWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShrineMediumWidget()
}

internal object WidgetDayRolloverScheduler {
    private const val REQUEST_CODE = 4_238
    private const val WINDOW_MS = 10 * 60 * 1_000L

    fun schedule(context: Context, now: Instant = Instant.now(), zoneId: ZoneId = ZoneId.systemDefault()) {
        val applicationContext = context.applicationContext
        val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            REQUEST_CODE,
            Intent(applicationContext, WidgetDayRolloverReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setWindow(
            AlarmManager.RTC,
            WidgetDayRolloverContract.nextTriggerEpochMs(now, zoneId),
            WINDOW_MS,
            pendingIntent,
        )
    }
}

class WidgetDayRolloverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != null && intent.action !in FoodWidgetContract.dayRefreshActions) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runWidgetRollover(
                    reschedule = { WidgetDayRolloverScheduler.schedule(context) },
                    redraw = { FoodBlobWidgetUpdater.updateAll(context.applicationContext) },
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}

internal suspend fun runWidgetRollover(
    reschedule: () -> Unit,
    redraw: suspend () -> Unit,
) {
    reschedule()
    redraw()
}

private fun accessibility(context: Context, state: WidgetContentState): WidgetAccessibility {
    val counts = state.counts
    val blob = if (state.available) {
        context.resources.getQuantityString(
            R.plurals.blob_count_description,
            counts.total,
            counts.total,
            counts.green,
            counts.yellow,
            counts.red,
        )
    } else {
        context.getString(R.string.widget_unavailable)
    }
    val addLabels = mapOf(
        FoodColor.GREEN to R.string.green_add,
        FoodColor.YELLOW to R.string.yellow_add,
        FoodColor.RED to R.string.red_add,
    )
    val removeLabels = mapOf(
        FoodColor.GREEN to R.string.green_remove,
        FoodColor.YELLOW to R.string.yellow_remove,
        FoodColor.RED to R.string.red_remove,
    )
    fun actionDescription(labelId: Int, count: Int): String = context.resources.getQuantityString(
        R.plurals.widget_action_count_description,
        count,
        context.getString(labelId),
        count,
    )
    return WidgetAccessibility(
        openBlob = context.getString(R.string.widget_open_description, blob),
        unavailable = context.getString(R.string.widget_unavailable_open),
        add = addLabels.mapValues { (_, label) -> actionDescription(label, widgetActionStatusCount(counts)) },
        remove = removeLabels.mapValues { (_, label) -> actionDescription(label, widgetActionStatusCount(counts)) },
    )
}

internal fun widgetActionStatusCount(counts: FoodCounts): Int = counts.total
