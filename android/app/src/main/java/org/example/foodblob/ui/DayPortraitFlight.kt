package org.example.foodblob.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import org.example.foodblob.domain.FoodCounts
import org.example.foodblob.domain.SkinId

internal val LocalDayPortrait = staticCompositionLocalOf<DayPortraitFlight?> { null }

/** Draw-only bridge from the selected calendar portrait to the real editor hero. */
internal class DayPortraitFlight(
    val date: String,
    val counts: FoodCounts,
    val skin: SkinId,
    val source: Rect
) {
    var target by mutableStateOf(Rect.Zero)
    val progress = Animatable(0f)
}

@Composable
internal fun DayPortraitOverlay(
    flight: DayPortraitFlight,
    root: Offset,
    onFinished: () -> Unit
) {
    LaunchedEffect(flight, flight.target) {
        if (flight.target != Rect.Zero) {
            flight.progress.animateTo(1f, tween(410, easing = FastOutSlowInEasing))
            onFinished()
        }
    }
    val density = LocalDensity.current
    val target = flight.target
    if (target != Rect.Zero) {
        Box(
            Modifier
                .size(
                    with(density) { target.width.toDp() },
                    with(density) { target.height.toDp() }
                )
                .testTag("history-portrait-flight")
                .clearAndSetSemantics {}
                .graphicsLayer {
                    val t = flight.progress.value
                    val width = flight.source.width + (target.width - flight.source.width) * t
                    val height = flight.source.height + (target.height - flight.source.height) * t
                    val left = flight.source.left + (target.left - flight.source.left) * t
                    val top = flight.source.top + (target.top - flight.source.top) * t
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    translationX = left - root.x
                    translationY = top - root.y
                    scaleX = width / target.width
                    scaleY = height / target.height
                }
        ) {
            LivingFoodBlob(
                flight.counts,
                "",
                skin = flight.skin,
                motionAllowed = false,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val ratio = (
                            org.example.foodblob.domain.LivingBlob.heroGrowthScale(flight.counts.total) /
                                org.example.foodblob.domain.LivingBlob.growthScale(flight.counts.total)
                            ).toFloat()
                        scaleX = 1f + (ratio - 1f) * flight.progress.value
                        scaleY = scaleX
                    }
            )
        }
    }
}
