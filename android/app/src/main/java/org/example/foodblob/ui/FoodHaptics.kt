package org.example.foodblob.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/** View feedback respects the user's system setting and needs no vibration permission. */
internal fun View.performHaptic(cue: HapticCue) {
    performHapticFeedback(hapticFeedbackConstant(cue))
}

/** A soft landing or playful poke; callers coalesce rapid contacts before reaching the view. */
internal fun View.performBlobContactHaptic() {
    performHapticFeedback(blobContactHapticConstant())
}

internal fun blobContactHapticConstant(sdkInt: Int = Build.VERSION.SDK_INT): Int =
    if (sdkInt >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK

internal fun hapticFeedbackConstant(cue: HapticCue, sdkInt: Int = Build.VERSION.SDK_INT): Int = when (cue) {
    HapticCue.ADD -> if (sdkInt >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    HapticCue.REMOVE -> HapticFeedbackConstants.LONG_PRESS
    HapticCue.UNDO -> if (sdkInt >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.VIRTUAL_KEY
    HapticCue.SUCCESS -> if (sdkInt >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    HapticCue.ERROR -> if (sdkInt >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
}
