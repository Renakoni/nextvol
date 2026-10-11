package indi.renakoni.nextvol.ui.book.download

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.github.michaelbull.result.get
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.download.BookDownloadStatus
import indi.renakoni.nextvol.data.download.DownloadFailure
import indi.renakoni.nextvol.data.download.DownloadSelectionState
import indi.renakoni.nextvol.data.download.DownloadStage
import indi.renakoni.nextvol.data.download.DownloadSubmission
import indi.renakoni.nextvol.data.download.DownloadTaskStatus
import indi.renakoni.nextvol.data.download.downloadFailure
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BookDownloadUiState(
    val bookId: String,
    val information: BookInformation? = null,
    val volumes: BookVolumes? = null,
    val loading: Boolean = true,
    val directoryFailure: DownloadFailure? = null,
    val selected: Set<String> = emptySet(),
    val chapters: DownloadSelectionState = DownloadSelectionState(),
    val status: BookDownloadStatus = BookDownloadStatus(),
    val submitting: Boolean = false,
) {
    val allChapters get() = volumes?.volumes.orEmpty().flatMap { it.chapters }
    /** Downloaded chapters are final, so only the rest can be selected. */
    val downloadableIds get() = allChapters.map { it.id }.filterTo(mutableSetOf()) { chapters.chapters[it]?.downloaded != true }
    val locked get() = submitting || status.task.active || status.task.status in
        setOf(DownloadTaskStatus.WaitingRetry, DownloadTaskStatus.WaitingVerification)
    val ready get() = !loading && directoryFailure == null && allChapters.isNotEmpty()
}

@HiltViewModel
class BookDownloadViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle, private val books: BookRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<BookDownloadRoute>()
    var state by mutableStateOf(BookDownloadUiState(BookIdentity.bookKey(route.bookId)))
        private set
    private val messages = Channel<DownloadSubmission>(Channel.BUFFERED)
    val submissions = messages.receiveAsFlow()
    private var directoryJob: Job? = null
    private var initializedSelection = false

    init {
        viewModelScope.launch { books.downloadInformationFlow(state.bookId).collect {
            state = state.copy(information = it.get())
        } }
        viewModelScope.launch { books.downloadStatusFlow(state.bookId).collect {
            state = state.copy(status = it)
            updateChapterStates()
        } }
        loadDirectory()
    }

    fun loadDirectory() {
        if (directoryJob?.isActive == true) return
        state = state.copy(loading = true, directoryFailure = null)
        directoryJob = viewModelScope.launch {
            try {
                val result = books.downloadDirectory(BookIdentity.book(state.bookId))
                val seen = mutableSetOf<String>()
                val volumes = result.get()?.let { book -> book.copy(volumes = book.volumes.map { volume ->
                    volume.copy(chapters = volume.chapters.filter { seen.add(it.id) })
                }.filter { it.chapters.isNotEmpty() }) }
                if (volumes == null || seen.isEmpty()) {
                    state = state.copy(loading = false, directoryFailure = downloadFailure(result.component2(), DownloadStage.Directory))
                    return@launch
                }
                val chapters = books.downloadSelection(state.bookId, volumes)
                val book = BookIdentity.book(state.bookId)
                val downloadable = seen.filterTo(mutableSetOf()) { chapters.chapters[it]?.downloaded != true }
                // Restore what is left of the last selection; otherwise offer every chapter not downloaded yet.
                val selected = if (initializedSelection) state.selected.intersect(downloadable) else
                    chapters.selectedChapterIds?.let { stored -> downloadable.filterTo(mutableSetOf()) { BookIdentity.chapter(it, book).remoteId in stored } }
                        ?.takeIf { it.isNotEmpty() } ?: downloadable
                initializedSelection = true
                state = state.copy(volumes = volumes, chapters = chapters, selected = selected, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                state = state.copy(loading = false, directoryFailure = downloadFailure(WebRequestError("", "", error), DownloadStage.Directory))
            }
        }
    }

    private suspend fun updateChapterStates() {
        val volumes = state.volumes ?: return
        val snapshot = books.downloadSelection(state.bookId, volumes)
        if (state.volumes != volumes) return
        val book = BookIdentity.book(state.bookId)
        val selected = if (state.locked) state.allChapters.filter { chapter ->
            snapshot.selectedChapterIds?.contains(BookIdentity.chapter(chapter.id, book).remoteId) ?: true
        }.map { it.id }.toSet() else state.selected
        val next = state.copy(chapters = snapshot)
        state = next.copy(selected = selected.intersect(next.downloadableIds))
    }

    fun select(ids: Set<String>) {
        if (!state.locked && state.ready) state = state.copy(selected = ids.intersect(state.downloadableIds))
    }

    fun submit(resume: Boolean = false) {
        // A late tap on a task that has already ended must not start a whole-book download.
        if (state.submitting || resume && !state.status.task.canResume ||
            !resume && (!state.ready || state.locked || state.selected.isEmpty())) return
        val selected = if (resume) null else state.selected.toList()
        state = state.copy(submitting = true)
        viewModelScope.launch {
            try {
                val result = books.submitDownload(state.bookId, selected)
                messages.send(result)
            } finally { state = state.copy(submitting = false) }
        }
    }

    fun cancel() {
        // Only a live task can be cancelled; a finished one must not turn into "Cancelled".
        if (state.submitting || !state.locked) return
        state = state.copy(submitting = true)
        viewModelScope.launch {
            try { books.dismissDownload(state.bookId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { messages.send(DownloadSubmission.Rejected(DownloadFailure.Scheduling)) }
            finally { state = state.copy(submitting = false) }
        }
    }
}
