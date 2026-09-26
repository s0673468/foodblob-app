package org.example.foodblob.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.example.foodblob.domain.BlobColor
import org.example.foodblob.domain.BlobPoint
import org.example.foodblob.domain.FoodColor
import org.example.foodblob.domain.FoodCounts
import org.example.foodblob.domain.LivingBlob
import org.example.foodblob.domain.SkinId
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal data class FoodMutation(val color: FoodColor, val delta: Int) {
    companion object {
        fun detect(previous: FoodCounts, current: FoodCounts): FoodMutation? {
            val changes = FoodColor.entries.mapNotNull { color ->
                val delta = current.count(color) - previous.count(color)
                delta.takeIf { it != 0 }?.let { FoodMutation(color, it) }
            }
            return changes.singleOrNull()
        }
    }
}

internal enum class BlobPaintAction {
    START,
    SETTLE,
}

internal object BlobMotionPolicy {
    fun showsTransientPaint(
        interactive: Boolean,
        motionAllowed: Boolean,
        previousScope: String?,
        currentScope: String?,
    ): Boolean = interactive && motionAllowed && previousScope == currentScope

    fun paintAction(mutation: FoodMutation?, transientAllowed: Boolean): BlobPaintAction =
        if (transientAllowed && mutation != null && mutation.delta > 0) {
            BlobPaintAction.START
        } else {
            BlobPaintAction.SETTLE
        }

    fun interpolatePoints(previous: List<BlobPoint>, current: List<BlobPoint>, progress: Float): List<BlobPoint> {
        require(previous.size == current.size) { "Blob outlines must have matching point counts" }
        val amount = progress.coerceIn(0f, 1f).toDouble()
        return previous.zip(current) { from, to ->
            BlobPoint(
                x = from.x + (to.x - from.x) * amount,
                y = from.y + (to.y - from.y) * amount,
            )
        }
    }

    fun persistentHaloAlpha(@Suppress("UNUSED_PARAMETER") skin: SkinId): Float = 0f

    fun idleBreathDurationMs(skin: SkinId): Int = if (skin == SkinId.SHRINE) 5_500 else 4_500
}

internal data class CounterJellyFrame(val x: Float, val y: Float, val durationMs: Int)

internal object CounterMotionPolicy {
    val frames = listOf(
        CounterJellyFrame(1.11f, .87f, 130),
        CounterJellyFrame(.94f, 1.06f, 120),
        CounterJellyFrame(1.03f, .98f, 110),
        CounterJellyFrame(1f, 1f, 100),
    )

    fun impactScaleX(adding: Boolean, motionAllowed: Boolean): Float =
        if (!motionAllowed) 1f else if (adding) 1.08f else .94f

    fun impactScaleY(adding: Boolean, motionAllowed: Boolean): Float =
        if (!motionAllowed) 1f else if (adding) .92f else 1.06f
}

internal data class FoodBlobPalette(
    val background: Color,
    val surface: Color,
    val ink: Color,
    val mutedInk: Color,
    val outline: Color,
    val green: Color,
    val yellow: Color,
    val red: Color,
)

internal val MeadowPalette = FoodBlobPalette(
    background = Color(0xFFF4F0DC),
    surface = Color(0xFFFFF9E9),
    ink = Color(0xFF24392D),
    mutedInk = Color(0xFF5D6E61),
    outline = Color(0xFF819B83),
    green = Color(.13f, .82f, .44f),
    yellow = Color(.99f, .79f, .18f),
    red = Color(.96f, .30f, .35f),
)

internal val ShrinePalette = FoodBlobPalette(
    background = Color(0xFF0A1421),
    surface = Color(0xFF0F2433),
    ink = Color(0xFFEBF5F5),
    mutedInk = Color(0x80EBF5F5),
    outline = Color(0x2E80E8DB),
    green = Color(.13f, .82f, .44f),
    yellow = Color(.99f, .79f, .18f),
    red = Color(.96f, .30f, .35f),
)

internal fun SkinId.palette(): FoodBlobPalette = if (this == SkinId.SHRINE) ShrinePalette else MeadowPalette

@Composable
internal fun WorldBackground(
    skin: SkinId,
    motionAllowed: Boolean,
    modifier: Modifier = Modifier,
) {
    if (motionAllowed && skin == SkinId.SKY_MEADOW) {
        val transition = rememberInfiniteTransition(label = "world atmosphere")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(7_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "world atmosphere phase",
        )
        Canvas(modifier.clearAndSetSemantics {}) { drawWorld(skin, phase) }
    } else {
        Canvas(modifier.clearAndSetSemantics {}) { drawWorld(skin, 0f) }
    }
}

