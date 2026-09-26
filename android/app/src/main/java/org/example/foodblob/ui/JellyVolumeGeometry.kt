package org.example.foodblob.ui

import org.example.foodblob.domain.BlobPoint
import kotlin.math.*

/** View-local geometry; neither the gel volume nor a touch can change a food count. */
internal data class JellyNormal(val x: Double, val y: Double, val z: Double)

internal data class JellyProfile(val center: BlobPoint, val scale: Double, val radii: FloatArray) {
    fun radiusAt(angle: Double): Double {
        val phase = ((angle / (2 * PI) % 1 + 1) % 1) * radii.size
        val index = floor(phase).toInt()
        fun at(offset: Int) = radii[(index + offset + radii.size) % radii.size].toDouble()
        val t = phase-index
        val a = at(-1); val b = at(0); val c = at(1); val d = at(2)
        return max(.08,.5*((2*b)+(-a+c)*t+(2*a-5*b+4*c-d)*t*t+(-a+3*b-3*c+d)*t*t*t))
    }

    /** Catmull-Rom radius and its exact derivative with respect to the polar angle. */
    private fun radiusAndSlope(angle: Double): BlobPoint {
        val phase = ((angle / (2 * PI) % 1 + 1) % 1) * radii.size
        val index = floor(phase).toInt()
        fun at(offset: Int) = radii[(index + offset + radii.size) % radii.size].toDouble()
        val t = phase - index
        val a = at(-1); val b = at(0); val c = at(1); val d = at(2)
        val radius = .5 * ((2*b) + (-a+c)*t + (2*a-5*b+4*c-d)*t*t + (-a+3*b-3*c+d)*t*t*t)
        val slope = .5 * ((-a+c) + 2*(2*a-5*b+4*c-d)*t + 3*(-a+3*b-3*c+d)*t*t) * radii.size / (2*PI)
        return BlobPoint(max(.08, radius), if (radius > .08) slope else 0.0)
    }

    fun height(x: Double, y: Double): Double {
        val distance = hypot(x, y)
        val edgeDistance = max(0.0, radiusAt(atan2(y, x)) - distance)
        val shoulder = .68 * sqrt(1 - exp(-edgeDistance * 4.5))
        // Blend the broad centre into the rounded shoulder. Polar lobes must not
        // produce spokes or a pinched highlight where their rays meet.
        val t = (distance / .40).coerceIn(0.0, 1.0)
        val blend = t * t * (3 - 2 * t)
        return .66 * (1 - blend) + shoulder * blend
    }

    /** One contour lookup replaces four finite-difference height evaluations. */
    fun gradient(x: Double, y: Double): BlobPoint {
        val distance = hypot(x,y)
        if (distance < .000001) return BlobPoint(0.0,0.0)
        val radial = radiusAndSlope(atan2(y,x))
        val edge = radial.x-distance
        if (edge < -.000001) return BlobPoint(0.0,0.0)
        // The final mesh ring sits exactly on the edge; roundoff must not flip its normal.
        val attenuation = exp(-max(edge,.000001)*4.5)
        val root = sqrt(1-attenuation).coerceAtLeast(.000001)
        val shoulder = .68*root
        val slope = .68*2.25*attenuation/root
        val t = (distance/.40).coerceIn(0.0,1.0)
        val blend = t*t*(3-2*t)
        val blendSlope = if (distance < .40) 6*t*(1-t)/.40 else 0.0
        val radialSlope = (shoulder-.66)*blendSlope-slope*blend
        val angularSlope = slope*blend*radial.y/(distance*distance)
        return BlobPoint(radialSlope*x/distance-angularSlope*y, radialSlope*y/distance+angularSlope*x)
    }

    fun normal(x: Double, y: Double): JellyNormal {
        val gradient = gradient(x,y)
        val length = sqrt(gradient.x*gradient.x+gradient.y*gradient.y+1)
        return JellyNormal(-gradient.x/length,-gradient.y/length,1/length)
    }
}

internal object JellyVolumeGeometry {
    const val RADII = 64
    const val CONTACTS = 4
    const val PAINTS = 8

