package org.example.foodblob.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.example.foodblob.R
import org.example.foodblob.domain.*
import kotlin.math.*

internal data class ReturningFood(val color: FoodColor, val origin: BlobPoint, val target: BlobPoint, val startedMs: Long)

internal val LocalBlobFeedback = staticCompositionLocalOf<ConnectedBlobFeedback?> { null }

internal class ConnectedBlobFeedback {
    private val session = BlobInteractionSession()
    val generation get() = session.generation
    var revision by mutableIntStateOf(0)
        private set
    var nowMs by mutableLongStateOf(SystemClock.uptimeMillis())
    private var frameClock = BlobPresentationClock(nowMs)
    var blobBounds = Rect.Zero
    var contour: List<BlobPoint> = emptyList()
    var rootOffset = Offset.Zero
    val rowOrigins = mutableMapOf<FoodColor, BlobPoint>()
    val drag = BlobColorDrag()
    var dragPoint by mutableStateOf<Offset?>(null)
    var draggingColor by mutableStateOf<FoodColor?>(null)
    var returns by mutableStateOf<List<ReturningFood>>(emptyList())
        private set
    private var undoBefore: FoodCounts? = null
    var greetingStartedMs by mutableStateOf<Long?>(null)
        private set

    fun beginDrag(color: FoodColor, point: Offset) {
        if (!available) return
        drag.begin(color, generation, BlobPoint(point.x.toDouble(), point.y.toDouble()))
        draggingColor = color; dragPoint = point
    }
    fun moveDrag(point: Offset) {
        drag.move(BlobPoint(point.x.toDouble(), point.y.toDouble())); dragPoint = point
    }
    fun finishDrag(): FoodColor? = drag.finish(generation, contour).also { draggingColor = null; dragPoint = null }
    fun cancelDrag() { drag.cancel(); draggingColor = null; dragPoint = null }
    fun requestUndo(counts: FoodCounts) { interrupt(); undoBefore = counts }
    fun observeCounts(counts: FoodCounts, motionAllowed: Boolean) {
        val before = undoBefore ?: return
        if (counts == before) return
        undoBefore = null
        val color = CoreBlobMotion.removedColor(before, counts) ?: return
        val target = rowOrigins[color] ?: return
        if (!motionAllowed || contour.isEmpty()) return
        val origin = ConnectedBlobMotion.contact(target, BlobPoint(blobBounds.center.x.toDouble(),blobBounds.center.y.toDouble()),contour)
        startClock()
        returns = returns + ReturningFood(color,origin,target,nowMs)
        recoilStartedMs = nowMs
        revision += 1
    }
    private fun startClock() {
        if (!isAnimating) { nowMs = SystemClock.uptimeMillis(); frameClock = BlobPresentationClock(nowMs) }
    }
    fun greet() {
        if (!available) return
        startClock(); greetingStartedMs = nowMs; revision += 1
    }
    val drops get() = session.drops
    var heldCounts by mutableStateOf<FoodCounts?>(null)
        private set
    var pokes by mutableStateOf<List<Pair<Long, Offset>>>(emptyList())
        private set
    private var sounded = emptySet<FoodDrop>()
    private val contactCadence = BlobContactCadence()
    var contactHaptic: () -> Unit = {}
    var recoilStartedMs by mutableStateOf<Long?>(null)
        private set
    val isAnimating get() = drops.isNotEmpty() || pokes.isNotEmpty() || recoilStartedMs != null || returns.isNotEmpty() || greetingStartedMs != null
    var available = true
    var playAcceptedSound: () -> Unit = {}

    fun beginAdd(counts: FoodCounts): BlobAddRequest = session.beginAdd(counts).also {
        undoBefore = null
        heldCounts = session.heldCounts
    }

    fun finishAdd(request: BlobAddRequest, acceptedCounts: FoodCounts?) {
        session.finishAdd(request, acceptedCounts)
        heldCounts = session.heldCounts
    }

    fun accept(token: Int, color: FoodColor, counts: FoodCounts, origin: BlobPoint, motionAllowed: Boolean) {
        if (!available || token != generation) return
        if (!motionAllowed) {
            playAcceptedSound()
            return
        }
        if (contour.isEmpty() || blobBounds.width <= 0f || blobBounds.height <= 0f) return
        val center = BlobPoint(blobBounds.center.x.toDouble(), blobBounds.center.y.toDouble())
        val landing = ConnectedBlobMotion.contact(origin, center, contour)
        val idle = !isAnimating
        val now = if (idle) SystemClock.uptimeMillis() else nowMs
        if (idle) frameClock = BlobPresentationClock(now)
        if (session.accept(token, color, counts, now, origin, landing)) {
            nowMs = now
            revision += 1
        }
    }

