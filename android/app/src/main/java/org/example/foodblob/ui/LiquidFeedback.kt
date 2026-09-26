package org.example.foodblob.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

internal val LocalLiquidMotion = staticCompositionLocalOf { true }
internal val LocalLiquidStrength = staticCompositionLocalOf { 1f }

internal data class LiquidControlFrame(val scaleX: Float, val scaleY: Float, val lift: Float, val light: Float)

/** Finite, volume-preserving recoil. No idle clock and no moving hit-layout geometry. */
internal object LiquidFeedbackMotion {
    fun releaseLight(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return (sin(t * PI) * (1-t) * (1-t) * 2).toFloat()
    }

    fun frame(pressure: Float, release: Float, motionAllowed: Boolean): LiquidControlFrame {
        if (!motionAllowed) return LiquidControlFrame(1f, 1f, 0f, 0f)
        val t = release.coerceIn(0f, 1f)
        val recoil = if (t == 1f) 0f else (sin(t * PI * 2) * exp(-t * 4.5)).toFloat()
        val p = pressure.coerceIn(0f, 1f)
        val x = 1f - .018f * p + .032f * recoil
        return LiquidControlFrame(x, (1f - .070f * p) / x, p * 1.6f, releaseLight(t))
    }

    fun landingBeadTravel(progress: Float): Float =
        sin(progress.coerceIn(0f,1f) * PI).toFloat()

    fun landingLight(progress: Float): Float {
        val t = progress.coerceIn(0f,1f)
        return (sin(t * PI) * (1-t)).toFloat()
    }
}

internal class LiquidControlFeedback(val interactions: MutableInteractionSource, val modifier: Modifier)

/** Observe the control's own interaction stream, preserving clicks, scrolling and accessibility. */
@Composable
internal fun rememberLiquidControl(
    shape: Shape = RoundedCornerShape(18.dp),
    tint: Color = MaterialTheme.colorScheme.primary,
    motionAllowed: Boolean = LocalLiquidMotion.current,
): LiquidControlFeedback {
    val strength=LocalLiquidStrength.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pressure = animateFloatAsState(
        if (pressed) 1f else 0f,
        if (!motionAllowed) snap() else if (pressed) tween(70) else spring(.82f, 550f),
        label = "liquid control pressure",
    )
    val release = remember { Animatable(1f) }
    var contact by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(source, motionAllowed) {
        release.snapTo(1f)
        var releaseJob: Job? = null
        source.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> {
                    contact = event.pressPosition
                    releaseJob?.cancel()
                    release.snapTo(1f)
                }
                is PressInteraction.Release -> if (motionAllowed) {
                    releaseJob?.cancel()
                    releaseJob = launch {
                        release.snapTo(0f)
                        release.animateTo(1f, tween(420, easing = androidx.compose.animation.core.LinearEasing))
                    }
                }
                is PressInteraction.Cancel -> {
                    releaseJob?.cancel()
                    release.snapTo(1f)
                }
            }
        }
    }
    return LiquidControlFeedback(source, Modifier.graphicsLayer {
        val frame = LiquidFeedbackMotion.frame(pressure.value, release.value, motionAllowed)
        scaleX = 1f+(frame.scaleX-1f)*strength
        scaleY = 1f+(frame.scaleY-1f)*strength
        translationY = frame.lift.dp.toPx()*strength
        this.shape = shape
        clip = true
    }.drawWithContent {
        drawContent()
        val p = pressure.value.coerceIn(0f,1f)
        val frame = LiquidFeedbackMotion.frame(p, release.value, motionAllowed)
        if (p > 0f) drawRect(tint.copy(alpha = p * .08f))
        if (frame.light > .001f) {
            val origin = Offset(contact.x.coerceIn(0f,size.width), contact.y.coerceIn(0f,size.height))
            val radius = (size.maxDimension * (.12f + release.value * .90f)).coerceAtLeast(1f)
            drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = frame.light*.18f*strength),Color.Transparent),origin,radius),radius,origin)
            // A shallow elliptical crest reads as a liquid meniscus, not an expanding target.
            drawOval(Color.White.copy(alpha = frame.light*.30f*strength),
                topLeft = origin-Offset(radius,radius*.48f), size = Size(radius*2,radius*.96f),
                style = Stroke(1.4.dp.toPx()))
            drawOutline(shape.createOutline(size, layoutDirection, this), tint.copy(alpha = frame.light*.34f*strength), style = Stroke(1.dp.toPx()))
        }
    })
}

/** One soft arrival when navigation changes; no duplicate outgoing screen or data owner. */
@Composable
internal fun Modifier.liquidPageArrival(key: Any?): Modifier {
    val motionAllowed = LocalLiquidMotion.current
    val arrival = remember { Animatable(0f) }
    LaunchedEffect(key, motionAllowed) {
        if (motionAllowed) {
            arrival.snapTo(1f)
            arrival.animateTo(0f, tween(210, easing = FastOutSlowInEasing))
        } else arrival.snapTo(0f)
    }
    return graphicsLayer {
        translationY = if (motionAllowed) 7.dp.toPx() * arrival.value else 0f
        alpha = if (motionAllowed) 1f - .18f * arrival.value else 1f
    }
}

@Composable
internal fun Modifier.liquidSelection(selected: Boolean): Modifier {
    val motionAllowed = LocalLiquidMotion.current
    val settle = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(selected) }
    LaunchedEffect(selected, motionAllowed) {
        val changed = selected && !previous
        previous = selected
        if (changed && motionAllowed) {
            settle.snapTo(0f)
            settle.animateTo(1f, tween(360))
        } else settle.snapTo(1f)
    }
    return graphicsLayer {
        val pop = if (motionAllowed) LiquidFeedbackMotion.releaseLight(settle.value) else 0f
        scaleX = 1f + pop*.08f
        scaleY = 1f + pop*.08f
        translationY = -pop*1.5.dp.toPx()
    }
}

@Composable
internal fun LiquidIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    haptic: Boolean = true,
    motionAllowed: Boolean = LocalLiquidMotion.current,
    content: @Composable () -> Unit,
) {
    val feedback = rememberLiquidControl(CircleShape, motionAllowed = motionAllowed)
    val view = LocalView.current
    IconButton(onClick = { if (haptic) view.performBlobContactHaptic(); onClick() },
        modifier = modifier.then(feedback.modifier), enabled = enabled,
        interactionSource = feedback.interactions, content = content)
}

@Composable
internal fun LiquidButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val feedback = rememberLiquidControl(CircleShape)
    val view = LocalView.current
    Button(onClick = { view.performBlobContactHaptic(); onClick() }, modifier = modifier.then(feedback.modifier),
        enabled = enabled, interactionSource = feedback.interactions, content = content)
}

@Composable
internal fun LiquidTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val feedback = rememberLiquidControl(CircleShape)
    val view = LocalView.current
    TextButton(onClick = { view.performBlobContactHaptic(); onClick() }, modifier = modifier.then(feedback.modifier),
        enabled = enabled, interactionSource = feedback.interactions, content = content)
}

@Composable
internal fun LiquidTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val feedback = rememberLiquidControl(CircleShape)
    FilledTonalButton(onClick = onClick, modifier = modifier.then(feedback.modifier), enabled = enabled,
        interactionSource = feedback.interactions, content = content)
}
