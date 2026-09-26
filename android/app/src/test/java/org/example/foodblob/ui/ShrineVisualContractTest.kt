package org.example.foodblob.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.example.foodblob.domain.SkinId
import org.example.foodblob.domain.FoodCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShrineVisualContractTest {
    @Test
    fun `shrine keeps the ios navy teal night palette`() {
        assertEquals(Color(0xFF0A1421), ShrinePalette.background)
        assertEquals(Color(0xFF0F2433), ShrinePalette.surface)
        assertEquals(Color(0xFFEBF5F5), ShrinePalette.ink)
        assertEquals(Color(0x2E80E8DB), ShrinePalette.outline)
        assertEquals(Color(.13f, .82f, .44f), ShrinePalette.green)
        assertEquals(Color(.99f, .79f, .18f), ShrinePalette.yellow)
        assertEquals(Color(.96f, .30f, .35f), ShrinePalette.red)
    }

    @Test
    fun `red shrine jelly uses opaque light labels and bright meadow mixtures use dark labels`() {
        assertEquals(Color.White, blobContentColor(FoodCounts(red = 8), SkinId.SHRINE))
        for (counts in listOf(FoodCounts(green = 8), FoodCounts(yellow = 8), FoodCounts(green = 4, yellow = 4))) {
            assertEquals(Color.Black, blobContentColor(counts, SkinId.SKY_MEADOW))
        }
    }

    @Test
    fun `labels remain opaque and readable across settled mixtures in both worlds and history cards`() {
        for (skin in SkinId.entries) for (green in 0..10) for (yellow in 0..10-green) {
            val counts = FoodCounts(green, yellow, 10-green-yellow)
            for (backdrop in listOf(blobLabelBackdrop(skin), skin.palette().surface)) {
                val ink = blobContentColor(counts, skin, backdrop)
                val body = predictedBlobCore(counts, skin, backdrop)
                assertEquals(1f, ink.alpha, 0f)
                assertTrue("$counts $skin must preserve small unit text contrast", blobLabelContrast(ink, body) >= 4.5)
            }
        }
    }

    @Test
    fun `contrast converts srgb midtones to linear light`() {
        assertEquals(5.3172, blobLabelContrast(Color.Black, Color(.5f,.5f,.5f)), .001)
    }

    @Test
    fun `richer food controls preserve normal text contrast including held shade`() {
        for (skin in SkinId.entries) {
            val palette = skin.palette()
            for (tint in listOf(palette.green, palette.yellow, palette.red)) {
                val fill = tint.copy(alpha = if (skin == SkinId.SHRINE) .17f else .37f).compositeOver(palette.surface)
                val held = Color.Black.copy(alpha = .07f).compositeOver(fill)
                assertTrue(blobLabelContrast(palette.ink,fill) >= 4.5)
                assertTrue(blobLabelContrast(palette.ink,held) >= 4.5)
            }
        }
    }
}
