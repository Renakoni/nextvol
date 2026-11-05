package indi.renakoni.nextvol.tts

import android.content.Context
import com.github.michaelbull.result.getOrElse
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.content.ComponentDataRegistry
import indi.renakoni.nextvol.data.download.BookDownloadScheduler
import indi.renakoni.nextvol.data.download.BookDownloadStatus
import indi.renakoni.nextvol.data.download.DownloadFailure
import indi.renakoni.nextvol.data.download.DownloadStage
import indi.renakoni.nextvol.data.download.DownloadSubmission
import indi.renakoni.nextvol.data.download.DownloadTaskStatus
import indi.renakoni.nextvol.data.download.downloadFailure
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.localbook.LocalBookStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterReader
import java.io.Reader
import java.util.UUID
import javax.inject.Inject

data class SpeechAnalysisBook(val bookId: String, val title: String, val cachedChapters: Int,
    val totalChapters: Int, val local: Boolean)

data class SpeechAnalysisBookText(val book: SpeechAnalysisBook, val reader: Reader)

class SpeechAnalysisDownloadException(val failure: DownloadFailure) : Exception(failure.name)

/** Reading cached text never refreshes a source or changes the reader's position. */
class SpeechAnalysisBookSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val local: LocalBookDataSource,
    private val localBooks: LocalBookStore,
    private val components: ComponentDataRegistry,
    private val books: BookRepository,
    private val scheduler: BookDownloadScheduler,
) {
    suspend fun inspect(bookId: String): SpeechAnalysisBook = withContext(Dispatchers.IO) {
        val (summary, chapters) = snapshot(bookId)
        var cached = 0
        for (chapter in chapters) {
            ensureActive()
            if (summary.local || local.isChapterContentExists(chapter)) cached++
        }
        summary.copy(cachedChapters = cached)
    }

    /** One temporary input preserves batch statistics across chapter boundaries without retaining the whole book. */
    suspend fun openCached(bookId: String): SpeechAnalysisBookText {
        var file: File? = null
        var result: SpeechAnalysisBookText? = null
        try {
            withContext(Dispatchers.IO) {
                val (summary, chapters) = snapshot(bookId)
                val book = BookIdentity.book(summary.bookId)
                val text = File.createTempFile("speech-analysis-", ".txt", context.cacheDir)
                file = text
                var included = 0
                text.bufferedWriter().use { output ->
                    for (chapterId in chapters) {
                        ensureActive()
                        val chapter = if (summary.local) localBooks.readChapter(BookIdentity.chapter(chapterId, book))
                            .getOrElse { throw SpeechException(SpeechError.SourceUnavailable) }
                        else local.getChapterContent(chapterId) ?: continue
                        val body = try { speechText(chapter.content, components) }
                        catch (failure: SpeechException) {
                            if (failure.error != SpeechError.EmptyText) throw failure
                            "" // An illustration-only chapter contributes no words.
                        }
                        output.append(body).append('\n')
                        included++
                    }
                }
                ensureActive()
                val reader = object : FilterReader(text.bufferedReader()) {
                    override fun close() { try { super.close() } finally { text.delete() } }
                }
                result = SpeechAnalysisBookText(summary.copy(cachedChapters = included), reader)
            }
            return checkNotNull(result)
        } catch (failure: Throwable) {
            try { result?.reader?.close() } finally { file?.delete() }
            throw failure
        }
    }

    private suspend fun snapshot(bookId: String): Pair<SpeechAnalysisBook, List<String>> {
        val book = BookIdentity.book(bookId)
        val imported = LocalBookStore.isLocal(book)
        val volumes = if (imported) localBooks.readVolumes(book)
            .getOrElse { throw SpeechException(SpeechError.SourceUnavailable) }
        else local.getBookVolumes(book.storageKey)
        val chapters = volumes?.volumes.orEmpty().flatMap { it.chapters }.map { it.id }.distinct()
        val information = local.getBookInformation(book.storageKey) ?: if (imported)
            localBooks.readInformation(book).getOrElse { throw SpeechException(SpeechError.SourceUnavailable) }
        else null
        val title = information?.title.orEmpty()
        return SpeechAnalysisBook(book.storageKey, title, 0, chapters.size, imported) to chapters
    }

    /** An active selected download finishes first; the next submission explicitly selects the whole directory. */
    suspend fun download(bookId: String, onProgress: (BookDownloadStatus) -> Unit): SpeechAnalysisBook =
        withContext(Dispatchers.IO) {
            val initial = inspect(bookId)
            if (initial.local || initial.totalChapters > 0 && initial.cachedChapters == initial.totalChapters)
                return@withContext initial
            val book = BookIdentity.book(initial.bookId)
            val volumes = books.downloadDirectory(book).getOrElse {
                throw SpeechAnalysisDownloadException(downloadFailure(it, DownloadStage.Directory))
            }
            val chapters = volumes.volumes.flatMap { it.chapters }.map { it.id }.distinct()
            if (chapters.isEmpty()) throw SpeechAnalysisDownloadException(DownloadFailure.SelectionUnavailable)
            var owned: UUID? = null
            try {
                var available = initial
                while (available.totalChapters == 0 || available.cachedChapters != available.totalChapters) {
                    ensureActive()
                    // Retain the acknowledgement even if cancellation arrives while WorkManager enqueues it.
                    val submission = withContext(NonCancellable) { books.submitDownload(book.storageKey, chapters) }
                    val accepted = when (submission) {
                        is DownloadSubmission.Accepted -> submission
                        is DownloadSubmission.Rejected -> throw SpeechAnalysisDownloadException(submission.failure)
                    }
                    owned = accepted.workId.takeUnless { accepted.existing }
                    ensureActive()
                    // The repository follows replacement workers after source verification.
                    val status = books.downloadStatusFlow(book.storageKey).first { progress ->
                        ensureActive()
                        onProgress(progress)
                        val task = progress.task
                        !task.active && task.status !in setOf(
                            DownloadTaskStatus.WaitingRetry, DownloadTaskStatus.WaitingVerification)
                    }
                    owned = null
                    if (status.task.status != DownloadTaskStatus.Complete)
                        throw SpeechAnalysisDownloadException(status.task.failure ?: DownloadFailure.SystemInterrupted)
                    available = inspect(book.storageKey)
                    if ((available.totalChapters == 0 || available.cachedChapters != available.totalChapters) &&
                        (!accepted.existing || accepted.selectionMatches))
                        throw SpeechAnalysisDownloadException(DownloadFailure.SelectionUnavailable)
                }
                available
            } catch (cancelled: CancellationException) {
                owned?.let { withContext(NonCancellable) { scheduler.dismiss(book, it.toString()) } }
                throw cancelled
            }
        }
}
