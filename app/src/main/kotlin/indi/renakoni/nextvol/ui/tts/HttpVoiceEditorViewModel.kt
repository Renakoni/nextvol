package indi.renakoni.nextvol.ui.tts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hnovel.speech.*
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.*
import indi.renakoni.nextvol.tts.matcha.*
import indi.renakoni.nextvol.tts.offline.OfflineAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class HttpVoiceEditorViewModel @Inject constructor(
    private val repository: HttpSpeechRepository, private val settings: SpeechSettingsRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OfflineVoiceEditorState())
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
                val data = selected.speakerData()
                fun text(key: String, fallback: String = "") = (data[key] as? JsonPrimitive)?.contentOrNull ?: fallback
                val config = OfflineVoiceConfig("http", text("code", id), rawParameters = text("param"), extendUI = text("extendUI"),
                    locale = text("locale"), speed = text("speed", "1").toFloat(), pitch = text("pitch", "1").toFloat(),
                    volume = text("volume", "1").toFloat(), sampleRate = text("sampleRate", "16000").toInt().let { if (it == 0) 16000 else it },
                    gender = text("gender", "0").toInt(), type = text("type", "0").toInt())
                val voice = InstalledOfflineVoice(OfflineVoiceRef(id, "http"), selected.definition.name, emptyMap(), configuration = config,
                    enabled = selected.enabled, metadata = OfflineVoiceMetadata(text("note"), text("desc"), text("avatar")))
                val draft = restored?.let { runCatching { Json.decodeFromString<OfflineVoiceDraft>(it) }.getOrNull() }
                    ?: OfflineVoiceDraft.from(voice).let { if (engineCode != null) it.copy(name = "", code = "")
                        else if (copyPattern == null) it else it.copy(name = String.format(copyPattern, it.name)) }
                OfflineVoiceEditorState(voice, draft, busy = true, packageAvatar = repository.avatar(selected)?.path,
                    controls = runCatching { speechExtensionControls(draft.extensions) }.getOrNull(),
                    availability = OfflineAvailability(if (selected.isConfigured) R.string.tts_offline_unverified else R.string.tts_needs_key,
                        canRun = selected.isConfigured))
            }
            source = selected
            mutableState.value = prepared
        }
    }

    fun edit(draft: OfflineVoiceDraft) {
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

    private fun candidate(source: SavedHttpSpeechSource, original: InstalledOfflineVoice, draft: OfflineVoiceDraft, copy: Boolean): SavedHttpSpeechSource {
        val voice = draft.applyTo(original)
        val configuration = requireNotNull(voice.configuration)
        val fields = buildJsonObject {
            put("code", configuration.code); put("name", voice.name); put("locale", configuration.locale)
            put("param", configuration.rawParameters.orEmpty()); put("extendUI", configuration.extendUI)
            put("speed", configuration.speed); put("pitch", configuration.pitch); put("volume", configuration.volume)
            put("sampleRate", configuration.sampleRate); put("gender", configuration.gender); put("type", configuration.type)
            put("note", voice.metadata.note); put("desc", voice.metadata.description); put("avatar", voice.metadata.avatar)
        }
        return editHttpVoice(source, fields, voice.enabled, copy)
    }

    fun save(copy: Boolean, onSaved: () -> Unit) = work {
        val current = state.value
        val selected = requireNotNull(source)
        val candidate = withContext(Dispatchers.Default) { candidate(selected, requireNotNull(current.voice), requireNotNull(current.draft), copy) }
        withContext(NonCancellable) {
            repository.saveVoice(candidate)
            settings.update { if (it.httpSource == candidate.definition.id) it.copy(httpRevision = UUID.randomUUID().toString()) else it }
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
