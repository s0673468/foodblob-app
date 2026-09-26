package org.example.foodblob.ui

import org.example.foodblob.domain.BlobPoint
import org.example.foodblob.domain.FoodColor
import org.example.foodblob.domain.FoodCounts
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/** Short-lived presentation only. No pending animation owns or delays a food write. */
internal data class FoodDrop(
    val color: FoodColor,
    val afterCounts: FoodCounts,
    val startedMs: Long,
    val origin: BlobPoint,
    val contact: BlobPoint,
) {
    val beforeCounts: FoodCounts get() = afterCounts.applyDelta(color, -1)
}

internal data class BlobAddRequest(val id: Long, val generation: Int)

internal class BlobInteractionSession {
    var generation: Int = 0
        private set
    var drops: List<FoodDrop> = emptyList()
        private set
    var heldCounts: FoodCounts? = null
        private set
    private var nextRequestId = 0L
    private val pendingAdds = mutableSetOf<BlobAddRequest>()

    // A saved snapshot may arrive before its success callback. Hold only the blob's
    // appearance during that gap; the rows and total still show the saved counts.
    fun beginAdd(counts: FoodCounts): BlobAddRequest {
        if (pendingAdds.isEmpty()) heldCounts = counts
        return BlobAddRequest(nextRequestId++, generation).also { pendingAdds += it }
    }

    fun finishAdd(request: BlobAddRequest, acceptedCounts: FoodCounts?) {
        if (!pendingAdds.remove(request)) return
        if (pendingAdds.isEmpty()) heldCounts = null
        else if (acceptedCounts != null) heldCounts = acceptedCounts
    }

    fun accept(token: Int, color: FoodColor, counts: FoodCounts, nowMs: Long, origin: BlobPoint, contact: BlobPoint): Boolean {
        if (token != generation) return false
        drops = drops + FoodDrop(color, counts, nowMs, origin, contact)
        return true
    }

    fun advance(nowMs: Long) {
        drops = drops.filter { CoreBlobMotion.weightedAge(nowMs-it.startedMs, it.afterCounts.total) < ConnectedBlobMotion.SETTLED_MS }
    }

    fun interrupt() {
        generation += 1
        drops = emptyList()
        pendingAdds.clear()
        heldCounts = null
    }
}

internal data class BlobGrounding(val center: BlobPoint, val radiusX: Double, val radiusY: Double)

internal data class BlobBodyPose(val scaleX: Double, val scaleY: Double)

internal object ConnectedBlobMotion {
    const val FLIGHT_MS = 200L
    const val SETTLED_MS = 700L

    fun acceptsTouch(point: BlobPoint, outline: List<BlobPoint>, minimumDiameter: Double): Boolean {
        if (outline.isEmpty()) return false
        if (BlobColorDrag.contains(point, outline)) return true
        val left = outline.minOf { it.x }
        val right = outline.maxOf { it.x }
        val top = outline.minOf { it.y }
        val bottom = outline.maxOf { it.y }
        return right - left < minimumDiameter && bottom - top < minimumDiameter &&
            hypot(point.x - (left + right) / 2, point.y - (top + bottom) / 2) <= minimumDiameter / 2
    }

    fun touchInfluence(normalizedDistance: Double): Double {
        val t=(normalizedDistance/.16).coerceIn(0.0,1.0)
        return t*t*(3-2*t)
    }

    /** Two low angular modes spread finger pressure without pinching the silhouette. */
    fun touchDisplacement(alignment: Double, depth: Double): Double {
        val a=alignment.coerceIn(-1.0,1.0)
        return depth * (-4.6*a-1.6*(2*a*a-1))
    }

