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
import kotlinx.coroutines.delay
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
        require(original.engine == "http" || safeSpeechPackagePath(code))
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
        require(candidate.speed.isFinite() && candidate.speed >= 0 && candidate.pitch.isFinite() && candidate.pitch > 0)
        require(candidate.volume.isFinite() && candidate.volume >= 0 && candidate.sampleRate >= 0)
        val configuration = if (candidate.engine == "http") candidate else normalizeOfflineVoice(candidate, voice.sizes.keys)
        require(configuration.engine != "microsoft" || configuration.type == 0)
        return voice.copy(name = name, configuration = configuration, enabled = enabled,
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
    val valid: Boolean = false,
    val packageAvatar: String? = null,
)

@HiltViewModel
class OfflineVoiceEditorViewModel @Inject constructor(
    private val repository: OfflineSpeechRepository,
    private val settings: SpeechSettingsRepository,
    private val availability: OfflineAvailabilityChecker,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OfflineVoiceEditorState())
    val state = mutableState.asStateFlow()
    private var loaded: String? = null
    private var checking: Job? = null

    init {
        viewModelScope.launch { availability.changes.collectLatest { checkDraft() } }

    }

    fun load(id: String, copyPattern: String? = null) {
        if (loaded == id) return
        loaded = id
        work {
            val installed = repository.voices().single { it.reference.id == id }
            val voice = withContext(Dispatchers.IO) { installed.copy(configuration = repository.configuration(installed).copy(engineParameters = "")) }
            val draft = saved.get<String>("voice.draft")?.let { runCatching { Json.decodeFromString<OfflineVoiceDraft>(it) }.getOrNull() }
                ?: OfflineVoiceDraft.from(voice).let { if (copyPattern == null) it else it.copy(name = String.format(copyPattern, it.name)) }
            mutableState.value = OfflineVoiceEditorState(voice, draft, busy = true,
                packageAvatar = repository.avatar(voice)?.path)
            checkDraft()
        }
    }

    fun edit(draft: OfflineVoiceDraft) {
        saved["voice.draft"] = Json.encodeToString(draft)
        mutableState.update { it.copy(draft = draft, error = null) }
        checking?.cancel()
        checking = viewModelScope.launch { delay(150); checkDraft() }
    }

    private suspend fun checkDraft() {
        val snapshot = state.value
        val original = snapshot.voice ?: return
        val draft = snapshot.draft ?: return
        val (valid, status) = withContext(Dispatchers.IO) {
            val candidate = runCatching { draft.applyTo(original) }.getOrNull()
            val result = candidate?.let {
                try { availability.installed(it.copy(enabled = true), repository) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { OfflineAvailability(R.string.tts_voice_save_failed, canRun = false) }
            }
            (candidate != null) to result
        }
        mutableState.update { if (it.draft == draft) it.copy(valid = valid, availability = status) else it }
    }

    fun save(copy: Boolean, onSaved: () -> Unit) = work {
        val current = state.value
        val edited = requireNotNull(current.draft).applyTo(requireNotNull(current.voice))
        withContext(NonCancellable) {
            val result = repository.save(edited, copy)
            if (!copy) settings.update { if (it.offlineVoice?.id == result.reference.id) it.copy(offlineVoice = result.reference) else it }
        }
        onSaved()
    }

    private fun work(action: suspend () -> Unit) = viewModelScope.launch {
        if (state.value.busy) return@launch
        mutableState.update { it.copy(busy = true, error = null) }
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(error = R.string.tts_voice_save_failed) } }
        finally { mutableState.update { it.copy(busy = false) } }
    }
}
