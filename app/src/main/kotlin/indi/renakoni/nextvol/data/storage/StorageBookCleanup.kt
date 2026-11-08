package indi.renakoni.nextvol.data.storage

import androidx.room.withTransaction
import androidx.work.WorkManager
import androidx.work.await
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.download.DownloadProgressRepository
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.localbook.LocalBookStore
import indi.renakoni.nextvol.data.work.CacheBookWork
import indi.renakoni.nextvol.data.work.ExportBookToEPUBWork
import javax.inject.Inject

enum class BookClearTarget {
    VolumeAndChapterIndex,
    ChapterContent,
    ReadingRecord
}

class StorageBookCleanup @Inject constructor(
    private val database: NextVolDatabase,
    private val storageUsageRepository: StorageUsageRepository,
    private val workManager: WorkManager,
    private val downloads: BookDownloadStore,
    private val downloadProgressRepository: DownloadProgressRepository,
    private val localBooks: LocalBookStore,
) {
    suspend fun deleteBooks(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val (imported, cached) = ids.partition { LocalBookStore.isLocal(BookIdentity.book(it)) }
        imported.forEach { localBooks.delete(BookIdentity.book(it)) }
        removeDownloads(cached)
        val chapterIds = database.bookVolumesDao()
            .getVolumeEntitiesByBookIds(cached)
            .flatMap { it.chapterIds }
            .distinct()
        database.withTransaction {
            if (chapterIds.isNotEmpty()) {
                database.chapterContentDao().deleteByIds(chapterIds)
            }
            database.bookInformationDao().deleteByIds(cached)
        }
        storageUsageRepository.invalidateSnapshot()
        return ids.size
    }

    suspend fun clearOrphanedData(): Int {
        val count = database.withTransaction {
            val chapterToBookMap = chapterOwners(
                catalogChapters = database.bookVolumesDao().getAllVolumeEntities().asSequence().flatMap { volume ->
                    volume.chapterIds.asSequence().map { it to volume.bookId }
                },
                downloadedChapters = database.bookDownloadDao().allChapters().asSequence().map { it.id to it.bookId },
            )
            val orphanChapterInfoIds = database.bookVolumesDao()
                .getAllChapterInformationEntities()
                .map { it.id }
                .filterNot(chapterToBookMap::containsKey)
            val orphanChapterContentIds = database.chapterContentDao()
                .getAllEntities()
                .map { it.id }
                .filterNot(chapterToBookMap::containsKey)

            if (orphanChapterInfoIds.isNotEmpty()) {
                database.bookVolumesDao().deleteChapterInformationByIds(orphanChapterInfoIds)
            }
            if (orphanChapterContentIds.isNotEmpty()) {
                database.chapterContentDao().deleteByIds(orphanChapterContentIds)
            }
            orphanChapterInfoIds.size + orphanChapterContentIds.size
        }
        storageUsageRepository.invalidateSnapshot()
        return count
    }

    suspend fun clearBookData(bookId: String, targets: List<BookClearTarget>): Int {
        if (targets.isEmpty()) return 0

        val volumeEntities = database.bookVolumesDao().getVolumeEntitiesByBookId(bookId)
        val chapterIds = volumeEntities
            .flatMap { it.chapterIds }
            .distinct()
        val targetSet = targets.toSet()
        val hasReadingRecord = database.userReadingDataDao().getEntity(bookId) != null
        val chapterContentIds = if (BookClearTarget.ChapterContent in targetSet && chapterIds.isNotEmpty()) {
            chapterIds.filter { database.chapterContentDao().getId(it) != null }
        } else {
            emptyList()
        }
        if (BookClearTarget.ChapterContent in targetSet) removeDownloads(listOf(bookId))

        database.withTransaction {
            if (BookClearTarget.VolumeAndChapterIndex in targetSet) {
                if (chapterIds.isNotEmpty()) {
                    database.bookVolumesDao().deleteChapterInformationByIds(chapterIds)
                }
                database.bookVolumesDao().deleteByBookIds(listOf(bookId))
            }
            if (chapterContentIds.isNotEmpty()) {
                database.chapterContentDao().deleteByIds(chapterContentIds)
            }
            if (BookClearTarget.ReadingRecord in targetSet && hasReadingRecord) {
                database.userReadingDataDao().deleteByIds(listOf(bookId))
            }
        }

        storageUsageRepository.invalidateSnapshot()

        var clearedCount = 0
        if (BookClearTarget.VolumeAndChapterIndex in targetSet) {
            clearedCount += volumeEntities.size + chapterIds.size
        }
        clearedCount += chapterContentIds.size
        if (BookClearTarget.ReadingRecord in targetSet && hasReadingRecord) {
            clearedCount += 1
        }
        return clearedCount
    }

    private suspend fun removeDownloads(bookIds: List<String>) {
        for (bookId in bookIds) {
            workManager.cancelUniqueWork(CacheBookWork.ofId(bookId)).await()
            workManager.cancelUniqueWork(ExportBookToEPUBWork.ofId(bookId)).await()
        }
        downloads.removeBooks(bookIds.map(BookIdentity::book))
        downloadProgressRepository.clearCachedItems(bookIds.toSet())
    }
}
