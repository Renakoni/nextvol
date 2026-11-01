package indi.renakoni.nextvol.ui.tts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.tts.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SpeechVoiceRoutingState(
    val settings: SpeechSettings = SpeechSettings(),
    val choices: List<SpeechVoiceRef> = emptyList(),
    val loading: Boolean = true,
    val plan: SpeechPlan? = null,
    val preparing: Boolean = false,
    val error: SpeechError? = null,
)

@HiltViewModel
class SpeechVoiceRoutingViewModel @Inject constructor(
    private val settings: SpeechSettingsRepository,
    private val catalog: SpeechVoiceCatalog,
    private val chapters: SpeechChapterSource,
    private val plans: SpeechPlanPreparer,
    private val controller: ReadAloudController,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SpeechVoiceRoutingState())
    val state = mutableState.asStateFlow()
    private var previewJob: Job? = null
    private var chapter: SpeechChapter? = null
    private var context = SpeechContext()

    init {
        viewModelScope.launch {
            settings.changes.collect { value ->
                mutableState.update { it.copy(settings = value) }
                if (chapter != null) prepare()
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val choices = catalog.choices()
                mutableState.update { it.copy(choices = choices) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(error = failure.speechError(SpeechError.Storage)) } }
            finally { mutableState.update { it.copy(loading = false) } }
        }
    }

    fun toggle(dialogue: Boolean, voice: SpeechVoiceRef) = update { value ->
        val configured = value.copy(voiceRoutingConfigured = true)
        val current = if (dialogue) value.dialogueVoices else value.narrationVoices
        val other = if (dialogue) value.narrationVoices else value.dialogueVoices
        if (other.any { it.key == voice.key }) {
            return@update if (dialogue) configured.copy(narrationVoices = other.filterNot { it.key == voice.key })
                else configured.copy(dialogueVoices = other.filterNot { it.key == voice.key })
        }
        val selected = if (current.any { it.key == voice.key }) current.filterNot { it.key == voice.key } else current + voice
        if (dialogue) configured.copy(dialogueVoices = selected) else configured.copy(narrationVoices = selected)
    }

    fun assign(person: String, voice: SpeechVoiceRef?, onSaved: () -> Unit) {
        val bookId = chapter?.bookId ?: return
        update(onSaved) { value ->
            val assignments = value.speakerVoices.takeIf { value.speakerBookId == bookId }.orEmpty()
            value.copy(speakerBookId = bookId,
                speakerVoices = if (voice == null) assignments - person else assignments + (person to voice))
        }
    }

    private fun update(onSaved: () -> Unit = {}, change: (SpeechSettings) -> SpeechSettings) {
        viewModelScope.launch {
            try {
                settings.update(change)
                mutableState.update { it.copy(error = null) }
                onSaved()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = SpeechError.Storage) } }
        }
    }

    fun loadPlan() {
        val playback = controller.state.value
        val request = playback.request?.takeUnless { it.isPreview } ?: return
        previewJob?.cancel()
        mutableState.update { it.copy(preparing = true, plan = null, error = null) }
        previewJob = viewModelScope.launch {
            try {
                chapter = chapters.load(request.bookId, request.chapterId)
                context = playback.planContext
                buildPlan()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(error = failure.speechError(SpeechError.SourceUnavailable)) } }
            finally { if (currentCoroutineContext().isActive) mutableState.update { it.copy(preparing = false) } }
        }
    }

    private fun prepare() {
        previewJob?.cancel()
        mutableState.update { it.copy(preparing = true, error = null) }
        previewJob = viewModelScope.launch {
            try { buildPlan() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(error = failure.speechError(SpeechError.SynthesisFailed)) } }
            finally { if (currentCoroutineContext().isActive) mutableState.update { it.copy(preparing = false) } }
        }
    }

    private suspend fun buildPlan() {
        val loaded = chapter ?: return
        val selected = state.value.settings
        val plan = withContext(Dispatchers.Default) { plans.prepare(loaded, selected, context) }
        mutableState.update { it.copy(plan = plan) }
    }
}
