package org.example.foodblob.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.example.foodblob.R
import org.example.foodblob.domain.BlobPoint
import org.example.foodblob.domain.FoodColor
import org.example.foodblob.domain.FoodCounts
import org.example.foodblob.domain.SkinId
import org.example.foodblob.widget.FoodWidgetArtworkRenderer
import org.example.foodblob.widget.FoodWidgetGeometry
import org.example.foodblob.widget.FoodWidgetPinning
import org.example.foodblob.widget.FoodWidgetVariant
import org.example.foodblob.widget.PinRequestResult
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private enum class Destination(val label: Int, val icon: ImageVector) {
    TODAY(R.string.nav_today, Icons.Rounded.Home),
    HISTORY(R.string.nav_history, Icons.Rounded.CalendarMonth),
    SKINS(R.string.nav_skins, Icons.Rounded.ColorLens),
    SETTINGS(R.string.nav_settings, Icons.Rounded.Settings),
}

private enum class SettingsPage { WIDGETS, PRIVACY }

@Composable
internal fun FoodBlobStartupFailure() {
    FoodBlobTheme(SkinId.SKY_MEADOW) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.Info, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(18.dp))
                Text(
                    stringResource(R.string.startup_error_title),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.startup_error_detail),
                    Modifier.widthIn(max = 520.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun FoodBlobApp(
    openTodayRequests: StateFlow<Long>,
    viewModel: FoodBlobViewModel = viewModel(),
    onFirstUsableFrame: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val todayRequest by openTodayRequests.collectAsState()
    val motionAllowed = rememberMotionAllowed()
    val snackbar = remember { SnackbarHostState() }
    val view = LocalView.current
    val context = LocalContext.current
    val resources = LocalResources.current
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var destination by rememberSaveable { mutableStateOf(Destination.TODAY) }
    var historyMonthKey by rememberSaveable { mutableStateOf(YearMonth.from(LocalDate.now()).toString()) }
    var detailDate by rememberSaveable { mutableStateOf<String?>(null) }
    var portraitFlight by remember { mutableStateOf<DayPortraitFlight?>(null) }
    var contentRoot by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(motionAllowed) { if (!motionAllowed) portraitFlight = null }
    LaunchedEffect(state.selectedDateKey) {
        if (portraitFlight?.date?.let { it != state.selectedDateKey } == true) portraitFlight = null
    }
    var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmRollback by rememberSaveable { mutableStateOf(false) }
    var fullyDrawnReported by remember { mutableStateOf(false) }
    val effectScope = rememberCoroutineScope()
    var messageJob by remember { mutableStateOf<Job?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val content = pendingExport
        if (uri != null && content != null) viewModel.writeExport(uri, content)
        pendingExport = null
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.inspectImport(uri)
    }

    DisposableEffect(context, state.selectedSkin, state.onboardingComplete) {
        context.findActivity()?.let { activity ->
            val lightIcons = !state.onboardingComplete || state.selectedSkin == SkinId.SKY_MEADOW
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = lightIcons
                isAppearanceLightNavigationBars = lightIcons
            }
            if (Build.VERSION.SDK_INT >= 29) activity.window.isNavigationBarContrastEnforced = false
        }
        onDispose { }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, destination, detailDate) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && destination == Destination.TODAY && detailDate == null) {
                viewModel.refreshLocalDay()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(context, destination, detailDate) {
        val clockChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (destination == Destination.TODAY && detailDate == null) {
                    viewModel.refreshLocalDay()
                }
            }
        }
        val clockChanges = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(
            context,
            clockChangeReceiver,
            clockChanges,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(clockChangeReceiver) }
    }

    LaunchedEffect(todayRequest) {
        if (todayRequest > 0) {
            destination = Destination.TODAY
            detailDate = null
            settingsPage = null
            viewModel.selectToday()
        }
    }

    LaunchedEffect(state.snapshot.revision) {
        if (!fullyDrawnReported && state.snapshot.revision.isNotEmpty()) {
            withFrameNanos { }
            fullyDrawnReported = true
            onFirstUsableFrame()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is FoodBlobEffect.Haptic -> view.performHaptic(effect.cue)
                is FoodBlobEffect.Message -> {
                    if (effect.notice == Notice.SAVED || effect.notice == Notice.REMOVED) return@collect
                    messageJob?.cancel()
                    snackbar.currentSnackbarData?.dismiss()
                    messageJob = effectScope.launch {
                        val result = snackbar.showSnackbar(
                            message = resources.getString(effect.notice.stringId()),
                            actionLabel = resources.getString(R.string.undo).takeIf {
                                effect.undoDateKey != null
                            },
                            duration = SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            effect.undoDateKey?.let(viewModel::undoSnackbarAction)
                        }
                    }
                }
                is FoodBlobEffect.ExportReady -> {
                    pendingExport = effect.json
                    exportLauncher.launch(resources.getString(R.string.export_file_name))
                }
            }
        }
    }

    fun back(): Boolean = when {
        detailDate != null -> {
            detailDate = null
            portraitFlight = null
            true
        }
        settingsPage != null -> {
            settingsPage = null
            true
        }
        destination != Destination.TODAY -> {
            destination = Destination.TODAY
            viewModel.selectToday()
            true
        }
        else -> false
    }
    BackHandler(enabled = detailDate != null || settingsPage != null || destination != Destination.TODAY) { back() }

    FoodBlobTheme(state.selectedSkin, motionAllowed) {
        if (state.onboardingComplete) BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .semantics { testTagsAsResourceId = true },
        ) {
            val useRail = maxWidth >= 600.dp
            if (destination != Destination.TODAY && detailDate == null) {
                Box(Modifier.fillMaxSize().background(state.selectedSkin.menuPalette().background))
            } else {
                WorldBackground(state.selectedSkin, motionAllowed, Modifier.fillMaxSize())
            }
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
                snackbarHost = { SnackbarHost(snackbar, Modifier.testTag("snackbar-host")) },
                bottomBar = {
                    if (!useRail && detailDate == null && settingsPage == null) {
                        DestinationBar(destination, state.selectedSkin) {
                            destination = it
                            if (it == Destination.TODAY) viewModel.selectToday()
                        }
                    }
                },
            ) { insets ->
                Row(Modifier.fillMaxSize().padding(insets)) {
                    if (useRail) {
                        DestinationRail(destination, state.selectedSkin) {
                            destination = it
                            detailDate = null
                            settingsPage = null
                            if (it == Destination.TODAY) viewModel.selectToday()
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().onGloballyPositioned {contentRoot=it.boundsInWindow().topLeft}.liquidPageArrival(Triple(destination, detailDate, settingsPage))) {
                        CompositionLocalProvider(LocalDayPortrait provides portraitFlight) {
                        when {
                            detailDate != null -> DayDetailScreen(
                                state = state,
                                motionAllowed = motionAllowed,
                                onBack = { detailDate = null;portraitFlight=null },
                                onSelectDate = viewModel::selectDate,
                                onIncrement = viewModel::increment,
                                onDecrement = viewModel::decrement,
                                onUndo = viewModel::undo,
                            )
                            settingsPage == SettingsPage.WIDGETS -> SecondaryMenuTheme(state.selectedSkin) {
                                WidgetSetupScreen(state.selectedSkin) { settingsPage = null }
                            }
                            settingsPage == SettingsPage.PRIVACY -> SecondaryMenuTheme(state.selectedSkin) {
                                PrivacyScreen { settingsPage = null }
                            }
                            else -> when (destination) {
                                Destination.TODAY -> TodayScreen(
                                    state = state,
                                    motionAllowed = motionAllowed,
                                    onSelectDate = viewModel::selectDate,
                                    onIncrement = viewModel::increment,
                                    onDecrement = viewModel::decrement,
                                    onUndo = viewModel::undo,
                                )
                                Destination.HISTORY -> SecondaryMenuTheme(state.selectedSkin) {
                                    HistoryScreen(state,historyMonthKey,{historyMonthKey=it}) { dateKey,counts,bounds ->
                                        portraitFlight=if(motionAllowed&&!counts.isEmpty) DayPortraitFlight(dateKey,counts,state.selectedSkin,bounds) else null
                                        viewModel.selectDate(dateKey)
                                        detailDate = dateKey
                                    }
                                }
                                Destination.SKINS -> SecondaryMenuTheme(state.selectedSkin) {
                                    SkinsScreen(state.selectedSkin, state.snapshot.counts(LocalDate.now().toString()), viewModel::selectSkin)
                                }
                                Destination.SETTINGS -> SecondaryMenuTheme(state.selectedSkin) {
                                    SettingsScreen(
                                        counts = state.snapshot.counts(LocalDate.now().toString()),
                                        skin = state.selectedSkin,
                                        recoveredLocalStore = state.recoveredLocalStore,
                                        hasImportRecovery = state.importRecoveryPreview != null,
                                        onWidgets = { settingsPage = SettingsPage.WIDGETS },
                                        onPrivacy = { settingsPage = SettingsPage.PRIVACY },
                                        onExport = viewModel::requestExport,
                                        onImport = { importLauncher.launch(arrayOf("application/json", "text/json")) },
                                        onRestorePrevious = { confirmRollback = true },
                                        onWelcome = viewModel::replayOnboarding,
                                        onDelete = { confirmDelete = true },
                                    )
                                }
                            }
                        }
                        }
                        portraitFlight?.let { flight -> DayPortraitOverlay(flight,contentRoot) { if(portraitFlight===flight) portraitFlight=null } }
                    }
                }
            }
        }

        if (!state.onboardingComplete) Onboarding(motionAllowed, viewModel::completeOnboarding)
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(stringResource(R.string.delete_title)) },
                text = { Text(stringResource(R.string.delete_detail)) },
                confirmButton = {
                    LiquidTextButton(onClick = {
                        confirmDelete = false
                        viewModel.deleteAll()
                    }) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    LiquidTextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
        state.importPreview?.let { preview ->
            AlertDialog(
                modifier = Modifier.testTag("import-preview-dialog"),
                onDismissRequest = viewModel::cancelImport,
                icon = { Icon(Icons.Rounded.FileDownload, null) },
                title = { Text(stringResource(R.string.import_preview_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            pluralStringResource(
                                R.plurals.import_preview_days,
                                preview.dayCount,
                                preview.dayCount,
                            ),
                        )
                        Text(
                            stringResource(
                                R.string.import_preview_range,
                                preview.oldestDateKey ?: stringResource(R.string.import_preview_none),
                                preview.newestDateKey ?: stringResource(R.string.import_preview_none),
                            ),
                        )
                        Text(stringResource(R.string.import_preview_total, preview.total))
                        if (preview.duplicateDaysCollapsed > 0) {
                            Text(
                                pluralStringResource(
                                    R.plurals.import_preview_duplicates,
                                    preview.duplicateDaysCollapsed,
                                    preview.duplicateDaysCollapsed,
                                ),
                            )
                        }
                        Text(stringResource(R.string.import_preview_warning))
                    }
                },
                confirmButton = {
                    LiquidTextButton(
                        modifier = Modifier.testTag("import-confirm"),
                        enabled = !state.busy,
                        onClick = viewModel::confirmImport,
                    ) { Text(stringResource(R.string.import_replace)) }
                },
                dismissButton = {
                    LiquidTextButton(onClick = viewModel::cancelImport) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
        if (confirmRollback) {
            val recovery = state.importRecoveryPreview
            AlertDialog(
                modifier = Modifier.testTag("import-rollback-dialog"),
                onDismissRequest = { confirmRollback = false },
                icon = { Icon(Icons.Rounded.Restore, null) },
                title = { Text(stringResource(R.string.restore_previous_title)) },
                text = {
                    Text(
                        if (recovery == null) stringResource(R.string.restore_previous_unavailable)
                        else pluralStringResource(
                            R.plurals.restore_previous_detail,
                            recovery.dayCount,
                            recovery.dayCount,
                        ),
                    )
                },
                confirmButton = {
                    LiquidTextButton(
                        enabled = recovery != null && !state.busy,
                        onClick = {
                            confirmRollback = false
                            viewModel.restorePreviousState()
                        },
                    ) { Text(stringResource(R.string.restore_previous_action)) }
                },
                dismissButton = {
                    LiquidTextButton(onClick = { confirmRollback = false }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }
}

@Composable
private fun DestinationBar(selected: Destination, skin: SkinId, onSelect: (Destination) -> Unit) {
    val palette = skin.menuPalette()
    val view = LocalView.current
    NavigationBar(containerColor = palette.surface, tonalElevation = 0.dp) {
        Destination.entries.forEach { destination ->
            val feedback = rememberLiquidControl(CircleShape, palette.green)
            NavigationBarItem(
                modifier = Modifier.testTag(destination.testTag()),
                interactionSource = feedback.interactions,
                selected = destination == selected,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = palette.ink,
                    selectedTextColor = palette.ink,
                    indicatorColor = palette.green.copy(alpha = if (skin == SkinId.SHRINE) .19f else .15f),
                    unselectedIconColor = palette.mutedInk,
                    unselectedTextColor = palette.mutedInk,
                ),
                onClick = { if (destination != selected) view.performBlobContactHaptic(); onSelect(destination) },
                icon = { Icon(destination.icon, null, feedback.modifier.liquidSelection(destination == selected)) },
                label = { Text(stringResource(destination.labelId(skin))) },
            )
        }
    }
}

@Composable
private fun DestinationRail(selected: Destination, skin: SkinId, onSelect: (Destination) -> Unit) {
    val palette = skin.menuPalette()
    val view = LocalView.current
    NavigationRail(modifier = Modifier.fillMaxHeight(), containerColor = palette.surface) {
        Spacer(Modifier.height(12.dp))
        Destination.entries.forEach { destination ->
            val feedback = rememberLiquidControl(CircleShape, palette.green)
            NavigationRailItem(
                modifier = Modifier.testTag(destination.testTag()),
                interactionSource = feedback.interactions,
                selected = destination == selected,
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = palette.ink,
                    selectedTextColor = palette.ink,
                    indicatorColor = palette.green.copy(alpha = if (skin == SkinId.SHRINE) .19f else .15f),
                    unselectedIconColor = palette.mutedInk,
                    unselectedTextColor = palette.mutedInk,
                ),
                onClick = { if (destination != selected) view.performBlobContactHaptic(); onSelect(destination) },
                icon = { Icon(destination.icon, null, feedback.modifier.liquidSelection(destination == selected)) },
                label = { Text(stringResource(destination.labelId(skin))) },
            )
        }
    }
}

@Composable
private fun TodayScreen(
    state: FoodBlobUiState,
    motionAllowed: Boolean,
    onSelectDate: (String) -> Unit,
    onIncrement: (FoodColor, (FoodCounts) -> Unit, () -> Unit) -> Unit,
    onDecrement: (FoodColor) -> Unit,
    onUndo: () -> Unit,
) {
    val feedback = rememberConnectedBlobFeedback("${state.selectedDateKey}:${state.selectedSkin}", motionAllowed)
    fun add(color: FoodColor) {
        val request = feedback.beginAdd(state.counts)
        val origin = feedback.rowOrigins[color]
        var acceptedCounts: FoodCounts? = null
        onIncrement(color, { accepted ->
            acceptedCounts = accepted
            if (origin != null) feedback.accept(request.generation, color, accepted, origin, motionAllowed)
        }, { feedback.finishAdd(request, acceptedCounts) })
    }
    fun remove(color: FoodColor) { feedback.interrupt(); onDecrement(color) }
    fun undo() { feedback.requestUndo(state.counts); onUndo() }
    CompositionLocalProvider(LocalBlobFeedback provides feedback) {
        BoxWithConstraints(Modifier.fillMaxSize().testTag("today-screen").onGloballyPositioned {
            feedback.rootOffset = it.boundsInWindow().topLeft
        }) {
            val wide = maxWidth >= 700.dp
            val compactHeight = maxHeight < 620.dp
            if (wide) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BlobPanel(state, motionAllowed, Modifier.weight(1f), onSelectDate, compactHeight)
                    Column(Modifier.weight(1f).widthIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                        if (state.hasStoreError) StoreErrorCard()
                        CounterPanel(state, motionAllowed, ::add, ::remove, ::undo, Modifier.fillMaxWidth())
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item { BlobPanel(state, motionAllowed, Modifier.fillMaxWidth(), onSelectDate, compactHeight) }
                    if (state.hasStoreError) item { StoreErrorCard() }
                    item { CounterPanel(state, motionAllowed, ::add, ::remove, ::undo, Modifier.fillMaxWidth()) }
                }
            }
            FoodDropOverlay(feedback, state.selectedSkin)
        }
    }
}

@Composable
private fun BlobPanel(
    state: FoodBlobUiState,
    motionAllowed: Boolean,
    modifier: Modifier = Modifier,
    onSelectDate: ((String) -> Unit)? = null,
    compactHeight: Boolean = false,
) {
    val countDescription = if (state.counts.isEmpty) {
        stringResource(R.string.empty_blob)
    } else {
        pluralStringResource(
            R.plurals.blob_count_description,
            state.counts.total,
            state.counts.total,
            state.counts.green,
            state.counts.yellow,
            state.counts.red,
        )
    }
    var horizontalDrag by remember(state.selectedDateKey) { mutableFloatStateOf(0f) }
    val dateSwipe = if (onSelectDate != null) {
        Modifier.pointerInput(state.selectedDateKey, state.canMoveBackward) {
            detectHorizontalDragGestures(
                onDragStart = { horizontalDrag = 0f },
                onHorizontalDrag = { _, amount -> horizontalDrag += amount },
                onDragEnd = {
                    when {
                        horizontalDrag > 56f && state.canMoveBackward ->
                            onSelectDate(state.selectedDate.minusDays(1).toString())
                        horizontalDrag < -56f && state.selectedDate < LocalDate.now() ->
                            onSelectDate(state.selectedDate.plusDays(1).toString())
                    }
                    horizontalDrag = 0f
                },
                onDragCancel = { horizontalDrag = 0f },
            )
        }
    } else Modifier
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (onSelectDate != null) {
            Row(
                Modifier.fillMaxWidth().widthIn(max = 520.dp).then(dateSwipe),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LiquidIconButton(
                    onClick = { onSelectDate(state.selectedDate.minusDays(1).toString()) },
                    enabled = state.canMoveBackward,
                    modifier = Modifier.size(48.dp).testTag("previous-day"),
                ) { Icon(Icons.Rounded.ChevronLeft, stringResource(R.string.previous_day)) }
                Column(Modifier.weight(1f).testTag("today-title"),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(
                        text=if(state.selectedDate==LocalDate.now()) stringResource(R.string.today) else state.selectedDate.formatted(),
                        style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,
                    )
                    if(state.selectedDate==LocalDate.now()) Text(state.selectedDate.formatted(),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onBackground.copy(alpha=.62f))
                }
                LiquidIconButton(
                    onClick = { onSelectDate(state.selectedDate.plusDays(1).toString()) },
                    enabled = state.selectedDate < LocalDate.now(),
                    modifier = Modifier.size(48.dp).testTag("next-day"),
                ) { Icon(Icons.Rounded.ChevronRight, stringResource(R.string.next_day)) }
            }
        } else {
            Text(
                text = state.selectedDate.formatted(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        if (state.selectedSkin == SkinId.SHRINE) {
            ShrineBlobStage(state, countDescription, motionAllowed, compactHeight)
        } else {
            MeadowBlobStage(state, countDescription, motionAllowed, compactHeight)
        }
    }
}

@Composable
private fun ShrineBlobStage(state: FoodBlobUiState,countDescription: String,motionAllowed: Boolean,compactHeight: Boolean) {
    CoreBlobStage(state,countDescription,motionAllowed,compactHeight,"blob-stage-shrine")
}

@Composable
private fun MeadowBlobStage(state: FoodBlobUiState,countDescription: String,motionAllowed: Boolean,compactHeight: Boolean) {
    CoreBlobStage(state,countDescription,motionAllowed,compactHeight,"blob-stage-meadow")
}

@Composable
private fun CoreBlobStage(state: FoodBlobUiState,countDescription: String,motionAllowed: Boolean,compactHeight: Boolean,tag: String) {
    Column(Modifier.fillMaxWidth().widthIn(max=520.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(if(compactHeight) 226.dp else 264.dp).testTag(tag),contentAlignment=Alignment.Center) {
            BlobBreathingHalo(state.counts,state.selectedSkin,motionAllowed,Modifier.size(if(compactHeight) 208.dp else 240.dp))
            InteractiveBlobFrame(state,countDescription,motionAllowed,Modifier.size(if(compactHeight) 246.dp else 278.dp,if(compactHeight) 226.dp else 258.dp))
        }
        Row(Modifier.heightIn(min=46.dp).padding(bottom=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            BlobTotal(state.counts.total,motionAllowed,MaterialTheme.colorScheme.onBackground)
            Text(stringResource(R.string.blob_logged),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onBackground.copy(alpha=.66f))
            if(state.streak>0) {
                Text("·",color=MaterialTheme.colorScheme.onBackground.copy(alpha=.4f))
                Icon(Icons.Rounded.LocalFireDepartment,null,Modifier.size(14.dp),tint=MaterialTheme.colorScheme.onBackground.copy(alpha=.55f))
                Text("${state.streak}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onBackground.copy(alpha=.66f))
            }
        }
    }
}

@Composable
private fun InteractiveBlobFrame(
    state: FoodBlobUiState,
    countDescription: String,
    motionAllowed: Boolean,
    modifier: Modifier = Modifier,
) {
    val portrait=LocalDayPortrait.current?.takeIf { it.date==state.selectedDateKey }
    val inset=with(LocalDensity.current){8.dp.toPx()}
    Box(modifier.testTag("living-blob-frame").onGloballyPositioned { if(portrait?.target==Rect.Zero) {val rect=it.boundsInWindow();portrait.target=Rect(rect.left+inset,rect.top+inset,rect.right-inset,rect.bottom-inset)} }
        .graphicsLayer { alpha=if(portrait!=null&&portrait.progress.value<1f) 0f else 1f }, contentAlignment = Alignment.Center) {
        LivingFoodBlob(
            counts = state.counts,
            contentDescription = countDescription,
            skin = state.selectedSkin,
            motionAllowed = motionAllowed,
            interactive = true,
            animationScope = "${state.selectedDateKey}:${state.selectedSkin}",
            modifier = Modifier.fillMaxSize().padding(8.dp),
        )

    }
}

@Composable
private fun ShrineStreakPill(streak: Int) {
    val description = pluralStringResource(R.plurals.streak_days, streak, streak)
    Row(
        Modifier
            .padding(horizontal = 4.dp)
            .background(Color(0x1AE8C978), CircleShape)
            .border(1.dp, Color(0x66E8C978), CircleShape)
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Rounded.LocalFireDepartment, null, Modifier.size(16.dp), tint = Color(0xFFE8C978))
        Text("$streak", color = Color(0xFFE8C978), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BlobTotal(total: Int, motionAllowed: Boolean, color: Color) {
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    var previous by remember { mutableStateOf(total) }
    LaunchedEffect(total, motionAllowed) {
        if (motionAllowed && total != previous) {
            scale.animateTo(
                1.12f,
                androidx.compose.animation.core.tween(
                    110,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                ),
            )
            delay(170)
            scale.animateTo(
                .94f,
                androidx.compose.animation.core.tween(
                    170,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                ),
            )
            scale.animateTo(
                1f,
                androidx.compose.animation.core.spring(
                    dampingRatio = .76f,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
                ),
            )
        } else if (!motionAllowed) {
            scale.snapTo(1f)
        }
        previous = total
    }
    Text(
        "$total",
        Modifier.testTag("blob-total").graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        color = color,
        fontSize = 28.sp,
        fontWeight = FontWeight.Black,
    )
}

@Composable
private fun CounterPanel(
    state: FoodBlobUiState,
    motionAllowed: Boolean,
    onIncrement: (FoodColor) -> Unit,
    onDecrement: (FoodColor) -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.widthIn(max=520.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        FoodColor.entries.forEach { color ->
            FoodCounterRow(
                color = color,
                count = state.counts.count(color),
                busy = state.busy,
                skin = state.selectedSkin,
                motionAllowed = motionAllowed,
                onIncrement = onIncrement,
                onDecrement = onDecrement,
            )
        }
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(stringResource(R.string.color_drag_hint),Modifier.weight(1f).padding(end=8.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onBackground)
            LiquidTextButton(onClick=onUndo,enabled=state.canUndo&&!state.busy,modifier=Modifier.heightIn(min=48.dp).testTag("undo")) {
                Icon(Icons.AutoMirrored.Rounded.Undo,null,Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.undo))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FoodCounterRow(
    color: FoodColor,
    count: Int,
    busy: Boolean,
    skin: SkinId,
    motionAllowed: Boolean,
    onIncrement: (FoodColor) -> Unit,
    onDecrement: (FoodColor) -> Unit,
) {
    val palette = skin.palette()
    val tint = when (color) {
        FoodColor.GREEN -> palette.green
        FoodColor.YELLOW -> palette.yellow
        FoodColor.RED -> palette.red
    }
    val label = stringResource(color.labelId())
    val displayLabel = label
    val addLabel = stringResource(color.addLabelId())
    val removeLabel = stringResource(color.removeLabelId())
    val stateText = stringResource(R.string.color_count, label, count)
    val feedback = LocalBlobFeedback.current
    val currentIncrement by rememberUpdatedState(onIncrement)
    var rowBounds by remember { mutableStateOf(Rect.Zero) }
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    var pressLightX by remember { mutableFloatStateOf(.35f) }
    val pressDepth = androidx.compose.animation.core.animateFloatAsState(
        if (pressed) 1f else 0f,
        if (!motionAllowed) androidx.compose.animation.core.snap()
        else if (pressed) androidx.compose.animation.core.tween(85)
        else androidx.compose.animation.core.spring(dampingRatio = .48f, stiffness = 620f),
        label = "food lens compression",
    )
    val countPulse = remember(feedback) { androidx.compose.animation.core.Animatable(0f) }
    val acceptedWash = remember(feedback) { androidx.compose.animation.core.Animatable(1f) }
    var previousCount by remember(feedback) { mutableStateOf(count) }
    LaunchedEffect(count, motionAllowed, feedback) {
        val changed = previousCount != count
        val direction = if (count >= previousCount) 1f else -1f
        previousCount = count
        if (changed && motionAllowed) {
            countPulse.snapTo(direction)
            acceptedWash.snapTo(0f)
            coroutineScope {
                launch { countPulse.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = .58f, stiffness = 350f)) }
                launch { acceptedWash.animateTo(1f, androidx.compose.animation.core.tween(430)) }
            }
        } else {
            countPulse.snapTo(0f)
            acceptedWash.snapTo(1f)
        }
    }
    LaunchedEffect(interactions, feedback) {
        interactions.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) {
                val point = rowBounds.topLeft + interaction.pressPosition
                pressLightX = if (rowBounds.width > 0f) (interaction.pressPosition.x / rowBounds.width).coerceIn(.12f, .88f) else .35f
                feedback?.rowOrigins?.set(color, BlobPoint(point.x.toDouble(), point.y.toDouble()))
            }
        }
    }
    val rowShape = if (skin == SkinId.SHRINE) CircleShape else RoundedCornerShape(24.dp)
    // The hit surface and launch origin stay still while the visible lens moves inside it.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .onGloballyPositioned {
                rowBounds = it.boundsInWindow()
                if (!pressed) feedback?.rowOrigins?.set(color, BlobPoint(rowBounds.center.x.toDouble(), rowBounds.center.y.toDouble()))
            }
            .testTag("add-${color.storageId}")
            .semantics {
                role = Role.Button
                stateDescription = stateText
                customActions = listOf(CustomAccessibilityAction(removeLabel) {
                    if (count > 0 && !busy) onDecrement(color)
                    count > 0 && !busy
                })
            }
            .clickable(interactionSource=interactions,indication=null,enabled=!busy,onClickLabel=addLabel,onClick={onIncrement(color)})
            .then(if(feedback!=null&&!busy) Modifier.pointerInput(feedback,color) {
                var world=Offset.Zero
                detectDragGesturesAfterLongPress(
                    onDragStart={ point -> world=rowBounds.topLeft+point; feedback.beginDrag(color,world) },
                    onDrag={change,amount -> change.consume(); world+=amount; feedback.moveDrag(world)},
                    onDragCancel={feedback.cancelDrag()},
                    onDragEnd={
                        val point=feedback.dragPoint
                        val accepted=feedback.finishDrag()
                        if(accepted!=null&&point!=null) {
                            val previousOrigin=feedback.rowOrigins[accepted]
                            feedback.rowOrigins[accepted]=BlobPoint(point.x.toDouble(),point.y.toDouble())
                            currentIncrement(accepted)
                            if(previousOrigin!=null) feedback.rowOrigins[accepted]=previousOrigin
                        }
                    },
                )
            } else Modifier),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().graphicsLayer {
                val compression = if (motionAllowed) pressDepth.value else 0f
                val pulse = if (motionAllowed) countPulse.value else 0f
                scaleX = 1f - .025f * compression + .026f * pulse
                scaleY = 1f - .075f * compression - .055f * pulse
                translationY = 4.dp.toPx() * compression
                shadowElevation = (if (pressed) .5.dp else 2.dp).toPx()
                shape = rowShape
                clip = true
            }.drawWithContent {
                drawContent()
                // A small fixed glint and pressed shade keep tactile feedback readable without motion.
                drawRect(Brush.verticalGradient(listOf(
                    Color.White.copy(alpha = .15f + if (motionAllowed) .12f * kotlin.math.abs(countPulse.value) else 0f),
                    Color.Transparent,
                    Color.Black.copy(alpha = if (skin == SkinId.SHRINE) .09f else .05f),
                )))
                val shade = pressDepth.value.coerceIn(0f, 1f)
                if (shade > 0f) drawRect(Color.Black.copy(alpha = .07f * shade))
                // A curved bright lip makes the colour lens feel filled with gel. Its
                // reflection follows the finger without moving the stable hit surface.
                val glint = Offset(size.width * pressLightX, 2.dp.toPx())
                drawOval(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = .34f + shade * .12f), Color.Transparent), glint, size.width * .34f),
                    topLeft = Offset(8.dp.toPx(), 2.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(size.width - 16.dp.toPx(), 9.dp.toPx()),
                )
                drawLine(tint.copy(alpha = .15f), Offset(24.dp.toPx(), size.height-3.dp.toPx()), Offset(size.width-24.dp.toPx(), size.height-3.dp.toPx()), 1.dp.toPx())
                val wash = if (motionAllowed) LiquidFeedbackMotion.releaseLight(acceptedWash.value) else 0f
                if (wash > .001f) {
                    val origin = Offset(size.width*pressLightX, size.height*.55f)
                    val radius = size.width*(.10f + .95f*acceptedWash.value)
                    drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = wash*.25f),Color.Transparent),origin,radius),radius,origin)
                    drawOval(Color.White.copy(alpha = wash*.25f),origin-Offset(radius,radius*.28f),
                        androidx.compose.ui.geometry.Size(radius*2,radius*.56f),style = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx()))
                }
            },
            colors = CardDefaults.cardColors(
                // Opaque fill prevents the elevated surface from showing its own shadow through the content.
                containerColor = tint.copy(alpha = if (skin == SkinId.SHRINE) .17f else .37f).compositeOver(palette.surface),
                contentColor = MaterialTheme.colorScheme.onBackground,
            ),
            border = BorderStroke(.5.dp, tint.copy(alpha = if (skin == SkinId.SHRINE) .28f else .16f)),
            shape = rowShape,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(18.dp)
                        .then(if (skin == SkinId.SHRINE) Modifier.rotate(45f) else Modifier)
                        .background(tint, if (skin == SkinId.SHRINE) RoundedCornerShape(3.dp) else CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    displayLabel,
                    Modifier.weight(1f),
                    color = if (skin == SkinId.SHRINE) ShrinePalette.ink else MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    letterSpacing = 0.sp,
                )
                Text(
                    "$count",
                    modifier = Modifier.graphicsLayer {
                        val pulse = if (motionAllowed) countPulse.value else 0f
                        scaleX = 1f + .16f * kotlin.math.abs(pulse)
                        scaleY = scaleX
                        translationY = -3.dp.toPx() * pulse
                    },
                    color = if (skin == SkinId.SHRINE) ShrinePalette.ink else MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp,
                )
                Spacer(Modifier.width(8.dp))
                Spacer(Modifier.size(48.dp))
                if (skin != SkinId.SHRINE) Icon(Icons.Rounded.Add, null, Modifier.padding(horizontal = 8.dp).graphicsLayer {
                    val pulse = if (motionAllowed) countPulse.value else 0f
                    rotationZ = -12f * pulse
                    scaleX = 1f + .18f * kotlin.math.abs(pulse)
                    scaleY = scaleX
                })
            }
        }
        LiquidIconButton(
            onClick = { onDecrement(color) },
            haptic = false,
            motionAllowed = motionAllowed,
            enabled = count > 0 && !busy,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = if (skin == SkinId.SHRINE) 8.dp else 48.dp)
                .size(48.dp)
                .then(
                    if (skin == SkinId.SHRINE) {
                        Modifier
                            .background(Color(0x0F80E8DB), CircleShape)
                            .border(1.dp, Color(0x2E80E8DB), CircleShape)
                    } else Modifier
                )
                .testTag("remove-${color.storageId}")
                .semantics { role = Role.Button },
        ) { Icon(Icons.Rounded.Remove, removeLabel) }
    }
}

