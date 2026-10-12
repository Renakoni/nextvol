package indi.renakoni.nextvol.ui.book.reader.content

import indi.renakoni.nextvol.ui.book.reader.content.componet.SimpleTextComponent
import indi.renakoni.nextvol.ui.book.reader.content.flip.ReaderContentAnchor

/** Session coordinates refer to source content, never a page, split part or pixel. */
internal data class ReaderPosition(
    val bookId: String,
    val chapterId: String,
    val componentIndex: Int,
    val offset: Int,
    val fingerprint: String,
) {
    fun resolve(book: String, chapter: ChapterContentUiState): ResolvedReaderPosition? {
        if (bookId.isBlank() || chapterId.isBlank() || bookId != book || chapterId != chapter.id ||
            fingerprint != chapter.bookmarkFingerprint || offset < 0) return null
        val component = chapter.content.getOrNull(componentIndex) ?: return null
        return if (component is SimpleTextComponent) {
            if (offset !in component.data.text.indices) null
            else ResolvedReaderPosition(ReaderContentAnchor(componentIndex, offset), exact = true)
        } else {
            // An opaque component's old split index is not a coordinate in a new layout.
            ResolvedReaderPosition(ReaderContentAnchor(componentIndex, 0), exact = false)
        }
    }

    companion object {
        fun capture(book: String, chapter: ChapterContentUiState, anchor: ReaderContentAnchor): ReaderPosition? {
            if (book.isBlank() || chapter.id.isBlank()) return null
            val component = chapter.content.getOrNull(anchor.componentIndex) ?: return null
            val offset = if (component is SimpleTextComponent) {
                anchor.offset.takeIf { it in component.data.text.indices } ?: return null
            } else 0
            return ReaderPosition(book, chapter.id, anchor.componentIndex, offset, chapter.bookmarkFingerprint)
        }
    }
}

internal data class ResolvedReaderPosition(val anchor: ReaderContentAnchor, val exact: Boolean)
