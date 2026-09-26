package org.example.foodblob.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.example.foodblob.R
import org.example.foodblob.domain.FoodColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodWidgetPressFeedbackTest {
    @Test
    fun launcherPressIsVisibleImmediatelyAndCancelLeavesNoFalseConfirmation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Inspect the real drawable layer used by RemoteViews. Its held state
            // must work before the first ripple frame, including zero animator scale.
            val resources = FoodColor.entries.flatMap { color ->
                listOf(widgetPressRipple(color, 1), widgetPressRipple(color, -1))
            }.toSet() + R.drawable.widget_control_ripple
            for (resource in resources) {
                val ripple = context.getDrawable(resource) as RippleDrawable
                val hold = ripple.findDrawableByLayerId(android.R.id.background)
                assertNotNull("A press needs an immediate state layer as well as an animated ripple", hold)
                requireNotNull(hold).setBounds(0, 0, 132, 132)
                assertEquals(0, Color.alpha(centerPixel(hold)))
                hold.state = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed)
                assertTrue("Held press must be visible without advancing an animation clock", Color.alpha(centerPixel(hold)) > 0)
                hold.state = intArrayOf(android.R.attr.state_enabled)
                assertEquals("Cancel/release must not suggest a committed meal", 0, Color.alpha(centerPixel(hold)))
                hold.state = intArrayOf(android.R.attr.state_pressed)
                assertEquals("Disabled controls must not light up", 0, Color.alpha(centerPixel(hold)))
            }
        }
    }

    private fun centerPixel(drawable: android.graphics.drawable.Drawable): Int {
        val bitmap = Bitmap.createBitmap(132, 132, Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bitmap))
        return bitmap.getPixel(66, 66).also { bitmap.recycle() }
    }
}
