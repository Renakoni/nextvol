package indi.renakoni.nextvol.ui.tts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.sovits.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal enum class SovitsImportField(val suffix: String?) { Gpt(".ckpt"), Sovits(".pth"), Audio(null) }

internal data class SovitsImportState(
    val draft: SovitsImportDraft = SovitsImportDraft(),
    val picking: Boolean = false,
    val busy: Boolean = false,
    val cancelling: Boolean = false,
    val progress: SovitsImportMessage? = null,
    val error: SovitsImportMessage? = null,
    val completed: Boolean = false,
)

@HiltViewModel
internal class SovitsImportViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val importer: SovitsWeightImport,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SovitsImportState())
    val state = mutableState.asStateFlow()
    private var importing: Job? = null

    fun edit(draft: SovitsImportDraft) {
        if (!state.value.busy && !state.value.picking) mutableState.update { it.copy(draft = draft, error = null) }
    }

    fun pickAudio(uri: Uri) {
        if (state.value.busy || state.value.picking) return
        mutableState.update { it.copy(picking = true, error = null) }
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
                }
                mutableState.update { it.copy(draft = it.draft.copy(audio = SovitsImportFile(uri, name))) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = SovitsImportMessage(R.string.sovits_file_unreadable)) } }
            finally { mutableState.update { it.copy(picking = false) } }
        }
    }

    fun install() {
        val current = state.value
        if (current.busy || current.picking || !current.draft.ready) return
        mutableState.update { it.copy(busy = true, error = null, completed = false) }
        importing = viewModelScope.launch {
            try {
                importer.install(current.draft) { message -> mutableState.update { it.copy(progress = message) } }
                mutableState.update { it.copy(completed = true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: SovitsImportException) { mutableState.update { it.copy(error = failure.text) } }
            catch (failure: Exception) {
                android.util.Log.w("NextVolSoVITS", "Weight import failed", failure)
                mutableState.update { it.copy(error = SovitsImportMessage(R.string.sovits_import_failed)) }
            } finally { mutableState.update { it.copy(busy = false, cancelling = false, progress = null) } }
        }
    }

    fun cancel() {
        if (!state.value.busy) return
        mutableState.update { it.copy(cancelling = true) }
        importing?.cancel()
    }

}
