package org.example.foodblob.ui

import org.example.foodblob.domain.BlobPoint
import org.example.foodblob.domain.FoodColor
import org.example.foodblob.domain.FoodCounts
import org.junit.Assert.*
import org.junit.Test

class ConnectedBlobMotionTest {
    @Test fun `central pressure cannot inflate outline or select arbitrary direction`() {
        assertEquals(0.0,ConnectedBlobMotion.touchInfluence(0.0),0.0)
        assertTrue(ConnectedBlobMotion.touchInfluence(.0001)<.00001)
        assertEquals(1.0,ConnectedBlobMotion.touchInfluence(.16),0.0)
        assertEquals(1.0,ConnectedBlobMotion.touchInfluence(.8),0.0)
    }
    @org.junit.Test fun `held touch has a rounded shoulder and redistributes volume`() {
        val shifts=(0 until 360).map { ConnectedBlobMotion.touchDisplacement(kotlin.math.cos(it*Math.PI/180),1.0) }
        org.junit.Assert.assertEquals(0.0,shifts.average(),.000001)
        org.junit.Assert.assertTrue(shifts[45] < shifts[0]*.45)
        org.junit.Assert.assertTrue(shifts[90] > 0)
        org.junit.Assert.assertTrue(shifts[180] > 0)
        for (i in shifts.indices) org.junit.Assert.assertTrue(kotlin.math.abs(shifts[(i+1)%360]-2*shifts[i]+shifts[(i+359)%360]) < .004)
    }
    @Test fun `flight starts on the pressed surface and lands on actual contour after 200ms`() {
        val origin = BlobPoint(160.0, 540.0)
        val contour = listOf(BlobPoint(80.0, 120.0), BlobPoint(240.0, 120.0), BlobPoint(240.0, 260.0), BlobPoint(80.0, 260.0))
        val contact = ConnectedBlobMotion.contact(origin, BlobPoint(160.0, 190.0), contour)
        assertEquals(BlobPoint(160.0, 260.0), contact)
        assertEquals(origin, ConnectedBlobMotion.flightPoint(origin, contact, 0.0))
        assertEquals(contact, ConnectedBlobMotion.flightPoint(origin, contact, 1.0))
        assertTrue(ConnectedBlobMotion.flightPoint(origin, contact, .95).y > contact.y)
        assertEquals(200L, ConnectedBlobMotion.FLIGHT_MS)
    }

    @Test fun `burst keeps independent flights and adds contact impulses without resetting older phases`() {
        val session = BlobInteractionSession()
        repeat(9) { index -> session.accept(session.generation, FoodColor.GREEN, FoodCounts(green = index + 1), index * 20L, BlobPoint(0.0, 10.0), BlobPoint(0.0, 0.0)) }
        assertEquals(9, session.drops.size)
        assertEquals(0L, session.drops.first().startedMs)
        assertTrue(ConnectedBlobMotion.impulse(240) != 0.0)
        assertEquals(ConnectedBlobMotion.impulse(240) + ConnectedBlobMotion.impulse(220), ConnectedBlobMotion.combinedImpulse(listOf(240, 220)), 0.00001)
        assertEquals(0.0, ConnectedBlobMotion.impulse(199), 0.0)
        assertEquals(0.0, ConnectedBlobMotion.impulse(700), 0.0)
    }

    @Test fun `interruption rejects late success presentation but does not change accepted food counts`() {
        val session = BlobInteractionSession()
        val pendingGeneration = session.generation
        session.accept(pendingGeneration, FoodColor.RED, FoodCounts(red = 1), 0, BlobPoint(0.0, 10.0), BlobPoint(0.0, 0.0))
        session.interrupt()
        val persisted = FoodCounts(red = 2)
        assertFalse(session.accept(pendingGeneration, FoodColor.RED, persisted, 40, BlobPoint(0.0, 10.0), BlobPoint(0.0, 0.0)))
        assertTrue(session.drops.isEmpty())
        assertEquals(2, persisted.red)
    }

    @Test fun `snapshot arriving before accepted callback cannot flash the new fill`() {
        val session = BlobInteractionSession()
        val before = FoodCounts()
        val after = FoodCounts(green = 1)
        val request = session.beginAdd(before)
        assertEquals(before, session.heldCounts ?: after)
        session.accept(request.generation, FoodColor.GREEN, after, 0, BlobPoint(0.0, 10.0), BlobPoint(0.0, 0.0))
        session.finishAdd(request, after)
        assertEquals(before, session.drops.first().beforeCounts)
        session.advance(700)
        assertEquals(after, session.heldCounts ?: after)
    }

    @Test fun `failed and no-op additions release their presentation hold`() {
        val session = BlobInteractionSession()
        val request = session.beginAdd(FoodCounts(green = 1))
        val queued = session.beginAdd(FoodCounts(green = 1))
        session.finishAdd(request, null)
        assertEquals(FoodCounts(green = 1), session.heldCounts)
        session.finishAdd(queued, null)
        assertNull(session.heldCounts)
        assertTrue(session.drops.isEmpty())
    }

    @Test fun `pending burst holds latest accepted fill and stale completions cannot clear a new scope`() {
        val session = BlobInteractionSession()
        val first = session.beginAdd(FoodCounts())
        val second = session.beginAdd(FoodCounts())
        session.finishAdd(first, FoodCounts(green = 1))
        assertEquals(FoodCounts(green = 1), session.heldCounts)
        session.interrupt()
        val current = session.beginAdd(FoodCounts(red = 3))
        session.finishAdd(second, FoodCounts(green = 2))
        assertEquals(FoodCounts(red = 3), session.heldCounts)
        session.finishAdd(current, null)
        assertNull(session.heldCounts)
    }

