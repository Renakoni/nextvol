package indi.renakoni.nextvol.ui.book.reader.content.flip

import androidx.compose.foundation.pager.PagerState
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.get
import com.github.michaelbull.result.onOk
import indi.renakoni.nextvol.data.book.BookReadingDataAccess
import indi.renakoni.nextvol.data.book.ChapterNavigation
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.ReaderChapterLoader
import indi.renakoni.nextvol.ui.book.reader.content.ReaderModeController
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

class FlipReaderController(
    private val chapters: ReaderChapterLoader,
    private val readingData: BookReadingDataAccess,
    val coroutineScope: CoroutineScope,
    val updateReadingProgress: (String, Float) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReaderModeController {
    private var latestRequestedChapterId: String? = null
    override val requestedChapterId: String? get() = latestRequestedChapterId
    private var positionNavigation: (String, String, Boolean) -> Unit = { _, _, _ -> }
    override fun observeNavigation(listener: (String, String, Boolean) -> Unit) { positionNavigation = listener }

    override val uiState: MutableFlipPageContentUiState = MutableFlipPageContentUiState(
        loadPrevChapter = { loadAdjacent(previous = true, entry = ChapterEntry.End) },
        loadNextChapter = ::loadNextChapter,
        changeChapter = ::changeChapter,
        updatePageState = ::updatePagerState,
        updateAnchoredPageState = { progress.updateAnchoredPageState(it) },
        updateSpeechPageState = { progress.updateSpeechPageState(it) },
        onProgressRestoring = { progress.restoring(it) },
        commitPendingChapter = ::commitPendingChapter,
        failPendingChapter = ::failPendingChapter,
        retryPendingChapter = {
            uiState.pendingChapter?.let { loadChapter(it.chapterId, it.entry) }
        },
        cancelPendingChapter = ::cancelPendingChapter,
    )
    private val progress = FlipReadingProgress(uiState, readingData, coroutineScope, updateReadingProgress, ioDispatcher)
    private class Request(val bookId: String, val chapterId: String, var committed: Boolean)
    @Volatile private var request: Request? = null
    private var chapterLoadJob: Job? = null
    private var preloadJob: Job? = null
    private val readerRequests = indi.renakoni.nextvol.data.book.ReaderChapterRequests(coroutineScope)
    private var navigation: ChapterNavigation? = null
    private val readingMetadataMutex = Mutex()

    init { progress.start() }

    private fun isCurrent(expected: Request) = request === expected && coroutineScope.isActive

    fun updatePagerState(pagerState: PagerState) = progress.updatePagerState(pagerState)
    override fun flushProgress() = progress.writeProgressRightNow()

    override fun changeBookId(id: String) {
        if (uiState.bookId == id) return
        request = null
        readerRequests.clear()
        chapterLoadJob?.cancel()
        preloadJob?.cancel()
        navigation = null
        latestRequestedChapterId = null
        uiState.pendingChapter = null
        progress.resetForChapter()
        uiState.bookId = id
        uiState.readingChapterId = null
        uiState.readingChapterContent = null
        uiState.pagerState = PagerState { 0 }
    }

    override fun loadNextChapter() = loadAdjacent(previous = false, entry = ChapterEntry.Start)
    override fun loadPrevChapter() = loadAdjacent(previous = true, entry = ChapterEntry.Start)

    override fun updateNavigation(navigation: ChapterNavigation) {
        if (indi.renakoni.nextvol.data.book.BookIdentity.bookKey(navigation.bookId) !=
            indi.renakoni.nextvol.data.book.BookIdentity.bookKey(uiState.bookId)) return
        this.navigation = navigation
        uiState.pendingChapter?.result?.get()?.updateNavigation(navigation)
        val content = uiState.readingChapterContent?.get() ?: return
        if (content.updateNavigation(navigation)) preload(content, uiState.bookId)
    }

    private fun loadAdjacent(previous: Boolean, entry: ChapterEntry) {
        val content = uiState.readingChapterContent?.get() ?: return
        val id = (if (previous) content.prevChapter else content.nextChapter) ?: return
        if (id == uiState.readingChapterId) return
        val pending = uiState.pendingChapter
        if (pending?.chapterId == id && pending.entry == entry && pending.result?.isErr != true) return
        // A staged boundary remains in the readable chapter until real pages commit.
        uiState.readingChapterId?.let { positionNavigation(uiState.bookId, it, true) }
        loadChapter(id, entry)
    }

    override fun changeChapter(id: String) {
        if (id.isBlank()) return
        positionNavigation(uiState.bookId, id, false)
        latestRequestedChapterId = id
        progress.resetForChapter()
        uiState.pendingChapter = null
        uiState.readingChapterId = id
        uiState.readingChapterContent = null
        uiState.pagerState = PagerState { 0 }
        loadChapter(id, ChapterEntry.Restore)
        progress.recoverForChapter(id, uiState.bookId)
    }

    private fun loadChapter(id: String, entry: ChapterEntry) {
        readerRequests.retain(uiState.bookId, listOf(id))
        chapterLoadJob?.cancel()
        preloadJob?.cancel()
        val expected = Request(uiState.bookId, id, committed = entry == ChapterEntry.Restore).also { request = it }
        if (!expected.committed) uiState.pendingChapter = FlipChapterTransition(id, entry)
        chapterLoadJob = coroutineScope.launch {
            chapters.load(id, expected.bookId, WebDataSourcePriority.High).flowOn(ioDispatcher + readerRequests).collect { result ->
                if (!isCurrent(expected)) return@collect
                result.onOk { it.updateNavigation(navigation) }
                if (!expected.committed) {
                    // Keep the displayed chapter and its progress until the UI has real pages.
                    uiState.pendingChapter = FlipChapterTransition(id, entry, result)
                } else {
                    uiState.readingChapterId = id
                    uiState.readingChapterContent = result
                    result.onOk { persistAndPreload(expected, it) }
                }
            }
        }
    }

    private fun commitPendingChapter(pending: FlipChapterTransition, pager: PagerState): Boolean {
        val expected = request ?: return false
        val content = pending.result?.get() ?: return false
        if (!isCurrent(expected) || uiState.pendingChapter !== pending || pager.pageCount == 0) return false
        expected.committed = true
        positionNavigation(expected.bookId, expected.chapterId, false)
        progress.resetForChapter()
        latestRequestedChapterId = expected.chapterId
        uiState.readingChapterId = expected.chapterId
        uiState.readingChapterContent = pending.result
        uiState.pendingChapter = null
        progress.updatePagerState(pager, anchored = true, publishInitial = true)
        coroutineScope.launch { persistAndPreload(expected, content) }
        return true
    }

    private fun failPendingChapter(pending: FlipChapterTransition, error: WebRequestError) {
        if (uiState.pendingChapter === pending) {
            uiState.pendingChapter = FlipChapterTransition(pending.chapterId, pending.entry, Err(error))
        }
    }

    private fun cancelPendingChapter() {
        if (uiState.pendingChapter == null) return
        request = null
        chapterLoadJob?.cancel()
        uiState.pendingChapter = null
        readerRequests.retain(uiState.bookId, listOfNotNull(uiState.readingChapterId))
        uiState.readingChapterId?.let { positionNavigation(uiState.bookId, it, true) }
    }

    private suspend fun persistAndPreload(expected: Request, content: ChapterContentUiState) {
        readingMetadataMutex.withLock {
            if (!isCurrent(expected)) return
            withContext(ioDispatcher) {
                readingData.updateUserReadingData(expected.bookId) { data ->
                    if (!isCurrent(expected)) data else data.copy(
                        lastReadTime = LocalDateTime.now(),
                        lastReadChapterId = expected.chapterId,
                        lastReadChapterTitle = content.title,
                    )
                }
            }
        }
        if (!isCurrent(expected)) return
        preload(content, expected.bookId)
    }

    private fun preload(content: ChapterContentUiState, bookId: String) {
        readerRequests.retain(bookId, listOfNotNull(uiState.readingChapterId, uiState.pendingChapter?.chapterId, content.nextChapter))
        preloadJob?.cancel()
        preloadJob = content.nextChapter?.let { next ->
            coroutineScope.launch(ioDispatcher + readerRequests) { chapters.preload(next, bookId) }
        }
    }
}