@Composable
internal fun ShrineMotes(
    motionAllowed: Boolean,
    modifier: Modifier = Modifier,
) {
    val phase = if (motionAllowed) {
        val transition = rememberInfiniteTransition(label = "shrine motes")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(11_000, easing = FastOutSlowInEasing)),
            label = "shrine mote rise",
        )
    } else remember { mutableFloatStateOf(.45f) }
    Canvas(modifier.clearAndSetSemantics {}) {
        val phaseValue = phase.value
        val alpha = if (motionAllowed) 1f - phaseValue else .45f
        drawCircle(
            Color(0xFF9BF2E6).copy(alpha = alpha),
            3f,
            Offset(size.width * .38f, size.height * (.82f - phaseValue * .52f)),
        )
        drawCircle(
            Color(0xFFCFF5F0).copy(alpha = alpha * .82f),
            3f,
            Offset(size.width * .63f, size.height * (.76f - phaseValue * .46f)),
        )
    }
}

@Composable
internal fun BlobBreathingHalo(
    counts: FoodCounts,
    skin: SkinId,
    motionAllowed: Boolean,
    modifier: Modifier = Modifier,
) {
    if (counts.isEmpty) return
    val phase = if (motionAllowed) {
        val transition = rememberInfiniteTransition(label = "blob halo")
        transition.animateFloat(
            initialValue = .55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    if (skin == SkinId.SHRINE) 4_000 else 4_500,
                    easing = FastOutSlowInEasing,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "blob halo breath",
        )
    } else remember { mutableFloatStateOf(.78f) }
    val mixed = BlobColor.mix(counts) ?: return
    val source = if (skin == SkinId.SHRINE) {
        Color(mixed.red.toFloat(), mixed.green.toFloat(), mixed.blue.toFloat())
    } else {
        Color(0xFFFFF5D6)
    }
    val maximumAlpha = if (skin == SkinId.SHRINE) .20f else .70f
    Canvas(modifier.clearAndSetSemantics {}) {
        val phaseValue = phase.value
        val radius = size.minDimension * .43f * (if (motionAllowed) 1f + .06f * phaseValue else 1f)
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to source.copy(alpha = maximumAlpha * phaseValue),
                    .48f to source.copy(alpha = maximumAlpha * phaseValue * .34f),
                    1f to source.copy(alpha = 0f),
                ),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
}

@Composable
internal fun BlobGroundShadow(
    motionAllowed: Boolean,
    modifier: Modifier = Modifier,
) {
    val phase = if (motionAllowed) {
        val transition = rememberInfiniteTransition(label = "blob ground shadow")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(3_500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "blob ground shadow breath",
        )
    } else remember { mutableFloatStateOf(0f) }
    Canvas(modifier.clearAndSetSemantics {}) {
        val phaseValue = phase.value
        val alpha = if (motionAllowed) .30f - .12f * phaseValue else .30f
        val horizontalScale = if (motionAllowed) 1f - .14f * phaseValue else 1f
        val radius = size.width * .5f * horizontalScale
        drawOval(
            brush = Brush.radialGradient(
                listOf(Color(0xFF1F3D29).copy(alpha = alpha), Color.Transparent),
                center = center,
                radius = radius,
            ),
            topLeft = Offset(center.x - radius, 0f),
            size = androidx.compose.ui.geometry.Size(radius * 2f, size.height),
        )
    }
}

