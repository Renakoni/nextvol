package indi.renakoni.nextvol.ui.tts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hnovel.speech.*
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.*
import indi.renakoni.nextvol.tts.offline.OfflineAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import javax.inject.Inject

internal fun VoiceDraft.Companion.from(source: SavedHttpSpeechSource, id: String): VoiceDraft {
    val data = source.speakerData()
    fun text(key: String, fallback: String = "") = (data[key] as? JsonPrimitive)?.contentOrNull ?: fallback
    return VoiceDraft(source.definition.name, text("code", id), text("locale"), text("param"), text("extendUI"),
        text("speed", "1").toFloat().toString(), text("pitch", "1").toFloat().toString(), text("volume", "1").toFloat().toString(),
        text("sampleRate", "16000").toInt().let { if (it == 0) 16000 else it }.toString(),
        text("gender", "0").toInt().toString(), text("type", "0").toInt().toString(),
        text("note"), text("desc"), text("avatar"), source.enabled)
}

internal fun VoiceFields.speakerFields(): JsonObject = buildJsonObject {
    put("code", code); put("name", name); put("locale", locale)
    put("param", parameters); put("extendUI", extensions)
    put("speed", speed); put("pitch", pitch); put("volume", volume)
    put("sampleRate", sampleRate); put("gender", gender); put("type", type)
    put("note", note); put("desc", description); put("avatar", avatar)
}

@HiltViewModel
class HttpVoiceEditorViewModel @Inject constructor(
    private val repository: HttpSpeechRepository,
    private val saved: SavedStateHandle,
    private val msc: MscVoiceEditing,
) : ViewModel() {
    private val mutableState = MutableStateFlow(VoiceEditorState())
    val state = mutableState.asStateFlow()
    private var source: SavedHttpSpeechSource? = null
    private var loadingControls: Job? = null
    val hasParameters get() = source?.importedEngineCode != null

    fun load(id: String, copyPattern: String? = null, cachedSource: SavedHttpSpeechSource? = null, engineCode: String? = null) {
        if (source != null) return
        work {
            val selected = cachedSource ?: withContext(Dispatchers.IO) {
                if (engineCode != null) newHttpVoice(repository.engines().single { it.code == engineCode && !it.deleted })
                else repository.sources().single { it.definition.id == id }
            }
            val restored = saved.get<String>("voice.draft")
            val prepared = withContext(Dispatchers.IO) {
                val initial = VoiceDraft.from(selected, id)
                val draft = restored?.let { runCatching { Json.decodeFromString<VoiceDraft>(it) }.getOrNull() }
                    ?: initial.let { if (engineCode != null) it.copy(name = "", code = "")
                        else if (copyPattern == null) it else it.copy(name = String.format(copyPattern, it.name)) }
                VoiceEditorState(draft, emptyCode = initial.code.isEmpty(), busy = true, packageAvatar = repository.avatar(selected)?.path,
                    controls = runCatching { speechExtensionControls(draft.extensions) }.getOrNull(),
                    availability = OfflineAvailability(if (selected.isConfigured) R.string.tts_offline_unverified else R.string.tts_needs_key,
                        canRun = selected.isConfigured))
            }
            source = selected
            mutableState.value = prepared
        }
    }

    fun edit(draft: VoiceDraft) {
        val extensionsChanged = state.value.draft?.extensions != draft.extensions
        saved["voice.draft"] = Json.encodeToString(draft)
        mutableState.update { it.copy(draft = draft, loadingControls = extensionsChanged || it.loadingControls, error = null) }
        if (!extensionsChanged) return
        loadingControls?.cancel()
        loadingControls = viewModelScope.launch {
            val controls = withContext(Dispatchers.Default) { runCatching { speechExtensionControls(draft.extensions) }.getOrNull() }
            mutableState.update { if (it.draft?.extensions == draft.extensions) it.copy(controls = controls, loadingControls = false) else it }
        }
    }

    fun save(copy: Boolean, onSaved: () -> Unit) = work {
        val current = state.value
        val selected = requireNotNull(source)
        val edited = withContext(Dispatchers.Default) { requireNotNull(current.draft).fields() }
        withContext(NonCancellable) {
            if (selected.httpEngine()?.let { it.type != "http" && it.code in setOf("msctts", "microsoft") } == true &&
                edited.type == 0) msc.toOffline(selected, edited.speakerFields(), edited.enabled, copy)
            else {
                val candidate = withContext(Dispatchers.Default) { editHttpVoice(selected, edited.speakerFields(), edited.enabled, copy) }
                repository.saveVoice(candidate)
                if (!copy) msc.replaceReference(
                    SpeechVoiceRef(httpSource = selected.definition.id, externalId = selected.packageVoiceId()),
                    SpeechVoiceRef(httpSource = candidate.definition.id, label = candidate.definition.name, externalId = candidate.packageVoiceId()))
            }
        }
        onSaved()
    }

    private fun work(action: suspend () -> Unit) = viewModelScope.launch {
        if (state.value.busy) return@launch
        mutableState.update { it.copy(busy = true, error = null) }
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalArgumentException) { mutableState.update { it.copy(error = R.string.tts_voice_invalid) } }
        catch (_: Exception) { mutableState.update { it.copy(error = R.string.tts_voice_save_failed) } }
        finally { mutableState.update { it.copy(busy = false) } }
    }
}
