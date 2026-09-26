package org.example.foodblob.ui

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.Lifecycle
import org.example.foodblob.MainActivity
import org.example.foodblob.R
import org.example.foodblob.domain.FoodColor
import org.example.foodblob.storage.DayEntity
import org.example.foodblob.storage.FoodBlobServices
import org.example.foodblob.storage.SettingsEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class FoodBlobComposeSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()
    private lateinit var services: FoodBlobServices
    private lateinit var originalSettings: SettingsEntity
    private lateinit var originalDateKey: String
    private var originalDay: DayEntity? = null
    private var originalLatestUndoId = 0L
    private var originalOnboardingComplete = false
    private var originalOnboardingPreferenceExists = false

    @Before
    fun rememberOriginalState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        services = FoodBlobServices.get(context)
        services.store.ensureInitialized()
        originalDateKey = services.store.currentDateKey()
        services.database.withTransaction {
            originalDay = services.database.dao().day(originalDateKey)
            originalSettings = requireNotNull(services.database.dao().settings())
            originalLatestUndoId = services.database.dao().latestUndoId() ?: 0L
        }
        val preferences = context.getSharedPreferences("food_blob_ui", 0)
        originalOnboardingPreferenceExists = preferences.contains("onboarding_complete")
        originalOnboardingComplete = preferences.getBoolean("onboarding_complete", false)
    }

    @After
    fun restoreOriginalState() {
        composeRule.waitForIdle()
        runBlocking {
            services.database.withTransaction {
                originalDay?.let { services.database.dao().upsertDay(it) }
                    ?: services.database.dao().deleteDay(originalDateKey)
                services.database.dao().deleteUndoAfter(originalLatestUndoId)
                services.database.dao().upsertSettings(originalSettings)
            }
        }
        val preferences = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("food_blob_ui", 0)
        val editor = preferences.edit()
        if (originalOnboardingPreferenceExists) {
            editor.putBoolean("onboarding_complete", originalOnboardingComplete)
        } else {
            editor.remove("onboarding_complete")
        }
        check(editor.commit()) { "Unable to restore onboarding state after device acceptance" }
    }

    @Test
    fun onboardingLoggingUndoAndTopLevelNavigationStayUsable() {
        finishOnboardingIfNeeded()

        composeRule.onNodeWithTag("today-screen").assertIsDisplayed()
        listOf("add-green", "remove-green", "undo").forEach { tag ->
            composeRule.onNodeWithTag(tag)
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
        }

        val originalTotal = originalDay?.let { it.green + it.yellow + it.red } ?: 0

        composeRule.onNodeWithTag("add-green").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("blob-total"), 5_000)
        composeRule.onNodeWithTag("blob-total").assertTextEquals("${originalTotal + 1}")

        composeRule.onNodeWithTag("remove-green").performClick()
        composeRule.waitUntil(5_000) {composeRule.onAllNodes(hasTestTag("blob-total") and hasText("$originalTotal")).fetchSemanticsNodes().isNotEmpty()}
        composeRule.onNodeWithTag("blob-total").assertTextEquals("$originalTotal")

        composeRule.onNodeWithTag("add-green").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("blob-total"), 5_000)
        composeRule.onNodeWithTag("undo").performClick()
        composeRule.waitUntil(5_000) {composeRule.onAllNodes(hasTestTag("blob-total") and hasText("$originalTotal")).fetchSemanticsNodes().isNotEmpty()}
        composeRule.onNodeWithTag("blob-total").assertTextEquals("$originalTotal")

        composeRule.onNodeWithTag("history-tab").performClick()
        composeRule.onNodeWithTag("history-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("skins-tab").performClick()
        composeRule.onNodeWithTag("skins-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-tab").performClick()
        composeRule.onNodeWithTag("settings-screen").assertIsDisplayed()
    }

    @Test
    fun shrineKeepsThePrimaryTitleReadableAgainstItsDarkWorld() {
        finishOnboardingIfNeeded()

        composeRule.onNodeWithTag("skins-tab").performClick()
        composeRule.onNodeWithTag("skins-screen").performScrollToNode(hasTestTag("skin-shrine"))
        composeRule.onNodeWithTag("skin-shrine").performClick()
        composeRule.onNodeWithTag("today-tab").performClick()
        composeRule.waitForIdle()

        val pixels = composeRule.onNodeWithTag("today-title").captureToImage().toPixelMap()
        val brightest = (0 until pixels.width).maxOf { x ->
            (0 until pixels.height).maxOf { y -> pixels[x, y].luminance() }
        }
        assert(brightest > 0.7f) { "Shrine's Today title must use a light foreground; max luminance was $brightest" }
    }

    @Test
    fun historyDetailSupportsAdjacentDayNavigation() {
        finishOnboardingIfNeeded()
        runBlocking { services.store.increment(org.example.foodblob.domain.FoodColor.GREEN, originalDateKey) }
        composeRule.onNodeWithTag("history-tab").performClick()
        composeRule.onNodeWithTag("history-day-${LocalDate.now()}").performClick()
        composeRule.onNodeWithTag("day-detail").assertIsDisplayed()
        composeRule.onNodeWithTag("previous-day").performClick()
        composeRule.onNodeWithTag("next-day").performClick()
        composeRule.onNodeWithTag("day-detail").assertIsDisplayed()
        composeRule.onNodeWithTag("detail-back").performClick()
        composeRule.onNodeWithTag("history-screen").assertIsDisplayed()
    }

    @Test
    fun resumingTodayPreservesAnExplicitlySelectedPastDate() {
        finishOnboardingIfNeeded()
        val previousDate = LocalDate.now().minusDays(1)

        composeRule.onNodeWithTag("previous-day").performClick()
        composeRule.onNodeWithText(previousDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
            .assertIsDisplayed()

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        composeRule.onNodeWithText(previousDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
            .assertIsDisplayed()
    }

    @Test
    fun persistentUndoFollowsTheDisplayedDateWithoutCoveringFoodControls() = runBlocking {
        services.database.withTransaction {
            services.database.dao().upsertDay(
                DayEntity(
                    dateKey = originalDateKey,
                    green = 0,
                    yellow = 0,
                    red = 0,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
        finishOnboardingIfNeeded()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasTestTag("blob-total") and hasText("0")).fetchSemanticsNodes().isNotEmpty()
        }

        val emptyBlobBounds = composeRule.onNodeWithTag("living-blob-frame").fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("add-green").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("blob-total"), 5_000)
        org.junit.Assert.assertEquals(
            "First-log confirmation must keep the flight landing geometry fixed",
            emptyBlobBounds,
            composeRule.onNodeWithTag("living-blob-frame").fetchSemanticsNode().boundsInRoot,
        )
        val previousDate = LocalDate.parse(originalDateKey).minusDays(1).toString()
        val previousCounts = services.store.snapshots.first().counts(previousDate)
        composeRule.onNodeWithTag("previous-day").performClick()
        composeRule.onNode(
            hasText(composeRule.activity.getString(R.string.undo)) and
                hasAnyAncestor(hasTestTag("snackbar-host")),
            useUnmergedTree = true,
        ).assertDoesNotExist()
        composeRule.onNodeWithTag("next-day").performClick()
        composeRule.onNodeWithTag("blob-total").assertTextEquals("1")
        composeRule.onNodeWithTag("undo").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasTestTag("blob-total") and hasText("0")).fetchSemanticsNodes().isNotEmpty()
        }
        org.junit.Assert.assertEquals(previousCounts, services.store.snapshots.first().counts(previousDate))

    }

    @Test
    fun historyShowsRecentDaysWithAccessibleCountsAndOpensTheSelectedDay() {
        finishOnboardingIfNeeded()
        runBlocking {
            services.database.withTransaction {
                services.database.dao().deleteDay(originalDateKey)
            }
        }
        composeRule.onNodeWithTag("history-tab").performClick()
        composeRule.onNodeWithTag("history-title").assertIsDisplayed()
        val today = LocalDate.now()
        val expectedTodayDescription = ApplicationProvider.getApplicationContext<android.content.Context>()
            .resources.getQuantityString(
                R.plurals.day_offerings,
                0,
                today.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                0,
            )
        composeRule.onNodeWithTag("history-day-$today")
            .assertContentDescriptionEquals(expectedTodayDescription)
        (0 until minOf(7,today.dayOfMonth)).forEach { offset ->
            val date = today.minusDays(offset.toLong())
            composeRule.onNodeWithTag("history-screen").performScrollToNode(hasTestTag("history-day-$date"))
            composeRule.onNodeWithTag("history-day-$date").assertIsDisplayed()
        }
        composeRule.onNodeWithTag("history-screen").performScrollToNode(hasTestTag("history-day-$today"))
        composeRule.onNodeWithTag("history-day-$today").performClick()
        composeRule.onNodeWithTag("day-detail").assertIsDisplayed()
    }

    @Test
    fun settingsExposeWidgetPrivacyAndCompleteWelcomeGuidance() {
        finishOnboardingIfNeeded()
        composeRule.onNodeWithTag("settings-tab").performClick()
        composeRule.onNodeWithTag("settings-widgets").performClick()
        composeRule.onNodeWithTag("widget-setup-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("widget-skin-sky-meadow").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("widget-skin-shrine").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("widget-presentation-quick-add").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("widget-presentation-full-control").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("widget-pin").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("information-back").performClick()
        composeRule.onNodeWithTag("settings-privacy").performClick()
        composeRule.onNodeWithTag("privacy-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("information-back").performClick()
        composeRule.onNodeWithTag("settings-replay").performScrollTo().performClick()

        composeRule.onNodeWithTag("onboarding-next").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-screen").assertDoesNotExist()
        composeRule.onNodeWithTag("today-screen").assertDoesNotExist()

        composeRule.onNodeWithTag("onboarding-next").performClick()
        composeRule.onNodeWithTag("onboarding-colors-guide").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding-next").performClick()
        composeRule.onNodeWithTag("onboarding-widget-previews").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding-done").performClick()
        composeRule.onNodeWithTag("settings-screen").assertIsDisplayed()
    }

    @Test
    fun settingsExposeImportWithoutRemovingExportOrDelete() {
        finishOnboardingIfNeeded()
        composeRule.onNodeWithTag("settings-tab").performClick()
        composeRule.onNodeWithTag("settings-import").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_export))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_delete))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun skinsExplainThatHistoryIsPreserved() {
        finishOnboardingIfNeeded()
        composeRule.onNodeWithTag("skins-tab").performClick()
        composeRule.onNodeWithTag("skins-screen").performScrollToNode(hasTestTag("skin-preservation"))
        composeRule.onNodeWithTag("skin-preservation").assertIsDisplayed()
    }

    private fun finishOnboardingIfNeeded() {
        repeat(2) {
            if (composeRule.onAllNodesWithTag("onboarding-next").fetchSemanticsNodes().isNotEmpty()) {
                composeRule.onNodeWithTag("onboarding-next").performClick()
                composeRule.waitForIdle()
            }
        }
        if (composeRule.onAllNodesWithTag("onboarding-done").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("onboarding-done").performClick()
        }
        composeRule.waitUntilExactlyOneExists(hasTestTag("today-screen"), 5_000)
    }
}
