package indi.renakoni.nextvol.ui.bookmanager

import androidx.annotation.StringRes
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.storage.BookClearTarget

enum class LocalBookClearTarget(
    @param:StringRes val label: Int,
    val target: BookClearTarget,
) {
    VolumeAndChapterIndex(R.string.local_book_clear_index, BookClearTarget.VolumeAndChapterIndex),
    ChapterContent(R.string.local_book_clear_content, BookClearTarget.ChapterContent),
    ReadingRecord(R.string.local_book_clear_reading, BookClearTarget.ReadingRecord)
}