    fun poke(point: Offset) {
        if (!available) return
        val idle = !isAnimating
        val now = if (idle) SystemClock.uptimeMillis() else nowMs
        if (idle) frameClock = BlobPresentationClock(now)
        nowMs = now
        pokes = pokes + (now to point)
        revision += 1
    }

    fun recoil() {
        if (!available) return
        if (!isAnimating) {
            nowMs = SystemClock.uptimeMillis()
            frameClock = BlobPresentationClock(nowMs)
        }
        recoilStartedMs = nowMs
        revision += 1
    }

    fun advanceFrame(frameNanos: Long, plop: () -> Unit) = advance(frameClock.atFrame(frameNanos), plop)

    fun advance(now: Long, plop: () -> Unit) {
        nowMs = now
        val arrivals = drops.filter { now - it.startedMs >= ConnectedBlobMotion.FLIGHT_MS && it !in sounded }
        if (arrivals.isNotEmpty()) {
            plop()
            if (contactCadence.accept(now)) contactHaptic()
            sounded = sounded + arrivals
        }
        session.advance(now)
        returns = returns.filter { now-it.startedMs < 460 }
        if (greetingStartedMs?.let { now-it >= 900 } == true) greetingStartedMs = null
        sounded = sounded.intersect(drops.toSet())
        pokes = pokes.filter { now - it.first < 500 }
        if (recoilStartedMs?.let { now - it >= 500 } == true) recoilStartedMs = null
    }

    fun interrupt() {
        session.interrupt()
        heldCounts = null
        pokes = emptyList()
        returns = emptyList()
        undoBefore = null
        greetingStartedMs = null
        cancelDrag()
        sounded = emptySet()
        contactCadence.reset()
        recoilStartedMs = null
        revision += 1
    }
}

