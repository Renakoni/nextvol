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
    @Test fun shortParagraphsSharePagesAndParagraphSpacingOnlyConsumesBoundaries() {
        val source = listOf(ReaderTextSource(0, "AA\nBB\nCC"))
        val compact = layout(source, height = 30, spacing = 0)
        val spaced = layout(source, height = 30, spacing = 5)
        assertEquals(1, compact.size)
        assertEquals(listOf(listOf("AA", "BB"), listOf("CC")), spaced.map { page -> page.map { it.text } })
        assertEquals(listOf(0, 5, 0), spaced.flatten().map { it.spacingBefore })
        assertEquals(compact.flatten().map { it.height }, spaced.flatten().map { it.height })
    }

    @Test fun longParagraphContinuesWithoutRepeatingParagraphSpacing() {
        val pages = layout(listOf(ReaderTextSource(0, "abcdefghijkl\nZ")), width = 3, height = 25, spacing = 9)
        assertEquals(listOf("abcdef", "ghijkl", "Z"), pages.map { it.single().text })
        assertTrue(pages.all { it.first().spacingBefore == 0 })
        assertEquals(listOf(0, 6, 13), pages.flatten().map { it.start })
    }

    @Test fun adjacentComponentsUseTheSameParagraphBoundaryRule() {
        val pages = layout(listOf(ReaderTextSource(3, "A"), ReaderTextSource(4, "B")), height = 25, spacing = 5)
        assertEquals(1, pages.size)
        assertEquals(listOf(0, 5), pages.single().map { it.spacingBefore })
        assertEquals(listOf(3, 4), pages.single().map { it.componentIndex })
    }

    @Test fun blankLinesArePreservedWithoutAnExtraLineAfterTerminalNewline() {
        val text = "\r\nA\r\n\nB\n"
        val pages = layout(listOf(ReaderTextSource(0, text)), height = 100, spacing = 3)
        assertEquals(listOf("", "A", "", "B"), pages.single().map { it.text })
        assertEquals(text, pages.flatten().joinToString("") { text.substring(it.start, it.end) })
    }

    @Test fun everyCharacterIsCoveredExactlyOnceAcrossWidthsHeightsAndSpacing() {
        val sources = listOf(ReaderTextSource(0, "\nA long paragraph with spaces.\n\nend\r\n"), ReaderTextSource(1, "Next block!"))
        for (width in listOf(-1, 0, 1, 3, 9, 80)) for (height in listOf(-1, 0, 1, 10, 21, 100)) for (spacing in listOf(0, 7, 64)) {
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

    @Test fun emptyTextHasNoPages() {
        assertTrue(layout(listOf(ReaderTextSource(0, ""))).isEmpty())
    }

    @Test fun scrollChunksKeepTheUnbrokenChapterHeightAndEveryParagraphGap() {
        val sources = listOf(ReaderTextSource(0, "ABCDEFGHIJK\n\nXYZ\nEND"))
        val original = layout(sources, width = 3, height = Int.MAX_VALUE, spacing = 7).flatten()
        val chunks = layout(sources, width = 3, height = 25, spacing = 7, continuous = true).flatten()
        assertEquals(original.sumOf { it.height + it.spacingBefore }, chunks.sumOf { it.height + it.spacingBefore })
        assertEquals(sources.single().text, chunks.joinToString("") { sources.single().text.substring(it.start, it.end) })
        assertTrue(chunks.all { it.height <= 25 })
    }

    @Test fun numericInputsRejectNonFiniteValuesAndClampToExistingControlRanges() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(22f, ReaderLayoutValues.fontSize(value))
            assertEquals(500f, ReaderLayoutValues.fontWeight(value))
            assertEquals(7f, ReaderLayoutValues.lineSpacing(value))
            assertEquals(0f, ReaderLayoutValues.paragraphSpacing(value))
            assertEquals(16f, ReaderLayoutValues.margin(value, 16f))
        }
        assertEquals(8f, ReaderLayoutValues.fontSize(-1f))
        assertEquals(64f, ReaderLayoutValues.fontSize(100f))
        assertEquals(900f, ReaderLayoutValues.fontWeight(1000f))
        assertEquals(0f, ReaderLayoutValues.paragraphSpacing(-1f))
        assertEquals(128f, ReaderLayoutValues.margin(1000f, 12f))
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