    /** Re-sampling after deformation makes reflected light follow the actual drawn edge. */
    fun profile(outline: List<BlobPoint>): JellyProfile {
        require(outline.size >= 3)
        val left = outline.minOf { it.x }; val right = outline.maxOf { it.x }
        val top = outline.minOf { it.y }; val bottom = outline.maxOf { it.y }
        val center = BlobPoint((left+right)*.5, (top+bottom)*.5)
        val scale = max(max(right-left, bottom-top)*.5, .001)
        val radii = FloatArray(RADII) { index ->
            val a = index * 2 * PI / RADII
            val contact = ConnectedBlobMotion.contact(
                BlobPoint(center.x + cos(a)*scale*4, center.y + sin(a)*scale*4), center, outline,
            )
            (hypot(contact.x-center.x, contact.y-center.y)/scale).coerceAtLeast(.08).toFloat()
        }
        return JellyProfile(center, scale, radii)
    }

    /** A restrained view-light shift follows touch without changing the resting material. */
    fun lightOffset(x: Double, y: Double, pressure: Double): BlobPoint {
        val amount = pressure.coerceIn(0.0,1.0)*.16
        if (amount == 0.0) return BlobPoint(0.0,0.0)
        return BlobPoint(x.coerceIn(-1.0,1.0)*amount,y.coerceIn(-1.0,1.0)*amount)
    }

    fun contactSlope(distance: Double, secondsAfterContact: Double): Double {
        if (secondsAfterContact < 0 || secondsAfterContact >= .5) return 0.0
        val t = secondsAfterContact
        val envelope = contactEnvelope(t)
        val dent = -.065*exp(-distance*distance/.32)*exp(-t*4)*envelope
        val front = distance-t*3.8
        val back = distance+t*3.8
        return dent*(-2*distance/.32) - .052*envelope/.1764 *
            (front*exp(-front*front/.1764)+back*exp(-back*back/.1764))
    }

    fun pressHeight(distance: Double, pressure: Double): Double =
        -.065*pressure.coerceIn(0.0,1.2)*exp(-distance*distance/.32)

    fun pressSlope(distance: Double, pressure: Double): Double =
        pressHeight(distance,pressure)*(-2*distance/.32)

    private fun contactEnvelope(t: Double): Double {
        val rise=(t/.06).coerceIn(0.0,1.0)
        return rise*rise*(3-2*rise)*(1-t/.5).pow(2)
    }

    fun paintFraction(ageMs: Long): Double = ConnectedBlobMotion.settleFraction(ageMs)

    fun contactHeight(distance: Double, secondsAfterContact: Double): Double {
        if (secondsAfterContact < 0 || secondsAfterContact >= .5) return 0.0
        val t = secondsAfterContact
        val envelope = contactEnvelope(t)
        val dent = -.065 * exp(-distance*distance/.32) * exp(-t*4)
        return dent*envelope + contactRipple(distance, t)
    }

    /** One broad crest crosses the entire body, then vanishes at the same settlement boundary. */
    fun contactRipple(distance: Double, secondsAfterContact: Double): Double {
        if (secondsAfterContact < 0 || secondsAfterContact >= .5) return 0.0
        val t = secondsAfterContact
        val front = distance - t * 3.8
        val back = distance + t * 3.8
        // The mirrored crest makes the radial derivative zero at the touch centre.
        // Smooth onset and settlement keep reflected light from snapping into a pit.
        return .026 * contactEnvelope(t) * (exp(-front*front/.1764)+exp(-back*back/.1764))
    }

    fun absorptionPoint(contact: BlobPoint, center: BlobPoint, ageMs: Long): BlobPoint {
        val t = ((ageMs - ConnectedBlobMotion.FLIGHT_MS) / 260.0).coerceIn(0.0, 1.0)
        val travel = .72 * (1 - (1 - t).pow(2))
        return BlobPoint(contact.x + (center.x-contact.x)*travel, contact.y + (center.y-contact.y)*travel)
    }

    fun absorptionAlpha(ageMs: Long): Double {
        if (ageMs < ConnectedBlobMotion.FLIGHT_MS) return 0.0
        val t = ((ageMs - ConnectedBlobMotion.FLIGHT_MS) / 360.0).coerceIn(0.0, 1.0)
        return .64 * (1-t).pow(2)
    }
}
