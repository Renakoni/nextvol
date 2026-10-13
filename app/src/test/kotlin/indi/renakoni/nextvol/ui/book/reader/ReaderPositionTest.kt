package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import indi.renakoni.nextvol.ui.book.reader.content.componet.SimpleTextComponent
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.ReaderPosition
import indi.renakoni.nextvol.ui.book.reader.content.flip.ReaderContentAnchor
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ReaderPositionTest {
    private fun text(value: String) = mockk<SimpleTextComponent> {
        every { data } returns SimpleTextComponentData(value)
    }

    private fun chapter(vararg components: AbstractContentComponent<*>, id: String = "chapter") =
        ChapterContentUiState(id, id, components.toList(), null, null)

    @Test fun textUsesOriginalUtf16OffsetsAcrossEquivalentChapterInstances() {
        val source = "〔P0037-S02〕中文😀é\nnext"
        val content = chapter(text(source))
        val anchor = ReaderContentAnchor(0, source.indexOf("😀"))
        val position = ReaderPosition.capture("book", content, anchor)!!
        val resolved = position.resolve("book", chapter(text(source)))!!
        assertEquals(anchor, resolved.anchor)
        assertTrue(resolved.exact)
        assertEquals(source.indexOf("😀"), position.offset)
    }

    @Test fun wrongBookChapterFingerprintAndOutOfRangeCoordinatesAreRejected() {
        val content = chapter(text("original"))
        val position = ReaderPosition.capture("book", content, ReaderContentAnchor(0, 3))!!
        assertNull(position.resolve("other-book", content))
        assertNull(position.resolve("book", chapter(text("original"), id = "other-chapter")))
        assertNull(position.resolve("book", chapter(text("modified"))))
        assertNull(position.copy(componentIndex = -1).resolve("book", content))
        assertNull(position.copy(componentIndex = 1).resolve("book", content))
        assertNull(position.copy(offset = -1).resolve("book", content))
        assertNull(position.copy(offset = "original".length).resolve("book", content))
        assertNull(position.copy(fingerprint = "stale").resolve("book", content))
    }

    @Test fun emptyContentAndEmptyTextHaveNoPretendExactPosition() {
        assertNull(ReaderPosition.capture("book", chapter(), ReaderContentAnchor(0, 0)))
        assertNull(ReaderPosition.capture("book", chapter(text("")), ReaderContentAnchor(0, 0)))
        assertNull(ReaderPosition.capture("", chapter(text("body")), ReaderContentAnchor(0, 0)))
        assertNull(ReaderPosition.capture("book", chapter(text("body")), ReaderContentAnchor(0, 4)))
    }

    @Test fun opaqueComponentsNeverReuseAnOldSplitPartAsAnExactPosition() {
        val component = mockk<AbstractContentComponent<AbstractContentComponentData>> {
            every { data } returns SimpleTextComponentData("opaque source payload")
        }
        val content = chapter(component)
        val position = ReaderPosition.capture("book", content, ReaderContentAnchor(0, 17))!!
        assertEquals(0, position.offset)
        val resolved = position.copy(offset = 17).resolve("book", content)!!
        assertEquals(ReaderContentAnchor(0, 0), resolved.anchor)
        assertFalse(resolved.exact)
    }
}
