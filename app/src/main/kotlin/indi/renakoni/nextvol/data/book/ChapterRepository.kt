package indi.renakoni.nextvol.data.book

import android.util.Log
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.map
import com.github.michaelbull.result.mapError
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.renakoni.nextvol.BuildConfig
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.localbook.LocalBookStore
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.text.TextProcessingRepository
import indi.renakoni.nextvol.data.web.WebSourceRegistry
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChapterRepository @Inject constructor(
    private val sourceRegistry: WebSourceRegistry,
    private val localBookDataSource: LocalBookDataSource,
    private val textProcessingRepository: TextProcessingRepository,
    private val localBooks: LocalBookStore,
    private val downloads: BookDownloadStore,
) : ChapterSource {
    companion object {
        private const val TAG = "BookRepository"
    }


    override fun getBookVolumesFlow(
        id: String,
        priority: WebDataSourcePriority
    ): Flow<Result<BookVolumes, WebRequestError>> = flow {
        val book = BookIdentity.book(id)
        if (LocalBookStore.isLocal(book)) {
            emit(localBooks.readVolumes(book))
            return@flow
        }
        val local = localBookDataSource.getBookVolumes(book.storageKey)?.takeIf { it.volumes.any { volume -> volume.chapters.isNotEmpty() } }
        local?.also {
            emit(Ok(it))
            if (BuildConfig.BENCHMARK) return@flow
        }
        refreshBookVolumes(book, priority).onErr {
                Log.e(TAG, "Source request failed for ${book.fileKey}: ${it.kind}")
            }
            .also {
                if (it.isOk || local == null) emit(it)
            }
    }.map { result ->
        result.map {
            textProcessingRepository.processBookVolumes { it }
        }.mapError { error -> error.mapAvailableVolumes { volumes -> textProcessingRepository.processBookVolumes { volumes } } }
    }

    internal suspend fun refreshBookVolumes(book: SourceBookId, priority: WebDataSourcePriority, fresh: Boolean = false,
        expectedRuntime: indi.renakoni.nextvol.data.web.SourceRuntime? = null, reuseRecent: Boolean = false): Result<BookVolumes, WebRequestError> {
        val requested = localBookDataSource.aliases.resolve(book)
        return sourceRegistry.request(requested, expectedRuntime) { runtime -> runtime.execute {
            runtime.getBookVolumes(requested.remoteId, priority, refresh = fresh, reuseRecent = reuseRecent).mapError { error ->
                error.mapAvailableVolumes { requested.bind(it).rebind(requested, book) }
            }.andThen { remote ->
                runtime.persistCanonicalBook(requested, localBookDataSource, downloads).map { canonical ->
                    val volumes = requested.bind(remote)
                    if (canonical == requested && (!fresh || volumes.volumes.any { it.chapters.isNotEmpty() }))
                        localBookDataSource.updateBookVolumes(volumes)
                    volumes.rebind(requested, book)
                }
            }
        } }
    }

    override fun getChapterContentFlow(
        chapterId: String,
        bookId: String,
        priority: WebDataSourcePriority
    ): Flow<Result<ChapterContent, WebRequestError>> = flow {
        val chapter = BookIdentity.chapter(chapterId, BookIdentity.book(bookId))
        if (LocalBookStore.isLocal(chapter.book)) {
            emit(localBooks.readChapter(chapter))
            return@flow
        }
        reusableChapter(chapter)?.let {
            emit(Ok(it))
            return@flow
        }
        val local = localBookDataSource.getChapterContent(chapter.storageKey)
        local?.also {
            emit(Ok(it))
            if (BuildConfig.BENCHMARK) return@flow
        }
        refreshChapter(chapter, priority, fresh = local != null).onErr {
                Log.e(TAG, "Source request failed for ${chapter.book.fileKey}: ${it.kind}")
            }
            .also {
                if (it.isOk || local == null) emit(it)
            }
    }.map { result ->
        result.map {
            textProcessingRepository.processChapterContent(BookIdentity.bookKey(bookId)) { it }
        }
    }

    override suspend fun preloadChapterContent(
        chapterId: String,
        bookId: String,
        priority: WebDataSourcePriority
    ) {
        val chapter = BookIdentity.chapter(chapterId, BookIdentity.book(bookId))
        if (LocalBookStore.isLocal(chapter.book)) return
        if (reusableChapter(chapter) != null) return
        refreshChapter(chapter, priority).onErr {
                Log.e(TAG, "Source request failed for ${chapter.book.fileKey}: ${it.kind}")
            }
    }

    private suspend fun reusableChapter(chapter: SourceChapterId): ChapterContent? {
        val revision = sourceRegistry.sources.value.firstOrNull { it.metadata.id == chapter.book.sourceId }?.metadata?.revision.orEmpty()
        return localBookDataSource.getReusableChapterContent(chapter.storageKey, revision)
    }

    internal suspend fun refreshChapter(chapter: SourceChapterId, priority: WebDataSourcePriority, fresh: Boolean = false,
        expectedRuntime: indi.renakoni.nextvol.data.web.SourceRuntime? = null): Result<ChapterContent, WebRequestError> {
        val requested = localBookDataSource.aliases.resolve(chapter.book)
        return sourceRegistry.request(requested, expectedRuntime) { runtime -> runtime.execute {
            runtime.getChapterContent(chapter.remoteId, requested.remoteId, priority, refresh = fresh).andThen { remote ->
                runtime.persistCanonicalBook(requested, localBookDataSource, downloads).map {
                    chapter.bind(remote).also {
                        val revision = if (downloads.isReusableReadingContent(it)) runtime.metadata.revision else ""
                        localBookDataSource.updateChapterContent(it, revision)
                    }
                }
            }
        } }
    }
}
