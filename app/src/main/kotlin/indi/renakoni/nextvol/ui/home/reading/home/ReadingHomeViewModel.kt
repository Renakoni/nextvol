package indi.renakoni.nextvol.ui.home.reading.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReadingHomeViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    userDataRepository: UserDataRepository
) : ViewModel() {

    private val readingBooksUserData =
        userDataRepository.stringListUserData(UserDataPath.ReadingBooks.path)

    private class BookObservation(
        val job: Job,
        val state: StateFlow<Result<RecentReadingBook, WebRequestError>?>,
    )

    private val observations = mutableMapOf<String, BookObservation>()

    var recentReadingBooks: List<Pair<String, StateFlow<Result<RecentReadingBook, WebRequestError>?>>> by mutableStateOf(emptyList())
        private set

    var chapterSheetUiState by mutableStateOf<ChapterSheetUiState?>(null)
        private set

    init {
        viewModelScope.launch {
            readingBooksUserData.getFlowWithDefault(emptyList()).collect { ids ->
                val ordered = ids.asReversed().filter(String::isNotBlank).distinct()
                val retained = ordered.toSet()
                observations.keys.filter { it !in retained }.forEach { id ->
                    observations.remove(id)?.job?.cancel()
                }
                recentReadingBooks = ordered.map { id ->
                    id to observations.getOrPut(id) { observeBook(id) }.state
                }
            }
        }
    }

    private fun observeBook(id: String): BookObservation {
        val job = SupervisorJob(viewModelScope.coroutineContext[Job])
        val scope = CoroutineScope(viewModelScope.coroutineContext + job)
        var information: Result<BookInformation, WebRequestError>? = null
        val state = bookRepository.getBookInformationFlow(id)
            .map { result ->
                // A failed refresh must not clear a known book or stop live progress updates.
                if (result.isOk || information?.isOk != true) information = result
                requireNotNull(information)
            }
            .onStart { information?.let { emit(it) } }
            .combine(bookRepository.getUserReadingDataFlow(id)) { result, reading ->
                result.map { RecentReadingBook(id, it, reading) }
            }
            .flowOn(Dispatchers.IO)
            // Header and history share one upstream. Returning restarts the repository refresh
            // while the last result remains available; removing the ID cancels its child scope.
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)
        return BookObservation(job, state)
    }

    fun openChapters(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val userData = bookRepository.getUserReadingData(bookId)
            chapterSheetUiState = ChapterSheetUiState(
                bookId =  bookId,
                readingChapterId = userData.lastReadChapterId ?: return@launch,
                bookVolumeFlow = bookRepository.getBookVolumesFlow(bookId)
            )
        }
    }

    fun setVolume(volumeId: String) {
        chapterSheetUiState = chapterSheetUiState?.copy(selectedVolumeId = volumeId)
    }

    fun closeContents() {
        chapterSheetUiState = null
    }

    fun removeFromReadingList(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            readingBooksUserData.update {
                it.toMutableList().apply { remove(bookId) }
            }
        }
    }

    fun addToReadingList(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            readingBooksUserData.update {
                (it + BookIdentity.bookKey(bookId)).distinct()
            }
        }
    }
}
