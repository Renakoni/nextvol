package indi.renakoni.nextvol.ui.book.reader.content.scroll

import android.app.Application
import android.net.Uri
import androidx.compose.ui.text.TextStyle
import indi.renakoni.nextvol.data.content.component.ImageComponent
import indi.renakoni.nextvol.data.content.component.SimpleTextComponent
import indi.renakoni.nextvol.ui.book.reader.ReaderTextLayoutInput
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.componet.ReaderTextFragment
import indi.renakoni.nextvol.ui.book.reader.content.flip.ReaderContentAnchor
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class PreparedScrollChapterTest {
    private fun text(value: String = "ABCD") = SimpleTextComponent(
        SimpleTextComponentData(value), mockk(relaxed = true), RuntimeEnvironment.getApplication())
    private fun layout(index: Int, value: String = "ABCD") = ScrollTextLayout(listOf(
        ReaderTextFragment(index, 0, value.length, value, 0, 20, listOf(0, 2), listOf(0, 10)),
    ), TextStyle.Default)

    @Test fun groupedTextKeepsSourceIndicesSpacingAndDynamicImageBoundaries() {
        val content = ChapterContentUiState("chapter", "Title", listOf(text(), text(""), text(),
            ImageComponent(ImageComponentData(Uri.parse("file:///fixture.png"))), text(), text("\n")), null, null)
        val prepared = PreparedScrollChapter(content, mapOf(0 to layout(0), 2 to layout(2),
            4 to layout(4), 5 to layout(5, "\n")),
            ReaderTextLayoutInput(mockk(), TextStyle.Default, mockk(), 7))
        assertEquals(listOf(0, 3, 4), prepared.blocks.map { it.componentIndex })
        assertEquals(listOf(0, 2), prepared.blocks[0].text!!.fragments.map { it.componentIndex })
        assertEquals(47, prepared.blocks[0].text!!.height)
        assertEquals(47, prepared.blocks[2].text!!.height)
        assertFalse(prepared.isPositioned(2))
        assertNull(prepared.offsetFor(ReaderContentAnchor(2, 0)))
        assertNull(prepared.anchorAt(0))
        prepared.blocks[0].top = 100
        prepared.blocks[1].top = 147
        prepared.blocks[2].top = 267
        assertTrue(prepared.isPositioned(2))
        assertFalse(prepared.isPositioned(1))
        assertEquals(127, prepared.offsetFor(ReaderContentAnchor(2, 0)))
        assertEquals(137, prepared.offsetFor(ReaderContentAnchor(2, 2)))
        assertEquals(ReaderContentAnchor(2, 2), prepared.anchorAt(137))
        assertEquals(ReaderContentAnchor(3, 0), prepared.anchorAt(180))
        assertEquals(ReaderContentAnchor(0, 0), prepared.anchorAt(0))
        assertEquals(ReaderContentAnchor(4, 2), prepared.anchorAt(277))
        // An asynchronously resized image moves only the following block's chapter-local origin.
        prepared.blocks[2].top = 467
        assertEquals(477, prepared.offsetFor(ReaderContentAnchor(4, 2)))
        assertEquals(ReaderContentAnchor(4, 2), prepared.anchorAt(477))
        assertEquals(137, prepared.offsetFor(ReaderContentAnchor(2, 2)))
        assertNull(prepared.offsetFor(ReaderContentAnchor(2, 4)))
        assertNull(prepared.offsetFor(ReaderContentAnchor(99, 0)))
    }

    @Test fun everyLineInAThousandComponentsRoundTripsWithoutChangingTheChapterHeight() {
        val content = ChapterContentUiState("chapter", "Title", List(1097) { text() }, null, null)
        val prepared = PreparedScrollChapter(content, (0 until 1097).associateWith { layout(it) },
            ReaderTextLayoutInput(mockk(), TextStyle.Default, mockk(), 7))
        val block = prepared.blocks.single()
        block.top = 80
        assertEquals(1097 * 20 + 1096 * 7, block.text!!.height)
        for (index in 0 until 1097) for (offset in listOf(0, 2)) {
            val anchor = ReaderContentAnchor(index, offset)
            val y = prepared.offsetFor(anchor)!!
            assertEquals(80 + index * 27 + offset / 2 * 10, y)
            assertEquals(anchor, prepared.anchorAt(y))
        }
    }
}
