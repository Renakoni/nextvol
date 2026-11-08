package indi.renakoni.nextvol.ui.book.reader

import android.util.Log
import indi.renakoni.nextvol.data.book.bookReadingProgress
import indi.renakoni.nextvol.data.reading.ReaderRecordStore
import indi.renakoni.nextvol.data.statistics.ReadingStatsUpdate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime

/** Records reader events; the owner supplies the existing scopes and live progress inputs. */
internal class ReaderReadingRecords(
    private val store: ReaderRecordStore,
    private val scope: CoroutineScope,
    private val statisticsScope: CoroutineScope,
    private val currentBookId: () -> String,
    private val currentChapterTitle: () -> String?,
    private val catalogChapterIds: suspend (String) -> Set<String>?,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // Capture once per reader entry, including callbacks first delivered after a reset.
    private val progressRevision = store.progressRevision()
    private val totalReadingTimeMutex = Mutex()
    private val progressLock = Any()
    private var progressJob: Job? = null
    private var pendingProgress: PendingProgress? = null
    private val accumulatedReadingTimeLock = Any()
    private var accumulatedReadingTimeJob: Job? = null

    private class PendingProgress(
        val bookId: String,
        val chapterId: String,
        var title: String,
        var progress: Float,
        var peak: Float,
        var isCurrentChapter: () -> Boolean,
    )

    fun openBook(bookId: String) {
        scope.launch(ioDispatcher) {
            store.updateRecentBooks {
                val newList = it.toMutableList()
                if (it.contains(bookId))
                    newList.remove(bookId)
                newList.add(bookId)
                newList
            }
        }
        scope.launch(ioDispatcher) {
            store.updateReadingStatistics(ReadingStatsUpdate(bookId = bookId, readEventDelta = 1))
        }
    }

    fun saveProgress(chapterId: String, progress: Float, isCurrentChapter: () -> Boolean = { true }) {
        val bookId = currentBookId()
        if (progress.isNaN() || progress <= 0f || bookId.isBlank()) return
        val title = currentChapterTitle() ?: return
        synchronized(progressLock) {
            pendingProgress?.takeIf { it.bookId == bookId && it.chapterId == chapterId }?.let {
                it.title = title
                it.progress = progress
                it.peak = maxOf(it.peak, progress)
                it.isCurrentChapter = isCurrentChapter
                return
            }
            val event = PendingProgress(bookId, chapterId, title, progress, progress, isCurrentChapter)
            pendingProgress = event
            val previous = progressJob
            progressJob = scope.launch(ioDispatcher) {
                // Bind the order at the event source, before Dispatchers.IO can reorder launches.
                previous?.join()
                synchronized(progressLock) {
                    // Only waiting consecutive events can merge. Once this write starts its
                    // captured values stay fixed, including when another chapter is queued.
                    if (pendingProgress === event) pendingProgress = null
                }
                val currentTime = now()
                // Resolve the catalog after the event has been queued and bind it to the
                // captured book. The UI's current-book state may have changed by now.
                val catalog = try {
                    catalogChapterIds(bookId)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }

                var finished = false
                val saved = store.updateChapterProgress(bookId, chapterId, progressRevision) { userReadingData ->
                    Log.v("ReaderViewModel", "$bookId/$chapterId Saving progress ${event.progress}. (${event.title})")
                    val updatedData = userReadingData.copy(
                        currentChapterReadingProgressMap = userReadingData.currentChapterReadingProgressMap +
                            (chapterId to event.progress),
                        maxChapterReadingProgressMap = userReadingData.maxChapterReadingProgressMap +
                            (chapterId to event.peak.coerceAtLeast(userReadingData.maxChapterReadingProgressMap[chapterId] ?: 0f)),
                    )
                    val readingProgress = catalog?.let {
                        bookReadingProgress(it, updatedData.maxChapterReadingProgressMap)
                    } ?: userReadingData.readingProgress
                    // A queued save may still update its chapter's history after navigation,
                    // but must not replace the newer resume chapter and title.
                    finished = !catalog.isNullOrEmpty() && readingProgress >= 1f
                    val current = event.isCurrentChapter()
                    updatedData.copy(
                        lastReadTime = if (current) currentTime else userReadingData.lastReadTime,
                        lastReadChapterId = if (current) chapterId else userReadingData.lastReadChapterId,
                        lastReadChapterTitle = if (current) event.title else userReadingData.lastReadChapterTitle,
                        readingProgress = readingProgress,
                    )
                }
                if (saved && finished) {
                    store.markBookFinished(bookId)
                }
            }
        }
    }

    suspend fun awaitProgress() { progressJob?.join() }

    fun updateTotalReadingTime(bookId: String, seconds: Int) {
        scope.launch(ioDispatcher) {
            totalReadingTimeMutex.withLock {
                store.updateUserReadingData(bookId) {
                    it.copy(lastReadTime = now(), totalReadTime = it.totalReadTime + seconds)
                }
            }
        }
    }

    fun accumulateReadingTime(bookId: String, seconds: Int) {
        if (bookId.isBlank()) return
        synchronized(accumulatedReadingTimeLock) {
            val previous = accumulatedReadingTimeJob
            accumulatedReadingTimeJob = statisticsScope.launch(ioDispatcher) {
                previous?.join()
                // Negative values remain the statistics repository's existing flush command.
                store.accumulateBookReadTime(bookId, seconds)
            }
        }
    }
}
