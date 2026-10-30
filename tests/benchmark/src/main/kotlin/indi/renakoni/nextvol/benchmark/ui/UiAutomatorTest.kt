package indi.renakoni.nextvol.benchmark.ui

import android.graphics.Rect
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.waitForStableInActiveWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before

@Suppress("unused", "SameParameterValue")
abstract class UiAutomatorTest {
    protected val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun resetTargetApp() {
        device.pressHome()
        shell("pm clear $TARGET_PACKAGE")
        // Older Android versions require an English device locale for these UI tests.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shell("cmd locale set-app-locales $TARGET_PACKAGE --user 0 --locales en-US")
            shell("pm grant $TARGET_PACKAGE android.permission.POST_NOTIFICATIONS")
        }
        // The one-time Android fullscreen tutorial otherwise covers the reader controls.
        shell("settings put secure immersive_mode_confirmations confirmed")
        shell(
            "am broadcast -W -n $TARGET_PACKAGE/.benchmark.BenchmarkFixtureReceiver " +
                "-a $SEED_ACTION"
        )
    }

    protected fun launchApp() {
        shell("am start -W -n $TARGET_PACKAGE/.MainActivity")
        assertTrue(
            "Target app did not become visible",
            device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), TIMEOUT),
        )
        device.waitForIdle()
    }

    protected fun restartApp() {
        shell("am force-stop $TARGET_PACKAGE")
        launchApp()
    }

    protected fun shell(command: String): String = device.executeShellCommand(command)

    protected fun assertText(text: String): UiObject2 {
        val object2 = device.wait(Until.findObject(By.text(text)), TIMEOUT)
        assertNotNull("Expected text was not visible: $text", object2)
        return object2
    }

    protected fun assertTextContains(text: String): UiObject2 {
        val object2 = device.wait(Until.findObject(By.textContains(text)), TIMEOUT)
        assertNotNull("Expected text fragment was not visible: $text", object2)
        return object2
    }

    protected fun assertTextNotVisible(text: String, timeout: Long = 1_500L) {
        assertFalse(
            "Text should not be visible: $text",
            device.wait(Until.hasObject(By.text(text)), timeout),
        )
    }

    protected fun assertDescription(description: String): UiObject2 {
        val object2 = device.wait(Until.findObject(By.desc(description)), TIMEOUT)
        assertNotNull("Expected content description was not visible: $description", object2)
        return object2
    }

    protected fun clickText(text: String) {
        clickCenter(assertText(text))
        device.waitForIdle()
    }

    protected fun clickTextContains(text: String) {
        clickCenter(assertTextContains(text))
        device.waitForIdle()
    }

    protected fun clickLastText(text: String) {
        val objects = device.wait(Until.findObjects(By.text(text)), TIMEOUT)
        assertTrue("Expected text was not visible: $text", objects.isNotEmpty())
        clickCenter(objects.last())
        device.waitForIdle()
    }

    protected fun clickDescription(description: String, index: Int = 0) {
        val objects = device.wait(Until.findObjects(By.desc(description)), TIMEOUT)
        assertTrue(
            "Expected content description was not visible: $description",
            objects != null && objects.size > index,
        )
        clickCenter(objects[index])
        device.waitForIdle()
    }

    protected fun longClickText(text: String) {
        assertText(text).longClick()
        device.waitForIdle()
    }

    protected fun clickMenuItemAfter(text: String) {
        var item = assertText(text)
        while (!item.isClickable && item.parent != null) {
            item = item.parent
        }
        val bounds = item.visibleBounds
        device.click(bounds.centerX(), bounds.bottom + bounds.height() / 2)
        device.waitForIdle()
    }

    protected fun clickCenter(object2: UiObject2) {
        val bounds: Rect = object2.visibleBounds
        device.click(bounds.centerX(), bounds.centerY())
    }

    protected fun clickClickableText(text: String) {
        var target = assertText(text)
        while (!target.isClickable && target.parent != null) {
            target = target.parent
        }
        if (target.isClickable) target.click() else clickCenter(target)
        device.waitForIdle()
    }

    protected fun scrollToText(
        text: String,
        attempts: Int = 12,
        direction: Direction = Direction.DOWN,
    ): UiObject2 = scrollTo(By.text(text), attempts, direction)

    protected fun scrollTo(
        selector: BySelector,
        attempts: Int = 12,
        direction: Direction = Direction.DOWN,
    ): UiObject2 {
        repeat(attempts) {
            try {
                device.findObject(selector)?.let {
                    if (!it.visibleBounds.isEmpty) return it
                }
                val bounds = device.findObjects(By.scrollable(true))
                    .map { it.visibleBounds }
                    .filterNot { it.isEmpty }
                    .maxByOrNull { it.height() }
                    ?: Rect(0, 0, device.displayWidth, device.displayHeight)
                // Keep the swipe inside the visible list instead of surrounding controls.
                val start = if (direction == Direction.UP) 0.22 else 0.78
                val end = if (direction == Direction.UP) 0.78 else 0.22
                device.swipe(
                    bounds.centerX(),
                    bounds.top + (bounds.height() * start).toInt(),
                    bounds.centerX(),
                    bounds.top + (bounds.height() * end).toInt(),
                    40,
                )
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                // Compose may replace semantics nodes during a scroll animation.
            }
            device.waitForIdle()
            device.waitForStableInActiveWindow()
        }
        val target = device.wait(Until.findObject(selector), TIMEOUT)
        assertNotNull("Expected scroll target was not visible: $selector", target)
        return target
    }

    protected fun clickScrolledText(text: String, direction: Direction = Direction.DOWN) {
        scrollToText(text, direction = direction)
        device.waitForStableInActiveWindow()
        clickCenter(assertText(text))
        device.waitForIdle()
    }

    protected fun openBottomNavigation(label: String) {
        clickText(label)
        assertText(label)
    }

    protected fun openAppSettings() {
        clickDescription("Settings")
        assertText("Settings")
    }

    protected fun pressBack() {
        device.pressBack()
        device.waitForIdle()
    }

    protected fun assertForegroundPackage(packageName: String) {
        assertEquals(packageName, device.currentPackageName)
    }

    protected fun setFirstTextField(value: String) {
        setTextField(0, value)
    }

    protected fun setTextField(index: Int, value: String) {
        val fields = device.wait(
            Until.findObjects(By.clazz("android.widget.EditText")),
            TIMEOUT,
        )
        assertTrue("Editable text field $index was not visible", fields.size > index)
        fields[index].text = value
        device.waitForIdle()
    }

    protected fun assertFirstSwitchChecked(expected: Boolean) {
        assertCheckableChecked(0, expected)
    }

    protected fun assertCheckableChecked(index: Int, expected: Boolean) {
        val checkables = device.wait(
            Until.findObjects(By.checkable(true)),
            TIMEOUT,
        )
        assertTrue("Checkable control $index was not visible", checkables.size > index)
        assertEquals("Unexpected checkable state at index $index", expected, checkables[index].isChecked)
    }

    protected fun clickFirstCheckable() {
        val checkable = device.wait(Until.findObject(By.checkable(true)), TIMEOUT)
        assertNotNull("No checkable control was visible", checkable)
        clickCenter(checkable)
        device.waitForIdle()
    }

    companion object {
        const val TARGET_PACKAGE = "indi.renakoni.nextvol"
        const val SEED_ACTION = "$TARGET_PACKAGE.benchmark.SEED"
        const val TIMEOUT = 10_000L
    }
}
