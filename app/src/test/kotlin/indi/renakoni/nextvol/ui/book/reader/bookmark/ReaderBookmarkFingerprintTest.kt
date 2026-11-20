package indi.renakoni.nextvol.ui.book.reader.bookmark

import android.app.Application
import android.net.Uri
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.SourceChapterId
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.componet.ImageComponent
import indi.renakoni.nextvol.ui.book.reader.content.componet.SimpleTextComponent
import indi.renakoni.nextvol.ui.book.reader.content.flip.ReaderContentAnchor
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.identifier.Identifier
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ReaderBookmarkFingerprintTest {
    private val book = SourceBookId(Identifier("fixture", "source"), "book")

    @Test fun fingerprintDetectsTextAndImageChangesButSurvivesInstallationPaths() {
        fun chapter(text: String, image: String) = ChapterContentUiState(SourceChapterId(book, "1").storageKey,
            "Chapter", listOf(SimpleTextComponent(SimpleTextComponentData(text), mockk(relaxed = true), RuntimeEnvironment.getApplication()),
                ImageComponent(ImageComponentData(Uri.parse(image)))), null, null)
        val original = chapter("A recognizable passage", "file:///old/local-books/book/assets/image.png")
        val saved = ReaderBookmarkPosition(book.storageKey, original, ReaderContentAnchor(0, 2), .4f).bookmark()
        assertEquals(ReaderContentAnchor(0, 2), saved.anchorIn(chapter("A recognizable passage", "file:///new/local-books/book/assets/image.png")))
        assertNull(saved.anchorIn(chapter("Changed text", "file:///new/local-books/book/assets/image.png")))
        assertNull(saved.anchorIn(chapter("A recognizable passage", "file:///new/local-books/book/assets/other.png")))
    }
}
