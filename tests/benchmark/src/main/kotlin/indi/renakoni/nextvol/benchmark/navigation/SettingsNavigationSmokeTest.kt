package indi.renakoni.nextvol.benchmark.navigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.waitForStableInActiveWindow
import indi.renakoni.nextvol.benchmark.ui.UiAutomatorTest
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class SettingsNavigationSmokeTest : UiAutomatorTest() {
    @Test
    fun mainScreenCanOpenSettingsAndReturnAfterBottomBarTransition() {
        launchApp()
        openAppSettings()
        device.waitForStableInActiveWindow()
        assertText("Extensions")
        assertTextNotVisible("Categories")

        pressBack()
        device.waitForStableInActiveWindow()
        assertText("Reading")
        assertText("Categories")
    }
}
