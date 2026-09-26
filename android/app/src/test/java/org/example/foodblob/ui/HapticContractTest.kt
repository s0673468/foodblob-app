package org.example.foodblob.ui

import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class HapticContractTest {
    @Test
    fun `add and remove use distinct light and medium platform cues`() {
        assertEquals(
            HapticFeedbackConstants.CLOCK_TICK,
            hapticFeedbackConstant(HapticCue.ADD, sdkInt = 30),
        )
        assertEquals(
            HapticFeedbackConstants.LONG_PRESS,
            hapticFeedbackConstant(HapticCue.REMOVE, sdkInt = 30),
        )
    }

    @Test
    fun `Android 14 uses lighter segmented cues for accepted add and contact`() {
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, hapticFeedbackConstant(HapticCue.ADD, sdkInt = 34))
        assertEquals(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK, blobContactHapticConstant(sdkInt = 34))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, hapticFeedbackConstant(HapticCue.REMOVE, sdkInt = 34))
    }

    @Test
    fun `contact has a supported light fallback before Android 14`() {
        listOf(26, 29, 30, 33).forEach { sdk ->
            assertEquals(HapticFeedbackConstants.CLOCK_TICK, blobContactHapticConstant(sdk))
            assertEquals(HapticFeedbackConstants.CLOCK_TICK, hapticFeedbackConstant(HapticCue.ADD, sdk))
        }
    }

    @Test
    fun `modern Android uses distinct semantic cues`() {
        assertEquals(
            HapticFeedbackConstants.GESTURE_END,
            hapticFeedbackConstant(HapticCue.UNDO, sdkInt = 30),
        )
        assertEquals(
            HapticFeedbackConstants.CONFIRM,
            hapticFeedbackConstant(HapticCue.SUCCESS, sdkInt = 30),
        )
        assertEquals(
            HapticFeedbackConstants.REJECT,
            hapticFeedbackConstant(HapticCue.ERROR, sdkInt = 30),
        )
    }

    @Test
    fun `older Android falls back without losing add-remove distinction`() {
        assertEquals(
            HapticFeedbackConstants.VIRTUAL_KEY,
            hapticFeedbackConstant(HapticCue.UNDO, sdkInt = 29),
        )
        assertEquals(
            HapticFeedbackConstants.VIRTUAL_KEY,
            hapticFeedbackConstant(HapticCue.SUCCESS, sdkInt = 29),
        )
        assertEquals(
            HapticFeedbackConstants.LONG_PRESS,
            hapticFeedbackConstant(HapticCue.ERROR, sdkInt = 29),
        )
    }
}