    @Test fun `cold layout cannot consume the flight before its first visible frame`() {
        val clock = BlobPresentationClock(1_000)
        assertEquals(1_000L, clock.atFrame(9_247_000_000))
        assertEquals(1_016L, clock.atFrame(9_263_000_000))
        assertEquals(1_200L, clock.atFrame(9_447_000_000))
    }

    @Test fun `a burst resumes from the existing visual time without restarting older drops`() {
        val clock = BlobPresentationClock(1_000)
        assertEquals(1_000L, clock.atFrame(10_000_000_000))
        assertEquals(1_110L, clock.atFrame(10_110_000_000))
        assertEquals(1_126L, clock.atFrame(10_126_000_000))
    }

    @Test fun `ground shadow follows the deformed outline instead of a separate phase`() {
        val outline = listOf(BlobPoint(40.0, 20.0), BlobPoint(160.0, 20.0), BlobPoint(170.0, 160.0), BlobPoint(30.0, 160.0))
        val shadow = ConnectedBlobMotion.groundShadow(outline)
        val shifted = ConnectedBlobMotion.groundShadow(outline.map { BlobPoint(it.x + 12, it.y - 8) })
        assertEquals(shadow.center.x + 12, shifted.center.x, .00001)
        assertEquals(shadow.center.y - 8, shifted.center.y, .00001)
        assertEquals(shadow.radiusX, shifted.radiusX, .00001)
        val grown = ConnectedBlobMotion.groundShadow(outline.map { BlobPoint(it.x * 1.15, it.y * 1.15) })
        assertEquals(shadow.radiusX * 1.15, grown.radiusX, .00001)
        assertEquals(shadow.radiusY * 1.15, grown.radiusY, .00001)
        val compressed = ConnectedBlobMotion.groundShadow(outline.map { BlobPoint(it.x * 1.05, it.y * .92) })
        assertTrue(compressed.radiusX > shadow.radiusX)
        assertTrue(compressed.radiusY < shadow.radiusY)
    }

    @Test fun `shape growth starts at contact and converges without a hard reset`() {
        assertEquals(0.0, ConnectedBlobMotion.settleFraction(199), 0.0)
        assertEquals(0.0, ConnectedBlobMotion.settleFraction(200), 0.0)
        assertEquals(.5, ConnectedBlobMotion.settleFraction(450), .00001)
        assertEquals(1.0, ConnectedBlobMotion.settleFraction(700), 0.0)
        assertEquals(1.0, ConnectedBlobMotion.settleFraction(1000), 0.0)
    }

    @Test fun `completed impulses are removed while newer accepted drops continue`() {
        val session = BlobInteractionSession()
        session.accept(0, FoodColor.GREEN, FoodCounts(green = 1), 0, BlobPoint(0.0, 10.0), BlobPoint(0.0, 0.0))
        session.accept(0, FoodColor.YELLOW, FoodCounts(green = 1, yellow = 1), 150, BlobPoint(0.0, 10.0), BlobPoint(0.0, 0.0))
        session.advance(700)
        assertEquals(1, session.drops.size)
        assertEquals(FoodColor.YELLOW, session.drops.single().color)
        assertEquals(FoodCounts(green = 1), session.drops.single().beforeCounts)
        session.advance(850)
        assertTrue(session.drops.isEmpty())
    }
    @Test fun `whole body lands after flight and keeps its area through a burst`() {
        val waiting = ConnectedBlobMotion.bodyPose(listOf(100, 199))
        assertEquals(1.0, waiting.scaleX, 0.0)
        assertEquals(1.0, waiting.scaleY, 0.0)
        val landed = ConnectedBlobMotion.bodyPose(listOf(250))
        assertTrue(landed.scaleX > 1.04)
        assertTrue(landed.scaleY < .97)
        assertEquals(1.0, landed.scaleX * landed.scaleY, .00001)
        val burst = ConnectedBlobMotion.bodyPose(List(30) { 250 })
        assertTrue(burst.scaleX <= 1.17)
        assertTrue(burst.scaleY >= .85)
        val resting = ConnectedBlobMotion.bodyPose(listOf(700, 900))
        assertEquals(1.0, resting.scaleX, 0.0)
        assertEquals(1.0, resting.scaleY, 0.0)
    }

    @Test fun `soft contact yields before its first rebound instead of snapping back early`() {
        assertTrue("The body should still yield150ms after contact", ConnectedBlobMotion.impulse(350) > 0.0)
        assertTrue("The first rebound should follow the slower yield", ConnectedBlobMotion.impulse(450) < 0.0)
        assertEquals(0.0, ConnectedBlobMotion.impulse(700), 0.0)
    }

    @Test fun `remove recoil starts with a gentle lift and settles without food flights`() {
        val recoil = ConnectedBlobMotion.bodyPose(emptyList(), recoilAgeMs = 50)
        assertTrue(recoil.scaleX < 1.0)
        assertTrue(recoil.scaleY > 1.0)
        val resting = ConnectedBlobMotion.bodyPose(emptyList(), recoilAgeMs = 500)
        assertEquals(1.0, resting.scaleX, 0.0)
    }

    @Test fun `contact cadence coalesces burst landings without repeating on later frames`() {
        val cadence = BlobContactCadence()
        assertTrue(cadence.accept(200))
        assertFalse(cadence.accept(210))
        assertFalse(cadence.accept(240))
        assertTrue(cadence.accept(250))
        cadence.reset()
        assertTrue(cadence.accept(251))
    }

}
