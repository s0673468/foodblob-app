package org.example.foodblob.widget

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.example.foodblob.domain.FoodCounts
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodWidgetArtworkRenderingTest {
    @Test
    fun nativeWidgetFixturesAndCountChangesStayInsideTheirReservedRegions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val captureDirectory = InstrumentationRegistry.getArguments().getString("widgetCaptureDir")
            ?.let { File(context.getExternalFilesDir(null), it).apply { mkdirs() } }
        val fixtures = listOf(
            "empty" to FoodCounts(),
            "one" to FoodCounts(green = 1),
            "two" to FoodCounts(green = 1, yellow = 1),
            "three" to FoodCounts(green = 1, yellow = 1, red = 1),
            "ten" to FoodCounts(green = 6, yellow = 3, red = 1),
            "mixed" to FoodCounts(green = 4, yellow = 2, red = 1),
            "red" to FoodCounts(red = 8),
            "yellow" to FoodCounts(yellow = 8),
            "grown" to FoodCounts(green = 12, yellow = 4, red = 2),
            "twenty-five" to FoodCounts(green = 15, yellow = 7, red = 3),
            "fifty" to FoodCounts(green = 30, yellow = 14, red = 6),
            "high" to FoodCounts(green = 999, yellow = 100, red = 20),
            "five-digits" to FoodCounts(green = 99_999),
            "boundary" to FoodCounts(green = Int.MAX_VALUE, yellow = 1, red = 1),
        )
        FoodWidgetVariant.entries.forEach { variant ->
            val sizes = if (variant.presentation == WidgetPresentation.SMALL) {
                listOf(152f to 156f, 168f to 156f, 203f to 102f, 108f to 108f, 130f to 220f)
            } else {
                listOf(338f to 152f, 338f to 168f, 250f to 108f, 212f to 168f, 420f to 220f)
            }
            sizes.forEach { (width, height) ->
                val geometry = FoodWidgetGeometry.resolve(width, height, variant.presentation)
                var previous: Bitmap? = null
                fixtures.forEach { (name, counts) ->
                    val bitmap = FoodWidgetArtworkRenderer.render(counts, variant, geometry, 2f, true)
                    assertEquals((width * 2).toInt(), bitmap.width)
                    assertEquals((height * 2).toInt(), bitmap.height)
                    previous?.let { before ->
                        assertFalse("Count change must have visible feedback", before.sameAs(bitmap))
                        assertNoCountPixelsOutsideReservedRegions(before, bitmap, geometry, 2f)
                        before.recycle()
                    }
                    captureDirectory?.let { directory ->
                        File(directory, "android-${variant.name.lowercase()}-${width.toInt()}x${height.toInt()}-$name.png")
                            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    previous = bitmap
                }
                previous?.recycle()
            }
        }
    }

    @Test
    fun paintIsOpaqueAndMaterialCacheKeepsAllThreeFinishesDistinct() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "paint-widget-captures").apply { mkdirs() }
        for (shrine in listOf(false, true)) {
            val images = listOf(0f, .5f, 1f).map { level ->
                FoodWidgetGelSnapshot.render(FoodCounts(8, 1, 1), shrine, 256, 256, level)
            }
            assertEquals(255, Color.alpha(images[0].getPixel(128, 128)))
            assertTrue(Color.alpha(images[1].getPixel(128, 128)) in 170..210)
            assertTrue(Color.alpha(images[2].getPixel(128, 128)) in 121..140)
            assertEquals(0, Color.alpha(images[0].getPixel(0, 0)))
            assertTrue(images[0] === FoodWidgetGelSnapshot.render(FoodCounts(8, 1, 1), shrine, 256, 256, .01f))
            assertFalse(images[0].sameAs(images[1]))
            assertFalse(images[1].sameAs(images[2]))
        }
        for (variant in FoodWidgetVariant.entries) {
            val width = if (variant.presentation == WidgetPresentation.SMALL) 168f else 338f
            val height = if (variant.presentation == WidgetPresentation.SMALL) 156f else 168f
            val geometry = FoodWidgetGeometry.resolve(width, height, variant.presentation)
            for ((name, level) in listOf("paint" to 0f, "half" to .5f, "glass" to 1f)) {
                val bitmap = FoodWidgetArtworkRenderer.render(FoodCounts(4, 2, 1), variant, geometry, 2f, true, level)
                File(directory, "${variant.name.lowercase()}-$name.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
            }
        }
    }

    @Test
    fun footerTotalRemainsReadableAndStableWhileTheBlobGrows() {
        for (presentation in WidgetPresentation.entries) {
            val two = FoodWidgetArtworkRenderer.totalTextSize(presentation, 2)
            val ten = FoodWidgetArtworkRenderer.totalTextSize(presentation, 10)
            assertTrue("Two-food numeral must remain readable", two >= 16f)
            assertTrue("The footer leaves the jelly unobstructed", ten <= 24f)
            assertEquals(FoodWidgetArtworkRenderer.totalTextSize(presentation, 0),
                FoodWidgetArtworkRenderer.totalTextSize(presentation, 99_999))
        }
    }

    @Test
    fun blobAndFooterKeepSeparateSpaceInsideEveryReservedRegion() {
        FoodWidgetVariant.entries.forEach { variant ->
            listOf(152f to 156f, 338f to 168f, 130f to 220f, 1f to 1f).forEach size@ { (width, height) ->
                val geometry = FoodWidgetGeometry.resolve(width, height, variant.presentation)
                val reserved = geometry.blobRect ?: return@size
                val composition = FoodWidgetBlobComposition.resolve(reserved, variant.presentation)
                assertTrue(composition.body.left >= reserved.leftDp)
                assertTrue(composition.body.top >= reserved.topDp)
                assertTrue(composition.body.right <= reserved.rightDp)
                assertTrue(composition.footer.bottom <= reserved.bottomDp)
                assertTrue(composition.body.bottom <= composition.footer.top)
            }
        }
    }

    @Test
    fun crampedPortraitKeepsAnInvitingSeedAndDistinctBoundedGrowth() {
        val body = android.graphics.RectF(0f, 0f, 45f, 26f)
        val zero = FoodWidgetBlobComposition.growthScale(0, body)
        val one = FoodWidgetBlobComposition.growthScale(1, body)
        val two = FoodWidgetBlobComposition.growthScale(2, body)
        val ten = FoodWidgetBlobComposition.growthScale(10, body)
        assertTrue(zero >= .5f)
        assertTrue(one > zero)
        assertTrue(two > one)
        assertTrue(ten > two * 1.3f)
        assertTrue(FoodWidgetBlobComposition.growthScale(99_999, body) <=
            org.example.foodblob.domain.LivingBlob.maximumGrowthScale.toFloat())
    }

    @Test
    fun footerInkContrastsWithTheWorldBelowTheGlass() {
        fun luminance(color: Int): Double {
            fun linear(channel: Int): Double {
                val value = channel / 255.0
                return if (value <= .04045) value / 12.92 else Math.pow((value + .055) / 1.055, 2.4)
            }
            return .2126 * linear(Color.red(color)) + .7152 * linear(Color.green(color)) +
                .0722 * linear(Color.blue(color))
        }
        FoodWidgetVariant.entries.forEach { variant ->
            val geometry = FoodWidgetGeometry.resolve(
                if (variant.presentation == WidgetPresentation.SMALL) 152f else 338f,
                168f, variant.presentation)
            val region = requireNotNull(geometry.blobRect)
            val footer = FoodWidgetBlobComposition.resolve(region, variant.presentation).footer
            val bitmap = FoodWidgetArtworkRenderer.render(FoodCounts(4, 2, 1), variant, geometry, 2f, true)
            // Adjacent background in the exact footer row, outside the numeral.
            val background = luminance(bitmap.getPixel(((footer.centerX() - 26f) * 2).toInt(),
                (footer.centerY() * 2).toInt()))
            val foreground = luminance(FoodWidgetArtworkRenderer.footerInk(variant.skin))
            val contrast = (maxOf(background, foreground) + .05) / (minOf(background, foreground) + .05)
            assertTrue("Footer needs readable contrast in $variant: $contrast", contrast >= 4.5)
            bitmap.recycle()
        }
    }

    @Test
    fun gelSnapshotHasTransparentOutsideAndSoftUnclippedLight() {
        val image = FoodWidgetGelSnapshot.render(FoodCounts(4, 2, 1), false, 184, 172, translucency = 1f)
        assertEquals(0, Color.alpha(image.getPixel(0, 0)))
        val center = image.getPixel(92, 86)
        assertTrue("Gel core should transmit the world behind it", Color.alpha(center) in 121..140)
        // The curved window is brighter than the tinted, thick central body.
        val bodyLuma = Color.red(center) + Color.green(center) + Color.blue(center)
        fun brightness(pixel: Int) = Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
        val reflection = (35 until 80).flatMap { y ->
            (45 until 90).map { x -> image.getPixel(x, y) }
        }.filter { Color.alpha(it) > 120 }.maxBy { brightness(it) }
        val reflectedLuma = brightness(reflection)
        assertTrue("Jelly reflection must be distinct from its tinted core", reflectedLuma > bodyLuma + 90)
        assertTrue("Window light must remain readable over the transmitting body",
            Color.alpha(reflection) > Color.alpha(center) + 35)
        assertTrue("Soft gel should not clip into a white metallic hotspot", reflectedLuma < 735)
        assertTrue(FoodWidgetGelSnapshot.render(FoodCounts(4, 2, 1), false, 184, 172, translucency = 1f).sameAs(image))
        assertFalse(FoodWidgetGelSnapshot.render(FoodCounts(4, 2, 1), true, 184, 172, translucency = 1f).sameAs(image))
    }

    @Test
    fun shrineCoreReceivesFillLightWithoutLosingItsTransparency() {
        val day = FoodWidgetGelSnapshot.render(FoodCounts(4, 2, 1), false, 256, 256, translucency = 1f).getPixel(128, 128)
        val night = FoodWidgetGelSnapshot.render(FoodCounts(4, 2, 1), true, 256, 256, translucency = 1f).getPixel(128, 128)
        fun brightness(pixel: Int) = Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
        assertEquals(Color.alpha(day), Color.alpha(night))
        assertTrue("Pigment needs its own fill light against the dark world, not extra opacity",
            brightness(night) > brightness(day) + 10)
    }

    @Test
    fun launcherPlaceholderSizeRendersWithoutAZeroRadiusShader() {
        FoodWidgetVariant.entries.forEach { variant ->
            val geometry = FoodWidgetGeometry.resolve(1f, 1f, variant.presentation)
            FoodWidgetArtworkRenderer.render(FoodCounts(1), variant, geometry, 1f, true).recycle()
        }
    }

    @Test
    fun mixedPuddleReflectsProportionsThroughoutTheBody() {
        val geometry = FoodWidgetGeometry.resolve(168f, 156f, WidgetPresentation.SMALL)
        val green = FoodWidgetArtworkRenderer.render(
            FoodCounts(8, 1, 1), FoodWidgetVariant.SKY_MEADOW_SMALL, geometry, 3f, true,
        )
        val red = FoodWidgetArtworkRenderer.render(
            FoodCounts(1, 1, 8), FoodWidgetVariant.SKY_MEADOW_SMALL, geometry, 3f, true,
        )
        // The total now has a footer, leaving both sides of the compact body
        // unobstructed. Sample inside its uniformly fitted silhouette; fixed
        // category colour regions would ignore the changed proportions here.
        val blob = requireNotNull(geometry.blobRect)
        val body = FoodWidgetBlobComposition.resolve(blob, WidgetPresentation.SMALL).body
        listOf(-12f, 12f).forEach { offset ->
            val x = ((blob.leftDp + blob.widthDp / 2f + offset) * 3).toInt()
            val y = ((body.top + body.height() * 0.56f) * 3).toInt()
            val greenPixel = green.getPixel(x, y)
            val redPixel = red.getPixel(x, y)
            assertTrue(Color.green(greenPixel) - Color.red(greenPixel) >
                Color.green(redPixel) - Color.red(redPixel) + 30)
        }
        green.recycle()
        red.recycle()
    }

    private fun assertNoCountPixelsOutsideReservedRegions(
        before: Bitmap,
        after: Bitmap,
        geometry: ResolvedWidgetGeometry,
        density: Float,
    ) {
        val regions = geometry.actionTargets.map { it.rect } + listOfNotNull(geometry.blobRect)
        for (y in 0 until after.height) for (x in 0 until after.width) {
            if (before.getPixel(x, y) == after.getPixel(x, y)) continue
            val inside = regions.any { rect ->
                x >= rect.leftDp * density - 1 && x <= rect.rightDp * density + 1 &&
                    y >= rect.topDp * density - 1 && y <= rect.bottomDp * density + 1
            }
            assertTrue("Changed count leaked outside its fixed region at $x,$y", inside)
        }
    }
}
