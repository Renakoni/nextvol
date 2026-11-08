package indi.renakoni.nextvol.ui.book.detail

import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import org.junit.Assert.*
import org.junit.Test

class DetailDirectoryPagingTest {
    private fun volume(count: Int) = Volume(
        "volume", "Volume", (1..count).map { ChapterInformation("id-$it", "Chapter $it") },
    )

    @Test fun shortAndEmptyBooksKeepOnePage() {
        for (count in listOf(0, 100)) assertEquals(1, directoryPageCount(count))
        assertTrue(directoryPageChapters(directoryChapters(listOf(volume(0))), 0, false).isEmpty())
        assertTrue(directoryPageChapters(directoryChapters(listOf(volume(0))), 0, true).isEmpty())
        assertEquals(12, directoryPageChapters(directoryChapters(listOf(volume(12))), 0, false).size)
    }

    @Test fun descendingFillsPagesFromTheEndWithoutChangingCanonicalOrder() {
        val volume = volume(205)
        val original = volume.chapters.toList()
        val entries = directoryChapters(listOf(volume))
        assertEquals((205 downTo 106).map { "id-$it" },
            directoryPageChapters(entries, 0, true).map { it.chapter.id })
        assertEquals((105 downTo 6).map { "id-$it" },
            directoryPageChapters(entries, 1, true).map { it.chapter.id })
        assertEquals((5 downTo 1).map { "id-$it" },
            directoryPageChapters(entries, 2, true).map { it.chapter.id })
        assertEquals(original, volume.chapters)
        assertSame(volume.chapters[204], directoryPageChapters(entries, 0, true).first().chapter)
    }

    @Test fun chapterLocationUsesTheCurrentOrderAndMatchesPageBoundaries() {
        assertEquals(0, directoryPageForChapter(207, 99, false))
        assertEquals(1, directoryPageForChapter(207, 100, false))
        assertEquals(0, directoryPageForChapter(207, 107, true))
        assertEquals(1, directoryPageForChapter(207, 106, true))
        assertEquals(2, directoryPageForChapter(207, 0, true))
        assertEquals(0, directoryPageForChapter(0, -1, true))
        assertEquals(0, directoryPageForChapter(207, -1, true))
    }

    @Test fun changedCatalogClampsAnOldPageWithoutLosingItsLastChapter() {
        assertEquals("id-101", directoryPageChapters(directoryChapters(listOf(volume(101))), 9, false).single().chapter.id)
        assertEquals(0..99, directoryPageRange(201, -1))
        assertEquals("id-1", directoryPageChapters(directoryChapters(listOf(volume(101))), 9, true).single().chapter.id)
        assertEquals(200 downTo 101, directoryPageRange(201, -1, true))
    }

    @Test fun ninetyEightChapterVolumeFlowsIntoTheSameHundredChapterPage() {
        val first = volume(98)
        val second = Volume("second", "Second volume", (1..105).map { ChapterInformation("second-$it", "Chapter $it") })
        val third = Volume("third", "Third volume", (1..4).map { ChapterInformation("third-$it", "Chapter $it") })
        val entries = directoryChapters(listOf(first, second, third))
        assertEquals(207, entries.size)
        val firstPage = directoryPageChapters(entries, 0, false)
        assertEquals(100, firstPage.size)
        assertSame(first.chapters.last(), firstPage[97].chapter)
        assertSame(second.chapters.first(), firstPage[98].chapter)
        assertEquals("second-2", firstPage.last().chapter.id)
        assertEquals("second-3", directoryPageChapters(entries, 1, false).first().chapter.id)
        assertEquals("second-103", directoryPageChapters(entries, 2, false).first().chapter.id)
        assertEquals("third-4", directoryPageChapters(entries, 2, false).last().chapter.id)
        assertEquals(entries, (0 until 3).flatMap { directoryPageChapters(entries, it, false) })
        val descendingPages = (0 until 3).map { directoryPageChapters(entries, it, true) }
        assertEquals(listOf(100, 100, 7), descendingPages.map { it.size })
        assertEquals("third-4", descendingPages[0].first().chapter.id)
        assertEquals("second-10", descendingPages[0].last().chapter.id)
        assertEquals("second-9", descendingPages[1].first().chapter.id)
        assertEquals("id-8", descendingPages[1].last().chapter.id)
        assertEquals(entries.asReversed(), descendingPages.flatten())
    }

}
