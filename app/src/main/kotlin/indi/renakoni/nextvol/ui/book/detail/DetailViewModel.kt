package indi.renakoni.nextvol.ui.book.detail

import indi.renakoni.nextvol.data.book.availableVolumes
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.github.michaelbull.result.onOk
import com.github.michaelbull.result.map
import com.github.michaelbull.result.get
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.book.BookReadingDataRepository
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.download.DownloadProgressRepository
import indi.renakoni.nextvol.data.download.DownloadType
import indi.renakoni.nextvol.data.work.ExportBookToEPUBWork
import indi.renakoni.nextvol.data.book.observeSubmittedUniqueWork
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val bookshelfRepository: BookshelfRepository,
    private val downloadProgressRepository: DownloadProgressRepository,
    private val workManager: WorkManager,
    private val readingDataRepository: BookReadingDataRepository,
) : ViewModel() {
    private val _uiState = MutableDetailUiState()
    var exportSettings = ExportSettings()
    private var book: indi.renakoni.nextvol.data.book.SourceBookId? = null
    private var informationJob: Job? = null
    private val informationReady = CompletableDeferred<Unit>()
    private val directoryRetry = MutableStateFlow(0)
    private val foreground = indi.renakoni.nextvol.data.web.ForegroundSourceRequest()
    fun setActive(value: Boolean, retainBrowser: Boolean = false) = foreground.setActive(value, retainBrowser)
    val uiState: DetailUiState = _uiState

    var isInitialized by mutableStateOf(false)
        private set

    fun init(bookId: String) {
        Log.d("DetailViewModel", "Init bookId = $bookId")
        if (isInitialized) return
        book = indi.renakoni.nextvol.data.book.BookIdentity.book(bookId)
        isInitialized = true
        loadInformation(bookId)
        viewModelScope.launch(Dispatchers.IO + foreground) {
            combine(bookRepository.readingAvailability(bookId), directoryRetry) { availability, _ -> availability }.collectLatest { availability ->
                _uiState.readingAvailable = availability.available
                _uiState.canCache = availability.online
                _uiState.metadataOnly = availability.metadataOnly
                _uiState.bookVolumes = null
                if (availability.available) {
                    // Rule sources may have one permit for the entire request. Publish metadata before
                    // admitting the long directory refresh, even when there is no host cache yet.
                    if (availability.online) informationReady.await()
                    bookRepository.getBookVolumesFlow(bookId, WebDataSourcePriority.High).collect {
                        _uiState.bookVolumes = it
                    }
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            bookRepository.getUserReadingDataFlow(bookId).collect {
                _uiState.userReadingData = it
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            bookRepository.downloadStatusFlow(bookId).collect { _uiState.downloadState = it }
        }
        viewModelScope.launch(Dispatchers.IO) {
            bookshelfRepository.getBookshelfBookMetadataFlow(bookId).collect {
                _uiState.isInBookshelf = it != null
            }
        }
        viewModelScope.launch {
            snapshotFlow { downloadProgressRepository.downloadItemIdList }.collect {
                _uiState.downloadItem = downloadProgressRepository.downloadItemIdList.findLast { it.bookId == bookId && it.type == DownloadType.CACHE }
            }
        }
    }

    fun retryInformation() { book?.let { loadInformation(it.storageKey) } }

    fun retryVolumes() { if (_uiState.readingAvailable) directoryRetry.value++ }

    suspend fun markChaptersUnread(chapterIds: Set<String>) {
        val bookId = checkNotNull(book).storageKey
        val volumes = checkNotNull(_uiState.bookVolumes?.availableVolumes())
        val catalogIds = volumes.volumes.flatMap { it.chapters }.mapTo(mutableSetOf()) { it.id }
        withContext(Dispatchers.IO) {
            readingDataRepository.markChaptersUnread(bookId, chapterIds, catalogIds)
            _uiState.userReadingData = readingDataRepository.getUserReadingData(bookId)
        }
    }

    private fun loadInformation(bookId: String) {
        informationJob?.cancel()
        _uiState.bookInformation = null
        informationJob = viewModelScope.launch(Dispatchers.IO + foreground) {
            bookRepository.getBookInformationFlow(bookId, WebDataSourcePriority.High).collect { result ->
                result.onOk {
                    val metadata = bookshelfRepository.getBookshelfBookMetadata(bookId) ?: return@onOk
                    metadata.bookShelfIds.forEach { shelf -> bookshelfRepository.deleteBookFromBookshelfUpdatedBookIds(shelf, bookId) }
                    if (it.lastUpdated.year > 1970)
                        bookshelfRepository.updateBookshelfBookMetadataLastUpdateTime(bookId, it.lastUpdated)
                }
                _uiState.bookInformation = result.map { bookRepository.bookInformationForDisplay(it) }
                informationReady.complete(Unit)
            }
        }
    }

    suspend fun tagPage(tag: String) = book?.let { bookRepository.bookTagPage(it, tag) }


    var exportResult by mutableStateOf<WorkInfo?>(null)
        private set
    var exportSubmissionFailed by mutableStateOf(false)
        private set
    private var exportObserver: Job? = null

    fun startEpubExport(bookId: String, title: String) {
        exportObserver?.cancel()
        clearExportResult()
        exportObserver = viewModelScope.launch {
            try {
                exportResult = exportToEpub(bookId, title).first { it?.state?.isFinished == true }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                exportSubmissionFailed = true
            }
        }
    }

    fun clearExportResult() {
        exportResult = null
        exportSubmissionFailed = false
    }

    fun exportToEpub(bookId: String, title: String): Flow<WorkInfo?> {
        if (!_uiState.readingAvailable) return flowOf(null)
        val key = indi.renakoni.nextvol.data.book.BookIdentity.bookKey(bookId)
        val generation = bookRepository.downloadGeneration()
        val workRequest = OneTimeWorkRequestBuilder<ExportBookToEPUBWork>()
            .addTag(indi.renakoni.nextvol.data.work.CacheBookWork.generationTag(generation))
            .setInputData(
                workDataOf(
                    "bookId" to key,
                    "title" to title,
                    "downloadGeneration" to generation,
                    "includeImages" to exportSettings.includeImages,
                    "exportType" to exportSettings.exportType.name,
                    "selectedVolume" to exportSettings.selectedVolumeIds.joinToString(",")
                )
            )
            .build()
        val operation = workManager.enqueueUniqueWork(
            ExportBookToEPUBWork.ofId(key),
            ExistingWorkPolicy.KEEP,
            workRequest
        )
        return workManager.observeSubmittedUniqueWork(ExportBookToEPUBWork.ofId(key), operation)
    }
}
