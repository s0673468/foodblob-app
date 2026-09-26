package org.example.foodblob.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LivingBlobTest {
    @Test
    fun startingHeroInvitesTouchAndEarlyAdditionsStillGrowDistinctly() {
        assertTrue(LivingBlob.heroGrowthScale(0) * 250 * .74 >= 70)
        for (total in 0 until 3) {
            val before = LivingBlob.heroGrowthScale(total)
            val after = LivingBlob.heroGrowthScale(total + 1)
            assertTrue(after * after / (before * before) >= 1.25)
        }
        val threeToTen = LivingBlob.heroGrowthScale(10) / LivingBlob.heroGrowthScale(3)
        val twoToTen = LivingBlob.heroGrowthScale(10) / LivingBlob.heroGrowthScale(2)
        assertTrue(threeToTen * threeToTen >= 3.0)
        assertTrue(twoToTen * twoToTen >= 3.8)
    }

    @Test
    fun growthIsClampedToReservedSpace() {
        assertEquals(0.34, LivingBlob.growthScale(-1), 0.000_001)
        assertEquals(0.40, LivingBlob.heroGrowthScale(-1), 0.000_001)
        for (count in 0 until 200) {
            assertTrue(LivingBlob.growthScale(count + 1) > LivingBlob.growthScale(count))
            assertTrue(LivingBlob.heroGrowthScale(count + 1) > LivingBlob.heroGrowthScale(count))
            assertTrue(LivingBlob.growthScale(count) <= 1.1806)
            assertTrue(LivingBlob.heroGrowthScale(count) <= 1.3744)
        }
    }

    @Test
    fun tenFoodsHaveAtLeastTripleTheVisibleAreaOfThree() {
        fun area(total: Int): Double {
            val counts = FoodCounts(green = total)
            val points = LivingBlob.samplePoints(counts, total, 0.0)
            val twice = points.indices.sumOf { i ->
                val a = points[i]; val b = points[(i+1)%points.size]
                a.x*b.y-b.x*a.y
            }
            return kotlin.math.abs(twice)*.5*LivingBlob.growthScale(total)*LivingBlob.growthScale(total)
        }
        assertTrue(area(10) / area(3) >= 3.0)
        assertTrue(area(10) / area(2) >= 3.8)
        for (i in 0 until 18) assertTrue(LivingBlob.growthScale(i+1)>LivingBlob.growthScale(i))
    }

    @Test
    fun seededContourMatchesAppleParityFixture() {
        // Same independent fixture in LivingBlobTests.swift.
        val expected = listOf(33.5125807824, 40.8754926552, 40.6596391322, 40.2938821281, 33.2042397032, 34.0215370631, 39.6170165289, 33.0038664)
        val counts = FoodCounts(green = 4, yellow = 2, red = 1)
        val points = LivingBlob.samplePoints(counts, LivingBlob.derivedTapSeed(counts), 0.0)
        for (index in expected.indices) {
            val point = points[index * 6]
            assertEquals(expected[index], kotlin.math.hypot(point.x - 50, point.y - 52), 0.000001)
        }
    }

    @Test
    fun friendlyContourHasBroadCurvesWithoutTightKinks() {
        for (total in 0..120) {
            val points = LivingBlob.samplePoints(FoodCounts(green=total), total, 0.0)
            val radii = points.map { kotlin.math.hypot(it.x-50,it.y-52) }
            for(i in radii.indices) {
                val bend = radii[(i+1)%48]-2*radii[i]+radii[(i+47)%48]
                assertTrue("Sharp contour at count $total, sample $i",kotlin.math.abs(bend)<.8)
            }
        }
    }

    @Test
    fun earlySilhouettesDifferAndSeededVariationsPreserveArea() {
        fun radii(counts: FoodCounts, seed: Int = LivingBlob.derivedTapSeed(counts)) =
            LivingBlob.samplePoints(counts, seed, 0.0).map { kotlin.math.hypot(it.x - 50, it.y - 52) }
        for (total in 0..120) {
            val r = radii(FoodCounts(green = total / 2, yellow = total - total / 2))
            val area = r.indices.sumOf { r[it] * r[(it + 1) % 48] * kotlin.math.sin(2 * kotlin.math.PI / 48) / 2 }
            assertEquals(kotlin.math.PI * 37 * 37, area, 0.001)
            assertTrue(r.all { it.isFinite() && it in 25.0..46.0 })
            if (total < 3) {
                val next = radii(FoodCounts(green = total + 1))
                assertTrue(r.zip(next).sumOf { kotlin.math.abs(it.first - it.second) } / 48 > 1.5)
            }
        }
        val counts = FoodCounts(green = 4, yellow = 2, red = 1)
        assertNotEquals(radii(counts, 7), radii(counts, 8))
        assertNotEquals(radii(counts), radii(FoodCounts(green = 3, yellow = 3, red = 1)))
    }

    @Test
    fun geometryIsDeterministicBoundedAndCountSensitive() {
        val counts = FoodCounts(green = 4, yellow = 2, red = 1)
        val first = LivingBlob.samplePoints(counts, tapSeed = counts.total, boost = 0.0)
        val second = LivingBlob.samplePoints(counts, tapSeed = counts.total, boost = 0.0)
        val changed = LivingBlob.samplePoints(counts.applyDelta(FoodColor.GREEN, 1), tapSeed = 8, boost = 0.0)

        assertEquals(first, second)
        assertNotEquals(first, changed)
        assertEquals(48, first.size)
        assertTrue(first.all { it.x in 0.0..100.0 && it.y in 0.0..100.0 })
    }
}
