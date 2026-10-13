package indi.renakoni.nextvol.data.book

import indi.renakoni.nextvol.data.local.LocalBookDataSource
import io.nightfish.lightnovelreader.api.book.UserReadingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookReadingDataRepository @Inject constructor(
    private val localBookDataSource: LocalBookDataSource,
) : BookReadingDataAccess {
    private val progressMutex = Mutex()
    private val revision = AtomicLong()
    // Source-qualified chapter keys keep resets independent across books and sources.
    private val chapterResetRevisions = mutableMapOf<String, Long>()

    override fun progressRevision(): Long = revision.get()

    override suspend fun updateChapterProgress(
        bookId: String, chapterId: String, revision: Long,
        update: (UserReadingData) -> UserReadingData,
    ): Boolean = progressMutex.withLock {
        val chapter = BookIdentity.chapter(chapterId, BookIdentity.book(bookId))
        if ((chapterResetRevisions[chapter.storageKey] ?: 0L) > revision) return@withLock false
        if (revision >= this.revision.get()) {
            localBookDataSource.updateUserReadingData(bookId, update)
            return@withLock true
        }
        localBookDataSource.aliases.withResolved(chapter.book) { canonical ->
            val reset = chapterResetRevisions.any { (key, resetRevision) ->
                if (resetRevision <= revision) return@any false
                val saved = SourceChapterId.fromStorageKey(key)
                saved.remoteId == chapter.remoteId &&
                    saved.book.sourceId == canonical.sourceId && localBookDataSource.aliases.resolve(saved.book) == canonical
            }
            if (reset) false else {
                localBookDataSource.updateUserReadingData(bookId, update)
                true
            }
        }
    }

    suspend fun markChaptersUnread(bookId: String, chapterIds: Set<String>, catalogIds: Set<String>) {
        val book = BookIdentity.book(bookId)
        val catalog = catalogIds.mapTo(mutableSetOf()) { BookIdentity.chapter(it, book).storageKey }
        val selected = chapterIds.mapTo(mutableSetOf()) { BookIdentity.chapter(it, book).storageKey }
            .intersect(catalog)
        if (selected.isEmpty()) return
        progressMutex.withLock {
            // A page recreation must not interrupt publication after SQLite commits.
            withContext(NonCancellable) {
                localBookDataSource.updateUserReadingData(book.storageKey) { data ->
                    val maximum = data.maxChapterReadingProgressMap - selected
                    data.copy(
                        currentChapterReadingProgressMap = data.currentChapterReadingProgressMap - selected,
                        maxChapterReadingProgressMap = maximum,
                        readingProgress = bookReadingProgress(catalog, maximum),
                    )
                }
                // Only selected chapters reject old queued saves; unrelated resume updates remain valid.
                val nextRevision = revision.get() + 1
                selected.forEach { chapterResetRevisions[it] = nextRevision }
                revision.set(nextRevision)
            }
        }
    }

    override suspend fun getUserReadingData(bookId: String): UserReadingData =
        localBookDataSource.getUserReadingData(bookId)

    suspend fun getUserReadingData(book: SourceBookId): UserReadingData =
        localBookDataSource.getUserReadingData(book.storageKey)

    fun getUserReadingDataFlow(bookId: String): Flow<UserReadingData> =
        localBookDataSource.getUserReadingDataFlow(bookId)

    suspend fun getAllUserReadingData(): List<UserReadingData> =
        localBookDataSource.getAllUserReadingData()

    override suspend fun updateUserReadingData(id: String, update: (UserReadingData) -> UserReadingData) {
        localBookDataSource.updateUserReadingData(id, update)
    }

    suspend fun updateUserReadingData(book: SourceBookId, update: (UserReadingData) -> UserReadingData) {
        localBookDataSource.updateUserReadingData(book.storageKey, update)
    }
}
