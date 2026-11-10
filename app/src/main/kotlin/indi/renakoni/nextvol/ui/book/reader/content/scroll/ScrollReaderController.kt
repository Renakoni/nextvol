package indi.renakoni.nextvol.ui.book.reader.content.scroll

import androidx.compose.ui.unit.IntSize
import com.github.michaelbull.result.onOk
import indi.renakoni.nextvol.data.book.BookReadingDataAccess
import indi.renakoni.nextvol.ui.book.reader.content.ReaderChapterLoader
import indi.renakoni.nextvol.ui.book.reader.content.ReaderModeController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

/** Scroll-mode commands and settings; the chapter window and progress observer own their tasks. */
class ScrollReaderController(
    chapters: ReaderChapterLoader,
    readingData: BookReadingDataAccess,
    private val coroutineScope: CoroutineScope,
    private val settings: ContinuousScrollSettings,
    updateReadingProgress: (String, Float) -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : ReaderModeController {
    private var lazyColumnSize = IntSize(0, 0)
    private var positionNavigation: (String, String, Boolean) -> Unit = { _, _, _ -> }
    override fun observeNavigation(listener: (String, String, Boolean) -> Unit) { positionNavigation = listener }

    override val requestedChapterId: String?
        get() = uiState.readingChapterId

    override val uiState: MutableScrollContentUiSate = MutableScrollContentUiSate(
        loadPrevChapter = ::loadPrevChapter,
        loadNextChapter = ::loadNextChapter,
        changeChapter = ::changeChapter,
        setLazyColumnSize = {
            if (lazyColumnSize != IntSize.Zero && lazyColumnSize != it) progress.restoring(uiState.lazyListState)
            lazyColumnSize = it
        },
        writeProgressRightNow = ::writeProgressRightNow,
        retryChapter = { id ->
            if (uiState.readingChapterId == id) positionNavigation(uiState.bookId, id, true)
            chaptersWindow.retryChapter(id)
        },
        onProgressRestored = { progress.restored(it) },
        onProgressRestoring = { progress.restoring(it) },
        onReadingPositioned = { progress.readingPositioned(it) },
    )

    private val chaptersWindow = ScrollChapterWindow(
        uiState, chapters, readingData, settings, coroutineScope, { lazyColumnSize.height }, ioDispatcher,
    )
    private val progress = ScrollReadingProgress(
        uiState, coroutineScope, updateReadingProgress, { lazyColumnSize.height }, mainDispatcher,
    )

    init {
        coroutineScope.launch {
            settings.getFlow().distinctUntilChanged().collect {
                if (it) {
                    chaptersWindow.startContinuousObservation()
                    val hasAdjacentChapters = uiState.contentList.getOrNull(0) != null || uiState.contentList.getOrNull(2) != null
                    if (!hasAdjacentChapters) {
                        coroutineScope.launch(mainDispatcher) {
                            uiState.readingChapterId?.let { id -> reloadChapter(id) }
                        }
                    }
                } else {
                    chaptersWindow.stopContinuousObservation()
                    val hasAdjacentChapters = uiState.contentList.getOrNull(0) != null || uiState.contentList.getOrNull(2) != null
                    if (hasAdjacentChapters) {
                        coroutineScope.launch(mainDispatcher) {
                            uiState.readingChapterId?.let { id -> reloadChapter(id) }
                        }
                    }
                }
            }
        }
        progress.start()
    }

    override fun changeBookId(id: String) {
        chaptersWindow.changeBookId(id)
    }

    override fun updateNavigation(navigation: indi.renakoni.nextvol.data.book.ChapterNavigation) =
        chaptersWindow.updateNavigation(navigation)

    override fun loadNextChapter() {
        uiState.readingChapterContent?.onOk { readingChapterContent ->
            if (!readingChapterContent.hasNextChapter()) return
            val next = readingChapterContent.nextChapter ?: return
            positionNavigation(uiState.bookId, next, false)
            chaptersWindow.changeChapter(next, restoreProgress = false)
        }
    }

    override fun loadPrevChapter() {
        uiState.readingChapterContent?.onOk { readingChapterContent ->
            if (!readingChapterContent.hasPrevChapter()) return
            val previous = readingChapterContent.prevChapter ?: return
            positionNavigation(uiState.bookId, previous, false)
            chaptersWindow.changeChapter(previous, restoreProgress = false)
        }
    }

    override fun changeChapter(id: String) {
        if (id.isBlank()) return
        positionNavigation(uiState.bookId, id, false)
        chaptersWindow.changeChapter(id)
    }

    private fun reloadChapter(id: String) {
        positionNavigation(uiState.bookId, id, true)
        chaptersWindow.changeChapter(id)
    }

    private fun writeProgressRightNow() = progress.writeProgressRightNow()
    override fun flushProgress() = progress.writeProgressRightNow()
}
