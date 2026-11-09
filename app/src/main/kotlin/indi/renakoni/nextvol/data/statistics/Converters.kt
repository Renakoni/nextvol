package indi.renakoni.nextvol.data.statistics

import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.local.room.entity.BookRecordEntity

fun BookRecordEntity.toData(bookRepository: BookRepository): BookRecord =
    BookRecord(
        bookId = bookId,
        bookInformationFlow = bookRepository.getBookInformationFlow(bookId),
        date = date,
        reads = reads,
        seconds = seconds,
        isFinished = isFinished,
        isFavorited = isFavorited,
        firstSeen = firstSeen,
        lastSeen = lastSeen,
    )