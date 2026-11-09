package indi.renakoni.nextvol.ui.localbook

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.localbook.LocalBookDraft
import indi.renakoni.nextvol.data.localbook.LocalBookFormat
import indi.renakoni.nextvol.data.localbook.LocalBookImportFailure
import indi.renakoni.nextvol.data.localbook.LocalBookRelinkPreview
import indi.renakoni.nextvol.data.localbook.LocalBookStore
import indi.renakoni.nextvol.data.localbook.TxtBookParser
import indi.renakoni.nextvol.data.storage.StorageUsageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LocalBookRelinkState(
    val visible: Boolean = false,
    val busy: Boolean = false,
    val saving: Boolean = false,
    val fileName: String = "",
    val fileBytes: Long = 0,
    val format: LocalBookFormat? = null,
    val encoding: String? = null,
    val rule: String = TxtBookParser.DEFAULT_RULE,
    val preview: LocalBookRelinkPreview? = null,
    val error: LocalBookImportFailure? = null,
) {
    val canRelink get() = !busy && !saving && error == null && preview?.canRelink == true
}

@HiltViewModel
class LocalBookRelinkViewModel @Inject constructor(
    private val books: LocalBookStore,
    private val storage: StorageUsageRepository,
) : ViewModel() {
    var state by mutableStateOf(LocalBookRelinkState())
        private set
    private var target: SourceBookId? = null
    private var draft: LocalBookDraft? = null
    private var operation: Job? = null
    private var revision = 0
    private val completion = Channel<Unit>(Channel.BUFFERED)
    val completed = completion.receiveAsFlow()

    fun open(book: SourceBookId, uri: Uri) {
        if (state.saving) return
        dismiss()
        target = book
        state = LocalBookRelinkState(visible = true, busy = true)
        val current = ++revision
        operation = viewModelScope.launch {
            try {
                val settings = books.relinkSettings(book)
                state = state.copy(encoding = settings?.encoding, rule = settings?.rule ?: TxtBookParser.DEFAULT_RULE)
                val staged = books.stage(uri)
                draft = staged
                state = state.copy(fileName = staged.originalName, fileBytes = staged.original.length(), format = staged.format)
                showPreview(book, staged, current)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (current == revision) state = state.copy(error = LocalBookImportFailure.from(failure))
            } finally {
                if (current == revision) state = state.copy(busy = false)
            }
        }
    }

    fun changeEncoding(value: String?) {
        if (state.saving) return
        state = state.copy(encoding = value)
        updatePreview()
    }
    fun changeRule(value: String) {
        if (state.saving) return
        state = state.copy(rule = value)
        updatePreview()
    }

    private fun updatePreview() {
        val staged = draft ?: return
        val book = target ?: return
        operation?.cancel()
        val current = ++revision
        state = state.copy(busy = true, preview = null, error = null)
        operation = viewModelScope.launch {
            try {
                delay(300)
                showPreview(book, staged, current)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (current == revision) state = state.copy(error = LocalBookImportFailure.from(failure))
            } finally {
                if (current == revision) state = state.copy(busy = false)
            }
        }
    }

    private suspend fun showPreview(book: SourceBookId, staged: LocalBookDraft, current: Int) {
        val preview = books.previewRelink(book, staged, state.encoding, state.rule)
        if (current != revision || draft !== staged) throw CancellationException("Preview was superseded")
        state = state.copy(preview = preview, error = null)
    }

    fun confirm() {
        if (!state.canRelink) return
        val preview = state.preview ?: return
        state = state.copy(saving = true)
        operation = viewModelScope.launch {
            try {
                books.relink(preview)
                draft = null
                runCatching { storage.invalidateSnapshot() }.onFailure { Log.e("LocalBookRelink", "Cannot invalidate storage estimate", it) }
                state = LocalBookRelinkState()
                completion.send(Unit)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                state = state.copy(saving = false, error = LocalBookImportFailure.from(failure))
            } finally {
                if (!currentCoroutineContext().isActive) {
                    draft?.let(::discardLater)
                    draft = null
                }
            }
        }
    }

    fun dismiss() {
        if (state.saving) return
        revision++
        operation?.cancel()
        draft?.let(::discardLater)
        draft = null
        state = LocalBookRelinkState()
    }

    private fun discardLater(staged: LocalBookDraft) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { books.discard(staged) }.onFailure { Log.e("LocalBookRelink", "Cannot remove relink draft", it) }
        }
    }

    override fun onCleared() {
        if (!state.saving) draft?.let(::discardLater)
        super.onCleared()
    }
}