private fun DrawScope.drawWorld(skin: SkinId, phase: Float) {
    if (skin == SkinId.SKY_MEADOW) {
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFFBFE2F1), Color(0xFFF6EDCE), Color(0xFFE2DBA8)),
                endY = size.height,
            ),
        )
        drawCircle(
            color = Color(0xFFFFE59A).copy(alpha = .48f),
            radius = size.minDimension * .13f,
            center = Offset(size.width * .78f, size.height * .15f),
        )
        val cloudShift = (phase - .5f) * size.width * .05f
        listOf(.13f to .18f, .20f to .17f, .27f to .19f).forEach { (x, y) ->
            drawCircle(Color.White.copy(alpha = .53f), size.minDimension * .07f, Offset(size.width * x + cloudShift, size.height * y))
        }
        drawOval(
            color = Color(0xFF8EBB75).copy(alpha = .65f),
            topLeft = Offset(-size.width * .15f, size.height * .73f),
            size = androidx.compose.ui.geometry.Size(size.width * 1.3f, size.height * .44f),
        )
    } else {
        drawRect(
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color(0xFF0A1421),
                    .55f to Color(0xFF12293B),
                    1f to Color(0xFF0D1726),
                ),
                endY = size.height,
            ),
        )
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0x1C80E8DB), Color(0x0080E8DB)),
                center = Offset(size.width * .5f, size.height * .36f),
                radius = size.width * .42f,
            ),
            radius = size.width * .42f,
            center = Offset(size.width * .5f, size.height * .36f),
        )
        listOf(
            Triple(.15f, .09f, 1f),
            Triple(.44f, .06f, .50f),
            Triple(.75f, .11f, .40f),
        ).forEach { (x, y, alpha) ->
            drawCircle(Color(0xFFDEF5F2).copy(alpha = alpha), 2f, Offset(size.width * x, size.height * y))
        }
        drawOval(
            Color(0xFF0A1724).copy(alpha=.42f),
            topLeft=Offset(-size.width*.2f,size.height*.80f),
            size=androidx.compose.ui.geometry.Size(size.width*1.4f,size.height*.42f),
        )
    }
}

@Composable
internal fun FoodBlobTheme(
    skin: SkinId,
    motionAllowed: Boolean = LocalLiquidMotion.current,
    content: @Composable () -> Unit,
) {
    val palette = skin.palette()
    val scheme = if (skin == SkinId.SHRINE) {
        darkColorScheme(
            primary = palette.green,
            onPrimary = Color(0xFF061E13),
            primaryContainer = Color(0xFF103B34),
            onPrimaryContainer = palette.ink,
            secondary = palette.yellow,
            onSecondary = Color(0xFF251A00),
            secondaryContainer = Color(0xFF173D42),
            onSecondaryContainer = palette.ink,
            tertiary = palette.red,
            onTertiary = Color(0xFF2C0A0C),
            tertiaryContainer = Color(0xFF3A2329),
            onTertiaryContainer = palette.ink,
            background = palette.background,
            surface = palette.surface,
            surfaceVariant = Color(0xFF122C3B),
            surfaceContainer = Color(0xFF0F2433),
            surfaceContainerLow = Color(0xFF0C1C2A),
            surfaceContainerHigh = Color(0xFF15303F),
            surfaceContainerLowest = palette.background,
            surfaceContainerHighest = Color(0xFF193847),
            surfaceDim = palette.background,
            surfaceBright = Color(0xFF193847),
            surfaceTint = Color(0xFF80E8DB),
            onBackground = palette.ink,
            onSurface = palette.ink,
            onSurfaceVariant = palette.mutedInk,
            outline = palette.outline,
            outlineVariant = Color(0xFF244653),
            inverseSurface = Color(0xFFEAF5F4),
            inverseOnSurface = palette.background,
            inversePrimary = Color(0xFF206A45),
            error = palette.red,
            onError = Color(0xFF3B0C14),
            errorContainer = Color(0xFF3A2329),
            onErrorContainer = Color(0xFFFFDAD9),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF206A45),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDAEAD5),
            onPrimaryContainer = palette.ink,
            secondary = Color(0xFF8B6500),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFF4E5B6),
            onSecondaryContainer = Color(0xFF43351D),
            tertiary = Color(0xFF9C3438),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFFFDAD7),
            onTertiaryContainer = Color(0xFF4A2023),
            background = palette.background,
            surface = palette.surface,
            surfaceVariant = Color(0xFFECE6D5),
            surfaceContainerLowest = Color(0xFFFFFCF5),
            surfaceContainerLow = Color(0xFFFFF9EC),
            surfaceContainer = Color(0xFFFCF5E8),
            surfaceContainerHigh = Color(0xFFF4ECDC),
            surfaceContainerHighest = Color(0xFFECE3D1),
            surfaceDim = Color(0xFFE3DAC8),
            surfaceBright = Color(0xFFFFFBF2),
            surfaceTint = Color(0xFF8B795B),
            onBackground = palette.ink,
            onSurface = palette.ink,
            onSurfaceVariant = palette.mutedInk,
            outline = palette.outline,
            outlineVariant = Color(0xFFD7C9AE),
            inverseSurface = palette.ink,
            inverseOnSurface = palette.surface,
            inversePrimary = Color(0xFFA8D8B6),
            error = palette.red,
            onError = Color.White,
            errorContainer = Color(0xFFFFDAD7),
            onErrorContainer = Color(0xFF592125),
        )
    }
    val translucency by rememberBlobTranslucency(LocalContext.current)
    CompositionLocalProvider(LocalLiquidMotion provides motionAllowed, LocalBlobTranslucency provides translucency) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@Composable
