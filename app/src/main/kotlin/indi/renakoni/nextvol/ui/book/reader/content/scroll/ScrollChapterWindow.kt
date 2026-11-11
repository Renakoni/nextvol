package indi.renakoni.nextvol.ui.book.reader.content.scroll

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import com.github.michaelbull.result.get
import com.github.michaelbull.result.onOk
import indi.renakoni.nextvol.data.book.BookReadingDataAccess
import indi.renakoni.nextvol.data.book.ChapterNavigation
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.ReaderChapterLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/** Publishes chapter slots on the mode scope; I/O never owns the mutable window. */
internal class ScrollChapterWindow(
    private val uiState: MutableScrollContentUiSate,
    private val chapters: ReaderChapterLoader,
    private val readingData: BookReadingDataAccess,
    private val settings: ContinuousScrollSettings,
    private val coroutineScope: CoroutineScope,
    private val viewportHeight: () -> Int,
    private val ioDispatcher: CoroutineDispatcher,
) {
    // Identity, rather than chapter equality, also distinguishes retries of the same chapter.
    private class Request(val bookId: String, val chapterId: String, val restoreProgress: Boolean = true)
    @Volatile private var request: Request? = null
    private var settingsJob: Job? = null
    private var observationJob: Job? = null
    private val slotJobs = arrayOfNulls<Job>(3)
    private val slotGenerations = LongArray(3)
    private val metadataMutex = Mutex()
    private var observationGeneration = 0L
    private var continuous = false
    private var navigation: ChapterNavigation? = null
    private var preloadJob: Job? = null
    private val readerRequests = indi.renakoni.nextvol.data.book.ReaderChapterRequests(coroutineScope)

    private fun isCurrent(expected: Request) = request === expected && coroutineScope.isActive

    private fun cancelSlot(index: Int) {
        slotGenerations[index]++
        slotJobs[index]?.cancel()
        slotJobs[index] = null
    }

    private fun invalidateRequest() {
        request = null
        settingsJob?.cancel()
        settingsJob = null
        preloadJob?.cancel()
        repeat(3, ::cancelSlot)
    }

    fun changeBookId(id: String) {
        if (uiState.bookId == id) return
        invalidateRequest()
        readerRequests.clear()
        navigation = null
        uiState.bookId = id
        uiState.readingChapterId = null
        uiState.readingProgress = 0f
        uiState.isRestoringProgress = false
        uiState.contentList.fill(null)
        uiState.lazyListState = LazyListState()
    }

    fun updateNavigation(navigation: ChapterNavigation) {
        if (indi.renakoni.nextvol.data.book.BookIdentity.bookKey(navigation.bookId) !=
            indi.renakoni.nextvol.data.book.BookIdentity.bookKey(uiState.bookId)) return
        this.navigation = navigation
        val content = uiState.readingChapterContent?.get()
        val changed = content?.updateNavigation(navigation) == true
        uiState.contentList.forEachIndexed { index, entry ->
            if (index != 1) entry?.second?.get()?.updateNavigation(navigation)
        }
        val expected = request ?: return
        if (changed) {
            if (continuous) replaceAdjacent(expected, content) else preload(expected, content)
        }
    }

    fun changeChapter(id: String, restoreProgress: Boolean = true) {
        if (id.isBlank()) return
        readerRequests.retain(uiState.bookId, listOf(id))
        invalidateRequest()
        val expected = Request(uiState.bookId, id, restoreProgress).also { request = it }
        // Keep the fixed-size window addressable throughout reset.
        uiState.contentList.fill(null)
        uiState.readingChapterId = id
        uiState.readingProgress = 0f
        uiState.isRestoringProgress = true
        uiState.lazyListState = LazyListState()
        val observation = observationGeneration
        settingsJob = coroutineScope.launch {
            val enabled = withContext(ioDispatcher) { settings.isEnabled() }
            if (!isCurrent(expected)) return@launch
            collectCurrent(expected, restoreProgress = restoreProgress, preload = true,
                observeAdjacent = enabled, observation = observation)
        }
    }

    fun retryChapter(id: String) {
        val expected = request ?: return
        if (id == expected.chapterId) {
            // Keep a manual chapter-start request until its position has actually been restored.
            changeChapter(id, restoreProgress = expected.restoreProgress || !uiState.isRestoringProgress)
            return
        }
        val content = uiState.readingChapterContent?.get() ?: return
        val index = when (id) {
            content.prevChapter -> 0
            content.nextChapter -> 2
            else -> return
        }
        if (continuous && uiState.contentList[index]?.first == id) {
            collectChapter(index, id, expected, interactive = true)
        }
    }

    fun startContinuousObservation() {
        continuous = true
        val observation = ++observationGeneration
        observationJob?.cancel()
        observationJob = coroutineScope.launch {
            snapshotFlow {
                if (uiState.isRestoringProgress) null else
                    uiState.lazyListState.layoutInfo.visibleItemsInfo.firstOrNull() to uiState.contentList.toList()
            }.collect { position ->
                val (item, _) = position ?: return@collect
                if (!continuous || observation != observationGeneration) return@collect
                val content = uiState.readingChapterContent?.get() ?: return@collect
                val index = when {
                    item == null -> return@collect
                    content.prevChapter != null && item.key == content.prevChapter &&
                        viewportHeight() > 0 && item.offset <= -viewportHeight() -> 0
                    content.nextChapter != null && item.key == content.nextChapter -> 2
                    else -> return@collect
                }
                // An error/loading neighbour cannot become the current reading chapter.
                promoteAdjacent(index, item, observation)
            }
        }
    }

    fun stopContinuousObservation() {
        continuous = false
        observationGeneration++
        observationJob?.cancel()
        cancelSlot(0)
        cancelSlot(2)
    }

    private fun promoteAdjacent(index: Int, item: LazyListItemInfo, observation: Long) {
        val adjacent = uiState.contentList[index] ?: return
        val content = adjacent.second.get() ?: return
        // A successful retry can arrive before the old error block has been remeasured.
        if (item.contentType != true) return
        val previous = uiState.contentList[1] ?: return
        readerRequests.retain(uiState.bookId, listOfNotNull(adjacent.first, previous.first, content.prevChapter, content.nextChapter))
        invalidateRequest()
        val expected = Request(uiState.bookId, adjacent.first).also { request = it }
        uiState.contentList.fill(null)
        uiState.contentList[2 - index] = previous
        uiState.contentList[1] = adjacent
        uiState.readingChapterId = expected.chapterId
        uiState.readingProgress = ((-item.offset + viewportHeight()).toFloat() / item.size.coerceAtLeast(1))
            .coerceIn(0f, 1f)
        collectChapter(2 - index, previous.first, expected)
        collectCurrent(expected, restoreProgress = false, preload = false,
            observeAdjacent = true, observation = observation)
        coroutineScope.launch { updateLastReadChapter(expected, content.title, restoreProgress = false) }
    }

    private fun collectCurrent(
        expected: Request,
        restoreProgress: Boolean,
        preload: Boolean,
        observeAdjacent: Boolean,
        observation: Long,
    ) {
        var restore = restoreProgress
        collectChapter(1, expected.chapterId, expected, beforePublish = { content ->
            // History must be ready before Compose can lay out and save this chapter.
            updateLastReadChapter(expected, content.title, restore)
            restore = false
        }) { content ->
            if (isCurrent(expected) && continuous && observeAdjacent && observation == observationGeneration) {
                // Adjacent collectors already preload without interaction and can publish fallback
                // content before revalidation. A separate preload would block that local read.
                replaceAdjacent(expected, content)
            } else if (preload) preload(expected, content)
        }
    }

    private fun preload(expected: Request, content: ChapterContentUiState) {
        readerRequests.retain(expected.bookId, listOfNotNull(expected.chapterId, content.nextChapter))
        preloadJob?.cancel()
        preloadJob = content.nextChapter?.let { next ->
            coroutineScope.launch(ioDispatcher + readerRequests) { chapters.preload(next, expected.bookId) }
        }
    }

    private fun replaceAdjacent(expected: Request, content: ChapterContentUiState) {
        val prev = content.prevChapter?.takeUnless { it == expected.chapterId || it == content.nextChapter }
        val next = content.nextChapter?.takeUnless { it == expected.chapterId || it == content.prevChapter }
        readerRequests.retain(expected.bookId, listOfNotNull(expected.chapterId, prev, next))
        cancelSlot(0)
        cancelSlot(2)
        // Clear both old identities before publishing either replacement.
        if (uiState.contentList[0]?.first != prev) uiState.contentList[0] = null
        if (uiState.contentList[2]?.first != next) uiState.contentList[2] = null
        prev?.let { collectChapter(0, it, expected) }
        next?.let { collectChapter(2, it, expected) }
    }

    private fun collectChapter(
        index: Int,
        chapterId: String,
        expected: Request,
        interactive: Boolean = index == 1,
        beforePublish: suspend (ChapterContentUiState) -> Unit = {},
        onLoaded: suspend (ChapterContentUiState) -> Unit = {},
    ) {
        cancelSlot(index)
        val generation = slotGenerations[index]
        val retained = uiState.contentList[index]?.takeIf { it.first == chapterId }?.second?.get()
        slotJobs[index] = coroutineScope.launch {
            chapters.load(chapterId, expected.bookId, interactive = interactive, retainedContent = retained)
                .flowOn(ioDispatcher + readerRequests).collect { result ->
                    if (!isCurrent(expected) || generation != slotGenerations[index]) return@collect
                    result.onOk { it.updateNavigation(navigation) }
                    result.onOk { beforePublish(it) }
                    if (!isCurrent(expected) || generation != slotGenerations[index]) return@collect
                    uiState.contentList[index] = chapterId to result
                    result.onOk { onLoaded(it) }
                }
        }
    }

    private suspend fun updateLastReadChapter(expected: Request, title: String?, restoreProgress: Boolean) {
        var recoveredProgress: Float? = null
        metadataMutex.withLock {
            if (!isCurrent(expected)) return
            withContext(ioDispatcher) {
                readingData.updateUserReadingData(expected.bookId) { data ->
                    if (!isCurrent(expected)) data else {
                        if (restoreProgress) recoveredProgress = data.currentChapterReadingProgressMap[expected.chapterId] ?: 0f
                        data.copy(
                            lastReadTime = LocalDateTime.now(),
                            lastReadChapterId = expected.chapterId,
                            lastReadChapterTitle = title ?: data.lastReadChapterTitle,
                        )
                    }
                }
            }
        }
        if (isCurrent(expected)) recoveredProgress?.let { uiState.readingProgress = it }
    }
}
