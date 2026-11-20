package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.ui.text.TextLayoutResult
import indi.renakoni.nextvol.ui.book.reader.content.componet.ReaderTextSource
import indi.renakoni.nextvol.ui.book.reader.content.componet.layoutReaderText
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.max

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [27], application = android.app.Application::class)
class ReaderTextLayoutTest {
    @Test fun blankLinesArePreservedWithoutAnExtraLineAfterTerminalNewline() {
        val text = "\r\nA\r\n\nB\n"
        val pages = layout(listOf(ReaderTextSource(0, text)), height = 100, spacing = 3)
        assertEquals(listOf("", "A", "", "B"), pages.single().map { it.text })
        assertEquals(text, pages.flatten().joinToString("") { text.substring(it.start, it.end) })
    }

    @Test fun everyCharacterIsCoveredExactlyOnceAcrossWidthsHeightsAndSpacing() {
        val sources = listOf(ReaderTextSource(0, "\nA long paragraph with spaces.\n\nend\r\n"), ReaderTextSource(1, "Next block!"))
        for ((width, height, spacing) in listOf(
            Triple(-1, 100, 0), Triple(80, -1, 0), Triple(0, 0, 7),
            Triple(1, 1, 0), Triple(3, 10, 0), Triple(9, 21, 7), Triple(80, 100, 64),
        )) {
            val pages = layout(sources, width, height, spacing)
            assertTrue(pages.all { it.isNotEmpty() && it.first().spacingBefore == 0 })
            pages.forEach { page ->
                assertTrue(page.sumOf { it.height + it.spacingBefore } <= max(1, height) || page.size == 1)
            }
            sources.forEach { source ->
                val fragments = pages.flatten().filter { it.componentIndex == source.componentIndex }
                assertEquals(source.text, fragments.joinToString("") { source.text.substring(it.start, it.end) })
                fragments.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
            }
        }
    }

    @Test fun scrollChunksKeepTheUnbrokenChapterHeightAndEveryParagraphGap() {
        val sources = listOf(ReaderTextSource(0, "ABCDEFGHIJK\n\nXYZ\nEND"))
        val original = layout(sources, width = 3, height = Int.MAX_VALUE, spacing = 7).flatten()
        val chunks = layout(sources, width = 3, height = 25, spacing = 7, continuous = true).flatten()
        assertEquals(original.sumOf { it.height + it.spacingBefore }, chunks.sumOf { it.height + it.spacingBefore })
        assertEquals(sources.single().text, chunks.joinToString("") { sources.single().text.substring(it.start, it.end) })
        assertTrue(chunks.all { it.height <= 25 })
    }

    private fun layout(sources: List<ReaderTextSource>, width: Int = 100, height: Int = 100, spacing: Int = 0, continuous: Boolean = false) =
        layoutReaderText(sources, width, height, spacing, continuous) { text, columns ->
            val count = max(1, (text.length + columns - 1) / columns)
            mockk<TextLayoutResult> {
                every { lineCount } returns count
                every { getLineStart(any()) } answers { minOf(firstArg<Int>() * columns, text.length) }
                every { getLineTop(any()) } answers { firstArg<Int>() * 10f }
                every { getLineBottom(any()) } answers { (firstArg<Int>() + 1) * 10f }
            }
        }
}
