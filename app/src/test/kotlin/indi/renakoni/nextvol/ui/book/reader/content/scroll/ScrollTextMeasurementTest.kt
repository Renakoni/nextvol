package indi.renakoni.nextvol.ui.book.reader.content.scroll

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import indi.renakoni.nextvol.data.content.component.SimpleTextComponent
import indi.renakoni.nextvol.ui.book.reader.ReaderSettings
import indi.renakoni.nextvol.ui.book.reader.ReaderLayoutSettings
import indi.renakoni.nextvol.ui.book.reader.rememberReaderTextLayout
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.componet.LocalReaderTextWorkObserver
import indi.renakoni.nextvol.ui.book.reader.content.componet.ReaderTextSource
import indi.renakoni.nextvol.ui.book.reader.content.componet.layoutReaderText
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrollTextMeasurementTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun fixedBodyWidthPreservesNativePageGeometryAndSourceAnchors() {
        val sources = listOf(
            ReaderTextSource(0, "\r\n午后的阳光。Mixed words 𠮷🙂e\u0301\u2028soft break\n\n" +
                "مرحبا بالعالم 123 中文\nעברית English\n\ttrailing spaces  \n"),
            ReaderTextSource(2, "A paragraph with several words and punctuation! ".repeat(5)),
        )
        val resolver = createFontFamilyResolver(org.robolectric.RuntimeEnvironment.getApplication())
        val settings = ReaderLayoutSettings(15f, 500f, 7f, 3f, Uri.EMPTY, false, 12f, 12f, 16f, 16f)
        for (density in listOf(Density(1f), Density(3f, 1.3f))) for (direction in LayoutDirection.entries) {
            val measurer = TextMeasurer(resolver, density, direction, cacheSize = 0)
            for (font in listOf(FontFamily.Default, FontFamily.Serif)) {
                val style = settings.textStyle(font, LocaleList("zh-CN,en-US,ar"))
                for (width in listOf(1, 90, 620)) for (height in listOf(1, 160, 600)) {
                    val original = layoutReaderText(sources, width, height, 9) { text, maxWidth ->
                        measurer.measure(text, style, constraints = Constraints(maxWidth = maxWidth))
                    }
                    val fixed = layoutReaderText(sources, width, height, 9, style, measurer)
                    assertEquals("$density $direction $font width=$width height=$height", original, fixed)
                    sources.forEach { source ->
                        assertEquals(source.text, fixed.flatten().filter { it.componentIndex == source.componentIndex }
                            .joinToString("") { source.text.substring(it.start, it.end) })
                    }
                }
            }
        }
    }

    @Test fun paragraphGapsAndHeightReuseLinesButFontWidthAndTextStillRemeasure() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var spacing by mutableStateOf(0f)
        var font by mutableStateOf(15f)
        var width by mutableStateOf(300)
        var height by mutableStateOf(200)
        fun chapter(text: String) = ChapterContentUiState("chapter", "Title",
            listOf(SimpleTextComponent(SimpleTextComponentData(text), mockk(relaxed = true), activity.get())), null, null)
        var content by mutableStateOf(chapter("第一段文字\nSecond paragraph\n\nLast paragraph"))
        val shapes = AtomicInteger()
        var result: PreparedScrollChapter? = null
        val settings = mockk<ReaderSettings>(relaxed = true) {
            every { fontFamilyUri } returns Uri.EMPTY
            every { fontSize } answers { font }
            every { fontLineHeight } returns 7f
            every { fontWeigh } returns 500f
            every { paragraphSpacing } answers { spacing }
        }
        fun await(condition: () -> Boolean) = compose.waitUntil(10_000) { compose.waitForIdle(); condition() }
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalReaderTextWorkObserver provides { phase ->
                    if (phase == "shape") shapes.incrementAndGet()
                }) {
                    result = rememberPreparedScrollChapter(content, rememberReaderTextLayout(settings), width, height)
                }
            }
            await { result != null }
            assertEquals(4, shapes.get())
            val originalHeight = result!!.blocks.single().text!!.height
            compose.runOnIdle { spacing = 7f }
            await { result?.layout?.settings?.paragraphSpacing == 7f }
            assertEquals(4, shapes.get())
            assertTrue(result!!.blocks.single().text!!.height > originalHeight)
            compose.runOnIdle { height = 80 }
            await { result?.size?.height == 80 }
            assertEquals(4, shapes.get())
            compose.runOnIdle { font = 23f }
            await { result?.layout?.settings?.fontSize == 23f }
            assertEquals(8, shapes.get())
            compose.runOnIdle { width = 200 }
            await { result?.size?.width == 200 }
            assertEquals(12, shapes.get())
            compose.runOnIdle { content = chapter("Changed body") }
            await { result?.content === content }
            assertEquals(13, shapes.get())
            assertEquals("Changed body", result!!.blocks.single().text!!.fragments.joinToString("") { it.text })
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun cancelledQueuedChapterSkipsMeasurementAndNeverPublishesOldContent() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val dispatcher = Dispatchers.Default.limitedParallelism(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val shapes = AtomicInteger()
        fun chapter(id: String, text: String) = ChapterContentUiState(id, id,
            listOf(SimpleTextComponent(SimpleTextComponentData(text), mockk(relaxed = true), activity.get())), null, null)
        val current = chapter("current", "Current chapter")
        var adjacent by mutableStateOf(chapter("adjacent", "Stale queued content"))
        var result: PreparedScrollChapter? = null
        val settings = mockk<ReaderSettings>(relaxed = true) {
            every { fontFamilyUri } returns Uri.EMPTY
            every { fontSize } returns 15f
            every { fontLineHeight } returns 7f
            every { fontWeigh } returns 500f
        }
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalReaderTextWorkObserver provides { phase ->
                    if (phase == "shape" && shapes.incrementAndGet() == 1) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                }) {
                    val layout = rememberReaderTextLayout(settings)
                    rememberPreparedScrollChapter(current, layout, 300, 200, dispatcher)
                    result = rememberPreparedScrollChapter(adjacent, layout, 300, 200, dispatcher)
                }
            }
            compose.waitForIdle()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            compose.runOnIdle { adjacent = chapter("adjacent", "Updated content") }
            compose.waitForIdle()
            assertNull(result)
            assertEquals(1, shapes.get())
            release.countDown()
            compose.waitUntil(10_000) { compose.waitForIdle(); result != null }
            assertSame(adjacent, result!!.content)
            assertEquals(2, shapes.get())
            assertEquals("Updated content", result!!.blocks.single().text!!.fragments.joinToString("") { it.text })
        } finally {
            release.countDown()
            activity.pause().stop().destroy()
        }
    }
}
