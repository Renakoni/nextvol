package indi.renakoni.nextvol.ui.bookmanager

import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.MenuOptions
import indi.renakoni.nextvol.ui.components.MenuOptions.Option

data object LocalBookSortMenuOptions : MenuOptions(
    Option(LocalBookSort.Size.name, R.string.key_local_book_sort_size),
    Option(LocalBookSort.LastRead.name, R.string.key_local_book_sort_last_read),
    Option(LocalBookSort.ChapterCount.name, R.string.key_local_book_sort_chapter_count)
)
