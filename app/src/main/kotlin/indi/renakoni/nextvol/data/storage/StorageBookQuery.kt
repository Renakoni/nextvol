package indi.renakoni.nextvol.data.storage

import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.localbook.LocalBookStore
import java.time.LocalDateTime
import javax.inject.Inject

data class StorageBook(
    val usage: BookStorageUsage,
    val chapterCount: Int,
    val volumeCount: Int,
    val lastReadTime: LocalDateTime?,
    val readingRecordBytes: Long,
    val importedFileBytes: Long,
) {
    val id: String get() = usage.bookId
    val size: Long get() = usage.totalBytes + readingRecordBytes + importedFileBytes
}

class StorageBookQuery @Inject constructor(
    private val database: NextVolDatabase,
    private val storageUsageRepository: StorageUsageRepository,
    private val localBooks: LocalBookStore,
) {
    suspend fun getCachedBooks(): List<StorageBook>? =
        storageUsageRepository.getCachedSnapshot()?.let { query(it) }

    suspend fun refreshBooks(): List<StorageBook> {
        localBooks.restoreMetadata()
        return query(storageUsageRepository.refreshSnapshot())
    }

    private suspend fun query(snapshot: StorageUsageSnapshot): List<StorageBook> {
        val volumes = database.bookVolumesDao().getAllVolumeEntities()
        val readingData = database.userReadingDataDao().getAll()
        val bookReadingBytesMap = database.storageStatsDao()
            .getUserReadingBytes()
            .associate { it.id to it.bytes }
        val bookChapterCountMap = volumes.groupBy { it.bookId }
            .mapValues { (_, list) -> list.sumOf { it.chapterIds.size } }
        val bookVolumeCountMap = volumes.groupBy { it.bookId }
            .mapValues { (_, list) -> list.size }
        val bookLastReadTimeMap = readingData.associate { it.id to it.lastReadTime }

        return snapshot.books.map { usage ->
            StorageBook(
                usage = usage,
                chapterCount = bookChapterCountMap[usage.bookId] ?: 0,
                volumeCount = bookVolumeCountMap[usage.bookId] ?: 0,
                lastReadTime = bookLastReadTimeMap[usage.bookId],
                readingRecordBytes = bookReadingBytesMap[usage.bookId] ?: 0L,
                importedFileBytes = localBooks.storedBytes(BookIdentity.book(usage.bookId)),
            )
        }.filter { it.size > 0L }
    }
}
