package indi.renakoni.nextvol.ui.tts

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.BackgroundMusicRepository
import indi.renakoni.nextvol.tts.BackgroundMusicSettings
import indi.renakoni.nextvol.tts.forScheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class BackgroundMusicUiState(
    val settings: BackgroundMusicSettings = BackgroundMusicSettings(),
    val unavailable: Set<String> = emptySet(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: Int? = null,
)

@HiltViewModel
internal class BackgroundMusicViewModel @Inject constructor(private val repository: BackgroundMusicRepository,
    private val editor: SpeechSettingsEditor) : ViewModel() {
    private val mutableState = MutableStateFlow(BackgroundMusicUiState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.changes, editor.changes, repository.unavailable) { library, settings, unavailable ->
                library.forScheme(settings.backgroundMusic) to unavailable }
                .catch { mutableState.update { it.copy(loading = false, error = R.string.tts_music_save_failed) } }
                .collect { (settings, unavailable) ->
                    mutableState.update { it.copy(settings = settings, unavailable = unavailable, loading = false) }
                }
        }
    }

    fun refresh() = update { repository.refreshAvailability() }
    fun select(id: String, selected: Boolean) = update { editor.update { it.copy(backgroundMusic = it.backgroundMusic.copy(selected =
        if (selected) it.backgroundMusic.selected + id else it.backgroundMusic.selected - id)) } }
    fun volume(value: Float) = update { editor.update { it.copy(backgroundMusic = it.backgroundMusic.copy(volume = value)) } }
    fun delete(id: String) = update { repository.delete(id) }
    fun importTracks(uris: List<Uri>, replacingId: String? = null) = update(R.string.tts_music_import_failed) {
        repository.importTracks(uris, replacingId)
    }

    private fun update(error: Int = R.string.tts_music_save_failed, operation: suspend () -> Unit) {
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = error) } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
}
