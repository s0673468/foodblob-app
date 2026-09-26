package org.example.foodblob.widget

import org.example.foodblob.domain.FoodColor
import org.example.foodblob.domain.FoodCounts
import org.example.foodblob.domain.SkinId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

class FoodWidgetContractTest {
    @Test
    fun `all four fixed-skin variants remain distinct`() {
        assertEquals(4, FoodWidgetVariant.entries.size)
        assertEquals(4, FoodWidgetVariant.entries.map { it.receiverClassName }.toSet().size)
        assertEquals(4, FoodWidgetVariant.entries.map { it.widgetClassName }.toSet().size)
        assertEquals(
            setOf(SkinId.SKY_MEADOW, SkinId.SHRINE),
            FoodWidgetVariant.entries.map { it.skin }.toSet(),
        )
        assertEquals(
            setOf(WidgetPresentation.SMALL, WidgetPresentation.MEDIUM),
            FoodWidgetVariant.entries.map { it.presentation }.toSet(),
        )
        assertEquals(
            mapOf(
                FoodWidgetVariant.SKY_MEADOW_SMALL to listOf(
                    "org.example.foodblob.widget.SkyMeadowSmallWidgetReceiver",
                    "org.example.foodblob.widget.SkyMeadowSmallWidget",
                ),
                FoodWidgetVariant.SHRINE_SMALL to listOf(
                    "org.example.foodblob.widget.ShrineSmallWidgetReceiver",
                    "org.example.foodblob.widget.ShrineSmallWidget",
                ),
                FoodWidgetVariant.SKY_MEADOW_MEDIUM to listOf(
                    "org.example.foodblob.widget.SkyMeadowMediumWidgetReceiver",
                    "org.example.foodblob.widget.SkyMeadowMediumWidget",
                ),
                FoodWidgetVariant.SHRINE_MEDIUM to listOf(
                    "org.example.foodblob.widget.ShrineMediumWidgetReceiver",
                    "org.example.foodblob.widget.ShrineMediumWidget",
                ),
            ),
            FoodWidgetVariant.entries.associateWith { listOf(it.receiverClassName, it.widgetClassName) },
        )
    }

    @Test
    fun `each receiver owns a distinct Glance widget runtime class`() {
        val widgetClasses = listOf(
            SkyMeadowSmallWidget::class.java,
            ShrineSmallWidget::class.java,
            SkyMeadowMediumWidget::class.java,
            ShrineMediumWidget::class.java,
        )

        assertEquals(4, widgetClasses.toSet().size)
        assertEquals(
            FoodWidgetVariant.entries.map { it.widgetClassName }.toSet(),
            widgetClasses.map { it.name }.toSet(),
        )
    }

    @Test
    fun `controls always use stable green yellow red order`() {
        assertEquals(
            listOf(FoodColor.GREEN, FoodColor.YELLOW, FoodColor.RED),
            FoodWidgetContract.colors,
        )
    }

    @Test
    fun `widget action descriptions report the whole blob total`() {
        assertEquals(9, widgetActionStatusCount(FoodCounts(green = 2, yellow = 3, red = 4)))
    }

    @Test
    fun `quick add chooses native size buckets without shrinking touch targets`() {
        val wide = FoodWidgetGeometry.resolve(
            widthDp = 203f,
            heightDp = 102f,
            presentation = WidgetPresentation.SMALL,
        )
        assertEquals(WidgetLayout.QUICK_WIDE, wide.layout)
        assertEquals(203f, wide.widthDp)
        assertEquals(102f, wide.heightDp)

        val tall = FoodWidgetGeometry.resolve(
            widthDp = 130f,
            heightDp = 220f,
            presentation = WidgetPresentation.SMALL,
        )
        assertEquals(WidgetLayout.QUICK_TALL, tall.layout)

        val grid = FoodWidgetGeometry.resolve(108f, 108f, WidgetPresentation.SMALL)
        assertEquals(WidgetLayout.QUICK_GRID, grid.layout)

        val featured = FoodWidgetGeometry.resolve(168f, 156f, WidgetPresentation.SMALL)
        assertEquals(WidgetLayout.QUICK_FEATURED, featured.layout)

        listOf(wide, tall, grid, featured).forEach { geometry ->
            assertEquals(FoodWidgetContract.colors, geometry.actionTargets.map { it.color })
            assertTrue(geometry.actionTargets.all { it.delta == 1 })
            assertTrue(geometry.actionTargets.all { it.rect.widthDp >= FoodWidgetContract.MIN_TOUCH_TARGET_DP })
            assertTrue(geometry.actionTargets.all { it.rect.heightDp >= FoodWidgetContract.MIN_TOUCH_TARGET_DP })
        }

        val impossible = FoodWidgetGeometry.resolve(108f, 64f, WidgetPresentation.SMALL)
        assertEquals(WidgetLayout.OPEN_ONLY, impossible.layout)
        assertTrue(impossible.actionTargets.isEmpty())
    }

