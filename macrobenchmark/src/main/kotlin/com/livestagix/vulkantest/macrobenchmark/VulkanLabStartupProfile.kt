package com.livestagix.vulkantest.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.uiAutomator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET_PACKAGE = "com.livestagix.vulkantest"

@LargeTest
@RunWith(AndroidJUnit4::class)
class VulkanLabStartupProfile {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun launcherStartup() {
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            maxIterations = 10,
            stableIterations = 3,
            includeInStartupProfile = true
        ) {
            uiAutomator {
                startApp(TARGET_PACKAGE)
            }
        }
    }
}
