package indi.renakoni.nextvol.ui.tts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hnovel.speech.*
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.*
import indi.renakoni.nextvol.tts.matcha.*
import indi.renakoni.nextvol.tts.offline.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import javax.inject.Inject

@Serializable
data class OfflineVoiceDraft(
    val name: String, val code: String, val locale: String, val parameters: String, val extensions: String,
    val speed: String, val pitch: String, val volume: String, val sampleRate: String,
    val gender: String, val type: String, val note: String, val description: String, val avatar: String,
    val enabled: Boolean,
) {
    fun applyTo(voice: InstalledOfflineVoice): InstalledOfflineVoice {
        require(name.isNotBlank() && code.matches(Regex("[-_a-zA-Z0-9.:]+")))
        require(listOf(speed, volume, pitch).all { it.matches(Regex("[0-9](\\.[0-9]+)?")) })
        require(pitch.toFloat() in 0.1f..2f)
        require(sampleRate.matches(Regex("[0-9]{4,5}")))
        require(listOf(parameters, extensions, note, description, avatar).all { it.length <= 64 * 1024 })
        val original = requireNotNull(voice.configuration)
        if (locale.isNotEmpty()) {
            val parts = locale.split('-')
            require(parts.size == 2)
            java.util.Locale(parts[0], parts[1]).let { require(it.isO3Language.isNotEmpty() && it.isO3Country.isNotEmpty()) }
        }
        val language = locale.ifEmpty {
            if (name.matches(Regex("^[-+_a-zA-Z0-9() ]+\\d*$"))) "en-US"
            else java.util.Locale.getDefault().let { "${it.language.lowercase()}-${it.country.uppercase()}" }
        }
        val candidate = original.copy(code = code, locale = language, rawParameters = parameters, extendUI = extensions,
            speed = speed.toFloat(), pitch = pitch.toFloat(), volume = volume.toFloat(), sampleRate = sampleRate.toInt(),
            gender = gender.toInt(), type = type.toInt())
        require(candidate.engine != "microsoft" || candidate.type == 0)
        return voice.copy(name = name, configuration = candidate, enabled = enabled,
            metadata = OfflineVoiceMetadata(note, description, avatar.trim()))
    }

    companion object {
        fun from(voice: InstalledOfflineVoice): OfflineVoiceDraft {
            val config = requireNotNull(voice.configuration)
            return OfflineVoiceDraft(voice.name, config.code, config.locale, config.rawParameters ?: config.parameters, config.extendUI,
                config.speed.toString(), config.pitch.toString(), config.volume.toString(), config.sampleRate.takeUnless { it == 0 }?.toString() ?: "16000",
                config.gender.toString(), config.type.toString(), voice.metadata.note, voice.metadata.description, voice.metadata.avatar, voice.enabled)
        }
    }
}

data class OfflineVoiceEditorState(
    val voice: InstalledOfflineVoice? = null, val draft: OfflineVoiceDraft? = null,
    val busy: Boolean = false, val error: Int? = null, val availability: OfflineAvailability? = null,
    val packageAvatar: String? = null,
    val controls: List<SpeechExtensionControl>? = emptyList(),
    val loadingControls: Boolean = false,
)

@HiltViewModel
class OfflineVoiceEditorViewModel @Inject constructor(
    private val repository: OfflineSpeechRepository,
    private val settings: SpeechSettingsRepository,
    private val saved: SavedStateHandle,
    private val msc: MscVoiceEditing,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OfflineVoiceEditorState())
    val state = mutableState.asStateFlow()
    private var loaded: String? = null
    private var loadingControls: Job? = null

    fun load(id: String, copyPattern: String? = null, cachedVoice: InstalledOfflineVoice? = null, newVoice: Boolean = false,
        engineCode: String? = null) {
        val key = engineCode ?: id
        if (loaded == key) return
        loaded = key
        work {
            val restored = saved.get<String>("voice.draft")
            mutableState.value = withContext(Dispatchers.IO) {
                val installed = if (engineCode != null) InstalledOfflineVoice(
                    OfflineVoiceRef(java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString()),
                    "", emptyMap(), configuration = OfflineVoiceConfig(engineCode, "", type = 1,
                        locale = java.util.Locale.getDefault().let { "${it.language}-${it.country}" }))
                else cachedVoice ?: repository.voices().single { it.reference.id == id }
                val voice = installed.copy(configuration = (installed.configuration ?: repository.configuration(installed)).copy(engineParameters = ""))
                val draft = restored?.let { runCatching { Json.decodeFromString<OfflineVoiceDraft>(it) }.getOrNull() }
                    ?: OfflineVoiceDraft.from(voice).let { if (newVoice) it.copy(name = "", note = "", description = "", avatar = "", enabled = true)
                        else if (copyPattern == null) it else it.copy(name = String.format(copyPattern, it.name)) }
                OfflineVoiceEditorState(voice, draft, busy = true,
                    packageAvatar = repository.avatar(voice)?.path,
                    controls = runCatching { speechExtensionControls(draft.extensions) }.getOrNull())
            }
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

    fun save(copy: Boolean, onSaved: () -> Unit, creating: Boolean = false) = work {
        val current = state.value
        val edited = withContext(Dispatchers.Default) { requireNotNull(current.draft).applyTo(requireNotNull(current.voice)) }
        withContext(NonCancellable) {
            if (edited.configuration?.let { it.engine == "msctts" && it.type != 0 } == true)
                msc.toOnline(requireNotNull(current.voice), edited, copy, creating)
            else {
                val result = if (creating) repository.create(edited) else repository.save(edited, copy)
                if (!copy && !creating) settings.update { value ->
                    val previous = requireNotNull(current.voice)
                    val updated = value.updateVoiceSelections(
                        SpeechVoiceRef(offlineVoice = previous.reference, externalId = previous.packageVoiceId()),
                        SpeechVoiceRef(offlineVoice = result.reference, label = result.name, externalId = result.packageVoiceId()))
                    if (updated.offlineVoice?.id == result.reference.id) updated.copy(offlineVoice = result.reference) else updated
                }
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
