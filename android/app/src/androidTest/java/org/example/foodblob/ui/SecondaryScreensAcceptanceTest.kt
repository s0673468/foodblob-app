package org.example.foodblob.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.example.foodblob.MainActivity
import org.example.foodblob.R
import org.example.foodblob.domain.SkinId
import org.example.foodblob.storage.DayEntity
import org.example.foodblob.storage.FoodBlobServices
import org.example.foodblob.storage.SettingsEntity
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Opt-in native visual acceptance. Restores the fixture day and preferences. */
@OptIn(ExperimentalTestApi::class)
class SecondaryScreensAcceptanceTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()
    private lateinit var services: FoodBlobServices
    private lateinit var date: String
    private var day: DayEntity? = null
    private val preservedDays=linkedMapOf<String,DayEntity?>()
    private lateinit var settings: SettingsEntity
    private var welcome: Boolean? = null

    @Before fun preserve() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        services = FoodBlobServices.get(context)
        services.store.ensureInitialized()
        date = services.store.currentDateKey()
        services.database.withTransaction {
            day = services.database.dao().day(date)
            repeat(30) { offset -> val key=java.time.LocalDate.parse(date).minusDays(offset.toLong()).toString();preservedDays[key]=services.database.dao().day(key) }
            settings = requireNotNull(services.database.dao().settings())
        }
        val prefs = context.getSharedPreferences("food_blob_ui", 0)
        welcome = if (prefs.contains("onboarding_complete")) prefs.getBoolean("onboarding_complete", false) else null
    }

    @After fun restore() {
        composeRule.waitForIdle()
        runBlocking {
            services.database.withTransaction {
                preservedDays.forEach { (key,original)->original?.let{services.database.dao().upsertDay(it)}?:services.database.dao().deleteDay(key) }
                services.database.dao().upsertSettings(settings)
            }
        }
        val editor = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("food_blob_ui", 0).edit()
        welcome?.let { editor.putBoolean("onboarding_complete", it) } ?: editor.remove("onboarding_complete")
        check(editor.commit())
    }

    @Test fun bothWorldsKeepSecondaryScreensReadableAndNavigable() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureSecondaryScreens") == "true")
        repeat(2) {
            if (composeRule.onAllNodesWithTag("onboarding-next").fetchSemanticsNodes().isNotEmpty()) {
                composeRule.onNodeWithTag("onboarding-next").performClick()
            }
        }
        if (composeRule.onAllNodesWithTag("onboarding-done").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("onboarding-done").performClick()
        }
        composeRule.waitUntilExactlyOneExists(hasTestTag("today-screen"), 5_000)
        for (skin in SkinId.entries) {
            runBlocking {
                services.database.withTransaction {
                    preservedDays.keys.forEachIndexed { index,key ->
                        val total=if(index%5==0&&index!=0) 0 else (index*7+6)%22
                        val green=(total*.55).toInt();val yellow=(total*.30).toInt()
                        services.database.dao().upsertDay(DayEntity(key,green,yellow,total-green-yellow,System.currentTimeMillis()))
                    }
                    services.database.dao().upsertDay(DayEntity(date,3,2,1,System.currentTimeMillis()))
                    services.database.dao().upsertSettings(settings.copy(selectedSkin = skin.storageId))
                }
                withTimeout(5_000) { services.store.snapshots.first { it.selectedSkin == skin && it.counts(date).total == 6 } }
            }
            composeRule.onNodeWithTag("history-tab").performClick()
            composeRule.onNodeWithTag("history-screen").assertIsDisplayed()
            capture("${skin.storageId}-history")
            composeRule.onNodeWithTag("history-previous-month").performClick()
            capture("${skin.storageId}-history-previous-month")
            val previousMonth=java.time.YearMonth.from(java.time.LocalDate.parse(date)).minusMonths(1)
            val previousDay=previousMonth.atEndOfMonth().toString()
            reach("history-screen","history-day-$previousDay").performClick()
            composeRule.onNodeWithTag("day-detail").assertIsDisplayed()
            composeRule.onNodeWithTag("detail-back").performClick()
            composeRule.onNodeWithTag("history-month-title").assertTextEquals(previousMonth.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy",java.util.Locale.getDefault())))
            composeRule.onNodeWithTag("history-next-month").performClick()
            composeRule.onNodeWithTag("history-screen").performScrollToNode(hasTestTag("history-day-$date"))
            captureHeld("history-day-$date", "${skin.storageId}-calendar-held")
            composeRule.mainClock.autoAdvance=false
            composeRule.onNodeWithTag("history-day-$date").performClick()
            composeRule.mainClock.advanceTimeBy(180)
            capture("${skin.storageId}-portrait-expanding")
            composeRule.mainClock.advanceTimeBy(600)
            composeRule.mainClock.autoAdvance=true
            composeRule.onNodeWithTag("day-detail").assertIsDisplayed()
            capture("${skin.storageId}-day")
            composeRule.onNodeWithTag("detail-back").performClick()
            composeRule.onNodeWithTag("skins-tab").performClick()
            composeRule.onNodeWithTag("skins-screen").assertIsDisplayed()
            capture("${skin.storageId}-themes")
            composeRule.onNodeWithTag("skins-screen").performScrollToNode(hasTestTag("skin-${skin.storageId}"))
            captureHeld("skin-${skin.storageId}", "${skin.storageId}-theme-held")
            composeRule.onNodeWithTag("skins-screen").performScrollToIndex(2)
            capture("${skin.storageId}-themes-shrine")
            composeRule.onNodeWithTag("skins-screen").performScrollToNode(hasTestTag("skin-preservation"))
            composeRule.onNodeWithTag("skin-preservation").assertIsDisplayed()
            composeRule.onNodeWithTag("settings-tab").performClick()
            composeRule.onNodeWithTag("settings-screen").assertIsDisplayed()
            capture("${skin.storageId}-settings")
            captureHeld("settings-privacy", "${skin.storageId}-setting-held")
            reach("settings-screen", "settings-import").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            composeRule.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("settings-replay"))
            reach("settings-screen", "settings-replay").performScrollTo()
            capture("${skin.storageId}-settings-welcome-reachable")
            reach("settings-screen", "settings-replay").assertIsDisplayed()
            composeRule.onNodeWithTag("interaction-sound").performScrollTo().assertIsDisplayed()
            capture("${skin.storageId}-settings-experience")
            reach("settings-screen", "settings-widgets").performScrollTo().performClick()
            composeRule.onNodeWithTag("widget-setup-screen").assertIsDisplayed()
            capture("${skin.storageId}-widget-small")
            reach("widget-setup-screen", "widget-presentation-full-control").performScrollTo()
            captureSelection("widget-presentation-full-control", "${skin.storageId}-widget-choice-release")
            capture("${skin.storageId}-widget-medium")
            reach("widget-setup-screen", "widget-pin").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            composeRule.onNodeWithTag("information-back").performClick()
            reach("settings-screen", "settings-privacy").performScrollTo().performClick()
            composeRule.onNodeWithTag("privacy-screen").assertIsDisplayed()
            capture("${skin.storageId}-privacy")
            composeRule.onNodeWithTag("information-back").performClick()
            reach("settings-screen", "settings-delete").performScrollTo().performClick()
            capture("${skin.storageId}-delete-confirmation")
            composeRule.onNodeWithText(composeRule.activity.getString(R.string.cancel)).performClick()
            composeRule.onNodeWithTag("today-tab").performClick()
        }
        composeRule.onNodeWithTag("settings-tab").performClick()
        composeRule.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("settings-replay"))
        reach("settings-screen", "settings-replay").performScrollTo().performClick()
        capture("welcome-mascot")
        composeRule.onNodeWithTag("onboarding-next").performClick()
        capture("welcome-colors")
        composeRule.onNodeWithTag("onboarding-next").performClick()
        capture("welcome-widgets")
        composeRule.onNodeWithTag("onboarding-done").performClick()
    }

    @Test fun highCountThemePreviewsKeepEveryNumberReadable() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureThemeHighCounts") == "true")
        repeat(2) {
            if (composeRule.onAllNodesWithTag("onboarding-next").fetchSemanticsNodes().isNotEmpty()) {
                composeRule.onNodeWithTag("onboarding-next").performClick()
            }
        }
        if (composeRule.onAllNodesWithTag("onboarding-done").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("onboarding-done").performClick()
        }
        composeRule.waitUntilExactlyOneExists(hasTestTag("today-screen"), 5_000)
        runBlocking {
            services.database.dao().upsertDay(DayEntity(date, 55_555, 33_333, 11_111, System.currentTimeMillis()))
            withTimeout(5_000) { services.store.snapshots.first { it.counts(date).total == 99_999 } }
        }
        composeRule.onNodeWithTag("skins-tab").performClick()
        for ((index, skin) in SkinId.entries.withIndex()) {
            composeRule.onNodeWithTag("skins-screen").performScrollToIndex(index + 1)
            capture("${skin.storageId}-themes-high-count")
            for (number in listOf("55555", "33333", "11111")) {
                val numberNode = composeRule.onNode(
                    hasText(number) and hasAnyAncestor(hasTestTag("skin-${skin.storageId}")),
                    useUnmergedTree = true
                )
                numberNode.assertIsDisplayed().assertTextEquals(number)
                val layouts = mutableListOf<TextLayoutResult>()
                numberNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                // Intrinsic width can exceed the measured width even when all glyphs fit.
                // Check the actual rendered line, its final character, and its bounds.
                check(
                    layout.lineCount == 1 &&
                        layout.getLineEnd(0) == number.length &&
                        !layout.isLineEllipsized(0) &&
                        layout.getLineLeft(0) >= -1f &&
                        layout.getLineRight(0) <= layout.size.width + 1f &&
                        !layout.didOverflowHeight
                ) {
                    "The ${skin.storageId} theme must show the complete $number count on one line: " +
                        "lines=${layout.lineCount}, widthOverflow=${layout.didOverflowWidth}, " +
                        "heightOverflow=${layout.didOverflowHeight}, size=${layout.size}, " +
                        "lineBounds=${layout.getLineLeft(0)}..${layout.getLineRight(0)}, " +
                        "lineBottom=${layout.getLineBottom(0)}."
                }
            }
        }
    }

    private fun reach(screen: String, tag: String): SemanticsNodeInteraction {
        // Lazy containers do not compose every offscreen section at large text.
        // Scroll the list to construct the section, then reveal its actual child.
        val scrollable=SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex)
        composeRule.onNode(scrollable and (hasTestTag(screen) or hasAnyAncestor(hasTestTag(screen)))).performScrollToNode(hasTestTag(tag))
        return composeRule.onNodeWithTag(tag).performScrollTo()
    }

    private fun captureHeld(tag: String, name: String) {
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithTag(tag).performTouchInput { down(center) }
            composeRule.mainClock.advanceTimeBy(120)
            capture(name)
            composeRule.onNodeWithTag(tag).performTouchInput { cancel() }
            composeRule.mainClock.advanceTimeBy(600)
        } finally { composeRule.mainClock.autoAdvance = true }
    }

    private fun captureSelection(tag: String, name: String) {
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithTag(tag).performTouchInput { down(center) }
            composeRule.mainClock.advanceTimeBy(100)
            composeRule.onNodeWithTag(tag).performTouchInput { up() }
            composeRule.mainClock.advanceTimeBy(96)
            capture(name)
            composeRule.mainClock.advanceTimeBy(600)
        } finally { composeRule.mainClock.autoAdvance = true }
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Semantics can settle before SurfaceFlinger presents that frame.
        // Give the native display time to present the just-scrolled content.
        android.os.SystemClock.sleep(200)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "secondary-captures")
        check(directory.isDirectory || directory.mkdirs())
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }
}