/** Secondary pages use the same warm paper and quiet ink as the Apple menus. */
private fun SkinId.menuPalette(): FoodBlobPalette = if (this == SkinId.SKY_MEADOW) {
    palette().copy(
        background = Color(0xFFF5EFDE),
        surface = Color(0xFFFCF5E8),
        ink = Color(0xFF3B3026),
        mutedInk = Color(0xFF80715C),
        outline = Color(0xFFB8AB93),
    )
} else palette().copy(mutedInk = palette().ink.copy(alpha = .68f))

@Composable
private fun SecondaryMenuTheme(skin: SkinId, content: @Composable () -> Unit) {
    val palette = skin.menuPalette()
    CompositionLocalProvider(LocalLiquidStrength provides .42f) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            background = palette.background,
            surface = palette.surface,
            onBackground = palette.ink,
            onSurface = palette.ink,
            onSurfaceVariant = palette.mutedInk,
            outline = palette.outline,
            primary = if (skin == SkinId.SHRINE) Color(0xFF80E8DB) else palette.ink,
        ),
        content = content,
    )
    }
}

@Composable
private fun MenuTitle(title: String, tag: String) {
    Text(
        title,
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp).testTag(tag),
        color = MaterialTheme.colorScheme.onBackground,
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun MenuCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = null,
        shape = RoundedCornerShape(24.dp),
        content = content,
    )
}

