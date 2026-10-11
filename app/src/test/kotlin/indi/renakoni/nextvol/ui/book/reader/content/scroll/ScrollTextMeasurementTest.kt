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
import indi.renakoni.nextvol.data.content.component.SimpleTextComponent
import indi.renakoni.nextvol.ui.book.reader.ReaderSettings
import indi.renakoni.nextvol.ui.book.reader.rememberReaderTextLayout
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.componet.LocalReaderTextWorkObserver
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrollTextMeasurementTest {
    @get:Rule val compose = createEmptyComposeRule()

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
}
