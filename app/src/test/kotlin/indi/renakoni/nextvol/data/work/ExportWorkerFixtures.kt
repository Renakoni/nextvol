package indi.renakoni.nextvol.data.work

import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.download.BookDownloadStore
import io.mockk.*
import kotlinx.coroutines.flow.first

/** Unit fixtures leave persistence to BookDownloadTest, which uses real Room and source images. */
internal fun exportDownloads(): BookDownloadStore = mockk<BookDownloadStore>(relaxed = true).also { store ->
    val checkpoints = mutableMapOf<Pair<String, String>, BookDownloadStore.ChapterCheckpoint>()
    coEvery { store.withBookOperation<Any?>(any(), any()) } coAnswers { secondArg<suspend () -> Any?>().invoke() }
    coEvery { store.begin(any(), any(), any()) } answers { BookDownloadStore.Attempt(firstArg(), secondArg(), thirdArg()) }
    coEvery { store.reusable(any(), any(), any()) } returns null
    coEvery { store.checkpoint(any(), any(), any()) } answers { checkpoints[secondArg<String>() to thirdArg<String>()] }
    coEvery { store.saveCandidate(any(), any(), any()) } answers {
        val content = secondArg<io.nightfish.lightnovelreader.api.book.ChapterContent>()
        val signature = thirdArg<String>()
        BookDownloadStore.ChapterCheckpoint(content, signature, emptyList(), "").also {
            checkpoints[content.id to signature] = it
        }
    }
    coEvery { store.hasVersionedChapter(any(), any()) } returns false
    coEvery { store.checkpointImage(any(), any(), any()) } returns null
    coEvery { store.revision(any()) } returns null
}

internal fun stubExportRepository(repository: BookRepository) {
    coEvery { repository.canonicalBook(any()) } answers { firstArg() }
    every { repository.sourceRevision(any()) } returns "1"
    coEvery { repository.exportInformation(any()) } coAnswers {
        repository.getBookInformationFlow(firstArg<indi.renakoni.nextvol.data.book.SourceBookId>().storageKey).first()
    }
    coEvery { repository.exportVolumes(any()) } coAnswers {
        repository.getBookVolumesFlow(firstArg<indi.renakoni.nextvol.data.book.SourceBookId>().storageKey).first()
    }
    coEvery { repository.exportChapter(any(), any()) } coAnswers {
        repository.getChapterContentFlow(secondArg(), firstArg<indi.renakoni.nextvol.data.book.SourceBookId>().storageKey).first()
    }
    coEvery { repository.exportContent(any(), any()) } answers { secondArg() }
    every { repository.exportMetadata(any()) } answers { firstArg() }
    every { repository.exportCatalog(any()) } answers { firstArg() }
}
