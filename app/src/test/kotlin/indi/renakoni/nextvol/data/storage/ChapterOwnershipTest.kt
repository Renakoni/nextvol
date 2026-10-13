package indi.renakoni.nextvol.data.storage

import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.SourceChapterId
import io.nightfish.lightnovelreader.api.identifier.Identifier
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterOwnershipTest {
    @Test fun downloadAttributionOverridesTheCatalogWithoutCombiningSources() {
        val book = SourceBookId(Identifier("rules", "a"), "book")
        val other = book.copy(sourceId = Identifier("rules", "b"))
        val chapter = SourceChapterId(book, "chapter").storageKey
        val otherChapter = SourceChapterId(other, "chapter").storageKey
        val earlierCatalog = book.copy(remoteId = "earlier").storageKey
        val latestCatalog = book.copy(remoteId = "latest").storageKey
        val owners = chapterOwners(
            catalogChapters = sequenceOf(chapter to earlierCatalog, chapter to latestCatalog,
                otherChapter to other.storageKey),
            downloadedChapters = sequenceOf(chapter to book.storageKey),
        )
        assertEquals(mapOf(chapter to book.storageKey, otherChapter to other.storageKey), owners)
    }
}
