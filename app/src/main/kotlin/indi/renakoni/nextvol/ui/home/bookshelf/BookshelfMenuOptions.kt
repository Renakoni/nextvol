package indi.renakoni.nextvol.ui.home.bookshelf

import indi.renakoni.nextvol.R
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType
import indi.renakoni.nextvol.ui.components.MenuOptions.MenuOptionsWithValues
import indi.renakoni.nextvol.ui.components.MenuOptions.OptionWithValue

data object BookshelfSortTypeOptions: MenuOptionsWithValues<BookshelfSortType>(
    OptionWithValue(BookshelfSortType.Default.key, R.string.bookshelf_sort_default, BookshelfSortType.Default),
    OptionWithValue(BookshelfSortType.Latest.key, R.string.bookshelf_sort_latest, BookshelfSortType.Latest),
    OptionWithValue(BookshelfSortType.Name.key, R.string.bookshelf_sort_name, BookshelfSortType.Name),
    OptionWithValue(BookshelfSortType.WordCount.key, R.string.bookshelf_sort_word_count, BookshelfSortType.WordCount)
)