@Composable
private fun HistoryScreen(state: FoodBlobUiState,monthKey: String,onMonth:(String)->Unit,onOpenDay: (String,FoodCounts,Rect)->Unit) {
    val today=LocalDate.now()
    val locale=LocalConfiguration.current.locales[0]
    val firstDay=remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val month=YearMonth.parse(monthKey)
    val cells=remember(month,firstDay) { CoreCalendar.cells(month,firstDay) }
    val oldest=if(state.snapshot.days.size>=org.example.foodblob.storage.FoodStore.MAX_HISTORY_DAYS) state.snapshot.days.minOfOrNull{LocalDate.parse(it.dateKey)} else null
    val loggedDays=cells.filterNotNull().count { !state.snapshot.counts(it.toString()).isEmpty }
    val fontScale=LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val calendarWidth=maxOf(maxWidth-24.dp, (if(fontScale>=1.4f) 48f*fontScale else 48f).dp*7)
        LazyColumn(Modifier.widthIn(max=680.dp).fillMaxSize().align(Alignment.TopCenter).testTag("history-screen"),contentPadding=PaddingValues(horizontal=12.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { MenuTitle(stringResource(R.string.nav_history),"history-title") }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically) {
                    LiquidIconButton(onClick={onMonth(month.minusMonths(1).toString())},enabled=oldest==null||!month.minusMonths(1).atEndOfMonth().isBefore(oldest),modifier=Modifier.size(48.dp).testTag("history-previous-month")) {Icon(Icons.Rounded.ChevronLeft,stringResource(R.string.previous_month))}
                    Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
                        Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy",locale)),Modifier.testTag("history-month-title"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                        Text(pluralStringResource(R.plurals.logged_days,loggedDays,loggedDays),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LiquidIconButton(onClick={onMonth(month.plusMonths(1).toString())},enabled=month<YearMonth.from(today),modifier=Modifier.size(48.dp).testTag("history-next-month")) {Icon(Icons.Rounded.ChevronRight,stringResource(R.string.next_month))}
                }
            }
            item {
                Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Column(Modifier.width(calendarWidth).testTag("history-calendar"),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth().padding(bottom=6.dp)) {
                            repeat(7) { offset ->
                                Text(firstDay.plus(offset.toLong()).getDisplayName(TextStyle.NARROW,locale),Modifier.weight(1f),textAlign=TextAlign.Center,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        cells.chunked(7).forEach { week ->
                            Row(Modifier.fillMaxWidth()) {
                                repeat(7) { index ->
                                    val date=week.getOrNull(index)
                                    Box(Modifier.weight(1f)) {
                                        if(date!=null) {
                                            val counts=state.snapshot.counts(date.toString())
                                            HistoryDayCell(date,counts,state.selectedSkin,date==today,date<=today&&(oldest==null||date>=oldest)) { bounds->onOpenDay(date.toString(),counts,bounds) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.history_retention),Modifier.fillMaxWidth().padding(top=12.dp),color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall,textAlign=TextAlign.Center) }
        }
    }
}

@Composable
private fun HistoryDayCell(date: LocalDate,counts: FoodCounts,skin: SkinId,today: Boolean,enabled: Boolean,onClick:(Rect)->Unit) {
    val description=stringResource(R.string.open_day,date.formatted())
    val countDescription=pluralStringResource(R.plurals.day_offerings,counts.total,date.formatted(),counts.total)
    val feedback=rememberLiquidControl(RoundedCornerShape(16.dp),skin.palette().green)
    val view=LocalView.current
    var portraitBounds by remember { mutableStateOf(Rect.Zero) }
    Column(Modifier.fillMaxWidth().heightIn(min=76.dp).testTag("history-day-$date").graphicsLayer{alpha=if(enabled)1f else .25f}
        .clickable(interactionSource=feedback.interactions,indication=null,role=Role.Button,onClickLabel=description,enabled=enabled) { view.performBlobContactHaptic();onClick(portraitBounds) }
        .then(feedback.modifier).then(if(today) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha=.07f),RoundedCornerShape(16.dp)) else Modifier)
        .semantics(mergeDescendants=true){contentDescription=countDescription},horizontalAlignment=Alignment.CenterHorizontally) {
        Text("${date.dayOfMonth}",Modifier.padding(top=5.dp).clearAndSetSemantics{},color=if(today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=if(counts.isEmpty) .60f else 1f),style=MaterialTheme.typography.labelMedium,fontWeight=if(today) FontWeight.Bold else FontWeight.Medium)
        Box(Modifier.fillMaxWidth().height(50.dp).onGloballyPositioned {portraitBounds=it.boundsInWindow()}.clearAndSetSemantics{},contentAlignment=Alignment.Center) {
            if(counts.isEmpty) Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.16f),CircleShape))
            else LivingFoodBlob(counts,"",skin=skin,motionAllowed=false,modifier=Modifier.fillMaxSize())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayDetailScreen(
    state: FoodBlobUiState,
    motionAllowed: Boolean,
    onBack: () -> Unit,
    onSelectDate: (String) -> Unit,
    onIncrement: (FoodColor, (FoodCounts) -> Unit, () -> Unit) -> Unit,
    onDecrement: (FoodColor) -> Unit,
    onUndo: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.testTag("day-detail"),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.nav_history),style=MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    LiquidIconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("detail-back")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            TodayScreen(state, motionAllowed, onSelectDate, onIncrement, onDecrement, onUndo)
        }
    }
}

