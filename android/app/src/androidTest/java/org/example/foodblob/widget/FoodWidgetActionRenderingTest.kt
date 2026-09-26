package org.example.foodblob.widget

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.example.foodblob.FoodBlobApplication
import org.example.foodblob.R
import org.example.foodblob.domain.FoodColor
import org.example.foodblob.domain.FoodCounts
import org.example.foodblob.storage.FoodBlobServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class FoodWidgetActionRenderingTest {
    @Test
    fun actualWidgetCompositionReadsMaterialPreferenceWithoutChangingFood() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue((context.applicationContext as FoodBlobApplication).startupSucceeded.await())
        val prefs = context.getSharedPreferences(org.example.foodblob.ui.BlobAppearance.PREFERENCES, 0)
        val key = org.example.foodblob.ui.BlobAppearance.KEY
        val original = prefs.all[key]
        val store = FoodBlobServices.get(context).store
        val before = store.effectiveSnapshot()
        val directory = java.io.File(context.getExternalFilesDir(null), "paint-widget-composition").apply { mkdirs() }
        try {
            for (widget in listOf(SkyMeadowSmallWidget(), ShrineMediumWidget())) {
                var previous: android.graphics.Bitmap? = null
                for ((name, level) in listOf("paint" to 0f, "half" to .5f, "glass" to 1f)) {
                    check(prefs.edit().putFloat(key, level).commit())
                    val width = if (widget is SkyMeadowSmallWidget) 168f else 338f
                    val root = inflate(widget, context, width, 168f)
                    val image = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                    onMain { root.draw(android.graphics.Canvas(image)) }
                    previous?.let { assertTrue("A saved material change must redraw the actual widget", !it.sameAs(image)); it.recycle() }
                    java.io.File(directory, "${widget.javaClass.simpleName}-$name.png").outputStream().use {
                        check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                    previous = image
                    assertEquals(before.revision, store.effectiveSnapshot().revision)
                }
                previous?.recycle()
            }
        } finally {
            val editor = prefs.edit()
            if (original is Float) editor.putFloat(key, original) else editor.remove(key)
            check(editor.commit())
        }
    }

    /**
     * Opt in only on an isolated emulator: this executes real widget PendingIntents
     * against its fixture database. Counts are balanced after each add, but the
     * authoritative ledger deliberately retains its normal action receipts.
     */
    @Test
    fun actualRemoteViewsTargetsAddAndRemoveThroughTheRegisteredReceiver() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolatedWidgetAcceptance") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue((context.applicationContext as FoodBlobApplication).startupSucceeded.await())
        val store = FoodBlobServices.get(context).store
        store.ensureInitialized()
        val dateKey = store.currentDateKey()
        val initialCounts = store.effectiveSnapshot().counts(dateKey)
        assumeTrue(initialCounts.total < 100_000)
        val fixtures = listOf(
            Triple(SkyMeadowSmallWidget(), 203f, 102f),
            Triple(SkyMeadowSmallWidget(), 168f, 156f),
            Triple(SkyMeadowSmallWidget(), 152f, 156f),
            Triple(ShrineSmallWidget(), 203f, 102f),
            Triple(ShrineSmallWidget(), 168f, 156f),
            Triple(SkyMeadowMediumWidget(), 250f, 108f),
            Triple(SkyMeadowMediumWidget(), 338f, 168f),
            Triple(ShrineMediumWidget(), 250f, 108f),
            Triple(ShrineMediumWidget(), 338f, 168f),
            Triple(SkyMeadowMediumWidget(), 338f, 152f),
            Triple(ShrineMediumWidget(), 420f, 220f),
        )
        for ((widget, width, height) in fixtures) {
            val presentation = if (widget is SkyMeadowSmallWidget || widget is ShrineSmallWidget) {
                WidgetPresentation.SMALL
            } else WidgetPresentation.MEDIUM
            val geometry = FoodWidgetGeometry.resolve(width, height, presentation)
            for (color in FoodColor.entries) {
                assertEquals("Do not cross a day rollover during this fixture test", dateKey, store.currentDateKey())
                val before = store.effectiveSnapshot().counts(dateKey)
                val root = inflate(widget, context, width, height)
                assertActionBounds(root, geometry, context, before)
                val add = actionView(root, context, color, 1, before)
                onMain { assertTrue(add.performClick()) }
                awaitCounts(context, dateKey, before.applyDelta(color, 1))

                if (presentation == WidgetPresentation.MEDIUM) {
                    val afterAdd = before.applyDelta(color, 1)
                    val updated = inflate(widget, context, width, height)
                    assertActionBounds(updated, geometry, context, afterAdd)
                    val remove = actionView(updated, context, color, -1, afterAdd)
                    onMain { assertTrue(remove.performClick()) }
                } else {
                    // Add-only widgets intentionally have no decrement control.
                    store.decrement(color)
                }
                awaitCounts(context, dateKey, before)
            }
        }
        assertEquals(initialCounts, store.effectiveSnapshot().counts(dateKey))
    }

    private suspend fun inflate(widget: GlanceAppWidget, context: Context, width: Float, height: Float): ViewGroup {
        val remoteViews = widget.compose(context, size = DpSize(width.dp, height.dp))
        lateinit var root: ViewGroup
        onMain {
            root = remoteViews.apply(context, FrameLayout(context)) as ViewGroup
            val density = context.resources.displayMetrics.density
            val pixelWidth = (width * density).toInt()
            val pixelHeight = (height * density).toInt()
            root.measure(
                View.MeasureSpec.makeMeasureSpec(pixelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(pixelHeight, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, pixelWidth, pixelHeight)
        }
        return root
    }

    private fun assertActionBounds(
        root: ViewGroup,
        geometry: ResolvedWidgetGeometry,
        context: Context,
        counts: FoodCounts,
    ) = onMain {
        val density = context.resources.displayMetrics.density
        for (target in geometry.actionTargets) {
            val view = actionView(root, context, target.color, target.delta, counts)
            assertEquals(FoodWidgetContract.isActionEnabled(counts, target.color, target.delta), view.isClickable)
            val actual = Rect()
            view.getDrawingRect(actual)
            root.offsetDescendantRectToMyCoords(view, actual)
            val expected = target.rect
            // Glance rounds each dp spacer separately when serializing RemoteViews.
            assertEquals(expected.leftDp * density, actual.left.toFloat(), 3f)
            assertEquals(expected.topDp * density, actual.top.toFloat(), 3f)
            assertEquals(expected.rightDp * density, actual.right.toFloat(), 3f)
            assertEquals(expected.bottomDp * density, actual.bottom.toFloat(), 3f)
        }
    }

    private fun actionView(root: ViewGroup, context: Context, color: FoodColor, delta: Int, counts: FoodCounts): View {
        val label = when (color to delta) {
            FoodColor.GREEN to 1 -> R.string.green_add
            FoodColor.GREEN to -1 -> R.string.green_remove
            FoodColor.YELLOW to 1 -> R.string.yellow_add
            FoodColor.YELLOW to -1 -> R.string.yellow_remove
            FoodColor.RED to 1 -> R.string.red_add
            else -> R.string.red_remove
        }
        val description = context.resources.getQuantityString(
            R.plurals.widget_action_count_description, counts.total, context.getString(label), counts.total,
        )
        val matches = descendants(root).filter { it.contentDescription?.toString() == description }
        assertEquals("Expected one native action view for $description", 1, matches.size)
        val labelled = matches.single()
        val labelledBounds = boundsInRoot(root, labelled)
        // Glance 1.1.1's transformBackgroundImageAndActionRipple moves the
        // ActionModifier onto a wrapper while retaining semantics on its child.
        // Require a real clickable view in that exact rectangle, rather than
        // assuming the labelled child itself owns the PendingIntent listener.
        val chain = generateSequence(labelled) { it.parent as? View }
            .takeWhile { it !== root && boundsInRoot(root, it) == labelledBounds }
            .toList()
        val clickable = chain.filter { it.isClickable }
        val enabled = FoodWidgetContract.isActionEnabled(counts, color, delta)
        assertEquals(
            "Native action ownership for $description: " + chain.joinToString { "${it.javaClass.simpleName}(clickable=${it.isClickable})" },
            if (enabled) 1 else 0,
            clickable.size,
        )
        return if (enabled) clickable.single() else labelled
    }

    private fun boundsInRoot(root: ViewGroup, view: View): Rect = Rect().also {
        view.getDrawingRect(it)
        root.offsetDescendantRectToMyCoords(view, it)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private suspend fun awaitCounts(context: Context, dateKey: String, expected: FoodCounts) {
        withTimeout(10_000) {
            while (FoodBlobServices.get(context).store.effectiveSnapshot().counts(dateKey) != expected) delay(25)
        }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
