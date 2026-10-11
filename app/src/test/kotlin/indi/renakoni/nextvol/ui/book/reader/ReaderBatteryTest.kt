package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import indi.renakoni.nextvol.R
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderBatteryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var context: BatteryContext
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
    private val visible = mutableStateOf(true)
    private val progress = mutableStateOf(0f)
    private val referenceIcon = mutableStateOf<Int?>(null)

    @Before fun open() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        context = BatteryContext(activity.get())
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
    }

    @After fun close() { activity.pause().stop().destroy() }

    private fun mount(indicator: Boolean = false, reduced: Boolean = true) {
        activity.get().setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalContext provides context, LocalLifecycleOwner provides owner,
                    LocalReduceReaderMotion provides reduced) {
                    if (indicator) Column {
                        Indicator(enableBatteryIndicator = visible.value, enableTimeIndicator = false,
                        enableChapterTitle = false, chapterTitle = "", enableReadingChapterProgressIndicator = true,
                        readingChapterProgress = progress.value)
                        referenceIcon.value?.let { Icon(painterResource(it), null,
                            Modifier.size(20.dp).testTag("reference-icon"), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    else if (visible.value) Text("battery:${rememberReaderBatteryLevel().value}")
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun progressRecompositionDoesNotQueryTheHealthServiceOrResubscribe() {
        mount(indicator = true)
        compose.waitUntil(5_000) { context.registered.get() == 1 }
        repeat(12) { value ->
            compose.runOnIdle { progress.value = value / 20f }
            compose.waitForIdle()
        }
        assertEquals(0, context.batteryServiceQueries.get())
        assertEquals(1, context.registered.get())
        assertTrue(context.binderThreads.none { it === Looper.getMainLooper().thread })
    }

    @Test fun progressIsImmediateAndKeepsItsWidthAcrossDigitBoundaries() {
        mount(indicator = true, reduced = false)
        val width = compose.onNodeWithTag("reader-progress-number").fetchSemanticsNode().boundsInRoot.width
        compose.mainClock.autoAdvance = false
        for (value in listOf(9, 10, 99, 100, 0)) {
            compose.runOnUiThread { progress.value = value / 100f }
            repeat(2) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
            compose.onNodeWithText(value.toString()).assertIsDisplayed()
            assertEquals(width, compose.onNodeWithTag("reader-progress-number").fetchSemanticsNode().boundsInRoot.width, 0.1f)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun broadcastsConvertScaleAndInvalidValuesRemainUnknown() {
        context.sticky = battery(3, 4)
        mount()
        compose.waitUntil(5_000) { context.registered.get() == 1 }
        awaitValue("75")
        listOf(battery(-1, 100), battery(20, 0), battery(101, 100), Intent(Intent.ACTION_BATTERY_CHANGED)).forEach {
            compose.runOnIdle { context.receiver!!.onReceive(context, it) }
            awaitValue("null")
        }
        compose.runOnIdle { context.receiver!!.onReceive(context, battery(0, 100)) }
        awaitValue("0")
    }

    @Test fun hiddenAndStoppedReadersReleaseSubscriptionAndResumeRefreshesIt() {
        mount()
        compose.waitUntil(5_000) { context.registered.get() == 1 }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.waitUntil(5_000) { context.unregistered.get() == 1 }
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        compose.waitUntil(5_000) { context.registered.get() == 2 }
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        compose.waitUntil(5_000) { context.unregistered.get() == 2 }
        context.sticky = battery(42, 100)
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(5_000) { context.registered.get() == 3 }
        awaitValue("42")
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
        compose.waitUntil(5_000) { context.unregistered.get() == 3 }
        assertTrue(context.binderThreads.none { it === Looper.getMainLooper().thread })
    }

    @Test fun indicatorDrawsUnknownAndEveryBatteryIconAtItsBoundaries() {
        context.sticky = Intent(Intent.ACTION_BATTERY_CHANGED)
        referenceIcon.value = R.drawable.battery_android_question_24px
        mount(indicator = true)
        compose.waitUntil(5_000) { context.registered.get() == 1 }
        val cases = listOf(
            null to R.drawable.battery_android_question_24px,
            0..15 to R.drawable.battery_android_alert_24px,
            16..35 to R.drawable.battery_android_3_24px,
            36..65 to R.drawable.battery_android_4_24px,
            66..80 to R.drawable.battery_android_5_24px,
            81..95 to R.drawable.battery_android_6_24px,
            96..100 to R.drawable.battery_android_full_24px,
            null to R.drawable.battery_android_question_24px,
        )
        for ((range, icon) in cases) for (level in range?.let { listOf(it.first, it.last) } ?: listOf(null)) {
            compose.runOnIdle {
                referenceIcon.value = icon
                context.receiver!!.onReceive(context, level?.let { battery(it, 100) } ?: Intent(Intent.ACTION_BATTERY_CHANGED))
            }
            compose.waitForIdle()
            if (level == null) compose.onNodeWithText("--").assertIsDisplayed()
            fun pixels(tag: String): IntArray {
                val map = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
                return IntArray(map.width * map.height) { map[it % map.width, it / map.width].toArgb() }
            }
            val expected = pixels("reference-icon")
            assertTrue("Reference icon must contain visible artwork", expected.toSet().size > 1)
            val actual = pixels("reader-battery-icon")
            assertEquals(expected.size, actual.size)
            // Skia dithering can differ by one channel step at different screen positions.
            expected.indices.forEach { pixel ->
                for (shift in listOf(0, 8, 16, 24)) assertTrue("Battery icon for $level at pixel $pixel",
                    kotlin.math.abs((expected[pixel] ushr shift and 255) - (actual[pixel] ushr shift and 255)) <= 2)
            }
        }
    }

    private fun awaitValue(value: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("battery:$value")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("battery:$value").assertIsDisplayed()
    }

    private class BatteryContext(base: Context) : ContextWrapper(base) {
        val registered = AtomicInteger()
        val unregistered = AtomicInteger()
        val batteryServiceQueries = AtomicInteger()
        val binderThreads = CopyOnWriteArrayList<Thread>()
        @Volatile var receiver: BroadcastReceiver? = null
        @Volatile var sticky = battery(73, 100)
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? {
            if (name == BATTERY_SERVICE) batteryServiceQueries.incrementAndGet()
            return super.getSystemService(name)
        }
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter,
            broadcastPermission: String?, scheduler: Handler?, flags: Int): Intent {
            assertTrue(filter.hasAction(Intent.ACTION_BATTERY_CHANGED))
            binderThreads += Thread.currentThread()
            this.receiver = receiver
            registered.incrementAndGet()
            val initial = sticky
            Handler(Looper.getMainLooper()).post { receiver?.onReceive(this, initial) }
            return sticky
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            assertSame(this.receiver, receiver)
            binderThreads += Thread.currentThread()
            this.receiver = null
            unregistered.incrementAndGet()
        }
    }

    companion object {
        private fun battery(level: Int, scale: Int) = Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, level).putExtra(BatteryManager.EXTRA_SCALE, scale)
    }
}
