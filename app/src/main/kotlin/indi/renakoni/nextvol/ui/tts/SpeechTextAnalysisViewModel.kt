package indi.renakoni.nextvol.ui.tts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.tts.*
import indi.renakoni.nextvol.tts.names.NewWordAlgorithm
import indi.renakoni.nextvol.tts.names.SpeechNameHint
import indi.renakoni.nextvol.data.download.BookDownloadStatus
import indi.renakoni.nextvol.data.download.DownloadFailure
import indi.renakoni.nextvol.data.download.DownloadStage
import indi.renakoni.nextvol.data.download.DownloadTaskState
import indi.renakoni.nextvol.data.download.DownloadTaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Reader
import javax.inject.Inject

data class SpeechTextAnalysisState(
    val loading: Boolean = false,
    val book: SpeechAnalysisBook? = null,
    val sourceName: String = "",
    val running: Boolean = false,
    val saving: Boolean = false,
    val download: BookDownloadStatus? = null,
    val result: SpeechTextAnalysisResult? = null,
    val error: String? = null,
    val speechError: SpeechError? = null,
    val downloadFailure: DownloadFailure? = null,
)

@HiltViewModel
class SpeechTextAnalysisViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val books: SpeechAnalysisBookSource,
    private val analysis: SpeechTextAnalysis,
    private val settings: SpeechSettingsRepository,
) : ViewModel() {
    val bookId = savedStateHandle.toRoute<SpeechTextAnalysisRoute>().bookId
    private val mutableState = MutableStateFlow(SpeechTextAnalysisState(loading = bookId != null))
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var file: Uri? = null
    private var fullBook = false

    init {
        if (bookId != null) job = viewModelScope.launch {
            try {
                val book = books.inspect(bookId)
                mutableState.update { it.copy(loading = false, book = book, sourceName = book.title) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(failure) }
        }
    }

    fun openFile(uri: Uri) {
        file = uri
        start(false)
    }

    fun retry() = start(fullBook)

    fun start(download: Boolean) {
        if (mutableState.value.saving) return
        fullBook = download
        val previous = job
        previous?.cancel()
        job = viewModelScope.launch {
            previous?.join()
            mutableState.update { it.copy(loading = false, running = true, result = null,
                error = null, speechError = null, downloadFailure = null, download = null) }
            try {
                if (bookId != null) {
                    if (download) {
                        mutableState.update { it.copy(download = BookDownloadStatus(
                            task = DownloadTaskState(DownloadTaskStatus.Queued, DownloadStage.Directory))) }
                        books.download(bookId) { progress -> mutableState.update { it.copy(download = progress) } }
                    }
                    val input = books.openCached(bookId)
                    input.reader.use { reader ->
                        mutableState.update { it.copy(book = input.book, sourceName = input.book.title, download = null) }
                        analyze(reader)
                    }
                } else {
                    val uri = file ?: return@launch
                    withContext(Dispatchers.IO) {
                        val resolver = context.contentResolver
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        } ?: uri.lastPathSegment.orEmpty()
                        mutableState.update { it.copy(sourceName = name.substringBeforeLast('.', name)) }
                        resolver.openInputStream(uri)?.bufferedReader()?.use { analyze(it) }
                            ?: throw SpeechException(SpeechError.Storage)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                ensureActive()
                failed(failure)
            }
        }
    }

    private suspend fun analyze(reader: Reader) {
        val text = settings.get().text
        // MultiTTS's ordinary analysis uses Native; its realtime 100/101 selector is unrelated.
        val result = analysis.analyze(reader, NewWordAlgorithm.Native, text.onlyNewPerson,
            text.personNameRegex, text.maxAutoCheckedPerson, text.maleNameRegex, text.femaleNameRegex)
        mutableState.update { it.copy(running = false, result = result) }
    }

    fun toggle(id: String) = editResult { items -> items.map {
        if (it.id == id) it.copy(selected = !it.selected) else it
    } }

    fun gender(id: String) = editResult { items -> items.map {
        if (it.id == id) it.copy(gender = if (it.gender == SpeechNameHint.Male) SpeechNameHint.Female else SpeechNameHint.Male) else it
    } }

    fun edit(id: String, name: String, aliases: List<String>) = editResult { items ->
        val updated = items.map { if (it.id == id) it.copy(name = name.trim(), edited = true,
            aliases = aliases.map(String::trim).filter(String::isNotEmpty).distinct()) else it }
        val target = updated.first { it.id == id }
        val names = (target.aliases + target.name).toSet()
        val merged = updated.filter { it.id != id && (it.aliases + it.name).any(names::contains) }.map { it.id }.toSet()
        mergeSpeechAnalysisCandidates(updated, id, merged)
    }

    private fun editResult(change: (List<SpeechTextAnalysisCandidate>) -> List<SpeechTextAnalysisCandidate>) {
        if (mutableState.value.saving) return
        mutableState.update { state -> state.copy(result = state.result?.let { it.copy(candidates = change(it.candidates)) }) }
    }

    fun save(onSaved: () -> Unit) {
        val state = mutableState.value
        val result = state.result ?: return
        if (state.saving) return
        mutableState.update { it.copy(saving = true, error = null, speechError = null) }
        job = viewModelScope.launch {
            try {
                analysis.save(state.sourceName, result.candidates)
                onSaved()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                ensureActive()
                failed(failure)
            } finally { mutableState.update { it.copy(saving = false) } }
        }
    }

    fun cancel() { job?.cancel() }

    private fun failed(failure: Exception) {
        mutableState.update { state -> state.copy(loading = false, running = false,
            error = failure.takeUnless { it is SpeechException || it is SpeechAnalysisDownloadException }?.let {
                it.message ?: it.javaClass.simpleName
            },
            speechError = (failure as? SpeechException)?.error,
            downloadFailure = (failure as? SpeechAnalysisDownloadException)?.failure,
            download = if (failure is SpeechAnalysisDownloadException) {
                val progress = state.download ?: BookDownloadStatus()
                progress.copy(task = progress.task.copy(
                    status = if (progress.task.status == DownloadTaskStatus.WaitingVerification)
                        DownloadTaskStatus.WaitingVerification else DownloadTaskStatus.Failed,
                    failure = failure.failure))
            } else state.download) }
    }
}
