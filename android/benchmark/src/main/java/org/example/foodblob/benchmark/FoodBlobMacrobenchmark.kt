package org.example.foodblob.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoodBlobMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.COLD,
        setupBlock = {
            prepareOnboarding()
            pressHome()
        },
    ) {
        startActivityAndWait()
        device.wait(Until.hasObject(By.res(PACKAGE_NAME, "today-screen")), UI_TIMEOUT_MS)
    }

    @Test
    fun warmStartup() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.WARM,
        setupBlock = {
            prepareOnboarding()
            pressHome()
        },
    ) {
        startActivityAndWait()
        device.wait(Until.hasObject(By.res(PACKAGE_NAME, "today-screen")), UI_TIMEOUT_MS)
    }

    @Test
    fun loggingAndNavigationFrames() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        iterations = 10,
        setupBlock = { prepareOnboarding() },
    ) {
        tap("add-green")
        tap("remove-green")
        tap("undo")
        tap("undo")
        tap("history-tab")
        tap("today-tab")
        device.waitForIdle()
    }

    @Test
    fun shelfFrames() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        iterations = 10,
        setupBlock = { prepareOnboarding() },
    ) {
        tap("history-tab")
        device.wait(Until.hasObject(By.res(PACKAGE_NAME, "history-title")), UI_TIMEOUT_MS)
        device.swipe(
            device.displayWidth / 2,
            device.displayHeight * 3 / 4,
            device.displayWidth / 2,
            device.displayHeight / 3,
            16,
        )
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.prepareOnboarding() {
        startActivityAndWait()
        repeat(3) {
            val next = device.wait(
                Until.findObject(By.res(PACKAGE_NAME, "onboarding-next")),
                500,
            ) ?: return@repeat
            next.click()
            device.waitForIdle()
        }
        device.findObject(By.res(PACKAGE_NAME, "onboarding-done"))?.click()
        device.wait(Until.hasObject(By.res(PACKAGE_NAME, "today-screen")), UI_TIMEOUT_MS)
    }

    private fun MacrobenchmarkScope.tap(resourceName: String) {
        val target = requireNotNull(
            device.wait(Until.findObject(By.res(PACKAGE_NAME, resourceName)), UI_TIMEOUT_MS),
        ) { "Missing UI target: $resourceName" }
        target.click()
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE_NAME = "org.example.foodblob"
        const val UI_TIMEOUT_MS = 5_000L
    }
}
