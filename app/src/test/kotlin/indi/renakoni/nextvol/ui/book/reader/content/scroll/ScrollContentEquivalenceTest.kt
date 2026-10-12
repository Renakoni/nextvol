package indi.renakoni.nextvol.ui.book.reader.content.scroll

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Density
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.ui.book.reader.content.ContentComponentFactory
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.ui.book.reader.ReaderSettings
import indi.renakoni.nextvol.ui.book.reader.rememberReaderTextLayout
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.ContentRenderer
import indi.renakoni.nextvol.ui.book.reader.mode.ModeTestEnvironment
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.builder.simpleText
import io.nightfish.lightnovelreader.api.ui.LocalTextLocaleList
import kotlinx.coroutines.launch
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrollContentEquivalenceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val env = ModeTestEnvironment()
    private lateinit var activity: ActivityController<ComponentActivity>
    private var content by mutableStateOf<ChapterContentUiState?>(null)
    private var color by mutableStateOf(Color.Black)
    private var fontSize by mutableStateOf(15f)
    private var width by mutableStateOf(320)
    private var density by mutableStateOf(1f)
    private var locales by mutableStateOf(LocaleList("en-US"))
    private var prepared: PreparedScrollChapter? = null
    private var preparations = 0
    private var publications = 0
    private val decodeNanos = mutableListOf<Long>()
    private val settings = mockk<ReaderSettings>(relaxed = true) {
        every { fontFamilyUri } returns Uri.EMPTY
        every { fontSize } answers { this@ScrollContentEquivalenceTest.fontSize }
        every { fontLineHeight } returns 7f
        every { fontWeigh } returns 500f
    }

    @Before fun open() { activity = Robolectric.buildActivity(ComponentActivity::class.java).setup() }
    @After fun close() { env.close(); activity.pause().stop().destroy() }

    @Test fun equivalentResultsPreservePreparedLayoutButContentAndGeometryChangesDoNot() {
        val renderer = ContentRenderer(ContentJsonDecoder(ContentComponentRegistry()),
            ContentComponentFactory(activity.get(), mockk(relaxed = true)))
        every { env.renderer.getContentDataFromJson(any()) } answers {
            val start = System.nanoTime()
            renderer.getContentDataFromJson(firstArg()).also { decodeNanos += System.nanoTime() - start }
        }
        env.scope.launch { env.loader.load("chapter", "book").collect { publications++; content = it.get() } }
        env.runCurrent()
        compose.runOnUiThread { activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(density), LocalTextLocaleList provides locales) {
                Box(Modifier.background(color)) {
                    val next = rememberPreparedScrollChapter(content, rememberReaderTextLayout(settings), width, 480)
                    SideEffect { if (next != null && next !== prepared) { prepared = next; preparations++ } }
                }
            }
        } }
        val body = (1..40).joinToString("\n") { "Paragraph $it contains deterministic layout text." }
        var chapter = ChapterContent("chapter", "Title", ContentBuilder().simpleText(body).build(), "prev", "next")
        publish(chapter, 1)
        val first = prepared
        compose.runOnIdle { repeat(3) { env.emit("chapter", Ok(chapter.copy())) } }
        compose.waitForIdle()
        assertEquals(1, publications)
        assertEquals(1, decodeNanos.size)
        assertEquals(1, preparations)
        assertSame(first, prepared)

        chapter = chapter.copy(content = ContentBuilder().simpleText(body.replace("Paragraph", "Rewritten")).build())
        publish(chapter, 2)
        assertNotSame(first, prepared)
        chapter = chapter.copy(title = "Renamed")
        publish(chapter, 3)
        assertEquals("Renamed", prepared!!.content.title)
        chapter = chapter.copy(prevChapter = "other-prev", nextChapter = "other-next")
        publish(chapter, 4)
        assertEquals("other-next", prepared!!.content.nextChapter)

        val beforeColor = prepared
        compose.runOnIdle { color = Color.Red }
        compose.waitForIdle()
        assertSame(beforeColor, prepared)
        assertEquals(4, preparations)
        compose.runOnIdle { fontSize = 20f }; awaitPreparation(5)
        compose.runOnIdle { width = 260 }; awaitPreparation(6)
        compose.runOnIdle { density = 1.2f }; awaitPreparation(7)
        compose.runOnIdle { locales = LocaleList("ja-JP") }; awaitPreparation(8)
        assertEquals(4, publications)
        assertEquals(4, decodeNanos.size)
        assertTrue(prepared!!.blocks.any { it.text != null })
        println("Host-only measurement: publications=$publications, decodes=${decodeNanos.size}, preparations=$preparations, decodeNanos=$decodeNanos")
    }

    private fun publish(chapter: ChapterContent, expected: Int) {
        compose.runOnIdle { env.emit("chapter", Ok(chapter)) }
        awaitPreparation(expected)
    }

    private fun awaitPreparation(expected: Int) {
        val start = System.nanoTime()
        compose.waitUntil(15_000) {
            compose.mainClock.advanceTimeByFrame()
            env.runCurrent()
            preparations >= expected
        }
        compose.waitForIdle()
        assertEquals(expected, preparations)
        println("Host-only preparation $expected observed after ${System.nanoTime() - start} ns (includes scheduling)")
    }
}
