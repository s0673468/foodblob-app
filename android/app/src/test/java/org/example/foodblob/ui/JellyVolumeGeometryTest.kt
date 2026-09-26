package org.example.foodblob.ui

import org.example.foodblob.domain.BlobPoint
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class JellyVolumeGeometryTest {
    private fun circle(cx: Double = 0.0, cy: Double = 0.0, radius: Double = 1.0) =
        List(64) { val a = it * Math.PI * 2 / 64; BlobPoint(cx + cos(a) * radius, cy + sin(a) * radius) }

    @Test fun `deformed contour radii remain attached to translated and stretched body`() {
        val source = circle().map { BlobPoint(it.x * 1.3, it.y * .8) }
        val profile = JellyVolumeGeometry.profile(source)
        val moved = JellyVolumeGeometry.profile(source.map { BlobPoint(it.x + 140, it.y - 32) })
        assertArrayEquals(profile.radii, moved.radii, .0001f)
        assertEquals(profile.center.x + 140, moved.center.x, .0001)
        assertTrue(profile.radiusAt(0.0) > profile.radiusAt(Math.PI / 2))
    }

    @Test fun `rounded thickness has a front facing center and a thinner illuminated rim`() {
        val shape = JellyVolumeGeometry.profile(circle())
        assertTrue(shape.height(0.0, 0.0) > shape.height(.8, 0.0))
        assertEquals(0.0, shape.height(1.2, 0.0), 0.0)
        val center = shape.normal(0.0, 0.0)
        val edge = shape.normal(.8, 0.0)
        assertTrue(center.z > .99)
        assertTrue(edge.x > .6)
        assertEquals(1.0, sqrt(edge.x * edge.x + edge.y * edge.y + edge.z * edge.z), .00001)
    }

    @Test fun `analytic lighting matches independent finite differences across lumpy shoulders`() {
        val shape = JellyVolumeGeometry.profile(circle().mapIndexed { i, p ->
            val radius = 1 + .13 * sin(i * 2 * PI / 64 * 3)
            BlobPoint(p.x * radius, p.y * radius * .85)
        })
        for (i in 0 until 72) for (radius in listOf(.0, .12, .30, .52, .72, .88)) {
            val angle = i * 2 * PI / 72
            val x = cos(angle) * shape.radiusAt(angle) * radius
            val y = sin(angle) * shape.radiusAt(angle) * radius
            val e = .00001
            val dx = (shape.height(x+e,y)-shape.height(x-e,y))/(2*e)
            val dy = (shape.height(x,y+e)-shape.height(x,y-e))/(2*e)
            val length = sqrt(dx*dx+dy*dy+1)
            val normal = shape.normal(x,y)
            assertEquals(-dx/length, normal.x, .0001)
            assertEquals(-dy/length, normal.y, .0001)
            assertEquals(1/length, normal.z, .0001)
        }
    }

    @Test fun `analytic contact slope matches the travelling ripple including its trailing trough`() {
        for (t in listOf(.0,.04,.12,.28,.44,.5)) for (i in 1..100) {
            val d = i*.02
            val e = .00001
            val numerical = (JellyVolumeGeometry.contactHeight(d+e,t)-JellyVolumeGeometry.contactHeight(d-e,t))/(2*e)
            assertEquals(numerical,JellyVolumeGeometry.contactSlope(d,t),.000001)
        }
    }

    @Test fun `exact mesh rim stays grazing under floating point roundoff`() {
        val shape = JellyVolumeGeometry.profile(circle())
        for (i in 0 until 64) {
            val angle = i*2*PI/64
            val radius = shape.radiusAt(angle)
            for (roundoff in listOf(-1e-12,0.0,1e-12)) {
                val normal = shape.normal(cos(angle)*(radius+roundoff),sin(angle)*(radius+roundoff))
                assertTrue("The outer mesh ring must not alternate between flat and steep normals",normal.z < .01)
            }
        }
    }

    @Test fun `touch lighting is bounded and returns exactly to its resting position`() {
        assertEquals(BlobPoint(0.0,0.0), JellyVolumeGeometry.lightOffset(3.0,-4.0,0.0))
        val left = JellyVolumeGeometry.lightOffset(-.8,.4,1.0)
        val right = JellyVolumeGeometry.lightOffset(.8,.4,1.0)
        assertTrue(left.x < 0 && right.x > 0)
        assertEquals(left.y,right.y,0.0)
        val outside = JellyVolumeGeometry.lightOffset(100.0,-100.0,4.0)
        assertTrue(abs(outside.x) <= .16 && abs(outside.y) <= .16)
    }

    @Test fun `contact dents are local additive and disappear at rest`() {
        val atContact = JellyVolumeGeometry.contactHeight(0.0, 0.08)
        assertTrue(atContact < 0)
        assertTrue(abs(atContact) > abs(JellyVolumeGeometry.contactHeight(1.0, .08)))
        assertEquals(0.0, JellyVolumeGeometry.contactHeight(0.0, -.01), 0.0)
        assertEquals(0.0, JellyVolumeGeometry.contactHeight(0.0, .5), .00001)
    }

    @Test fun `soft contact has a broad shallow dent rather than a tight crease`() {
        val center = JellyVolumeGeometry.contactHeight(0.0,.08)
        val shoulder = JellyVolumeGeometry.contactHeight(.18,.08)
        assertTrue("The contact should yield without a deep sharp pit", center > -.10)
        assertTrue("A broad shoulder follows the finger", shoulder < center * .45)
    }

    @Test fun `landing lighting starts continuously and has no radial cusp at its origin`() {
        assertEquals(0.0,JellyVolumeGeometry.contactHeight(0.0,0.0),0.0)
        for (t in listOf(.02,.08,.16,.32,.48)) {
            assertEquals("A radial normal has no preferred direction at the contact",0.0,JellyVolumeGeometry.contactSlope(0.0,t),.000001)
            assertTrue(abs(JellyVolumeGeometry.contactSlope(.0001,t)) < .001)
        }
        assertTrue(abs(JellyVolumeGeometry.contactHeight(.5,.4999)) < .000001)
    }

    @Test fun `held contact spreads over a broad shoulder with bounded optical slope`() {
        val center = JellyVolumeGeometry.pressHeight(0.0,1.0)
        assertTrue(center > -.08)
        assertTrue(JellyVolumeGeometry.pressHeight(.4,1.0) < center*.5)
        for (i in 0..100) {
            val r=i*.02
            val e=.00001
            val derivative=(JellyVolumeGeometry.pressHeight(r+e,1.0)-JellyVolumeGeometry.pressHeight(r-e,1.0))/(2*e)
            assertEquals(derivative,JellyVolumeGeometry.pressSlope(r,1.0),.000001)
            assertTrue(abs(derivative)<.15)
        }
    }

    @Test fun `ripple travels across the body instead of flickering only at its landing`() {
        val near = JellyVolumeGeometry.contactRipple(.38, .10)
        val far = JellyVolumeGeometry.contactRipple(1.14, .30)
        assertTrue("A visible crest reaches the near side", near > .015)
        assertTrue("The same crest reaches the far side later", far > .003)
        assertTrue(abs(JellyVolumeGeometry.contactRipple(1.14, .10)) < far * .2)
        assertEquals(0.0, JellyVolumeGeometry.contactRipple(.4, -.01), 0.0)
        assertEquals(0.0, JellyVolumeGeometry.contactRipple(1.9, .5), 0.0)
    }

    @Test fun `absorbed offering starts at the edge and moves inside before dissolving`() {
        val contact = BlobPoint(1.0, 0.0)
        val center = BlobPoint(0.0, 0.0)
        assertEquals(contact, JellyVolumeGeometry.absorptionPoint(contact, center, 200))
        val entered = JellyVolumeGeometry.absorptionPoint(contact, center, 320)
        assertTrue(entered.x in .3.. .8)
        assertEquals(0.0, JellyVolumeGeometry.absorptionAlpha(199), 0.0)
        assertTrue(JellyVolumeGeometry.absorptionAlpha(250) > .3)
        assertEquals(0.0, JellyVolumeGeometry.absorptionAlpha(700), 0.0)
    }

    @Test fun `paint arrives only at contact and fully integrates without a terminal colour jump`() {
        assertEquals(0.0, JellyVolumeGeometry.paintFraction(199), 0.0)
        assertEquals(0.0, JellyVolumeGeometry.paintFraction(200), 0.0)
        assertTrue(JellyVolumeGeometry.paintFraction(400) in .1.. .9)
        assertEquals(1.0, JellyVolumeGeometry.paintFraction(699), .0001)
        assertEquals(1.0, JellyVolumeGeometry.paintFraction(700), 0.0)
    }
}
