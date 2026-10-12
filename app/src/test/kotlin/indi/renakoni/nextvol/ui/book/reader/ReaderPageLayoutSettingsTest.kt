package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.IntSize
import indi.renakoni.nextvol.R
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.nightfish.lightnovelreader.api.userdata.StringUserData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderPageLayoutSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private var selected by mutableStateOf("auto")
    private var paginated by mutableStateOf(true)
    private val result = mutableStateOf<ReaderLayoutResult?>(null)
    private val userData = mockk<StringUserData>()
    private val settings = mockk<ReaderSettingsEditor>()
    private val geometry = ReaderBodyGeometry(0, 0, IntSize(480, 600))

    @Before fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        every { settings.pageLayout } answers { selected }
        every { settings.isUsingFlipPage } answers { paginated }
        every { settings.pageLayoutUserData } returns userData
        every { userData.asynchronousSet(any()) } answers { selected = firstArg() }
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalReaderLayoutResult provides result) { ReaderPageLayoutEntry(settings) }
                }
            }
        }
    }

    @After fun tearDown() { activity.pause().stop().destroy() }

    @Test fun menuExposesThreeIndependentPersistedChoices() {
        for (key in listOf("single", "double", "auto")) {
            compose.onNodeWithTag("reader-page-layout").assertHasClickAction().performClick()
            val option = ReaderPageLayoutOptions.optionList.single { it.key == key }
            compose.onNodeWithText(activity.get().getString(option.nameId)).performClick()
            compose.runOnIdle { assertEquals(key, selected) }
            verify(exactly = 1) { userData.asynchronousSet(key) }
        }
    }

    @Test fun actualLayoutReasonsAndScrollNoticeNeverRewriteThePreference() {
        compose.runOnIdle { selected = "double" }
        compose.onNodeWithText(activity.get().getString(R.string.reader_page_layout_waiting)).assertIsDisplayed()
        val reasons = mapOf(
            ReaderLayoutReason.WindowTooNarrow to R.string.reader_page_layout_narrow,
            ReaderLayoutReason.WindowTooShort to R.string.reader_page_layout_short,
            ReaderLayoutReason.TextTooLarge to R.string.reader_page_layout_large_text,
            ReaderLayoutReason.UnsupportedContent to R.string.reader_page_layout_unsupported,
            ReaderLayoutReason.ScrollMode to R.string.reader_page_layout_scroll,
        )
        for ((reason, text) in reasons) {
            compose.runOnIdle { result.value = ReaderLayoutResult(geometry, reason) }
            compose.onNodeWithText(activity.get().getString(text)).assertIsDisplayed()
        }
        compose.runOnIdle { result.value = ReaderLayoutResult(geometry.copy(columns = 2)) }
        compose.onNodeWithText(activity.get().getString(R.string.reader_page_layout_current_double)).assertIsDisplayed()
        compose.runOnIdle { result.value = ReaderLayoutResult(geometry) }
        compose.onNodeWithText(activity.get().getString(R.string.reader_page_layout_current_single)).assertIsDisplayed()
        compose.runOnIdle { paginated = false; result.value = null }
        compose.onNodeWithText(activity.get().getString(R.string.reader_page_layout_scroll)).assertIsDisplayed()
        assertEquals("double", selected)
        verify(exactly = 0) { userData.asynchronousSet(any()) }
    }

    @Test fun allFourLocalesHaveLayoutLabelsWithoutChangingPersistedKeys() {
        for ((locale, title) in mapOf("en" to "Page layout", "zh-CN" to "页面布局",
            "zh-TW" to "頁面版面", "ru" to "Макет страниц")) {
            val config = Configuration(activity.get().resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
            val context = activity.get().createConfigurationContext(config)
            assertEquals(title, context.getString(R.string.reader_page_layout_title))
            val labels = ReaderPageLayoutOptions.optionList.map { context.getString(it.nameId) }
            assertEquals(3, labels.distinct().size)
            assertTrue(labels.all { it.isNotBlank() })
            ReaderLayoutReason.entries.forEach { reason ->
                assertTrue(context.getString(readerPageLayoutDescription(true, ReaderLayoutResult(geometry, reason))).isNotBlank())
            }
        }
        assertEquals(listOf("auto", "single", "double"), ReaderPageLayoutOptions.optionList.map { it.key })
    }
}
