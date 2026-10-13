package indi.renakoni.nextvol.ui.dialog

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.binding
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.book.bookReadingProgress
import indi.renakoni.nextvol.data.statistics.StatsRepository
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import javax.inject.Inject

@HiltViewModel
class MarkAllChaptersAsReadDialogViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val statsRepository: StatsRepository
) : ViewModel() {
    var bookVolumeResult: Result<BookVolumes, WebRequestError>? by mutableStateOf(null)
        private set

    var bookId by mutableStateOf("")
        private set

    private var volumesJob: Job? = null
    private var volumesRequest = 0L

    fun load(bookId: String) {
        if (bookId == this.bookId) return
        this.bookId = bookId
        volumesJob?.cancel()
        val request = ++volumesRequest
        bookVolumeResult = null
        volumesJob = viewModelScope.launch(Dispatchers.IO) {
            bookRepository.getBookVolumesFlow(bookId).collect { result ->
                withContext(Dispatchers.Main.immediate) {
                    if (request == volumesRequest) bookVolumeResult = result
                }
            }
        }
    }

    fun markAllChaptersAsRead() = markAsRead(null)

    fun markChaptersAsRead(chapterIds: List<String>) = markAsRead(chapterIds)

    private fun markAsRead(chapterIds: List<String>?) = binding {
        val targetBookId = bookId
        if (targetBookId.isBlank()) return@binding Unit

        val allChapterIds = bookVolumeResult
            ?.bind()
            ?.volumes
            ?.flatMap { it.chapters }
            ?.map { it.id }?.toSet() ?: return@binding Unit
        val selected = chapterIds?.toSet()?.intersect(allChapterIds) ?: allChapterIds
        if (chapterIds != null && selected.isEmpty()) return@binding Unit

        viewModelScope.launch(Dispatchers.IO) {
            var finished = false
            bookRepository.updateUserReadingData(targetBookId) { userReadingData ->
                val newProgress = userReadingData.maxChapterReadingProgressMap.toMutableMap()
                for (id in selected) {
                    newProgress[id] = 1f
                }
                val progress = bookReadingProgress(allChapterIds, newProgress)
                finished = allChapterIds.isNotEmpty() && progress >= 1f
                userReadingData.copy(
                    lastReadTime = LocalDateTime.now(),
                    readingProgress = progress,
                    maxChapterReadingProgressMap = newProgress
                )
            }
            if (finished) {
                statsRepository.markBookFinished(targetBookId)
            }
        }
    }

    override fun onCleared() {
        volumesRequest++
        super.onCleared()
    }
}
