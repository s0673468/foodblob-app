package org.example.foodblob.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LiquidControlInteractionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun cancelledPressDoesNotPerformACommandAndTheNextTapPerformsExactlyOnce() {
        var commands = 0
        composeRule.setContent {
            MaterialTheme {
                LiquidIconButton({ commands += 1 }, Modifier.size(48.dp).testTag("liquid-control"), haptic = false) { Text("+") }
            }
        }
        val control = composeRule.onNodeWithTag("liquid-control")
        control.performTouchInput { down(center); advanceEventTime(100); cancel() }
        composeRule.runOnIdle { assertEquals(0, commands) }
        control.performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(1, commands) }
    }

    @Test fun reducedMotionPreservesTheTouchSurfaceAndAccessibleClick() {
        var commands = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalLiquidMotion provides false) {
                MaterialTheme {
                    LiquidIconButton({ commands += 1 }, Modifier.size(48.dp).testTag("liquid-control"), haptic = false) { Text("+") }
                }
            }
        }
        val control = composeRule.onNodeWithTag("liquid-control")
        val before = control.getUnclippedBoundsInRoot()
        control.performTouchInput { down(center); advanceEventTime(120) }
        composeRule.waitForIdle()
        assertEquals(before, control.getUnclippedBoundsInRoot())
        control.performTouchInput { cancel() }
        control.performClick()
        composeRule.runOnIdle { assertEquals(1, commands) }
    }
}
