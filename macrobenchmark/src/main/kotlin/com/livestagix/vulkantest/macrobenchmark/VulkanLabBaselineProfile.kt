package com.livestagix.vulkantest.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.textAsString
import androidx.test.uiautomator.uiAutomator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET_PACKAGE = "com.livestagix.vulkantest"

@RunWith(AndroidJUnit4::class)
class VulkanLabBaselineProfile {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startupAndWallpaperPreviewJourney() {
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            maxIterations = 10,
            stableIterations = 3
        ) {
            uiAutomator {
                startApp(TARGET_PACKAGE)
                onElement { textAsString() == "Set as Live Wallpaper" }.click()
                pressBack()
            }
        }
    }
}
