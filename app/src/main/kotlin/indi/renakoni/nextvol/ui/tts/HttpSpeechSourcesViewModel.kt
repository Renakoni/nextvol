package indi.renakoni.nextvol.ui.tts

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.imports.SourceOriginCandidates
import hnovel.network.sourceOrigin
import hnovel.speech.previewHttpSpeech
import hnovel.speech.HttpSpeechDefinition
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import javax.inject.Inject

data class HttpSpeechSourcesState(
    val sources: List<SavedHttpSpeechSource> = emptyList(),
    val avatars: Map<String, String> = emptyMap(),
    val selected: String? = null,
    val offlineSelected: Boolean = false,
    val busy: Boolean = false,
    val pending: List<SavedHttpSpeechSource>? = null,
    val invalid: Int = 0,
    val configurationSaved: Long = 0,
    val error: Int? = null,
    val deniedOrigins: Map<String, List<String>> = emptyMap(),
)

@HiltViewModel
class HttpSpeechSourcesViewModel @Inject constructor(
    private val repository: HttpSpeechRepository,
    private val settings: SpeechSettingsRepository,
    val controller: ReadAloudController,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HttpSpeechSourcesState())
    val state = mutableState.asStateFlow()
    private var packageUri: Uri? = null
    private var selectedSettings: SpeechSettings? = null

    init {
        work { refreshSources() }
        viewModelScope.launch { settings.changes.collect { value ->
            selectedSettings = value
            mutableState.update { it.copy(selected = value.httpSource, offlineSelected = value.offlineVoice != null) }
        } }
        viewModelScope.launch { repository.deniedOrigins.collect { value -> mutableState.update { it.copy(deniedOrigins = value) } } }
    }

    fun select(id: String?) = work {
        require(id == null || repository.sources().any { it.definition.id == id && it.isConfigured && it.enabled })
        settings.update { it.copy(httpSource = id, offlineVoice = null) }
    }

    fun previewVoice(id: String?, text: String) {
        val selected = selectedSettings ?: return
        if (state.value.busy || id != null && state.value.sources.none { it.definition.id == id && it.isConfigured && it.enabled }) return
        controller.preview(text, selected.copy(httpSource = id, offlineVoice = null))
    }

    fun configure(source: SavedHttpSpeechSource, credentials: Map<String, String>) = work {
        val engine = state.value.sources.find { it.definition.id == (source.engineReference ?: source.definition.id) } ?: return@work
        val related = state.value.sources.filter { it.group == engine.group && it.credentialKeys == engine.credentialKeys }
            .map { it.definition.id }.toSet()
        if (state.value.sources.any { isInUse(it.definition.id) && (it.definition.id in related || it.engineReference in related) })
            controller.command(SpeechAction.Stop)
        repository.configure(source.definition.id, credentials)
        refreshSources()
        mutableState.update { it.copy(configurationSaved = it.configurationSaved + 1) }
    }

    fun preview(uri: Uri) = work(R.string.tts_import_failed) {
        packageUri = null
        val preview = withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 4 * 1024 * 1024)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("Unreadable import")
            previewHttpSpeech(bytes.toString(Charsets.UTF_8))
        }
        val sources = preview.sources.map { SavedHttpSpeechSource(it, SourceOriginCandidates.speech(it.raw).map { site -> site.origin }.distinct()) }
        mutableState.update { it.copy(pending = sources, invalid = preview.issues.size) }
    }

    fun dismissPreview() { if (!state.value.busy) mutableState.update { it.copy(pending = null, invalid = 0) } }

    fun previewPackage(sources: List<HttpSpeechDefinition>, uri: Uri?) {
        if (state.value.busy || sources.isEmpty()) return
        packageUri = uri
        val pending = sources.map { source ->
            SavedHttpSpeechSource(source, SourceOriginCandidates.speech(source.raw).map { it.origin }.distinct())
        }
        mutableState.update { it.copy(pending = pending, invalid = 0, error = null) }
    }

    fun confirmImport() = work {
        val sources = state.value.pending ?: return@work
        val affected = state.value.sources.filter { old -> sources.any { it.definition.id == old.definition.id ||
            it.packageIdentity() != null && it.packageIdentity() == old.packageIdentity() } }.map { it.definition.id }.toSet()
        if (affected.any(::isInUse)) controller.command(SpeechAction.Stop)
        withContext(NonCancellable) {
            val uri = packageUri
            if (uri == null) repository.save(sources) else withContext(Dispatchers.IO) {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    repository.save(sources, java.io.File("/proc/self/fd/${descriptor.fd}"))
                } ?: error("Unreadable package")
            }
            if (state.value.selected in affected) settings.update { if (it.httpSource in affected) it.copy(httpRevision = java.util.UUID.randomUUID().toString()) else it }
        }
        refreshSources()
        mutableState.update { it.copy(pending = null, invalid = 0) }
    }

    fun delete(source: SavedHttpSpeechSource) = delete(listOf(source))

    fun refresh() = work { refreshSources() }

    fun enable(sources: List<SavedHttpSpeechSource>, enabled: Boolean) = work {
        if (sources.isEmpty()) return@work
        val ids = sources.map { it.definition.id }.toSet()
        if (!enabled && ids.any(::isInUse)) controller.command(SpeechAction.Stop)
        withContext(NonCancellable) {
            repository.enable(ids, enabled)
            settings.update { if (it.httpSource in ids) it.copy(httpRevision = java.util.UUID.randomUUID().toString()) else it }
        }
        refreshSources()
    }

    fun delete(sources: List<SavedHttpSpeechSource>) = work {
        if (sources.isEmpty()) return@work
        val ids = sources.filterNot { it.isBuiltIn }.map { it.definition.id }.toSet()
        if (ids.any(::isInUse)) controller.command(SpeechAction.Stop)
        withContext(NonCancellable) {
            repository.delete(ids)
            if (state.value.selected in ids) settings.update { if (it.httpSource in ids) it.copy(httpSource = null, offlineVoice = null) else it }
        }
        refreshSources()
    }

    fun copy(sources: List<SavedHttpSpeechSource>) = work {
        if (sources.isEmpty()) return@work
        repository.copyVoices(sources.map { it.definition.id }.toSet()) { context.getString(R.string.tts_voice_copy_suffix, it) }
        refreshSources()
    }

    fun sites(source: SavedHttpSpeechSource, text: String) = work(R.string.tts_source_sites_invalid) {
        val origins = text.lineSequence().map(String::trim).filter(String::isNotEmpty).map {
            requireNotNull(sourceOrigin(it))
        }.distinct().toList()
        require(origins.size <= 32)
        if (isInUse(source.definition.id)) controller.command(SpeechAction.Stop)
        repository.saveVoice(source.copy(origins = origins))
        refreshSources()
    }

    fun edit(source: SavedHttpSpeechSource, name: String, url: String, header: String) = work(R.string.tts_source_invalid) {
        val raw = source.definition.raw.toMutableMap().apply {
            this["id"] = this["id"]?.takeUnless { it == JsonNull } ?: JsonPrimitive(source.definition.id)
            this["name"] = JsonPrimitive(name.trim())
            this["url"] = JsonPrimitive(url.trim())
            if (header != source.definition.text("header")) this["header"] = JsonPrimitive(header)
        }
        val preview = previewHttpSpeech(JsonObject(raw).toString())
        require(preview.issues.isEmpty())
        if (isInUse(source.definition.id)) controller.command(SpeechAction.Stop)
        repository.saveVoice(source.copy(definition = preview.sources.single()))
        refreshSources()
        mutableState.update { it.copy(configurationSaved = it.configurationSaved + 1) }
    }

    fun editEngine(source: SavedHttpSpeechSource, name: String, url: String, header: String) = work(R.string.tts_source_invalid) {
        val code = requireNotNull(source.importedEngineCode)
        if (state.value.sources.any { it.importedEngineCode == code && isInUse(it.definition.id) }) controller.command(SpeechAction.Stop)
        repository.editImportedEngine(code, name, url, header)
        refreshSources()
        mutableState.update { it.copy(configurationSaved = it.configurationSaved + 1) }
    }

    private fun isInUse(id: String) = state.value.selected == id ||
        controller.state.value.request?.previewSettings?.httpSource == id

    private suspend fun refreshSources() {
        val sources = repository.sources()
        val avatars = withContext(Dispatchers.IO) { sources.filter { it.packageAvatar != null }.mapNotNull { source -> repository.avatar(source)?.path?.let { source.definition.id to it } }.toMap() }
        mutableState.update { it.copy(sources = sources, avatars = avatars) }
    }

    private fun work(error: Int = R.string.tts_error_storage, action: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = error) } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
}
