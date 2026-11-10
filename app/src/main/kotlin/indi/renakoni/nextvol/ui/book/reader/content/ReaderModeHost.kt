package indi.renakoni.nextvol.ui.book.reader.content

enum class ReaderMode { Scroll, Flip }

/**
 * Owns the selected controller and binds it before the reader publishes its UI state.
 * Replacing or closing the host closes the previous controller and its owned tasks.
 */
internal class ReaderModeHost(
    private val positions: ReaderPositionSession? = null,
    private val createController: (ReaderMode) -> ReaderModeController,
) {
    private var selectedMode: ReaderMode? = null
    private var controller: ReaderModeController? = null
    private var navigation: indi.renakoni.nextvol.data.book.ChapterNavigation? = null
    private var transitionRequestedChapterId: String? = null
    val uiState: ContentUiState? get() = controller?.uiState

    fun select(mode: ReaderMode, currentBookId: () -> String, currentChapterId: () -> String): Boolean {
        if (selectedMode == mode) return false
        val previousController = controller
        val checkpoint = positions?.captureNow()
        transitionRequestedChapterId = controller?.requestedChapterId
        positions?.deactivate()
        controller = createController(mode).also { next ->
            next.observeNavigation { book, chapter, preserve ->
                positions?.navigate(next.uiState, book, chapter, preserve)
            }
        }
        selectedMode = mode
        try {
            previousController?.close()
            val book = currentBookId()
            controller?.changeBookId(book)
            navigation?.takeIf { it.bookId == book }?.let { controller?.updateNavigation(it) }
            val chapter = currentChapterId()
            controller?.changeChapter(chapter)
            controller?.let { positions?.activate(it.uiState, book, chapter, checkpoint) }
        } finally {
            transitionRequestedChapterId = null
        }
        return true
    }

    fun close() {
        positions?.captureNow()
        positions?.deactivate()
        controller?.close()
        controller = null
        selectedMode = null
    }

    fun changeBookId(id: String) {
        if (navigation?.bookId != id) navigation = null
        controller?.changeBookId(id)
    }
    fun updateNavigation(volumes: io.nightfish.lightnovelreader.api.book.BookVolumes) {
        val updated = indi.renakoni.nextvol.data.book.ChapterNavigation(volumes)
        navigation = updated
        controller?.updateNavigation(updated)
    }
    fun changeChapter(id: String) = controller?.changeChapter(id)

    val requestedChapterId: String?
        get() = transitionRequestedChapterId ?: controller?.requestedChapterId
    fun loadNextChapter() = controller?.loadNextChapter()
    fun loadPrevChapter() = controller?.loadPrevChapter()
    fun flushProgress() = controller?.flushProgress()
}