    @Test
    fun `full control switches between short columns and normal rows`() {
        val short = FoodWidgetGeometry.resolve(
            widthDp = 250f,
            heightDp = 108f,
            presentation = WidgetPresentation.MEDIUM,
        )
        assertEquals(WidgetLayout.FULL_COLUMNS, short.layout)
        assertEquals(6, short.actionTargets.size)

        val normal = FoodWidgetGeometry.resolve(
            widthDp = 250f,
            heightDp = 168f,
            presentation = WidgetPresentation.MEDIUM,
        )
        assertEquals(WidgetLayout.FULL_ROWS, normal.layout)
        assertEquals(6, normal.actionTargets.size)

        listOf(short, normal).forEach { geometry ->
            assertEquals(
                FoodWidgetContract.colors.flatMap { listOf(it to 1, it to -1) },
                geometry.actionTargets.map { it.color to it.delta },
            )
            assertTrue(geometry.actionTargets.all { it.rect.widthDp >= FoodWidgetContract.MIN_TOUCH_TARGET_DP })
            assertTrue(geometry.actionTargets.all { it.rect.heightDp >= FoodWidgetContract.MIN_TOUCH_TARGET_DP })
        }
    }

    @Test
    fun `phone sized widgets keep the Apple composition and center the medium rows`() {
        // Pixel launchers can offer less than 168 dp for a two-cell widget.
        val small = FoodWidgetGeometry.resolve(152f, 156f, WidgetPresentation.SMALL)
        assertEquals(WidgetLayout.QUICK_FEATURED, small.layout)
        assertTrue(requireNotNull(small.blobRect).heightDp >= 90f)
        assertEquals(3, small.actionTargets.size)
        assertTrue(small.actionTargets.all { it.rect.widthDp >= 48f && it.rect.heightDp >= 48f })
        listOf(152f, 168f, 220f, 300f).forEach { height ->
            val medium = FoodWidgetGeometry.resolve(338f, height, WidgetPresentation.MEDIUM)
            assertEquals(WidgetLayout.FULL_ROWS, medium.layout)
            val first = medium.actionTargets.first().rect
            val last = medium.actionTargets.last().rect
            assertEquals(height / 2f, (first.topDp + last.bottomDp) / 2f, 0.01f)
            assertTrue(requireNotNull(medium.blobRect).widthDp >= 110f)
            assertTrue(medium.actionTargets.all { it.rect.widthDp >= 48f && it.rect.heightDp >= 48f })
        }
    }

    @Test
    fun `pinning maps every fixed variant to its existing receiver`() {
        FoodWidgetVariant.entries.forEach { variant ->
            assertEquals(variant.receiverClassName, FoodWidgetPinning.receiverClass(variant).name)
        }
    }

