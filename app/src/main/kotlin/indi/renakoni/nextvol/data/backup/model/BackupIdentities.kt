package indi.renakoni.nextvol.data.backup.model

import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.SourceChapterId
import indi.renakoni.nextvol.data.local.room.converter.ListConverter
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.serialization.json.Json

/** Validate identity ownership before any import writes, including metadata-only backups. */
internal fun LocalData.validateIdentities() {
    val aliasIds = bookAliases.map { it.id }.toSet()
    require(aliasIds.size == bookAliases.size) { "Duplicate book aliases" }
    bookAliases.forEach { alias ->
        val from = SourceBookId.fromStorageKey(alias.id)
        val to = SourceBookId.fromStorageKey(alias.canonicalId)
        require(from.sourceId == to.sourceId && to.storageKey !in aliasIds) { "Invalid book alias ownership" }
    }
    localBookFiles.forEach { it.validate() }
    readingBookmarks.forEach { it.validate() }
    val downloadedBooks = bookDownloadEntities.map { SourceBookId.fromStorageKey(it.bookId) }.toSet()
    downloadedChapterEntities.forEach { chapter ->
        val book = SourceBookId.fromStorageKey(chapter.bookId)
        require(book in downloadedBooks && SourceChapterId.fromStorageKey(chapter.id).book == book)
        Json.decodeFromString<List<String>>(chapter.images)
    }
    bookInformationEntities.forEach { SourceBookId.fromStorageKey(it.id) }
    bookRecordEntities.forEach { SourceBookId.fromStorageKey(it.bookId) }
    bookshelfEntities.forEach { shelf ->
        (shelf.allBookIds + shelf.pinnedBookIds + shelf.updatedBookIds).forEach { SourceBookId.fromStorageKey(it) }
    }
    bookshelfBookMetadataEntities.forEach { SourceBookId.fromStorageKey(it.id) }
    chapterInformationEntities.forEach { SourceChapterId.fromStorageKey(it.id) }
    formattingRuleEntities.filter { it.bookId.isNotEmpty() }.forEach { SourceBookId.fromStorageKey(it.bookId) }
    chapterContentEntities.forEach { content ->
        val chapter = SourceChapterId.fromStorageKey(content.id)
        listOf(content.prevChapter, content.nextChapter).filter(String::isNotEmpty).forEach {
            require(SourceChapterId.fromStorageKey(it).book == chapter.book)
        }
    }
    volumeEntities.forEach { volume ->
        val book = SourceBookId.fromStorageKey(volume.bookId)
        BookIdentity.volumeRemoteId(volume.volumeId, book)
        volume.chapterIds.forEach { require(SourceChapterId.fromStorageKey(it).book == book) }
    }
    userReadingDataEntities.forEach { reading ->
        val book = SourceBookId.fromStorageKey(reading.id)
        (reading.currentChapterReadingProgressMap.keys + reading.maxChapterReadingProgressMap.keys +
            listOf(reading.lastReadChapterId).filter(String::isNotEmpty)).forEach {
            require(SourceChapterId.fromStorageKey(it).book == book)
        }
    }
    userDataEntities.filter { it.path == UserDataPath.ReadingBooks.path }.forEach { entity ->
        ListConverter.stringToStringList(entity.value).forEach { SourceBookId.fromStorageKey(it) }
    }
}
