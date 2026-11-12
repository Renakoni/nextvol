package indi.renakoni.nextvol.ui.tts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hnovel.speech.*
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.*
import indi.renakoni.nextvol.tts.matcha.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import javax.inject.Inject

internal fun VoiceFields.applyTo(voice: InstalledOfflineVoice): InstalledOfflineVoice {
    val original = requireNotNull(voice.configuration)
    val candidate = original.copy(code = code, locale = locale, rawParameters = parameters, extendUI = extensions,
        speed = speed, pitch = pitch, volume = volume, sampleRate = sampleRate,
        gender = gender, type = type)
    return voice.copy(name = name, configuration = candidate, enabled = enabled,
        metadata = OfflineVoiceMetadata(note, description, avatar))
}

internal fun VoiceDraft.Companion.from(voice: InstalledOfflineVoice): VoiceDraft {
    val config = requireNotNull(voice.configuration)
    return VoiceDraft(voice.name, config.code, config.locale, config.rawParameters ?: config.parameters, config.extendUI,
        config.speed.toString(), config.pitch.toString(), config.volume.toString(), config.sampleRate.takeUnless { it == 0 }?.toString() ?: "16000",
        config.gender.toString(), config.type.toString(), voice.metadata.note, voice.metadata.description, voice.metadata.avatar, voice.enabled)
}

@HiltViewModel
class OfflineVoiceEditorViewModel @Inject constructor(
    private val repository: OfflineSpeechRepository,
    private val saved: SavedStateHandle,
    private val msc: MscVoiceEditing,
    private val references: SpeechVoiceReferences,
    private val operations: SpeechVoiceOperations,
) : ViewModel() {
    private val mutableState = MutableStateFlow(VoiceEditorState())
    val state = mutableState.asStateFlow()
    private var voice: InstalledOfflineVoice? = null
    private var loaded: String? = null
    private var loadingControls: Job? = null

    fun load(id: String, copyPattern: String? = null, cachedVoice: InstalledOfflineVoice? = null, newVoice: Boolean = false,
        engineCode: String? = null) {
        val key = engineCode ?: id
        if (loaded == key) return
        loaded = key
        work {
            val restored = saved.get<String>("voice.draft")
            val (original, prepared) = withContext(Dispatchers.IO) {
                val installed = if (engineCode != null) InstalledOfflineVoice(
                    OfflineVoiceRef(java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString()),
                    "", emptyMap(), configuration = OfflineVoiceConfig(engineCode, "", type = 1,
                        locale = java.util.Locale.getDefault().let { "${it.language}-${it.country}" }))
                else cachedVoice ?: repository.voices().single { it.reference.id == id }
                val original = installed.copy(configuration = (installed.configuration ?: repository.configuration(installed)).copy(engineParameters = ""))
                val draft = restored?.let { runCatching { Json.decodeFromString<VoiceDraft>(it) }.getOrNull() }
                    ?: VoiceDraft.from(original).let { if (newVoice) it.copy(name = "", note = "", description = "", avatar = "", enabled = true)
                        else if (copyPattern == null) it else it.copy(name = String.format(copyPattern, it.name)) }
                original to VoiceEditorState(draft, emptyCode = original.configuration?.code.isNullOrEmpty(), busy = true,
                    packageAvatar = repository.avatar(original)?.path,
                    controls = runCatching { speechExtensionControls(draft.extensions) }.getOrNull())
            }
            voice = original
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

    fun toggleEnabled(onSaved: () -> Unit) = work {
        val original = requireNotNull(voice)
        operations.enableOffline(listOf(original), !original.enabled)
        onSaved()
    }

    fun save(copy: Boolean, onSaved: () -> Unit, creating: Boolean = false) = work {
        val current = state.value
        val original = requireNotNull(voice)
        val edited = withContext(Dispatchers.Default) { requireNotNull(current.draft).fields().applyTo(original) }
        withContext(NonCancellable) {
            if (edited.configuration?.let { it.engine in setOf("msctts", "microsoft") && it.type != 0 } == true)
                msc.toOnline(original, edited, copy, creating)
            else {
                val result = if (creating) repository.create(edited) else repository.save(edited, copy)
                if (!copy && !creating) {
                    references.replaceReference(
                        SpeechVoiceRef(offlineVoice = original.reference, externalId = original.packageVoiceId()),
                        SpeechVoiceRef(offlineVoice = result.reference, label = result.name, externalId = result.packageVoiceId()))
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
