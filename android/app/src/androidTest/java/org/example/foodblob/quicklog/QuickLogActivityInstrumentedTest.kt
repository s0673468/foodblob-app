package org.example.foodblob.quicklog

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.widget.Button
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.example.foodblob.FoodBlobApplication
import org.example.foodblob.R
import org.example.foodblob.domain.FoodColor
import org.example.foodblob.storage.FoodBlobServices
import kotlinx.coroutines.runBlocking
import java.io.FileInputStream
import java.io.File
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class QuickLogActivityInstrumentedTest {
    @Test
    fun openingAValidShortcutRequiresConfirmationAndDoesNotMutate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = context.applicationContext as FoodBlobApplication
        assertTrue(application.startupSucceeded.await())
        val store = FoodBlobServices.get(context).store
        val before = store.effectiveSnapshot()
        val intent = Intent(context, QuickLogActivity::class.java)
            .setAction(QuickLogContract.ACTION_LOG)
            .putExtra(QuickLogContract.EXTRA_COLOR, FoodColor.GREEN.storageId)

        ActivityScenario.launch<QuickLogActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val action = activity.findViewById<Button>(R.id.quick_log_primary_action)
                assertTrue(action.isShown)
                assertTrue(action.isEnabled)
                assertEquals(activity.getString(R.string.quick_log_confirm_green), action.text.toString())
            }
        }

        assertEquals(before, store.effectiveSnapshot())
    }

    @Test
    fun confirmationActionRemainsReachableInLandscapeWithLargeText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalFontScale = shell("settings get system font_scale")
        val originalAutoRotate = shell("settings get system accelerometer_rotation")
        val originalRotation = shell("settings get system user_rotation")
        // Settings values alone do not prove the display's actual frozen state.
        val originalWindowRotation = shell("cmd window user-rotation")
        captureRotationDiagnostics("landscape-original")
        val restoreRotation = when {
            originalWindowRotation == "free" -> UiAutomation.ROTATION_UNFREEZE
            originalWindowRotation.matches(Regex("lock [0-3]")) -> originalWindowRotation.last().digitToInt()
            else -> error("Cannot safely restore original display rotation: $originalWindowRotation")
        }
        try {
            shell("settings put system font_scale 2.0")
            assertTrue(
                "The owned device must accept the explicit landscape rotation freeze",
                instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90),
            )
            captureRotationDiagnostics("landscape-requested")
            instrumentation.waitForIdleSync()

            val context = ApplicationProvider.getApplicationContext<Context>()
            val intent = Intent(context, QuickLogActivity::class.java)
                .setAction(QuickLogContract.ACTION_LOG)
                .putExtra(QuickLogContract.EXTRA_COLOR, FoodColor.GREEN.storageId)
            ActivityScenario.launch<QuickLogActivity>(intent).use { scenario ->
                waitForLandscapeWithLargeText(scenario)
                scenario.onActivity { activity ->
                    activity.findViewById<ScrollView>(R.id.quick_log_scroll).fullScroll(ScrollView.FOCUS_DOWN)
                }
                instrumentation.waitForIdleSync()
                captureFixture("landscape-large-text-action")
                scenario.onActivity { activity ->
                    assertTrue(activity.resources.configuration.fontScale >= 1.9f)
                    assertEquals(
                        Configuration.ORIENTATION_LANDSCAPE,
                        activity.resources.configuration.orientation,
                    )
                    val action = activity.findViewById<Button>(R.id.quick_log_primary_action)
                    val actionBounds = Rect()
                    val rootBounds = Rect()
                    assertTrue(action.getGlobalVisibleRect(actionBounds))
                    assertTrue(activity.window.decorView.getGlobalVisibleRect(rootBounds))
                    assertTrue(rootBounds.contains(actionBounds))
                    assertTrue(action.isEnabled)
                }
            }
        } finally {
            var restoreFailure: Throwable? = null
            val restoreActions = listOf<() -> Unit>(
                { restoreSetting("font_scale", originalFontScale) },
                { restoreSetting("accelerometer_rotation", originalAutoRotate) },
                { restoreSetting("user_rotation", originalRotation) },
                { assertTrue("Restore the owned display's original rotation mode",
                    instrumentation.uiAutomation.setRotation(restoreRotation)) },
                { instrumentation.waitForIdleSync() },
                { captureRotationDiagnostics("landscape-restored") },
            )
            for (restore in restoreActions) {
                try { restore() } catch (failure: Throwable) {
                    val previous = restoreFailure
                    if (previous == null) restoreFailure = failure else previous.addSuppressed(failure)
                }
            }
            restoreFailure?.let { throw it }
        }
    }

    private fun waitForLandscapeWithLargeText(scenario: ActivityScenario<QuickLogActivity>) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var ready = false
        var actualConfiguration = "Activity not observed"
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val configuration = activity.resources.configuration
                actualConfiguration = "orientation=${configuration.orientation};fontScale=${configuration.fontScale};" +
                    "widthDp=${configuration.screenWidthDp};heightDp=${configuration.screenHeightDp};" +
                    "displayRotation=${activity.display?.rotation};requestedOrientation=${activity.requestedOrientation}"
                ready = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    configuration.fontScale >= 1.9f
            }
            if (!ready) SystemClock.sleep(50)
        }
        captureRotationDiagnostics("landscape-configuration-ready-$ready", actualConfiguration)
        captureFixture("landscape-configuration-ready-$ready")
        assertTrue("The owned device must apply landscape and large text before checking reachability", ready)
    }

    private fun captureRotationDiagnostics(name: String, activityConfiguration: String? = null) {
        if (InstrumentationRegistry.getArguments().getString("captureInteractionStates") != "true") return
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = context.resources.configuration
        val directory = File(context.getExternalFilesDir(null), "acceptance-path-captures").apply { mkdirs() }
        val diagnostics = JSONObject()
            .put("uptimeMillis", SystemClock.uptimeMillis())
            .put("windowUserRotation", shell("cmd window user-rotation"))
            .put("fontScaleSetting", shell("settings get system font_scale"))
            .put("autoRotateSetting", shell("settings get system accelerometer_rotation"))
            .put("userRotationSetting", shell("settings get system user_rotation"))
            .put("applicationOrientation", configuration.orientation)
            .put("applicationFontScale", configuration.fontScale)
            .put("activityConfiguration", activityConfiguration ?: JSONObject.NULL)
        File(directory, "$name.json").writeText(diagnostics.toString(2))
    }

    private fun captureFixture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureInteractionStates") != "true") return
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "acceptance-path-captures").apply { mkdirs() }
        val image = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }

    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation()
        .uiAutomation
        .executeShellCommand(command)
        .use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText().trim() }
        }

    private fun restoreSetting(name: String, value: String) {
        if (value.isBlank() || value == "null") {
            shell("settings delete system $name")
        } else {
            shell("settings put system $name $value")
        }
    }
}
