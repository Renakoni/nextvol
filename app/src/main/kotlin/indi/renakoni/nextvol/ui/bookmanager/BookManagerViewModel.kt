package indi.renakoni.nextvol.ui.bookmanager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.download.DownloadItem
import indi.renakoni.nextvol.data.download.DownloadProgressRepository
import indi.renakoni.nextvol.data.download.DownloadType
import indi.renakoni.nextvol.data.download.DownloadSubmission
import indi.renakoni.nextvol.data.storage.StorageBook
import indi.renakoni.nextvol.data.storage.StorageBookCleanup
import indi.renakoni.nextvol.data.storage.StorageBookQuery
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
    val workManager: WorkManager,
    private val storageBooks: StorageBookQuery,
    private val cleanup: StorageBookCleanup,
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
            storageBooks.getCachedBooks()?.let { updateLocalBooks(it) }
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
        val count = cleanup.deleteBooks(ids)
        withContext(Dispatchers.Main) {
            localBookManagerUiState.isDeleting = false
            localBookManagerUiState.isSelecting = false
            localBookManagerUiState.selectedIds = emptySet()
        }
        refreshLocalBooks()
        return count
    }

    suspend fun clearOrphanedDataItems(): Int {
        val count = cleanup.clearOrphanedData()
        refreshLocalBooks()
        return count
    }

    suspend fun clearBookDataItems(bookId: String, targets: List<LocalBookClearTarget>): Int {
        if (targets.isEmpty()) return 0
        val count = cleanup.clearBookData(bookId, targets.map { it.target })
        refreshLocalBooks()
        return count
    }

    private suspend fun refreshLocalBooks() {
        withContext(Dispatchers.Main) {
            localBookManagerUiState.isLoading = true
        }
        updateLocalBooks(storageBooks.refreshBooks())
    }

    private fun updateLocalBooks(books: List<StorageBook>) {
        val bookList = books.map { book ->
            val usage = book.usage
            LocalBookItem(
                id = usage.bookId,
                bookInformationFlow = bookRepository.getBookInformationFlow(usage.bookId),
                size = book.size,
                chapterCount = book.chapterCount,
                volumeCount = book.volumeCount,
                lastReadTime = book.lastReadTime,
                bookInformationBytes = usage.bookInformationBytes,
                volumeBytes = usage.volumeBytes,
                chapterInformationBytes = usage.chapterInformationBytes,
                chapterContentBytes = usage.chapterContentBytes,
                readingRecordBytes = book.readingRecordBytes,
                importedFileBytes = book.importedFileBytes,
                downloadImageBytes = usage.downloadImageBytes,
                preparationBytes = usage.preparationBytes,
            )
        }
        val retainedSelectedIds = localBookManagerUiState.selectedIds.intersect(bookList.map { it.id }.toSet())

        localBookManagerUiState.isLoading = false
        localBookManagerUiState.bookList = bookList
        localBookManagerUiState.selectedIds = retainedSelectedIds
        localBookManagerUiState.isSelecting = localBookManagerUiState.isSelecting && retainedSelectedIds.isNotEmpty()
    }
}