internal fun rememberMotionAllowed(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun animationsEnabled(): Boolean = runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }.getOrDefault(true)
    var enabled by remember { mutableStateOf(animationsEnabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = animationsEnabled()
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

@Composable
internal fun LivingFoodBlob(
    counts: FoodCounts,
    contentDescription: String,
    modifier: Modifier = Modifier,
    skin: SkinId = SkinId.SKY_MEADOW,
    motionAllowed: Boolean = true,
    mascotFace: Boolean = false,
    interactive: Boolean = false,
    animationScope: String? = null,
) {
    val translucency = LocalBlobTranslucency.current
    val connected = LocalBlobFeedback.current
    if (interactive && connected != null) {
        ConnectedLivingFoodBlob(counts, contentDescription, skin, motionAllowed, connected, modifier)
        return
    }
    val scale by animateFloatAsState(
        targetValue = LivingBlob.growthScale(counts.total).toFloat(),
        animationSpec = if (motionAllowed) {
            spring(dampingRatio = .68f, stiffness = Spring.StiffnessMediumLow)
        } else snap(),
        label = "blob growth",
    )
    val idle = if (motionAllowed && counts.total > 0) {
        val transition = rememberInfiniteTransition(label = "blob idle")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = -3f,
            animationSpec = infiniteRepeatable(
                animation = tween(if (skin == SkinId.SHRINE) 4_500 else 2_400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "blob idle offset",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    val tilt = if (motionAllowed && counts.total > 0) {
        val transition = rememberInfiniteTransition(label = "blob tilt")
        transition.animateFloat(
            initialValue = -1.2f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(if (skin == SkinId.SHRINE) 5_500 else 3_200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "blob resting tilt",
        )
    } else remember { mutableFloatStateOf(0f) }
    val breath = if (motionAllowed && counts.total > 0) {
        val transition = rememberInfiniteTransition(label = "blob breath")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    BlobMotionPolicy.idleBreathDurationMs(skin),
                    easing = FastOutSlowInEasing,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "blob resting breath",
        )
    } else remember { mutableFloatStateOf(0f) }
    val impactX = remember { Animatable(1f) }
    val impactY = remember { Animatable(1f) }
    val paintProgress = remember { Animatable(1f) }
    val shapeProgress = remember { Animatable(1f) }
    val targetPoints = remember(counts) {
        LivingBlob.samplePoints(counts, LivingBlob.derivedTapSeed(counts), 0.0)
    }
    var previousCounts by remember(animationScope) { mutableStateOf(counts) }
    var shapeBasePoints by remember(animationScope) {
        mutableStateOf(LivingBlob.samplePoints(counts, counts.total, 0.0))
    }
    var paintBaseCounts by remember(animationScope) { mutableStateOf(counts) }
    var activePaint by remember(animationScope) { mutableStateOf<FoodMutation?>(null) }
    var previousScope by remember { mutableStateOf(animationScope) }
    var dragging by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val renderedDragX by animateFloatAsState(
        targetValue = if (dragging) dragX else 0f,
        animationSpec = if (dragging || !motionAllowed) snap() else spring(dampingRatio = .58f),
        label = "blob drag x",
    )
    val renderedDragY by animateFloatAsState(
        targetValue = if (dragging) dragY else 0f,
        animationSpec = if (dragging || !motionAllowed) snap() else spring(dampingRatio = .58f),
        label = "blob drag y",
    )
    LaunchedEffect(counts, animationScope, interactive, motionAllowed) {
        val oldCounts = previousCounts
        val oldTargetPoints = LivingBlob.samplePoints(oldCounts, oldCounts.total, 0.0)
        val renderedPoints = BlobMotionPolicy.interpolatePoints(
            shapeBasePoints,
            oldTargetPoints,
            shapeProgress.value,
        )
        val mutation = FoodMutation.detect(oldCounts, counts)
        val animate = mutation != null && BlobMotionPolicy.showsTransientPaint(
            interactive,
            motionAllowed,
            previousScope,
            animationScope,
        )
        val paintAction = BlobMotionPolicy.paintAction(mutation, transientAllowed = animate)
        if (paintAction == BlobPaintAction.SETTLE) activePaint = null
        previousCounts = counts
        previousScope = animationScope
        if (animate) {
            val adding = requireNotNull(mutation).delta > 0
            shapeBasePoints = renderedPoints
            shapeProgress.snapTo(0f)
            when (paintAction) {
                BlobPaintAction.START -> {
                    paintProgress.snapTo(0f)
                    paintBaseCounts = oldCounts
                    activePaint = requireNotNull(mutation)
                }
                BlobPaintAction.SETTLE -> paintProgress.snapTo(1f)
            }
            coroutineScope {
                launch {
                    impactX.animateTo(
                        if (adding) 1.19f else .90f,
                        tween(110, easing = FastOutSlowInEasing),
                    )
                    impactX.animateTo(
                        if (adding) .93f else 1.05f,
                        tween(170, easing = FastOutSlowInEasing),
                    )
                    impactX.animateTo(
                        if (adding) 1.045f else .982f,
                        tween(170, easing = FastOutSlowInEasing),
                    )
                    impactX.animateTo(
                        1f,
                        spring(dampingRatio = .76f, stiffness = Spring.StiffnessMedium),
                    )
                }
                launch {
                    impactY.animateTo(
                        if (adding) .83f else 1.11f,
                        tween(110, easing = FastOutSlowInEasing),
                    )
                    impactY.animateTo(
                        if (adding) 1.09f else .955f,
                        tween(170, easing = FastOutSlowInEasing),
                    )
                    impactY.animateTo(
                        if (adding) .972f else 1.014f,
                        tween(170, easing = FastOutSlowInEasing),
                    )
                    impactY.animateTo(
                        1f,
                        spring(dampingRatio = .76f, stiffness = Spring.StiffnessMedium),
                    )
                }
                launch {
                    shapeProgress.animateTo(1f, tween(580, easing = FastOutSlowInEasing))
                }
                if (adding) launch {
                    paintProgress.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
                    activePaint = null
                }
            }
        } else {
            shapeBasePoints = LivingBlob.samplePoints(counts, counts.total, 0.0)
            shapeProgress.snapTo(1f)
            impactX.snapTo(1f)
            impactY.snapTo(1f)
            paintProgress.snapTo(1f)
        }
    }
    val mixed = BlobColor.mix(counts) ?: BlobColor.EMPTY
    val targetFill = if (counts.isEmpty) Color(0xFFC5E5D8) else Color(mixed.red.toFloat(), mixed.green.toFloat(), mixed.blue.toFloat())
    val material = remember { createJellyVolumeMaterial() }
    val fill by animateColorAsState(
        targetValue = targetFill,
        animationSpec = if (motionAllowed) tween(420, easing = FastOutSlowInEasing) else snap(),
        label = "blob color mix",
    )

    val dragModifier = if (interactive && motionAllowed) {
        Modifier.pointerInput(animationScope) {
            detectDragGestures(
                onDragStart = { dragging = true },
                onDragEnd = { dragging = false; dragX = 0f; dragY = 0f },
                onDragCancel = { dragging = false; dragX = 0f; dragY = 0f },
                onDrag = { change, amount ->
                    change.consume()
                    dragX = (dragX + amount.x).coerceIn(-48f, 48f)
                    dragY = (dragY + amount.y).coerceIn(-48f, 48f)
                },
            )
        }
    } else Modifier

    Box(
        modifier = modifier
            .then(dragModifier)
            .semantics { this.contentDescription = contentDescription }
            .graphicsLayer {
                scaleX = scale * impactX.value * (1f + .026f * breath.value)
                scaleY = scale * impactY.value * (1f - .023f * breath.value)
                translationX = renderedDragX
                translationY = idle.value + renderedDragY
                rotationZ = tilt.value
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val points = BlobMotionPolicy.interpolatePoints(shapeBasePoints, targetPoints, shapeProgress.value)
            val path = Path()
            val offsets = points.map { point ->
                Offset(
                    (point.x / 100.0 * size.width).toFloat(),
                    (point.y / 100.0 * size.height).toFloat(),
                )
            }
            path.moveTo(offsets.first().x, offsets.first().y)
            offsets.indices.forEach { index ->
                val previous = offsets[(index - 1 + offsets.size) % offsets.size]
                val current = offsets[index]
                val next = offsets[(index + 1) % offsets.size]
                val afterNext = offsets[(index + 2) % offsets.size]
                path.cubicTo(
                    current.x + (next.x - previous.x) / 6f,
                    current.y + (next.y - previous.y) / 6f,
                    next.x - (afterNext.x - current.x) / 6f,
                    next.y - (afterNext.y - current.y) / 6f,
                    next.x,
                    next.y,
                )
            }
            path.close()
            val paint = activePaint?.takeIf { paintProgress.value < 1f }
            val baseMixed = BlobColor.mix(paintBaseCounts) ?: BlobColor.EMPTY
            val baseFill = if (paint == null) fill else if (paintBaseCounts.isEmpty) Color(0xFFC5E5D8)
                else Color(baseMixed.red.toFloat(),baseMixed.green.toFloat(),baseMixed.blue.toFloat())
            val incoming = paint?.let {
                val point = when (it.color) {
                    FoodColor.GREEN -> Offset(size.width*.28f,size.height*.62f)
                    FoodColor.YELLOW -> Offset(size.width*.50f,size.height*.35f)
                    FoodColor.RED -> Offset(size.width*.72f,size.height*.62f)
                }
                val tint = when (it.color) {
                    FoodColor.GREEN -> skin.palette().green
                    FoodColor.YELLOW -> skin.palette().yellow
                    FoodColor.RED -> skin.palette().red
                }
                JellyPaint(point,200L+(paintProgress.value*500).toLong(),targetFill,tint)
            }
            val grounding=ConnectedBlobMotion.groundShadow(offsets.map {BlobPoint(it.x.toDouble(),it.y.toDouble())})
            val ground=Offset(grounding.center.x.toFloat(),grounding.center.y.toFloat())
            val groundRadius=grounding.radiusX.toFloat().coerceAtLeast(1f)
            scale(1f,.18f,pivot=ground) {
                drawCircle(Brush.radialGradient(listOf(baseFill.copy(alpha=(if(skin==SkinId.SHRINE).18f else .12f)*translucency),Color.Transparent),ground,groundRadius*1.25f),groundRadius*1.25f,ground)
                drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha=if(skin==SkinId.SHRINE).26f else .12f),Color.Transparent),ground,groundRadius),groundRadius,ground)
            }
            material.draw(this,path,JellyFrame(
                profile = JellyVolumeGeometry.profile(offsets.map { BlobPoint(it.x.toDouble(),it.y.toDouble()) }),
                color = baseFill,
                paints = listOfNotNull(incoming),
                contacts = emptyList(), press = Offset.Zero, pressDepth = 0f, skin = skin, translucency = translucency,
            ))
            if (mascotFace) {
                val face = Color(0xFF291F17)
                val eyeSize = androidx.compose.ui.geometry.Size(size.width * .105f, size.height * .15f)
                val eyeTop = size.height * .34f
                listOf(size.width * .35f, size.width * .545f).forEach { eyeLeft ->
                    drawOval(face, Offset(eyeLeft, eyeTop), eyeSize)
                    drawCircle(
                        Color.White.copy(alpha = .96f),
                        size.minDimension * .0215f,
                        Offset(eyeLeft + eyeSize.width * .34f, eyeTop + eyeSize.height * .28f),
                    )
                }
                drawOval(
                    Color(0x5CFF7870),
                    Offset(size.width * .25f, size.height * .56f),
                    androidx.compose.ui.geometry.Size(size.width * .14f, size.height * .06f),
                )
                drawOval(
                    Color(0x5CFF7870),
                    Offset(size.width * .67f, size.height * .56f),
                    androidx.compose.ui.geometry.Size(size.width * .14f, size.height * .06f),
                )
                val mouth = Path().apply {
                    moveTo(size.width * .43f, size.height * .58f)
                    cubicTo(
                        size.width * .48f,
                        size.height * .575f,
                        size.width * .57f,
                        size.height * .575f,
                        size.width * .63f,
                        size.height * .58f,
                    )
                    cubicTo(
                        size.width * .63f,
                        size.height * .68f,
                        size.width * .45f,
                        size.height * .68f,
                        size.width * .43f,
                        size.height * .58f,
                    )
                    close()
                }
                drawPath(mouth, face)
                drawOval(
                    Color(0xFFF0786E),
                    Offset(size.width * .485f, size.height * .64f),
                    androidx.compose.ui.geometry.Size(size.width * .09f, size.height * .035f),
                )
            }
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