    @Test
    fun `responsive targets stay within the exact canvas and never overlap`() {
        val samples = listOf(
            Triple(168f, 48f, WidgetPresentation.SMALL),
            Triple(108f, 108f, WidgetPresentation.SMALL),
            Triple(168f, 116f, WidgetPresentation.SMALL),
            Triple(130f, 220f, WidgetPresentation.SMALL),
            Triple(250f, 108f, WidgetPresentation.MEDIUM),
            Triple(250f, 168f, WidgetPresentation.MEDIUM),
            Triple(420f, 220f, WidgetPresentation.MEDIUM),
        )

        samples.forEach { (width, height, presentation) ->
            val geometry = FoodWidgetGeometry.resolve(width, height, presentation)
            assertEquals(width, geometry.widthDp)
            assertEquals(height, geometry.heightDp)
            geometry.actionTargets.forEach { target ->
                assertTrue(target.rect.leftDp >= 0f)
                assertTrue(target.rect.topDp >= 0f)
                assertTrue(target.rect.rightDp <= width)
                assertTrue(target.rect.bottomDp <= height)
            }
            geometry.actionTargets.forEachIndexed { index, target ->
                geometry.actionTargets.drop(index + 1).forEach { other ->
                    assertFalse(target.rect.intersects(other.rect))
                }
                geometry.blobRect?.let { assertFalse(target.rect.intersects(it)) }
            }
        }
    }

    @Test
    fun `minus action is unavailable at zero without disabling add`() {
        val counts = FoodCounts()

        FoodWidgetContract.colors.forEach { color ->
            assertTrue(FoodWidgetContract.isActionEnabled(counts, color, delta = 1))
            assertFalse(FoodWidgetContract.isActionEnabled(counts, color, delta = -1))
        }
    }

    @Test
    fun `only one-step widget deltas are accepted`() {
        assertTrue(FoodWidgetContract.isSupportedDelta(-1))
        assertTrue(FoodWidgetContract.isSupportedDelta(1))
        assertFalse(FoodWidgetContract.isSupportedDelta(0))
        assertFalse(FoodWidgetContract.isSupportedDelta(2))
    }

    @Test
    fun `each widget callback receives a fresh event ID`() {
        val ids = List(32) { FoodWidgetContract.newEventId() }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `consecutive widget updates advance the live composition signal`() {
        val before = FoodBlobWidgetRefreshSignal.ticks.value

        FoodBlobWidgetRefreshSignal.advance()
        FoodBlobWidgetRefreshSignal.advance()

        assertEquals(before + 2L, FoodBlobWidgetRefreshSignal.ticks.value)
    }

    @Test
    fun `day boundary scheduler tracks daylight saving transitions`() {
        assertEquals(
            setOf(
                "android.intent.action.BOOT_COMPLETED",
                "android.intent.action.TIMEZONE_CHANGED",
                "android.intent.action.TIME_SET",
            ),
            FoodWidgetContract.dayRefreshActions,
        )

        val newYork = ZoneId.of("America/New_York")
        assertEquals(
            Instant.parse("2026-03-09T04:00:00Z").toEpochMilli(),
            WidgetDayRolloverContract.nextTriggerEpochMs(
                Instant.parse("2026-03-08T05:00:01Z"),
                newYork,
            ),
        )
        assertEquals(
            Instant.parse("2026-11-02T05:00:00Z").toEpochMilli(),
            WidgetDayRolloverContract.nextTriggerEpochMs(
                Instant.parse("2026-11-01T04:00:01Z"),
                newYork,
            ),
        )
    }

    @Test
    fun `rollover rearms before a failed redraw`() {
        val calls = mutableListOf<String>()

        try {
            kotlinx.coroutines.runBlocking {
                runWidgetRollover(
                    reschedule = { calls += "schedule" },
                    redraw = {
                        calls += "redraw"
                        throw IOException("synthetic redraw failure")
                    },
                )
            }
        } catch (_: IOException) {
            // Expected: ordering, not swallowing, is the contract under test.
        }

        assertEquals(listOf("schedule", "redraw"), calls)
    }

    @Test
    fun `legacy refresh contains runtime failure`() {
        kotlinx.coroutines.runBlocking {
            refreshLegacyWidgetSafely { error("synthetic widget refresh failure") }
        }
    }

    @Test(expected = CancellationException::class)
    fun `legacy refresh preserves cancellation`() {
        kotlinx.coroutines.runBlocking {
            refreshLegacyWidgetSafely { throw CancellationException("synthetic cancellation") }
        }
    }
}

private fun WidgetRect.intersects(other: WidgetRect): Boolean =
    leftDp < other.rightDp && rightDp > other.leftDp &&
        topDp < other.bottomDp && bottomDp > other.topDp
