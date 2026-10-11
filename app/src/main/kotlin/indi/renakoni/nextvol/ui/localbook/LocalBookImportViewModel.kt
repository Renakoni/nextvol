package indi.renakoni.nextvol.ui.localbook

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.localbook.LocalBookImportFailure
import indi.renakoni.nextvol.data.localbook.LocalBookDraft
import indi.renakoni.nextvol.data.localbook.LocalBookFormat
import indi.renakoni.nextvol.data.localbook.LocalBookStore
import indi.renakoni.nextvol.data.localbook.ParsedLocalBook
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
import java.io.File
import javax.inject.Inject

data class LocalBookImportState(
    val visible: Boolean = false,
    val busy: Boolean = false,
    val importing: Boolean = false,
    val fileName: String = "",
    val fileBytes: Long = 0,
    val bookKey: String? = null,
    val format: LocalBookFormat? = null,
    val title: String = "",
    val shelfName: String = "",
    val cover: Uri = Uri.EMPTY,
    val encoding: String? = null,
    val rule: String = TxtBookParser.DEFAULT_RULE,
    val preview: ParsedLocalBook? = null,
    val error: LocalBookImportFailure? = null,
) {
    val canImport get() = !busy && !importing && error == null && preview != null && title.isNotBlank() && title.length <= 200
}

@HiltViewModel
class LocalBookImportViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val books: LocalBookStore,
    private val storage: StorageUsageRepository,
) : ViewModel() {
    var state by mutableStateOf(LocalBookImportState())
        private set
    private var draft: LocalBookDraft? = null
    /** Every import goes to this bookshelf, created on the first confirmed import. */
    private val targetName: String get() = context.getString(R.string.local_bookshelf_name)
    private var operation: Job? = null
    private var revision = 0
    private val completed = Channel<Int>(Channel.BUFFERED)
    val imported = completed.receiveAsFlow()

    fun open(uri: Uri, name: String? = null, format: LocalBookFormat? = null) {
        if (state.importing) return
        dismiss()
        state = LocalBookImportState(visible = true, busy = true, shelfName = targetName)
        val current = ++revision
        operation = viewModelScope.launch {
            try {
                val staged = if (name != null && format != null) books.stage(uri, name, format) else books.stage(uri)
                draft = staged
                state = state.copy(fileName = staged.originalName, fileBytes = staged.original.length(),
                    bookKey = staged.book.storageKey, format = staged.format,
                    title = staged.originalName.substringBeforeLast('.').ifBlank { context.getString(R.string.cover_untitled) })
                showPreview(staged, current, first = true)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (current == revision) state = state.copy(error = LocalBookImportFailure.from(failure))
            } finally {
                if (current == revision) state = state.copy(busy = false)
            }
        }
    }

    fun changeTitle(value: String) { if (!state.importing) state = state.copy(title = value) }
    fun changeEncoding(value: String?) {
        if (state.importing) return
        state = state.copy(encoding = value)
        updatePreview()
    }
    fun changeRule(value: String) {
        if (state.importing) return
        state = state.copy(rule = value)
        updatePreview()
    }

    private fun updatePreview() {
        val staged = draft ?: return
        operation?.cancel()
        val current = ++revision
        state = state.copy(busy = true, preview = null, error = null)
        operation = viewModelScope.launch {
            try {
                delay(300)
                showPreview(staged, current)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (current == revision) state = state.copy(error = LocalBookImportFailure.from(failure))
            } finally {
                if (current == revision) state = state.copy(busy = false)
            }
        }
    }

    private suspend fun showPreview(staged: LocalBookDraft, current: Int, first: Boolean = false) {
        val parsed = books.preview(staged, state.encoding, state.rule)
        if (current != revision || draft !== staged) throw CancellationException("Preview was superseded")
        state = state.copy(preview = parsed, title = if (first) parsed.title else state.title,
            cover = parsed.coverPath?.let { File(staged.directory, it).toUri() } ?: Uri.EMPTY, error = null)
    }

    fun confirm() {
        if (!state.canImport) return
        val staged = draft ?: return
        val parsed = state.preview ?: return
        val title = state.title
        state = state.copy(importing = true)
        operation = viewModelScope.launch {
            try {
                val (_, shelfId) = books.publish(staged, parsed, title, null, localShelfName = targetName)
                draft = null
                runCatching { storage.invalidateSnapshot() }.onFailure { Log.e("LocalBookImport", "Cannot invalidate storage estimate", it) }
                state = LocalBookImportState()
                completed.send(shelfId)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                state = state.copy(importing = false, error = LocalBookImportFailure.from(failure))
            } finally {
                if (!currentCoroutineContext().isActive) {
                    draft?.let(::discardLater)
                    draft = null
                }
            }
        }
    }

    fun dismiss() {
        if (state.importing) return
        revision++
        operation?.cancel()
        draft?.let(::discardLater)
        draft = null
        state = LocalBookImportState()
    }

    private fun discardLater(staged: LocalBookDraft) {
        // Bounded cleanup must outlive the dialog/ViewModel that owned this draft.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { books.discard(staged) }.onFailure { Log.e("LocalBookImport", "Cannot remove import draft", it) }
        }
    }

    override fun onCleared() {
        // A confirmed import owns the draft until its file/Room publication finishes.
        if (!state.importing) draft?.let(::discardLater)
        super.onCleared()
    }
}
