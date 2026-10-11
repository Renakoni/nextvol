package indi.renakoni.nextvol.ui.bookmanager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.work.WorkManager
import androidx.work.await
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.download.DownloadItem
import indi.renakoni.nextvol.data.download.DownloadProgressRepository
import indi.renakoni.nextvol.data.download.DownloadType
import indi.renakoni.nextvol.data.download.DownloadSubmission
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.localbook.LocalBookStore
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.storage.StorageUsageRepository
import indi.renakoni.nextvol.data.storage.StorageUsageSnapshot
import indi.renakoni.nextvol.data.work.CacheBookWork
import indi.renakoni.nextvol.data.work.ExportBookToEPUBWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class BookManagerViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val downloadProgressRepository: DownloadProgressRepository,
    private val database: NextVolDatabase,
    private val storageUsageRepository: StorageUsageRepository,
    val workManager: WorkManager,
    private val downloads: BookDownloadStore,
    private val localBooks: LocalBookStore,
) : ViewModel() {
    val downloadItemIdList get() = downloadProgressRepository.downloadItemIdList
    private val _clearedItemsFlow = MutableSharedFlow<Int>()
    val clearedItemsFlow = _clearedItemsFlow.asSharedFlow()
    private val _downloadSubmissions = MutableSharedFlow<DownloadSubmission>()
    val downloadSubmissions = _downloadSubmissions.asSharedFlow()
    val localBookManagerUiState = MutableLocalBookManagerUiState(
        load = ::loadLocalBooks,
        setSort = ::setLocalBookSort,
        setReverse = ::setLocalBookReverse,
        enterSelection = ::enterLocalBookSelection,
        exitSelection = ::exitLocalBookSelection,
        toggleSelect = ::toggleLocalBookSelect,
        selectAll = ::selectAllLocalBooks,
        deleteSelected = ::deleteSelected,
        clearOrphanedData = ::clearOrphanedData,
        clearBookData = ::clearBookData,
        openStorageOverview = {},
        openBookDetailScreen = {}
    )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            storageUsageRepository.getCachedSnapshot()?.let { updateLocalBooks(it, false) }
            refreshLocalBooks()
        }
    }

    fun onClickCancel(item: DownloadItem) {
        if (item.type == DownloadType.CACHE) {
            viewModelScope.launch(Dispatchers.IO) { bookRepository.dismissDownload(item.bookId) }
            return
        }
        workManager.cancelUniqueWork(
            when (item.type) {
                DownloadType.EPUB_EXPORT -> ExportBookToEPUBWork.ofId(item.bookId)
                DownloadType.CACHE -> CacheBookWork.ofId(item.bookId)
            }
        )
        downloadProgressRepository.removeExportItem(item)
    }

    fun onClickClearCompleted() = downloadProgressRepository.clearCompleted()

    fun onClickRetry(item: DownloadItem) {
        if (item.type == DownloadType.CACHE) viewModelScope.launch {
            _downloadSubmissions.emit(bookRepository.submitDownload(item.bookId))
        }
    }

    fun loadLocalBooks() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshLocalBooks()
        }
    }

    fun setLocalBookSort(sort: LocalBookSort) {
        localBookManagerUiState.sort = sort
    }

    fun setLocalBookReverse(reverse: Boolean) {
        localBookManagerUiState.sortReverse = reverse
    }

    fun enterLocalBookSelection(id: String? = null) {
        localBookManagerUiState.isSelecting = true
        localBookManagerUiState.selectedIds = buildSet {
            addAll(localBookManagerUiState.selectedIds)
            if (id != null) add(id)
        }
    }

    fun exitLocalBookSelection() {
        localBookManagerUiState.isSelecting = false
        localBookManagerUiState.selectedIds = emptySet()
    }

    fun toggleLocalBookSelect(id: String) {
        localBookManagerUiState.selectedIds = localBookManagerUiState.selectedIds.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
        localBookManagerUiState.isSelecting = true
    }

    fun selectAllLocalBooks() {
        localBookManagerUiState.isSelecting = true
        localBookManagerUiState.selectedIds = localBookManagerUiState.bookList.map { it.id }.toSet()
    }

    fun deleteSelected() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = deleteSelectedLocalBooks()
            if (count > 0) _clearedItemsFlow.emit(count)
        }
    }

    fun clearOrphanedData() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = clearOrphanedDataItems()
            if (count > 0) _clearedItemsFlow.emit(count)
        }
    }

    fun clearBookData(bookId: String, targets: List<LocalBookClearTarget>) {
        viewModelScope.launch(Dispatchers.IO) {
            val count = clearBookDataItems(bookId, targets)
            if (count > 0) _clearedItemsFlow.emit(count)
        }
    }

    suspend fun deleteSelectedLocalBooks(): Int {
        val ids = localBookManagerUiState.selectedIds.toList()
        if (ids.isEmpty()) return 0
        localBookManagerUiState.isDeleting = true
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
        withContext(Dispatchers.Main) {
            localBookManagerUiState.isDeleting = false
            localBookManagerUiState.isSelecting = false
            localBookManagerUiState.selectedIds = emptySet()
        }
        refreshLocalBooks()
        return ids.size
    }

    suspend fun clearOrphanedDataItems(): Int {
        val count = database.withTransaction {
            val linkedChapterIds = database.bookVolumesDao()
                .getAllVolumeEntities()
                .flatMap { it.chapterIds }
                .toSet() + database.bookDownloadDao().allChapters().map { it.id }
            val orphanChapterInfoIds = database.bookVolumesDao()
                .getAllChapterInformationEntities()
                .map { it.id }
                .filterNot(linkedChapterIds::contains)
            val orphanChapterContentIds = database.chapterContentDao()
                .getAllEntities()
                .map { it.id }
                .filterNot(linkedChapterIds::contains)

            if (orphanChapterInfoIds.isNotEmpty()) {
                database.bookVolumesDao().deleteChapterInformationByIds(orphanChapterInfoIds)
            }
            if (orphanChapterContentIds.isNotEmpty()) {
                database.chapterContentDao().deleteByIds(orphanChapterContentIds)
            }
            orphanChapterInfoIds.size + orphanChapterContentIds.size
        }
        storageUsageRepository.invalidateSnapshot()
        refreshLocalBooks()
        return count
    }

    suspend fun clearBookDataItems(bookId: String, targets: List<LocalBookClearTarget>): Int {
        if (targets.isEmpty()) return 0

        val volumeEntities = database.bookVolumesDao().getVolumeEntitiesByBookId(bookId)
        val chapterIds = volumeEntities
            .flatMap { it.chapterIds }
            .distinct()
        val targetSet = targets.toSet()
        val hasReadingRecord = database.userReadingDataDao().getEntity(bookId) != null
        val chapterContentIds = if (LocalBookClearTarget.ChapterContent in targetSet && chapterIds.isNotEmpty()) {
            chapterIds.filter { database.chapterContentDao().getId(it) != null }
        } else {
            emptyList()
        }
        if (LocalBookClearTarget.ChapterContent in targetSet) removeDownloads(listOf(bookId))

        database.withTransaction {
            if (LocalBookClearTarget.VolumeAndChapterIndex in targetSet) {
                if (chapterIds.isNotEmpty()) {
                    database.bookVolumesDao().deleteChapterInformationByIds(chapterIds)
                }
                database.bookVolumesDao().deleteByBookIds(listOf(bookId))
            }
            if (chapterContentIds.isNotEmpty()) {
                database.chapterContentDao().deleteByIds(chapterContentIds)
            }
            if (LocalBookClearTarget.ReadingRecord in targetSet && hasReadingRecord) {
                database.userReadingDataDao().deleteByIds(listOf(bookId))
            }
        }

        storageUsageRepository.invalidateSnapshot()
        refreshLocalBooks()

        var clearedCount = 0
        if (LocalBookClearTarget.VolumeAndChapterIndex in targetSet) {
            clearedCount += volumeEntities.size + chapterIds.size
        }
        clearedCount += chapterContentIds.size
        if (LocalBookClearTarget.ReadingRecord in targetSet && hasReadingRecord) {
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

    private suspend fun refreshLocalBooks() {
        withContext(Dispatchers.Main) {
            localBookManagerUiState.isLoading = true
        }
        localBooks.restoreMetadata()
        updateLocalBooks(storageUsageRepository.refreshSnapshot(), loading = false)
    }

    private suspend fun updateLocalBooks(snapshot: StorageUsageSnapshot, loading: Boolean) {
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

        val bookList = snapshot.books.map { usage ->
            val readingRecordBytes = bookReadingBytesMap[usage.bookId] ?: 0L
            val importedFileBytes = localBooks.storedBytes(BookIdentity.book(usage.bookId))
            LocalBookItem(
                id = usage.bookId,
                bookInformationFlow = bookRepository.getBookInformationFlow(usage.bookId),
                size = usage.totalBytes + readingRecordBytes + importedFileBytes,
                chapterCount = bookChapterCountMap[usage.bookId] ?: 0,
                volumeCount = bookVolumeCountMap[usage.bookId] ?: 0,
                lastReadTime = bookLastReadTimeMap[usage.bookId],
                bookInformationBytes = usage.bookInformationBytes,
                volumeBytes = usage.volumeBytes,
                chapterInformationBytes = usage.chapterInformationBytes,
                chapterContentBytes = usage.chapterContentBytes,
                readingRecordBytes = readingRecordBytes,
                importedFileBytes = importedFileBytes,
                downloadImageBytes = usage.downloadImageBytes,
                preparationBytes = usage.preparationBytes,
            )
        }.filter { it.size > 0L }
        val retainedSelectedIds = localBookManagerUiState.selectedIds.intersect(bookList.map { it.id }.toSet())

        localBookManagerUiState.isLoading = loading
        localBookManagerUiState.bookList = bookList
        localBookManagerUiState.selectedIds = retainedSelectedIds
        localBookManagerUiState.isSelecting = localBookManagerUiState.isSelecting && retainedSelectedIds.isNotEmpty()
    }
}
