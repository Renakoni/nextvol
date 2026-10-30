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
        require(id == null || repository.sources().any { it.definition.id == id && it.isConfigured })
        settings.update { it.copy(httpSource = id, offlineVoice = null) }
    }

    fun previewVoice(id: String?, text: String) {
        val selected = selectedSettings ?: return
        if (state.value.busy || id != null && state.value.sources.none { it.definition.id == id && it.isConfigured }) return
        controller.preview(text, selected.copy(httpSource = id, offlineVoice = null))
    }

    fun configure(source: SavedHttpSpeechSource, credentials: Map<String, String>) = work {
        if (state.value.sources.any { isInUse(it.definition.id) && it.group == source.group }) controller.command(SpeechAction.Stop)
        repository.configure(source.definition.id, credentials)
        refreshSources()
        mutableState.update { it.copy(configurationSaved = it.configurationSaved + 1) }
    }

    fun preview(uri: Uri) = work(R.string.tts_import_failed) {
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

    fun previewPackage(sources: List<HttpSpeechDefinition>) {
        if (state.value.busy || sources.isEmpty()) return
        val pending = sources.map { source ->
            SavedHttpSpeechSource(source, SourceOriginCandidates.speech(source.raw).map { it.origin }.distinct())
        }
        mutableState.update { it.copy(pending = pending, invalid = 0, error = null) }
    }

    fun confirmImport() = work {
        val sources = state.value.pending ?: return@work
        if (sources.any { isInUse(it.definition.id) }) controller.command(SpeechAction.Stop)
        repository.save(sources)
        refreshSources()
        mutableState.update { it.copy(pending = null, invalid = 0) }
    }

    fun delete(source: SavedHttpSpeechSource) = work {
        val selected = state.value.selected == source.definition.id
        if (isInUse(source.definition.id)) controller.command(SpeechAction.Stop)
        repository.delete(source.definition.id)
        if (selected) settings.update { it.copy(httpSource = null) }
        refreshSources()
    }

    fun sites(source: SavedHttpSpeechSource, text: String) = work(R.string.tts_source_sites_invalid) {
        val origins = text.lineSequence().map(String::trim).filter(String::isNotEmpty).map {
            requireNotNull(sourceOrigin(it))
        }.distinct().toList()
        require(origins.size <= 32)
        if (isInUse(source.definition.id)) controller.command(SpeechAction.Stop)
        repository.save(listOf(source.copy(origins = origins)))
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
        repository.save(listOf(source.copy(definition = preview.sources.single())))
        refreshSources()
    }

    private fun isInUse(id: String) = state.value.selected == id ||
        controller.state.value.request?.previewSettings?.httpSource == id

    private suspend fun refreshSources() {
        val sources = repository.sources()
        mutableState.update { it.copy(sources = sources) }
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