    /** Ray/contour intersection keeps contact on the rendered outline for any layout. */
    fun contact(origin: BlobPoint, center: BlobPoint, contour: List<BlobPoint>): BlobPoint {
        val dx = origin.x - center.x
        val dy = origin.y - center.y
        var nearest = Double.POSITIVE_INFINITY
        var result = center
        contour.indices.forEach { index ->
            val a = contour[index]
            val b = contour[(index + 1) % contour.size]
            val sx = b.x - a.x
            val sy = b.y - a.y
            val cross = dx * sy - dy * sx
            if (kotlin.math.abs(cross) > .000001) {
                val ax = a.x - center.x
                val ay = a.y - center.y
                val t = (ax * sy - ay * sx) / cross
                val u = (ax * dy - ay * dx) / cross
                if (t >= 0 && u in 0.0..1.0 && t < nearest) {
                    nearest = t
                    result = BlobPoint(center.x + dx * t, center.y + dy * t)
                }
            }
        }
        return result
    }

    fun flightPoint(origin: BlobPoint, contact: BlobPoint, fraction: Double): BlobPoint {
        val t = fraction.coerceIn(0.0, 1.0)
        val distance = hypot(contact.x - origin.x, contact.y - origin.y)
        val arc = minOf(76.0, distance * .18) * sin(Math.PI * t)
        val travel = 1.0 - (1.0 - t) * (1.0 - t)
        return BlobPoint(origin.x + (contact.x - origin.x) * travel + arc, origin.y + (contact.y - origin.y) * travel)
    }

    fun impulse(ageMs: Long): Double {
        if (ageMs < FLIGHT_MS || ageMs >= SETTLED_MS) return 0.0
        val t = (ageMs - FLIGHT_MS) / 500.0
        // One broad yield and rebound feels soft; rapid oscillations read as a taut shell.
        return sin(t * 9.0) * (1.0 - t) * (1.0 - t)
    }

    /** Project the current outline onto its contact shadow, including local deformation. */
    fun groundShadow(outline: List<BlobPoint>): BlobGrounding {
        require(outline.isNotEmpty())
        val left = outline.minOf { it.x }
        val right = outline.maxOf { it.x }
        val top = outline.minOf { it.y }
        val bottom = outline.maxOf { it.y }
        val height = bottom - top
        return BlobGrounding(
            center = BlobPoint((left + right) * .5, bottom + height * .015),
            radiusX = (right - left) * .44,
            radiusY = height * .075,
        )
    }

    fun settleFraction(ageMs: Long): Double {
        val t = ((ageMs - FLIGHT_MS) / 500.0).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    fun combinedImpulse(agesMs: List<Long>): Double = agesMs.sumOf(::impulse).coerceIn(-1.4, 1.4)

    /** Volume-preserving squash makes contact visible across the whole silhouette. */
    fun bodyPose(agesMs: List<Long>, recoilAgeMs: Long? = null): BlobBodyPose {
        val recoil = recoilAgeMs?.let { -.65 * impulse(it + FLIGHT_MS) } ?: 0.0
        val wave = (agesMs.sumOf(::impulse) + recoil).coerceIn(-1.15, 1.15)
        val width = 1.0 + wave * .145
        return BlobBodyPose(width, 1.0 / width)
    }


    /** The entire body yields even when a finger lands in the middle of a small seed. */
    fun heldPose(depth: Double): BlobBodyPose {
        val width = 1.0 + depth.coerceIn(0.0, 1.0) * .05
        return BlobBodyPose(width, 1.0 / width)
    }

    fun localWeight(point: BlobPoint, contact: BlobPoint, radius: Double): Double {
        val distance = hypot(point.x - contact.x, point.y - contact.y)
        return exp(-(distance * distance) / (radius * radius))
    }
}

/** Start visual time on its first available frame, so a cold layout cannot skip a whole flight. */
internal class BlobPresentationClock(private val initialMs: Long) {
    private var firstFrameNanos: Long? = null

    fun atFrame(frameNanos: Long): Long {
        val first = firstFrameNanos ?: frameNanos.also { firstFrameNanos = it }
        return initialMs + ((frameNanos - first) / 1_000_000L).coerceAtLeast(0L)
    }
}

/** Nearby landings share one tactile cue; an interrupted scope never leaks a late cue. */
internal class BlobContactCadence {
    private var lastMs: Long? = null
    fun accept(nowMs: Long): Boolean {
        if (lastMs?.let { nowMs - it < 45 } == true) return false
        lastMs = nowMs
        return true
    }
    fun reset() { lastMs = null }
}