@Composable
internal fun rememberConnectedBlobFeedback(scope: String, motionAllowed: Boolean): ConnectedBlobFeedback {
    val feedback = remember(scope, motionAllowed) { ConnectedBlobFeedback() }
    val context = LocalContext.current
    val view = LocalView.current
    val sound = remember(context) { BlobPlop(context) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(feedback, owner) {
        feedback.available = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                feedback.available = false
                feedback.interrupt()
            } else if (event == Lifecycle.Event.ON_START) { feedback.available = true; if (motionAllowed) feedback.greet() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            feedback.available = false
            feedback.interrupt()
            owner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(feedback) { if (motionAllowed) feedback.greet() }
    DisposableEffect(sound) { onDispose { sound.close() } }
    DisposableEffect(feedback, view) {
        feedback.contactHaptic = { view.performBlobContactHaptic() }
        onDispose { feedback.contactHaptic = {} }
    }
    DisposableEffect(feedback, sound) {
        feedback.playAcceptedSound = sound::play
        onDispose { feedback.playAcceptedSound = {} }
    }
    LaunchedEffect(feedback, feedback.revision) {
        while (motionAllowed && feedback.available && feedback.isAnimating) {
            withFrameNanos { frame -> feedback.advanceFrame(frame, sound::play) }
        }
    }
    return feedback
}

/** Lives on a screen, not the data store. Sound is opt-in and never needs audio focus. */
private class BlobPlop(private val context: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
    ).build()
    private var ready = false
    private val sample: Int
    init {
        pool.setOnLoadCompleteListener { _, _, status -> ready = status == 0 }
        sample = pool.load(context, R.raw.blob_plop, 1)
    }
    fun play() {
        if (ready && context.getSharedPreferences("food_blob_ui", 0).getBoolean("interaction_sound", false)) {
            pool.play(sample, .28f, .28f, 0, 0, 1f)
        }
    }
    fun close() = pool.release()
}

@Composable
internal fun FoodDropOverlay(feedback: ConnectedBlobFeedback, skin: SkinId) {
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        val now = feedback.nowMs
        feedback.dragPoint?.let { point ->
            val color = feedback.draggingColor?.feedbackColor(skin) ?: Color.Transparent
            val position = point-feedback.rootOffset
            val target = BlobColorDrag.contains(BlobPoint(point.x.toDouble(),point.y.toDouble()),feedback.contour)
            val radius = (if (target) 13f else 10f)*density
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha=.28f),Color.Transparent),position,radius*2.6f),radius*2.6f,position)
            drawCircle(Brush.radialGradient(listOf(lerp(color,Color.White,.40f),color),position-Offset(radius*.3f,radius*.3f),radius*1.5f),radius,position)
            drawCircle(Color.White.copy(alpha=.65f),radius*.22f,position-Offset(radius*.30f,radius*.35f))
        }
        feedback.returns.forEach { bead ->
            val progress = ((now-bead.startedMs)/460.0).coerceIn(0.0,1.0)
            val point = ConnectedBlobMotion.flightPoint(bead.origin,bead.target,progress)
            val position = Offset(point.x.toFloat(),point.y.toFloat())-feedback.rootOffset
            val radius = (8f*sin(PI*progress).toFloat()+2f)*density
            val color = bead.color.feedbackColor(skin)
            drawCircle(color.copy(alpha=(sin(PI*progress)*.85).toFloat()),radius,position)
            drawCircle(Color.White.copy(alpha=(sin(PI*progress)*.5).toFloat()),radius*.25f,position-Offset(radius*.25f,radius*.30f))
        }
        feedback.drops.forEach { drop ->
            val age = now - drop.startedMs
            val color = drop.color.feedbackColor(skin)
            if (age < ConnectedBlobMotion.FLIGHT_MS) {
                val progress = age.toDouble() / ConnectedBlobMotion.FLIGHT_MS
                val point = ConnectedBlobMotion.flightPoint(drop.origin, drop.contact, progress)
                val position = Offset(point.x.toFloat(), point.y.toFloat()) - feedback.rootOffset
                val radius = (6.5f + 3f * sin(progress * Math.PI).toFloat()) * density
                repeat(4) { index ->
                    val past = (progress - (index + 1) * .045).coerceAtLeast(0.0)
                    val trail = ConnectedBlobMotion.flightPoint(drop.origin, drop.contact, past)
                    val tail = Offset(trail.x.toFloat(), trail.y.toFloat()) - feedback.rootOffset
                    drawCircle(color.copy(alpha = .22f * (1f - index / 4f)), radius * (.65f - index * .11f), tail)
                }
                val ahead = ConnectedBlobMotion.flightPoint(drop.origin, drop.contact, (progress + .015).coerceAtMost(1.0))
                val angle = Math.toDegrees(atan2(ahead.y - point.y, ahead.x - point.x)).toFloat() - 90f
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .28f), Color.Transparent), position, radius * 2.8f), radius * 2.8f, position)
                rotate(angle, position) {
                    drawOval(Brush.radialGradient(listOf(lerp(color, Color.White, .35f), color, lerp(color, Color.Black, .18f)), position - Offset(radius * .32f, radius * .38f), radius * 1.9f), position - Offset(radius * .72f, radius * 1.25f), androidx.compose.ui.geometry.Size(radius * 1.44f, radius * 2.5f))
                    drawOval(Color.White.copy(alpha = .66f), position - Offset(radius * .38f, radius * .71f), androidx.compose.ui.geometry.Size(radius * .28f, radius * .48f))
                    drawArc(Color.White.copy(alpha = .30f), 20f, 75f, false, position - Offset(radius * .58f, radius * .95f), androidx.compose.ui.geometry.Size(radius * 1.16f, radius * 1.9f), style = Stroke(radius * .09f))
                }
            } else if (age < 490) {
                val t = (age - ConnectedBlobMotion.FLIGHT_MS) / 290f
                val landing = Offset(drop.contact.x.toFloat(), drop.contact.y.toFloat()) - feedback.rootOffset
                val outward = landing - (feedback.blobBounds.center-feedback.rootOffset)
                val normal = outward/outward.getDistance().coerceAtLeast(1f)
                val tangent = Offset(-normal.y,normal.x)
                val light = LiquidFeedbackMotion.landingLight(t)
                val travel = LiquidFeedbackMotion.landingBeadTravel(t)
                // Three little beads lift from the meniscus and rejoin it. Every
                // trail belongs to an accepted offering, never an optimistic tap.
                repeat(3) { index ->
                    val spread = (index-1)*.7f
                    val point = landing + (normal*(14f+index*3f) + tangent*(spread*15f))*density*travel
                    val radius = (2.5f + if (index == 1) .9f else 0f)*density*(.5f+.5f*travel)
                    drawCircle(color.copy(alpha = light*.92f),radius,point)
                    drawCircle(Color.White.copy(alpha = light*.55f),radius*.30f,point-Offset(radius*.28f,radius*.32f))
                }
            }
        }
    }
}