@Composable
private fun SkinsScreen(selected: SkinId,counts: FoodCounts,onSelect:(SkinId)->Unit) {
    val view=LocalView.current
    val expandedPreview = LocalDensity.current.fontScale >= 1.3f || counts.total > 999
    LazyColumn(Modifier.fillMaxSize().testTag("skins-screen").selectableGroup(),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        item { Box(Modifier.widthIn(max=560.dp).fillMaxWidth()) { MenuTitle(stringResource(R.string.nav_skins),"skins-title") } }
        items(SkinId.entries) { skin ->
            val palette=skin.menuPalette()
            val feedback=rememberLiquidControl(RoundedCornerShape(26.dp),palette.green)
            val selectedDescription=stringResource(if(skin==selected) R.string.option_selected else R.string.option_not_selected)
            Card(Modifier.widthIn(max=560.dp).fillMaxWidth().testTag("skin-${skin.storageId}")
                .semantics {stateDescription=selectedDescription}
                .selectable(skin==selected,feedback.interactions,null,role=Role.RadioButton) { if(skin!=selected)view.performBlobContactHaptic();onSelect(skin) }
                .then(feedback.modifier),colors=CardDefaults.cardColors(containerColor=palette.background),
                border=if(skin==selected) BorderStroke(1.5.dp,if(skin==SkinId.SHRINE) Color(0xFF80E8DB) else palette.ink.copy(alpha=.55f)) else null,shape=RoundedCornerShape(26.dp)) {
                Box(Modifier.fillMaxWidth()) {
                    WorldBackground(skin,false,Modifier.matchParentSize())
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(if(skin==SkinId.SKY_MEADOW) R.string.skin_meadow_name else R.string.skin_shrine_name),Modifier.testTag("skin-heading-${skin.storageId}"),color=palette.ink,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                                Text(stringResource(if(skin==SkinId.SKY_MEADOW) R.string.skin_meadow_tagline else R.string.skin_shrine_tagline),color=palette.mutedInk,style=MaterialTheme.typography.bodySmall)
                            }
                            Icon(if(skin==selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,null,Modifier.size(24.dp).liquidSelection(skin==selected),tint=if(skin==SkinId.SHRINE) Color(0xFF80E8DB) else palette.ink)
                        }
                        if (expandedPreview) {
                            Column(
                                Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(Modifier.size(138.dp, 128.dp).clearAndSetSemantics {}) {
                                    LivingFoodBlob(counts, "", skin = skin, motionAllowed = false, modifier = Modifier.fillMaxSize())
                                }
                                Text(
                                    pluralStringResource(R.plurals.day_offerings, counts.total, stringResource(R.string.today), counts.total),
                                    Modifier.fillMaxWidth(),
                                    color = palette.mutedInk,
                                    style = MaterialTheme.typography.labelMedium
                                )
                                FoodColor.entries.forEach { color ->
                                    val tint = when (color) {
                                        FoodColor.GREEN -> palette.green
                                        FoodColor.YELLOW -> palette.yellow
                                        FoodColor.RED -> palette.red
                                    }
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(Modifier.size(12.dp).background(tint, CircleShape))
                                        Text(
                                            stringResource(color.labelId()),
                                            Modifier.weight(1f),
                                            color = palette.ink,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                        Text("${counts.count(color)}", color = palette.ink, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        } else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Box(Modifier.size(138.dp,128.dp).clearAndSetSemantics{}) { LivingFoodBlob(counts,"",skin=skin,motionAllowed=false,modifier=Modifier.fillMaxSize()) }
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                                Text(pluralStringResource(R.plurals.day_offerings,counts.total,stringResource(R.string.today),counts.total),color=palette.mutedInk,style=MaterialTheme.typography.labelMedium)
                                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { FoodColor.entries.forEach { color ->
                                    val tint=when(color){FoodColor.GREEN->palette.green;FoodColor.YELLOW->palette.yellow;FoodColor.RED->palette.red}
                                    Column(horizontalAlignment=Alignment.CenterHorizontally) {
                                        Box(Modifier.size(12.dp).background(tint,CircleShape))
                                        Text("${counts.count(color)}",color=palette.ink,style=MaterialTheme.typography.labelMedium)
                                    }
                                } }
                            }
                        }
                    }
                }
            }
        }
        item { Column(Modifier.widthIn(max=560.dp).fillMaxWidth().padding(4.dp).testTag("skin-preservation"),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(stringResource(R.string.skin_preservation_title),style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
            Text(stringResource(R.string.skin_preservation_detail),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }
}

@Composable
private fun WidgetPreviewPair(skin: SkinId, modifier: Modifier = Modifier) {
    Row(modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        WidgetPreviewCard(skin, medium = false, Modifier.weight(1f).aspectRatio(1f))
        WidgetPreviewCard(skin, medium = true, Modifier.weight(2.12f).aspectRatio(364f / 170f))
    }
}

@Composable
private fun SettingsScreen(
    counts: FoodCounts,
    skin: SkinId,
    recoveredLocalStore: Boolean,
    hasImportRecovery: Boolean,
    onWidgets: () -> Unit,
    onPrivacy: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onRestorePrevious: () -> Unit,
    onWelcome: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("food_blob_ui", 0) }
    var soundEnabled by remember { mutableStateOf(preferences.getBoolean("interaction_sound", false)) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("settings-screen"),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { Box(Modifier.widthIn(max = 680.dp).fillMaxWidth()) { MenuTitle(stringResource(R.string.nav_settings), "settings-title") } }
        if (recoveredLocalStore) {
            item {
                MenuCard(Modifier.widthIn(max = 680.dp)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.error)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.recovery_notice_title), fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.recovery_notice_detail), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        item {
            SettingsSection(stringResource(R.string.app_name), "settings-section-app") {
                SettingRow(Icons.Rounded.Widgets, R.string.settings_widgets, onWidgets, subtitle = R.string.settings_widgets_subtitle, testTag = "settings-widgets")
                SettingsDivider()
                SettingRow(Icons.Rounded.Info, R.string.settings_privacy, onPrivacy, subtitle = R.string.settings_privacy_subtitle, testTag = "settings-privacy")
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_section_data), "settings-section-data") {
                SettingRow(Icons.Rounded.FileUpload, R.string.settings_export, onExport, subtitle = R.string.settings_export_subtitle)
                SettingsDivider()
                SettingRow(Icons.Rounded.FileDownload, R.string.settings_import, onImport, subtitle = R.string.settings_import_subtitle, testTag = "settings-import")
                if (hasImportRecovery) {
                    SettingsDivider()
                    SettingRow(Icons.Rounded.Restore, R.string.settings_restore_previous, onRestorePrevious, testTag = "settings-restore-previous")
                }
                SettingsDivider()
                SettingRow(Icons.Rounded.DeleteForever, R.string.settings_delete, onDelete, destructive = true, subtitle = R.string.settings_delete_subtitle, testTag = "settings-delete")
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_section_experience), "settings-section-experience") {
                BlobAppearanceControl(counts, skin)
                SettingsDivider()
                val interactionSoundLabel = stringResource(R.string.interaction_sound)
                val soundFeedback = rememberLiquidControl()
                val view = LocalView.current
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 78.dp)
                        .testTag("interaction-sound")
                        .toggleable(value = soundEnabled, interactionSource = soundFeedback.interactions,
                            indication = null, role = Role.Switch) { enabled ->
                            view.performBlobContactHaptic()
                            soundEnabled = enabled
                            preferences.edit().putBoolean("interaction_sound",enabled).apply()
                        }
                        .then(soundFeedback.modifier)
                        .semantics(mergeDescendants = true) { contentDescription = interactionSoundLabel }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.VolumeUp, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(interactionSoundLabel, style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.interaction_sound_detail), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = soundEnabled,
                        onCheckedChange = null,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
                SettingsDivider()
                SettingRow(Icons.Rounded.AutoAwesome, R.string.settings_replay_onboarding, onWelcome, subtitle = R.string.settings_welcome_subtitle, testTag = "settings-replay")
            }
        }
        item {
            MenuCard(Modifier.widthIn(max = 680.dp)) {
                Text(
                    stringResource(R.string.settings_disclaimer),
                    Modifier.padding(18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun BlobAppearanceControl(counts: FoodCounts, skin: SkinId) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences(BlobAppearance.PREFERENCES, 0) }
    val saved = LocalBlobTranslucency.current
    var draft by remember(saved) { mutableFloatStateOf(saved) }
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.blob_translucency)
    val motionAllowed = LocalLiquidMotion.current
    val feedback = rememberConnectedBlobFeedback("material-preview", motionAllowed)
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("blob-appearance"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.blob_translucency_detail), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().height(156.dp).clip(RoundedCornerShape(20.dp)).testTag("blob-appearance-preview"), contentAlignment = Alignment.Center) {
            WorldBackground(skin, false, Modifier.matchParentSize())
            CompositionLocalProvider(LocalBlobTranslucency provides draft) {
                ConnectedLivingFoodBlob(counts, stringResource(R.string.blob_preview_poke), skin, motionAllowed,
                    feedback, Modifier.size(142.dp), previewFit = true)
            }
        }
        Text(stringResource(R.string.blob_preview_poke), Modifier.align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = draft,
            onValueChange = { draft = BlobAppearance.normalize(it) },
            onValueChangeFinished = {
                BlobAppearance.save(preferences, draft)
                scope.launch { org.example.foodblob.storage.WidgetUpdateBridge.update(context) }
            },
            steps = 19,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("blob-translucency")
                .semantics { contentDescription = label },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.blob_finish_paint), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.blob_finish_jelly), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SettingsSection(title: String, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, Modifier.padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        MenuCard(content = content)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(start = 54.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .20f))
}

@Composable
private fun StoreErrorCard() {
    Card(
        Modifier.fillMaxWidth().widthIn(max = 680.dp).padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.store_error_title),
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.store_error_detail),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    label: Int,
    onClick: () -> Unit,
    destructive: Boolean = false,
    subtitle: Int? = null,
    testTag: String? = null,
) {
    val feedback = rememberLiquidControl(tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    val view = LocalView.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 78.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .clickable(interactionSource = feedback.interactions, indication = null, role = Role.Button) {
                view.performBlobContactHaptic()
                onClick()
            }
            .then(feedback.modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(26.dp), tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(label), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(stringResource(subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (!destructive) Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetSetupScreen(currentSkin: SkinId, onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedSkin by rememberSaveable { mutableStateOf(currentSkin) }
    var fullControls by rememberSaveable { mutableStateOf(false) }
    var pinResult by remember { mutableStateOf<PinRequestResult?>(null) }
    val variant = when {
        selectedSkin == SkinId.SKY_MEADOW && !fullControls -> FoodWidgetVariant.SKY_MEADOW_SMALL
        selectedSkin == SkinId.SKY_MEADOW -> FoodWidgetVariant.SKY_MEADOW_MEDIUM
        !fullControls -> FoodWidgetVariant.SHRINE_SMALL
        else -> FoodWidgetVariant.SHRINE_MEDIUM
    }
    val pinSupported = remember(context) { FoodWidgetPinning.isSupported(context) }

    Scaffold(
        modifier = Modifier.testTag("widget-setup-screen"),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.settings_widgets)) },
                navigationIcon = {
                    LiquidIconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("information-back")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.settings_widgets_detail),
                    Modifier.fillMaxWidth().widthIn(max = 620.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            item {
                WidgetChoiceRow(
                    title = stringResource(R.string.widget_choose_world),
                    firstLabel = stringResource(R.string.skin_meadow_name),
                    secondLabel = stringResource(R.string.skin_shrine_name),
                    firstSelected = selectedSkin == SkinId.SKY_MEADOW,
                    firstTag = "widget-skin-sky-meadow",
                    secondTag = "widget-skin-shrine",
                    onFirst = { selectedSkin = SkinId.SKY_MEADOW },
                    onSecond = { selectedSkin = SkinId.SHRINE },
                )
            }
            item {
                WidgetChoiceRow(
                    title = stringResource(R.string.widget_choose_controls),
                    firstLabel = stringResource(R.string.widget_quick_add),
                    secondLabel = stringResource(R.string.widget_full_controls),
                    firstSelected = !fullControls,
                    firstTag = "widget-presentation-quick-add",
                    secondTag = "widget-presentation-full-control",
                    onFirst = { fullControls = false },
                    onSecond = { fullControls = true },
                )
            }
            item {
                WidgetPreviewCard(
                    selectedSkin,
                    medium = fullControls,
                    modifier = Modifier
                        .widthIn(max = if (fullControls) 364.dp else 220.dp)
                        .fillMaxWidth()
                        .aspectRatio(if (fullControls) 364f / 170f else 1f),
                )
            }
            item {
                LiquidButton(
                    onClick = { pinResult = FoodWidgetPinning.request(context, variant) },
                    enabled = pinSupported,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 620.dp)
                        .heightIn(min = 48.dp)
                        .testTag("widget-pin"),
                ) {
                    Icon(Icons.Rounded.Widgets, null)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.widget_add_home))
                }
            }
            if (pinResult == PinRequestResult.REQUEST_SENT) {
                item { Text(stringResource(R.string.widget_pin_prompt), Modifier.fillMaxWidth().widthIn(max = 620.dp)) }
            }
            if (!pinSupported || pinResult == PinRequestResult.UNSUPPORTED || pinResult == PinRequestResult.FAILED) {
                item {
                    Text(
                        stringResource(R.string.widget_pin_fallback),
                        Modifier.fillMaxWidth().widthIn(max = 620.dp).testTag("widget-pin-fallback"),
                    )
                }
            }
            item {
                MenuCard(Modifier.widthIn(max = 620.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        listOf(
                            R.string.widget_setup_step_one_title to R.string.widget_setup_step_one_detail,
                            R.string.widget_setup_step_two_title to R.string.widget_setup_step_two_detail,
                            R.string.widget_setup_step_three_title to R.string.widget_setup_step_three_detail,
                        ).forEachIndexed { index, (titleId, detailId) ->
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
                                Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape), contentAlignment = Alignment.Center) {
                                    Text("${index + 1}", color = MaterialTheme.colorScheme.surface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(stringResource(titleId), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(stringResource(detailId), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.widget_setup_static_note),
                    Modifier.fillMaxWidth().widthIn(max = 620.dp).padding(12.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun WidgetChoiceRow(
    title: String,
    firstLabel: String,
    secondLabel: String,
    firstSelected: Boolean,
    firstTag: String,
    secondTag: String,
    onFirst: () -> Unit,
    onSecond: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                Triple(firstLabel, firstSelected, Pair(firstTag, onFirst)),
                Triple(secondLabel, !firstSelected, Pair(secondTag, onSecond)),
            ).forEach { (label, selected, taggedAction) ->
                val feedback = rememberLiquidControl()
                val view = LocalView.current
                val selectedDescription = stringResource(
                    if (selected) R.string.option_selected else R.string.option_not_selected,
                )
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .testTag(taggedAction.first)
                        .selectable(selected, feedback.interactions, indication = null, role = Role.RadioButton) {
                            if (!selected) view.performBlobContactHaptic()
                            taggedAction.second()
                        }
                        .then(feedback.modifier)
                        .semantics {
                            role = Role.RadioButton
                            stateDescription = selectedDescription
                        },
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(
                        if (selected) 1.5.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .72f)
                        else MaterialTheme.colorScheme.outline.copy(alpha = .24f),
                    ),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        if (selected) Icon(Icons.Rounded.Check, stringResource(R.string.skin_selected), Modifier.liquidSelection(selected))
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacyScreen(onBack: () -> Unit) {
    DetailedInformationScreen(
        title = stringResource(R.string.settings_privacy),
        detail = stringResource(R.string.settings_privacy_detail),
        icon = Icons.Rounded.Info,
        items = listOf(
            R.string.privacy_local_title to R.string.privacy_local_detail,
            R.string.privacy_no_account_title to R.string.privacy_no_account_detail,
            R.string.privacy_no_network_title to R.string.privacy_no_network_detail,
            R.string.privacy_export_title to R.string.privacy_export_detail,
        ),
        footer = stringResource(R.string.settings_disclaimer),
        showWidgetPreviews = false,
        screenTag = "privacy-screen",
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailedInformationScreen(
    title: String,
    detail: String,
    icon: ImageVector,
    items: List<Pair<Int, Int>>,
    footer: String,
    showWidgetPreviews: Boolean,
    screenTag: String,
    onBack: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.testTag(screenTag),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(title) },
                navigationIcon = {
                    LiquidIconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("information-back")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                MenuCard(Modifier.widthIn(max = 620.dp)) {
                    Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(detail, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (showWidgetPreviews) {
                item {
                    Row(
                        Modifier.fillMaxWidth().widthIn(max = 620.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        WidgetPreviewCard(SkinId.SKY_MEADOW, false, Modifier.weight(1f).height(96.dp))
                        WidgetPreviewCard(SkinId.SHRINE, true, Modifier.weight(1.4f).height(96.dp))
                    }
                }
            }
            items(items) { (titleId, detailId) ->
                MenuCard(Modifier.widthIn(max = 620.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(titleId), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(stringResource(detailId), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Text(
                    footer,
                    Modifier.fillMaxWidth().widthIn(max = 620.dp).padding(12.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun WidgetPreviewCard(skin: SkinId, medium: Boolean, modifier: Modifier = Modifier) {
    val translucency = LocalBlobTranslucency.current
    val palette = skin.palette()
    val variant = when {
        skin == SkinId.SKY_MEADOW && !medium -> FoodWidgetVariant.SKY_MEADOW_SMALL
        skin == SkinId.SKY_MEADOW -> FoodWidgetVariant.SKY_MEADOW_MEDIUM
        !medium -> FoodWidgetVariant.SHRINE_SMALL
        else -> FoodWidgetVariant.SHRINE_MEDIUM
    }
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, palette.outline.copy(alpha = .24f)),
        shape = RoundedCornerShape(22.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            val density = LocalDensity.current.density
            val geometry = remember(variant) {
                FoodWidgetGeometry.resolve(
                    widthDp = if (medium) 364f else 172f,
                    heightDp = if (medium) 170f else 172f,
                    presentation = variant.presentation,
                )
            }
            val artwork = remember(variant, geometry, density, translucency) {
                FoodWidgetArtworkRenderer.render(
                    counts = FoodCounts(4, 2, 1),
                    variant = variant,
                    geometry = geometry,
                    density = density,
                    available = true,
                    translucency = translucency,
                ).asImageBitmap()
            }
            Image(
                bitmap = artwork,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Onboarding(motionAllowed: Boolean, onComplete: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    FoodBlobTheme(SkinId.SKY_MEADOW) {
        Box(Modifier.fillMaxSize()) {
            WorldBackground(SkinId.SKY_MEADOW, motionAllowed, Modifier.fillMaxSize())
            Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    HorizontalPager(pagerState, Modifier.weight(1f).fillMaxWidth()) { page ->
                        val title = when (page) {
                            0 -> R.string.onboarding_welcome_title
                            1 -> R.string.onboarding_colors_title
                            else -> R.string.onboarding_widgets_title
                        }
                        val detail = when (page) {
                            0 -> R.string.onboarding_welcome_detail
                            1 -> R.string.onboarding_colors_detail
                            else -> R.string.onboarding_widgets_detail
                        }
                        Column(
                            Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                        ) {
                            LivingFoodBlob(
                                counts = if (page == 0) FoodCounts(5, 2, 1) else FoodCounts(3, 2, 2),
                                contentDescription = "",
                                motionAllowed = motionAllowed,
                                mascotFace = page == 0,
                                modifier = Modifier.size(if(page==0)180.dp else if(page==1)112.dp else 140.dp),
                            )
                            Text(
                                stringResource(title),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Black,
                            )
                            Text(
                                stringResource(detail),
                                Modifier.widthIn(max = 520.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (page == 1) {
                                Column(Modifier.testTag("onboarding-colors-guide")) {
                                    listOf(
                                        FoodColor.GREEN to R.string.onboarding_green_detail,
                                        FoodColor.YELLOW to R.string.onboarding_yellow_detail,
                                        FoodColor.RED to R.string.onboarding_red_detail,
                                    ).forEach { (color, detailId) ->
                                        val tint = when (color) {
                                            FoodColor.GREEN -> MaterialTheme.colorScheme.primary
                                            FoodColor.YELLOW -> MaterialTheme.colorScheme.secondary
                                            FoodColor.RED -> MaterialTheme.colorScheme.tertiary
                                        }
                                        Row(
                                            Modifier.fillMaxWidth().widthIn(max = 520.dp).padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Box(Modifier.size(18.dp).background(tint, CircleShape))
                                            Spacer(Modifier.width(12.dp))
                                            Column {
                                                Text(stringResource(color.labelId()), fontWeight = FontWeight.Bold)
                                                Text(stringResource(detailId))
                                            }
                                        }
                                    }
                                    Text(
                                        stringResource(R.string.onboarding_reflection_note),
                                        Modifier.widthIn(max = 520.dp),
                                        textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            if (page == 2) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .widthIn(max = 520.dp)
                                        .height(100.dp)
                                        .testTag("onboarding-widget-previews"),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    WidgetPreviewCard(SkinId.SKY_MEADOW, false, Modifier.weight(1f))
                                    WidgetPreviewCard(SkinId.SHRINE, true, Modifier.weight(1.4f))
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(3) { index ->
                            Box(
                                Modifier.size(if (index == pagerState.currentPage) 12.dp else 8.dp)
                                    .background(
                                        if (index == pagerState.currentPage) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        CircleShape,
                                    ),
                            )
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    if (pagerState.currentPage > 0) {
                        LiquidTextButton(
                            onClick = onComplete,
                            modifier = Modifier.testTag("onboarding-skip"),
                        ) { Text(stringResource(R.string.onboarding_skip)) }
                    }
                    LiquidButton(
                        onClick = {
                            if (pagerState.currentPage == 2) onComplete()
                            else scope.launch {
                                if (motionAllowed) pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                else pagerState.scrollToPage(pagerState.currentPage + 1)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 520.dp)
                            .heightIn(min = 56.dp)
                            .testTag(if (pagerState.currentPage == 2) "onboarding-done" else "onboarding-next"),
                    ) {
                        Text(stringResource(if (pagerState.currentPage == 2) R.string.begin else R.string.continue_label))
                    }
                }
            }
        }
    }
}

private fun FoodColor.labelId(): Int = when (this) {
    FoodColor.GREEN -> R.string.green
    FoodColor.YELLOW -> R.string.yellow
    FoodColor.RED -> R.string.red
}

private fun Destination.testTag(): String = when (this) {
    Destination.TODAY -> "today-tab"
    Destination.HISTORY -> "history-tab"
    Destination.SKINS -> "skins-tab"
    Destination.SETTINGS -> "settings-tab"
}

private fun Destination.labelId(@Suppress("UNUSED_PARAMETER") skin: SkinId): Int = label

private fun FoodColor.addLabelId(): Int = when (this) {
    FoodColor.GREEN -> R.string.green_add
    FoodColor.YELLOW -> R.string.yellow_add
    FoodColor.RED -> R.string.red_add
}

private fun FoodColor.removeLabelId(): Int = when (this) {
    FoodColor.GREEN -> R.string.green_remove
    FoodColor.YELLOW -> R.string.yellow_remove
    FoodColor.RED -> R.string.red_remove
}

private fun Notice.stringId(): Int = when (this) {
    Notice.SAVED -> R.string.saved
    Notice.REMOVED -> R.string.removed
    Notice.UNDONE -> R.string.undone
    Notice.NOTHING_TO_UNDO -> R.string.nothing_to_undo
    Notice.EXPORTED -> R.string.exported
    Notice.IMPORTED -> R.string.imported
    Notice.ROLLED_BACK -> R.string.rolled_back
    Notice.DELETED -> R.string.deleted
    Notice.FAILED -> R.string.action_failed
}

private fun LocalDate.formatted(): String = format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

private fun LocalDate.shrineWeekday(): String =
    format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())).uppercase(Locale.getDefault())

private fun LocalDate.shrineDate(): String =
    format(
        DateTimeFormatter.ofPattern(
            DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMMd"),
            Locale.getDefault(),
        ),
    )

private fun LocalDate.dayCellLabel(): String = format(DateTimeFormatter.ofPattern("EEE d"))

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
