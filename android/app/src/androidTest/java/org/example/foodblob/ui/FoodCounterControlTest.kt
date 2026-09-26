package org.example.foodblob.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.example.foodblob.domain.FoodColor
import org.example.foodblob.domain.SkinId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FoodCounterControlTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun emptyLongPressNeverAddsRemovesOrBuzzes() {
        var adds = 0
        var removes = 0
        var haptics = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalBlobFeedback provides androidx.compose.runtime.remember {ConnectedBlobFeedback()},LocalHapticFeedback provides object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += 1 }
            }) {
                MaterialTheme {
                    FoodCounterRow(FoodColor.GREEN, 0, false, SkinId.SKY_MEADOW, true,
                        onIncrement = { adds += 1 }, onDecrement = { removes += 1 })
                }
            }
        }
        composeRule.onNodeWithTag("add-green").performTouchInput { longClick(androidx.compose.ui.geometry.Offset(width * .15f, height * .5f)) }
        composeRule.runOnIdle {
            assertEquals(0, adds)
            assertEquals(0, removes)
            assertEquals(0, haptics)
        }
    }

    @Test fun compressionChangesTheSurfaceWithoutMovingTouchTargets() {
        composeRule.setContent {
            MaterialTheme {
                FoodCounterRow(FoodColor.GREEN, 2, false, SkinId.SKY_MEADOW, true,
                    onIncrement = {}, onDecrement = {})
            }
        }
        composeRule.mainClock.autoAdvance = false
        val add = composeRule.onNodeWithTag("add-green")
        val minus = composeRule.onNodeWithTag("remove-green")
        val addBounds = add.getUnclippedBoundsInRoot()
        val minusBounds = minus.getUnclippedBoundsInRoot()
        val resting = add.captureToImage().toPixelMap()
        add.performTouchInput { down(androidx.compose.ui.geometry.Offset(width * .15f, height * .5f)) }
        composeRule.mainClock.advanceTimeBy(120)
        val compressed = add.captureToImage().toPixelMap()
        assertEquals(addBounds, add.getUnclippedBoundsInRoot())
        assertEquals(minusBounds, minus.getUnclippedBoundsInRoot())
        var changed = 0
        for (y in 0 until resting.height step 4) for (x in 0 until resting.width step 4) {
            if (resting[x, y] != compressed[x, y]) changed += 1
        }
        assertTrue("The lens should visibly compress or shade under a held finger", changed > 30)
        add.performTouchInput { cancel() }
        composeRule.mainClock.advanceTimeBy(1_000)
    }

    @Test fun minusAndAccessibleAddKeepSeparateActionsWithReducedMotion() {
        var adds = 0
        var removes = 0
        composeRule.setContent {
            MaterialTheme {
                FoodCounterRow(FoodColor.GREEN, 2, false, SkinId.SHRINE, false,
                    onIncrement = { adds += 1 }, onDecrement = { removes += 1 })
            }
        }
        composeRule.onNodeWithTag("remove-green").performClick()
        composeRule.onNodeWithTag("add-green").performClick()
        composeRule.runOnIdle {
            assertEquals(1, adds)
            assertEquals(1, removes)
        }
    }
}