@Composable
internal fun ConnectedLivingFoodBlob(
    counts: FoodCounts,
    description: String,
    skin: SkinId,
    motionAllowed: Boolean,
    feedback: ConnectedBlobFeedback,
    modifier: Modifier,
    previewFit: Boolean = false,
) {
    val pokeLabel = stringResource(R.string.poke_blob)
    val translucency = LocalBlobTranslucency.current
    DisposableEffect(feedback) {
        onDispose {
            feedback.contour = emptyList()
            feedback.blobBounds = Rect.Zero
            feedback.interrupt()
        }
    }
    var dragOrigin by remember(feedback, feedback.generation) { mutableStateOf(Offset.Zero) }
    var drag by remember(feedback, feedback.generation) { mutableStateOf(Offset.Zero) }
    var dragging by remember(feedback, feedback.generation) { mutableStateOf(false) }
    var pressOrigin by remember(feedback, feedback.generation) { mutableStateOf(Offset.Zero) }
    var pressing by remember(feedback, feedback.generation) { mutableStateOf(false) }
    var contactEligible by remember(feedback, feedback.generation) { mutableStateOf(false) }
    val pressDepth = animateFloatAsState(
        if ((pressing || dragging) && motionAllowed) .9f else 0f,
        if (!motionAllowed) snap() else spring(.72f, 380f),
        label = "gel surface pressure",
    )
    val material = remember { createJellyVolumeMaterial() }
    // The resting mould changes only with saved counts, not on every ripple frame.
    val outlines = remember(feedback) {
        object : LinkedHashMap<FoodCounts, List<BlobPoint>>(32, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FoodCounts, List<BlobPoint>>?): Boolean = size > 128
        }
    }
    var reducedPoke by remember(feedback) { mutableIntStateOf(0) }
    val confirmation = remember { Animatable(0f) }
    var previouslyConfirmed by remember(feedback) { mutableStateOf(counts) }
    LaunchedEffect(reducedPoke, counts, motionAllowed) {
        feedback.observeCounts(counts, motionAllowed)
        val changed = counts != previouslyConfirmed
        if (motionAllowed && counts.total < previouslyConfirmed.total) feedback.recoil()
        previouslyConfirmed = counts
        if (!motionAllowed && (reducedPoke > 0 || changed)) {
            confirmation.snapTo(.16f)
            confirmation.animateTo(0f, tween(160))
        } else confirmation.snapTo(0f)
    }
    val dragX = animateFloatAsState(if (dragging && motionAllowed) drag.x else 0f, if (!motionAllowed) snap() else spring(.82f, if (dragging) 850f else 220f), label = "local stretch x")
    val dragY = animateFloatAsState(if (dragging && motionAllowed) drag.y else 0f, if (!motionAllowed) snap() else spring(.82f, if (dragging) 850f else 220f), label = "local stretch y")
    val idle = if (motionAllowed) {
        val animation = rememberInfiniteTransition(label = "connected blob idle")
        animation.animateFloat(0f, 1f, infiniteRepeatable(tween(4_500, easing = LinearEasing)), label = "breath phase")
    } else remember { mutableFloatStateOf(0f) }
    val view = LocalView.current
    fun poke(point: Offset, haptic: Boolean = true) {
        if (haptic) view.performBlobContactHaptic()
        if (motionAllowed) feedback.poke(point) else reducedPoke += 1
    }
    fun accessiblePoke() {
        val outline = feedback.contour
        val target = if (outline.isEmpty()) feedback.blobBounds.center else Offset(
            outline.map { it.x }.average().toFloat(), outline.map { it.y }.average().toFloat(),
        )
        poke(target - feedback.blobBounds.topLeft)
    }
    Canvas(
        modifier.onGloballyPositioned {
            val bounds = it.boundsInWindow()
            if (feedback.blobBounds != Rect.Zero && feedback.blobBounds != bounds) feedback.interrupt()
            feedback.blobBounds = bounds
        }.testTag("playful-blob")
            .semantics {
                contentDescription = description
                onClick(pokeLabel) { accessiblePoke(); true }
                customActions = listOf(CustomAccessibilityAction(pokeLabel) {
                    accessiblePoke(); true
                })
            }
            .pointerInput(feedback, motionAllowed) {
                detectTapGestures(
                    onPress = { point ->
                        val world = point + feedback.blobBounds.topLeft
                        contactEligible = ConnectedBlobMotion.acceptsTouch(
                            BlobPoint(world.x.toDouble(), world.y.toDouble()), feedback.contour,
                            48.0 * view.resources.displayMetrics.density,
                        )
                        pressOrigin = point
                        pressing = contactEligible
                        if (contactEligible) view.performBlobContactHaptic()
                        try { tryAwaitRelease() } finally { pressing = false }
                    },
                    onTap = { if (contactEligible) poke(it, haptic = false) },
                )
            }
            .then(if (motionAllowed) Modifier.pointerInput(feedback) {
                detectDragGestures(
                    onDragStart = { dragOrigin = pressOrigin; dragging = contactEligible; drag = Offset.Zero },
                    onDragCancel = { dragging = false; drag = Offset.Zero; contactEligible = false },
                    onDragEnd = { dragging = false; drag = Offset.Zero; if (contactEligible) poke(dragOrigin, haptic = false) },
                ) { change, amount ->
                    if (contactEligible) {
                        change.consume()
                        drag += amount
                    }
                }
            } else Modifier),
    ) {
        val now = feedback.nowMs
        val drops = feedback.drops
        fun restingOutline(value: FoodCounts) = outlines.getOrPut(value) {
            LivingBlob.samplePoints(value, LivingBlob.derivedTapSeed(value), 0.0)
        }
        // Each accepted drop contributes its own morph. Later taps preserve earlier progress.
        val baseCounts = drops.firstOrNull()?.beforeCounts ?: feedback.heldCounts ?: counts
        val basePoints = restingOutline(baseCounts)
        val morphs = drops.map { drop ->
            Triple(
                restingOutline(drop.beforeCounts),
                restingOutline(drop.afterCounts),
                ConnectedBlobMotion.settleFraction(now - drop.startedMs),
            )
        }
        val growth = if (previewFit) {
            // A material swatch shows even an empty day's actual shape clearly.
            // The Today hero keeps its normal count-dependent growth.
            (82.0 / maxOf(basePoints.maxOf { it.x } - basePoints.minOf { it.x },
                basePoints.maxOf { it.y } - basePoints.minOf { it.y })).toFloat()
        } else LivingBlob.heroGrowthScale(baseCounts.total).toFloat() + drops.sumOf { drop ->
            (LivingBlob.heroGrowthScale(drop.afterCounts.total) - LivingBlob.heroGrowthScale(drop.beforeCounts.total)) *
                ConnectedBlobMotion.settleFraction(now - drop.startedMs)
        }.toFloat()
        val visibleSpan = minOf(
            (basePoints.maxOf { it.x } - basePoints.minOf { it.x }) * size.width,
            (basePoints.maxOf { it.y } - basePoints.minOf { it.y }) * size.height,
        ).toFloat() * growth / 100f
        val phase = idle.value * Math.PI * 2
        val greeting = feedback.greetingStartedMs?.let { CoreBlobMotion.greeting(now-it) }?.toFloat() ?: 0f
        val breath = sin(phase).toFloat() * .014f + greeting
        val lift = if (motionAllowed) (3f + sin(phase).toFloat() * 3f) * density else 0f
        val pose = ConnectedBlobMotion.bodyPose(
            drops.map { CoreBlobMotion.weightedAge(now-it.startedMs, counts.total) } + feedback.pokes.map { CoreBlobMotion.weightedAge(now-it.first+ConnectedBlobMotion.FLIGHT_MS,counts.total) },
            feedback.recoilStartedMs?.let { now - it },
        )
        val anticipation = drops.fold(Offset.Zero) { lean, drop ->
            val age = now-drop.startedMs
            if (age in 0..ConnectedBlobMotion.FLIGHT_MS) {
                val direction = Offset(drop.origin.x.toFloat(),drop.origin.y.toFloat())-feedback.blobBounds.center
                lean + direction/direction.getDistance().coerceAtLeast(1f) * (sin(PI*age/ConnectedBlobMotion.FLIGHT_MS).toFloat()*size.minDimension*.017f)
            } else lean
        }
        val heldPose = ConnectedBlobMotion.heldPose(if (motionAllowed) pressDepth.value.toDouble() else 0.0)
        val bodyPivot = Offset(size.width * .5f, size.height * .72f)
        val points = basePoints.mapIndexed { index, point ->
            val x = point.x + morphs.sumOf { (before, after, t) -> (after[index].x - before[index].x) * t }
            val y = point.y + morphs.sumOf { (before, after, t) -> (after[index].y - before[index].y) * t }
            val resting = Offset(
                size.width * (.5f + (x.toFloat() / 100 - .5f) * growth * (1 + breath)),
                size.height * (.5f + (y.toFloat() / 100 - .5f) * growth * (1 - breath)),
            )
            val original = bodyPivot + Offset((resting.x - bodyPivot.x) * (pose.scaleX * heldPose.scaleX).toFloat(), (resting.y - bodyPivot.y) * (pose.scaleY * heldPose.scaleY).toFloat()) - Offset(0f, lift) + Offset(anticipation.x.coerceIn(-size.width*.035f,size.width*.035f),anticipation.y.coerceIn(-size.height*.025f,size.height*.025f))
            val world = original + feedback.blobBounds.topLeft
            var shift = Offset.Zero
            drops.forEach { drop ->
                val impulse = ConnectedBlobMotion.impulse(now - drop.startedMs).toFloat()
                val weight = ConnectedBlobMotion.localWeight(BlobPoint(world.x.toDouble(), world.y.toDouble()), drop.contact, size.minDimension * .28).toFloat()
                val inward = (center - original) / size.minDimension
                shift += inward * (impulse * weight * size.minDimension * .24f)
                val distance = hypot(world.x-drop.contact.x, world.y-drop.contact.y) / (size.minDimension*.34)
                val ripple = JellyVolumeGeometry.contactRipple(distance, (now-drop.startedMs-ConnectedBlobMotion.FLIGHT_MS)/1000.0).toFloat()
                shift -= inward * (ripple * size.minDimension * .72f)
            }
            feedback.pokes.forEach { (started, contact) ->
                val impulse = ConnectedBlobMotion.impulse(now - started + 200).toFloat()
                val weight = exp(-(original - contact).getDistanceSquared() / (size.minDimension * size.minDimension * .09f))
                shift += (center - original) / size.minDimension * (impulse * weight * size.minDimension * .30f)
            }
            val radial=original-center
            val radialLength=radial.getDistance().coerceAtLeast(1f)
            val contactDirection=pressOrigin-center
            val contactLength=contactDirection.getDistance().coerceAtLeast(1f)
            val alignment=(radial.x*contactDirection.x+radial.y*contactDirection.y)/(radialLength*contactLength)
            val influence=ConnectedBlobMotion.touchInfluence((contactDirection.getDistance()/visibleSpan.coerceAtLeast(1f)).toDouble()).toFloat()
            val displacement=ConnectedBlobMotion.touchDisplacement(alignment.toDouble(),pressDepth.value.toDouble()).toFloat()*visibleSpan/100f*influence
            shift += radial/radialLength*displacement
            val dragWeight = exp(-(original - dragOrigin).getDistanceSquared() / (size.minDimension * size.minDimension * .16f))
            val stretchLimit=size.minDimension*.15f
            val stretch=Offset(tanh(dragX.value/stretchLimit)*stretchLimit,tanh(dragY.value/stretchLimit)*stretchLimit)
            original + shift + stretch * dragWeight
        }
        feedback.contour = points.map { BlobPoint((it.x + feedback.blobBounds.left).toDouble(), (it.y + feedback.blobBounds.top).toDouble()) }
        val path = smoothBlobPath(points)
        run {
            val shadow = ConnectedBlobMotion.groundShadow(points.map { BlobPoint(it.x.toDouble(), it.y.toDouble()) })
            val shadowCenter = Offset(shadow.center.x.toFloat(), shadow.center.y.toFloat() + lift)
            val shadowRadius = (shadow.radiusX.toFloat() * (1f - lift / size.height)).coerceAtLeast(1f)
            val shadowColor = if (skin == SkinId.SHRINE) Color.Black.copy(alpha = .48f) else Color(0xFF1F3D29).copy(alpha = .27f)
            val spillColor = counts.mixedFeedbackColor()
            val landing = drops.sumOf { LiquidFeedbackMotion.landingLight(((now-it.startedMs-ConnectedBlobMotion.FLIGHT_MS)/450f).coerceIn(0f,1f)).toDouble() }.toFloat().coerceAtMost(1f)
            scale(1f, .20f, pivot = shadowCenter) {
                drawCircle(Brush.radialGradient(listOf(spillColor.copy(alpha=((if(skin==SkinId.SHRINE) .14f else .10f)+landing*.12f)*translucency),Color.Transparent),shadowCenter,shadowRadius*1.35f),shadowRadius*1.35f,shadowCenter)
            }
            // This uses this frame's actual contour: growth, breath, landings and stretches share one phase.
            scale(1f, shadow.radiusY.toFloat() / shadowRadius, pivot = shadowCenter) {
                drawCircle(
                    Brush.radialGradient(listOf(shadowColor, Color.Transparent), shadowCenter, shadowRadius),
                    shadowRadius,
                    shadowCenter,
                )
            }
        }
        val visiblePaints = drops.takeLast(JellyVolumeGeometry.PAINTS)
        // Bounded GPU detail does not truncate motion or writes: all accepted drops still
        // contribute to the contour. A very large burst folds older pigment into its base.
        var materialBase = if (baseCounts.isEmpty) Color(0xFFC5E5D8) else baseCounts.mixedFeedbackColor()
        drops.dropLast(visiblePaints.size).forEach { drop ->
            materialBase = lerp(materialBase, drop.afterCounts.mixedFeedbackColor(), JellyVolumeGeometry.paintFraction(now-drop.startedMs).toFloat())
        }
        val frame = JellyFrame(
            profile = JellyVolumeGeometry.profile(points.map { BlobPoint(it.x.toDouble(), it.y.toDouble()) }),
            color = materialBase,
            paints = visiblePaints.map { drop ->
                JellyPaint(
                    Offset(drop.contact.x.toFloat(), drop.contact.y.toFloat()) - feedback.blobBounds.topLeft,
                    now - drop.startedMs, drop.afterCounts.mixedFeedbackColor(), drop.color.feedbackColor(skin),
                )
            },
            contacts = (drops.map { drop ->
                JellyContact(
                    Offset(drop.contact.x.toFloat(), drop.contact.y.toFloat()) - feedback.blobBounds.topLeft,
                    (now - drop.startedMs - ConnectedBlobMotion.FLIGHT_MS) / 1_000f,
                )
            } + feedback.pokes.map { (started, point) -> JellyContact(point, (now-started)/1_000f, 1.2f) })
                .filter { it.seconds in 0f.. .5f }.sortedBy { it.seconds }.take(JellyVolumeGeometry.CONTACTS),
            // Keep the same anchor throughout lift-off; switching at drag end snaps the light.
            press = pressOrigin + Offset(dragX.value, dragY.value)*.35f,
            pressDepth = if (motionAllowed) pressDepth.value.coerceAtLeast(0f) else 0f,
            skin = skin,
            translucency = translucency,
        )
        material.draw(this, path, frame)
        if (confirmation.value > 0) clipPath(path) { drawRect(Color.White.copy(alpha = confirmation.value)) }

    }
}

private fun smoothBlobPath(points: List<Offset>) = Path().apply {
    moveTo(points.first().x, points.first().y)
    points.indices.forEach { index ->
        val previous = points[(index - 1 + points.size) % points.size]
        val current = points[index]
        val next = points[(index + 1) % points.size]
        val after = points[(index + 2) % points.size]
        cubicTo(current.x + (next.x - previous.x) / 6, current.y + (next.y - previous.y) / 6, next.x - (after.x - current.x) / 6, next.y - (after.y - current.y) / 6, next.x, next.y)
    }
    close()
}

private fun FoodCounts.mixedFeedbackColor(): Color {
    val mixed = BlobColor.mix(this) ?: BlobColor.EMPTY
    return Color(mixed.red.toFloat(), mixed.green.toFloat(), mixed.blue.toFloat())
}

private fun FoodColor.feedbackColor(skin: SkinId): Color = when (this) {
    FoodColor.GREEN -> skin.palette().green
    FoodColor.YELLOW -> skin.palette().yellow
    FoodColor.RED -> skin.palette().red
}
