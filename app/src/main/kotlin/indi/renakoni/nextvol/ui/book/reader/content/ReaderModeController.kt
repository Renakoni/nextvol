package indi.renakoni.nextvol.ui.book.reader.content

/**
 * Commands shared by reading modes. Implementations borrow the reader's coroutine scope;
 * selecting another mode does not end that scope or flush progress.
 */
interface ReaderModeController {
    /** Most recent chapter selected by this controller, including internal next/previous moves. */
    val requestedChapterId: String?
        get() = null

    val uiState: ContentUiState
    fun changeBookId(id: String)
    fun loadNextChapter()
    fun loadPrevChapter()
    fun changeChapter(id: String)
    fun updateNavigation(navigation: indi.renakoni.nextvol.data.book.ChapterNavigation) = Unit

    /** Flush only a position authorized by this mode, never its display-only progress. */
    fun flushProgress() = Unit

    /** The host owns source-position recovery; mode reloads may preserve the current anchor. */
    fun observeNavigation(listener: (bookId: String, chapterId: String, preservePosition: Boolean) -> Unit) = Unit

    /** Stops work owned by this mode when the reader selects another controller. */
    fun close() = Unit
}
