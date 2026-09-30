package org.example.foodblob.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.example.foodblob.MainActivity
import org.example.foodblob.domain.FoodCounts
import org.example.foodblob.domain.SkinId
import org.example.foodblob.storage.DayEntity
import org.example.foodblob.storage.FoodBlobServices
import org.example.foodblob.storage.SettingsEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ConnectedBlobInteractionTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()
    private lateinit var services: FoodBlobServices
    private lateinit var date: String
    private var day: DayEntity? = null
    private lateinit var settings: SettingsEntity
    private var undoId = 0L
    private var originalPreferences: Map<String, *> = emptyMap<String, Any>()

    @Before fun preserve() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        services = FoodBlobServices.get(context)
        services.store.ensureInitialized()
        date = services.store.currentDateKey()
        services.database.withTransaction {
            day = services.database.dao().day(date)
            settings = requireNotNull(services.database.dao().settings())
            undoId = services.database.dao().latestUndoId() ?: 0L
        }
        originalPreferences = context.getSharedPreferences("food_blob_ui", 0).all
    }

    @After fun restore() {
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        runBlocking {
            services.database.withTransaction {
                day?.let { services.database.dao().upsertDay(it) } ?: services.database.dao().deleteDay(date)
                services.database.dao().deleteUndoAfter(undoId)
                services.database.dao().upsertSettings(settings)
            }
        }
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("food_blob_ui", 0)
        val editor = prefs.edit()
        listOf("onboarding_complete", "interaction_sound").forEach { key ->
            val original = originalPreferences[key]
            if (original is Boolean) editor.putBoolean(key, original) else editor.remove(key)
        }
        val material = originalPreferences[BlobAppearance.KEY]
        if (material is Float) editor.putFloat(BlobAppearance.KEY, material) else editor.remove(BlobAppearance.KEY)
        check(editor.commit())
    }

    @Test fun materialSliderPersistsAndPokeAndCancelledSliderNeverWriteFood() {
        ready()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(BlobAppearance.PREFERENCES, 0)
        check(prefs.edit().remove(BlobAppearance.KEY).commit())
        runBlocking {
            services.database.dao().upsertDay(DayEntity(date, 4, 2, 1, System.currentTimeMillis()))
            withTimeout(5_000) { services.store.snapshots.first { it.counts(date) == FoodCounts(4, 2, 1) } }
        }
        val expectedDay = runBlocking { services.database.dao().day(date) }
        val expectedUndo = runBlocking { services.database.dao().latestUndoId() }
        val directory = File(context.getExternalFilesDir(null), "paint-app-captures").apply { mkdirs() }
        fun capture(name: String) {
            composeRule.waitForIdle()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(200)
            val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(directory, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            image.recycle()
        }
        for (skin in SkinId.entries) {
            runBlocking {
                services.database.dao().upsertSettings(settings.copy(selectedSkin = skin.storageId))
                withTimeout(5_000) { services.store.snapshots.first { it.selectedSkin == skin } }
            }
            for ((name, level) in listOf("paint" to 0f, "half" to .5f, "glass" to 1f)) {
                composeRule.onNodeWithTag("settings-tab").performClick()
                composeRule.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("blob-translucency"))
                composeRule.onNodeWithTag("blob-translucency").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(level) }
                composeRule.waitUntil(5_000) { BlobAppearance.read(prefs) == level }
                composeRule.onNodeWithTag("blob-appearance").performScrollTo()
                capture("${skin.storageId}-settings-$name")
                composeRule.onNodeWithTag("today-tab").performClick()
                visibleTodayNode("playful-blob")
                capture("${skin.storageId}-today-$name")
                if (level == 0f) {
                    composeRule.mainClock.autoAdvance = false
                    try {
                        composeRule.onNodeWithTag("playful-blob").performTouchInput { down(center) }
                        composeRule.mainClock.advanceTimeBy(180)
                        capture("${skin.storageId}-paint-held")
                        composeRule.onNodeWithTag("playful-blob").performTouchInput { up() }
                        composeRule.mainClock.advanceTimeBy(100)
                        capture("${skin.storageId}-paint-rebound")
                        composeRule.mainClock.advanceTimeBy(900)
                    } finally { composeRule.mainClock.autoAdvance = true }
                }
            }
        }
        composeRule.onNodeWithTag("settings-tab").performClick()
        composeRule.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("blob-translucency"))
        composeRule.onNodeWithTag("blob-translucency").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { check(it(.5f)) }
        composeRule.waitUntil(5_000) { BlobAppearance.read(prefs) == .5f }
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntilExactlyOneExists(hasTestTag("settings-screen"), 5_000)
        composeRule.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("blob-translucency"))
        composeRule.onNodeWithTag("blob-translucency").performScrollTo()
            .assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(.5f, 0f..1f, 19))
        composeRule.onNodeWithTag("blob-appearance").performScrollTo()
        capture("slider-recreated-half")
        composeRule.onNodeWithTag("blob-translucency").performTouchInput { down(center); moveTo(centerRight); cancel() }
        composeRule.onNodeWithTag("today-tab").performClick()
        visibleTodayNode("playful-blob").performTouchInput { down(center); cancel() }
        composeRule.waitForIdle()
        assertEquals(expectedDay, runBlocking { services.database.dao().day(date) })
        assertEquals(expectedUndo, runBlocking { services.database.dao().latestUndoId() })
        val zero = DayEntity(date, 0, 0, 0, System.currentTimeMillis())
        runBlocking {
            services.database.dao().upsertDay(zero)
            withTimeout(5_000) { services.store.snapshots.first { it.counts(date).isEmpty } }
        }
        composeRule.onNodeWithTag("settings-tab").performClick()
        composeRule.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("blob-translucency"))
        composeRule.onNodeWithTag("blob-translucency").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        composeRule.waitUntil(5_000) { BlobAppearance.read(prefs) == 0f }
        for (skin in SkinId.entries) {
            runBlocking {
                services.database.dao().upsertSettings(settings.copy(selectedSkin = skin.storageId))
                withTimeout(5_000) { services.store.snapshots.first { it.selectedSkin == skin } }
            }
            composeRule.onNodeWithTag("blob-appearance").performScrollTo()
            composeRule.onNodeWithTag("playful-blob").performClick()
            capture("${skin.storageId}-settings-zero-paint")
        }
        assertEquals(zero, runBlocking { services.database.dao().day(date) })
        assertEquals(expectedUndo, runBlocking { services.database.dao().latestUndoId() })
    }

    @Test fun rapidAcceptedTapsPersistAndControlsStayReachable() {
        ready()
        val before = runBlocking { services.store.snapshots.first().counts(date) }
        val add = requireNotNull(composeRule.onNodeWithTag("add-green").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        // Nine real accessibility click actions in one UI turn, without waiting for animation idle.
        composeRule.runOnUiThread { repeat(9) { check(add()) } }
        composeRule.waitUntil(10_000) {
            runBlocking { services.store.snapshots.first().counts(date).green == before.green + 9 }
        }
        composeRule.onNodeWithTag("blob-total").assertTextEquals("${before.total + 9}")
        listOf("add-green", "add-yellow", "add-red", "undo").forEach {
            visibleTodayNode(it).assertHeightIsAtLeast(48.dp)
        }
        composeRule.onNodeWithTag("undo").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { services.store.snapshots.first().counts(date).green == before.green + 8 }
        }
        composeRule.onNodeWithTag("blob-total").assertTextEquals("${before.total + 8}")
    }

    @Test fun accessiblePokeAndStretchNeverWriteFoodOrChangeDay() {
        ready()
        val before = runBlocking { services.store.snapshots.first().counts(date) }
        val previousUndo = runBlocking { services.database.dao().latestUndoId() }
        composeRule.onNodeWithTag("playful-blob").performClick()
        composeRule.onNodeWithTag("playful-blob").performTouchInput {
            swipe(center, center + androidx.compose.ui.geometry.Offset(width * .22f, -height * .10f), 180)
        }
        composeRule.waitForIdle()
        assertEquals(before, runBlocking { services.store.snapshots.first().counts(date) })
        assertEquals(previousUndo, runBlocking { services.database.dao().latestUndoId() })
        visibleTodayNode("today-title")
    }

    @Test fun heldColorDragOnlyLogsOnValidDropAndUndoRestoresIt() {
        ready()
        val initial=FoodCounts(3,2,1)
        runBlocking {
            services.database.dao().upsertDay(DayEntity(date,3,2,1,System.currentTimeMillis()))
            withTimeout(5000) { services.store.snapshots.first{it.counts(date)==initial} }
        }
        visibleTodayNode("today-title")
        composeRule.waitForIdle()
        fun counts()=runBlocking {services.store.snapshots.first().counts(date)}
        val beforeUndo=runBlocking{services.database.dao().latestUndoId()}
        val source=composeRule.onNodeWithTag("add-green").fetchSemanticsNode().boundsInRoot
        val target=composeRule.onNodeWithTag("playful-blob").fetchSemanticsNode().boundsInRoot.center
        // Up without moving cancels a lifted colour, even though it began on Add.
        composeRule.onRoot().performTouchInput { down(source.center);advanceEventTime(650);up() }
        composeRule.waitForIdle()
        assertEquals(initial,counts())
        composeRule.onRoot().performTouchInput {
            down(source.center);advanceEventTime(650)
            moveTo(source.center+androidx.compose.ui.geometry.Offset(-100f,-50f),100);cancel()
        }
        composeRule.waitForIdle()
        assertEquals(initial,counts());assertEquals(beforeUndo,runBlocking{services.database.dao().latestUndoId()})
        composeRule.onRoot().performTouchInput {
            down(source.center);advanceEventTime(650)
            repeat(12) {index->moveTo(source.center+(target-source.center)*((index+1)/12f),24)}
            up()
        }
        composeRule.waitUntil(5000){counts()==FoodCounts(4,2,1)}
        composeRule.onNodeWithTag("undo").performClick()
        composeRule.waitUntil(5000){counts()==initial}
        // The next ordinary tap still belongs to the source row.
        composeRule.onNodeWithTag("add-green").performClick()
        composeRule.waitUntil(5000){counts()==FoodCounts(4,2,1)}
    }

    @Test fun reducedMotionAcceptedSoundUsesCurrentScopeWithoutStartingPhysics() {
        val feedback = ConnectedBlobFeedback()
        var sounds = 0
        feedback.playAcceptedSound = { sounds += 1 }
        val token = feedback.generation
        val origin = org.example.foodblob.domain.BlobPoint(0.0, 0.0)
        val counts = org.example.foodblob.domain.FoodCounts(green = 1)
        feedback.accept(token, org.example.foodblob.domain.FoodColor.GREEN, counts, origin, motionAllowed = false)
        assertEquals(1, sounds)
        assertEquals(0, feedback.drops.size)
        feedback.interrupt()
        feedback.accept(token, org.example.foodblob.domain.FoodColor.GREEN, counts, origin, motionAllowed = false)
        assertEquals(1, sounds)
        feedback.available = false
        feedback.accept(feedback.generation, org.example.foodblob.domain.FoodColor.GREEN, counts, origin, motionAllowed = false)
        assertEquals(1, sounds)
    }

    @Test fun soundIsOptionalAndItsPreferenceSurvivesScreenNavigation() {
        ready()
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("food_blob_ui", 0)
        check(prefs.edit().remove("interaction_sound").commit())
        composeRule.onNodeWithTag("settings-tab").performClick()
        composeRule.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("interaction-sound"))
        composeRule.onNodeWithTag("interaction-sound").assertIsOff().performClick().assertIsOn()
        composeRule.onNodeWithTag("today-tab").performClick()
        composeRule.onNodeWithTag("settings-tab").performClick()
        composeRule.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("interaction-sound"))
        composeRule.onNodeWithTag("interaction-sound").assertIsOn()
    }

    @Test fun skinChoicesExposeSelectionAndSoundHasOneFullRowTarget() {
        ready()
        composeRule.onNodeWithTag("skins-tab").performClick()
        for (skin in SkinId.entries) {
            composeRule.onNodeWithTag("skins-screen").performScrollToNode(hasTestTag("skin-${skin.storageId}"))
            composeRule.onNodeWithTag("skin-${skin.storageId}").performClick()
            runBlocking { withTimeout(5_000) { services.store.snapshots.first { it.selectedSkin == skin } } }
            composeRule.onNodeWithTag("skin-${skin.storageId}").assertIsSelected()
        }
        composeRule.onNodeWithTag("settings-tab").performClick()
        composeRule.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("interaction-sound"))
        val row = composeRule.onNodeWithTag("interaction-sound")
        row.assertHeightIsAtLeast(78.dp)
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("food_blob_ui",0)
        val before = prefs.getBoolean("interaction_sound",false)
        row.performClick()
        assertEquals(!before,prefs.getBoolean("interaction_sound",false))
        row.performClick()
        assertEquals(before,prefs.getBoolean("interaction_sound",false))
    }

    /** Opt-in menu evidence from the real app, with the same isolated fixture lifecycle. */
    @Test fun capturePolishedMenus() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("capturePolishedMenus") == "true")
        ready()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null),"menu-captures")
        check(directory.isDirectory || directory.mkdirs())
        fun capture(name: String) {
            composeRule.waitForIdle()
            val image = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(directory,name).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
            image.recycle()
        }
        for (skin in SkinId.entries) {
            runBlocking { services.store.setSkin(skin) }
            composeRule.onNodeWithTag("history-tab").performClick()
            scrollHistoryDayIntoView(date)
            composeRule.onNodeWithTag("history-day-$date").assertIsDisplayed().performClick()
            composeRule.onNodeWithTag("detail-back").performClick()
            capture("${skin.storageId}-history.png")
            composeRule.onNodeWithTag("skins-tab").performClick()
            composeRule.onNodeWithTag("skins-screen").performScrollToNode(hasTestTag("skin-${skin.storageId}"))
            composeRule.onNodeWithTag("skin-${skin.storageId}").assertIsSelected()
            capture("${skin.storageId}-skins.png")
            composeRule.onNodeWithTag("settings-tab").performClick()
            composeRule.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("settings-title"))
            composeRule.onNodeWithTag("settings-title").assertIsDisplayed()
            capture("${skin.storageId}-settings.png")
            composeRule.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("interaction-sound"))
            composeRule.onNodeWithTag("interaction-sound").assertIsDisplayed().assertHeightIsAtLeast(78.dp)
            capture("${skin.storageId}-settings-sound.png")
        }
    }

    /** Native Today captures; the existing hooks restore this date and settings. */
    @Test fun captureNativeInteractionStates() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureInteractionStates") == "true")
        ready()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "interaction-captures")
        check(directory.isDirectory || directory.mkdirs())
        val fixtures = if (InstrumentationRegistry.getArguments().getString("captureGrowthStates") == "true") listOf(
            "empty" to FoodCounts(),
            "one" to FoodCounts(green = 1),
            "two" to FoodCounts(green = 2),
            "three" to FoodCounts(green = 3),
            "ten" to FoodCounts(green = 10),
            "twenty" to FoodCounts(green = 12, yellow = 5, red = 3),
            "twenty-five" to FoodCounts(green = 15, yellow = 7, red = 3),
            "fifty" to FoodCounts(green = 30, yellow = 14, red = 6),
            "red-heavy" to FoodCounts(red = 8),
            "yellow-heavy" to FoodCounts(yellow = 8),
        ) else listOf(
            "empty" to FoodCounts(),
            "green-only" to FoodCounts(green = 1),
            "mixed" to FoodCounts(green = 3, yellow = 2, red = 1),
            "grown" to FoodCounts(green = 12, yellow = 4, red = 2),
        )
        for (skin in SkinId.entries) for ((name, counts) in fixtures) {
            assertEquals("Do not cross a day rollover during fixture capture", date, services.store.currentDateKey())
            runBlocking {
                services.database.withTransaction {
                    services.database.dao().upsertDay(
                        DayEntity(date, counts.green, counts.yellow, counts.red, System.currentTimeMillis()),
                    )
                    services.database.dao().upsertSettings(settings.copy(selectedSkin = skin.storageId))
                }
                withTimeout(5_000) {
                    services.store.snapshots.first { it.counts(date) == counts && it.selectedSkin == skin }
                }
            }
            composeRule.waitForIdle()
            listOf("add-green", "add-yellow", "add-red", "remove-green", "remove-yellow", "remove-red").forEach { tag ->
                visibleTodayNode(tag).assertHeightIsAtLeast(48.dp)
            }
            // Reveal through the current adaptive layout: a phone LazyColumn
            // and a wide display's counter scroll panel are different paths.
            visibleTodayNode("today-title")
            composeRule.onNodeWithTag("blob-total").assertTextEquals(counts.total.toString())
            composeRule.waitForIdle()
            // Capture the real composed display after state/layout assertions.
            // Compose captureToImage forces a window redraw with a fixed 2s
            // timeout, which can expire on a loaded emulator even when idle.
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
                "The Android display could not be captured"
            }
            File(directory, "android-${skin.storageId}-$name.png").outputStream().use {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            image.recycle()
        }
    }

    /** Opt-in real-time recording on the isolated acceptance emulator. @After restores data. */
    @Test fun captureJellyTouchAndColourSequence() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureJellyMotion") == "true")
        ready()
        val initial = FoodCounts(green = 2, yellow = 1)
        runBlocking {
            services.database.withTransaction {
                services.database.dao().upsertDay(DayEntity(date,2,1,0,System.currentTimeMillis()))
                services.database.dao().upsertSettings(settings.copy(selectedSkin = SkinId.SKY_MEADOW.storageId))
            }
            withTimeout(5_000) { services.store.snapshots.first { it.counts(date) == initial && it.selectedSkin == SkinId.SKY_MEADOW } }
        }
        visibleTodayNode("today-title")
        composeRule.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null),"jelly-motion-captures")
        check(directory.isDirectory || directory.mkdirs())
        composeRule.mainClock.autoAdvance = false
        fun pause(ms: Long) {
            var remaining = ms
            while (remaining > 0) {
                val step = minOf(16L,remaining)
                composeRule.mainClock.advanceTimeBy(step)
                android.os.SystemClock.sleep(step)
                remaining -= step
            }
        }
        fun capture(name: String) {
            val image = checkNotNull(automation.takeScreenshot())
            File(directory,"$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
            image.recycle()
        }
        fun event(action: Int, point: androidx.compose.ui.geometry.Offset, down: Long) {
            val sample = android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,point.x,point.y,0)
            sample.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { check(automation.injectInputEvent(sample,true)) } finally { sample.recycle() }
        }
        val body = composeRule.onNodeWithTag("playful-blob").fetchSemanticsNode().boundsInWindow
        val origin = androidx.compose.ui.geometry.Offset(body.left+body.width*.37f,body.top+body.height*.42f)
        pause(700); capture("01-rest")
        var down = android.os.SystemClock.uptimeMillis()
        event(android.view.MotionEvent.ACTION_DOWN,origin,down)
        pause(350); capture("02-held")
        val displacement = androidx.compose.ui.geometry.Offset(body.width*.16f,-body.height*.08f)
        repeat(8) { index ->
            event(android.view.MotionEvent.ACTION_MOVE,origin+displacement*((index+1)/8f),down)
            pause(35)
        }
        pause(180); capture("03-stretched")
        event(android.view.MotionEvent.ACTION_UP,origin+displacement,down)
        pause(80); capture("04-released")
        pause(900); capture("05-settled")
        val controls = listOf("green","yellow","red").map { color ->
            composeRule.onNodeWithTag("add-$color").assertIsDisplayed().fetchSemanticsNode().boundsInWindow.center
        }
        repeat(2) { controls.forEach { point ->
            down = android.os.SystemClock.uptimeMillis()
            event(android.view.MotionEvent.ACTION_DOWN,point,down)
            pause(45)
            event(android.view.MotionEvent.ACTION_UP,point,down)
            pause(55)
        } }
        pause(80); capture("06-feeding")
        pause(1_000)
        composeRule.waitUntil(5_000) {
            runBlocking { services.store.snapshots.first().counts(date) == FoodCounts(green=4,yellow=3,red=2) }
        }
        assertEquals(9,runBlocking { services.store.snapshots.first().counts(date).total })
        composeRule.onNodeWithTag("blob-total").assertTextEquals("9")
        capture("07-fed")
        pause(900)
    }

    private fun visibleTodayNode(tag: String): SemanticsNodeInteraction {
        val target = composeRule.onNodeWithTag(tag)
        if (!target.isDisplayed()) {
            val lists = composeRule.onAllNodes(
                hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("today-screen")),
            )
            if (lists.fetchSemanticsNodes().size == 1) {
                lists[0].performScrollToNode(hasTestTag(tag))
            } else {
                // Wide layouts keep the blob visible and scroll only counters.
                target.performScrollTo()
            }
        }
        return target.assertIsDisplayed()
    }

    private fun scrollHistoryDayIntoView(date: String) {
        val day = composeRule.onNodeWithTag("history-day-$date")
        val history = composeRule.onNodeWithTag("history-screen")
        history.performScrollToNode(hasTestTag("history-day-$date"))
        // A calendar is one LazyColumn item: locating its nested day scrolls
        // to the calendar, not necessarily to the last week within that item.
        repeat(4) {
            if (day.isDisplayed()) { captureFixture("history-day-$date"); return }
            history.performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        day.assertIsDisplayed()
    }

    private fun captureFixture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureInteractionStates") != "true") return
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "acceptance-path-captures").apply { mkdirs() }
        val image = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }

    private fun ready() {
        repeat(2) {
            if (composeRule.onAllNodesWithTag("onboarding-next").fetchSemanticsNodes().isNotEmpty()) {
                composeRule.onNodeWithTag("onboarding-next").performClick()
            }
        }
        if (composeRule.onAllNodesWithTag("onboarding-done").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("onboarding-done").performClick()
        }
        composeRule.waitUntilExactlyOneExists(hasTestTag("today-screen"), 5_000)
    }
}
