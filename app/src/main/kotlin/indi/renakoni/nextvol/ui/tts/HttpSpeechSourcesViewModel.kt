package indi.renakoni.nextvol.ui.tts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.imports.SourceOriginCandidates
import hnovel.network.sourceOrigin
import hnovel.speech.previewHttpSpeech
import hnovel.speech.HttpSpeechDefinition
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.logging.LoggerRepository
import indi.renakoni.nextvol.data.logging.SpeechLogRecord
import indi.renakoni.nextvol.data.logging.SpeechLogStage
import indi.renakoni.nextvol.data.logging.SpeechLogEvent
import indi.renakoni.nextvol.tts.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val loaded: Boolean = false,
    val pending: List<SavedHttpSpeechSource>? = null,
    val invalid: Int = 0,
    val configurationSaved: Long = 0,
    val error: Int? = null,
    val deniedOrigins: Map<String, List<String>> = emptyMap(),
)

@HiltViewModel
class HttpSpeechSourcesViewModel @Inject constructor(
    private val repository: HttpSpeechRepository,
    private val settings: SpeechSettingsEditor,
    private val logger: LoggerRepository,
    val controller: ReadAloudController,
    @ApplicationContext private val context: Context,
    private val catalog: SpeechVoiceCatalog,
    private val engineCatalog: SpeechEngineCatalogService,
    private val references: SpeechVoiceReferences,
    private val operations: SpeechVoiceOperations,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HttpSpeechSourcesState())
    val state = mutableState.asStateFlow()
    private var packageUri: Uri? = null
    private var selectedSettings: SpeechSettings? = null
    private var refreshing: Job? = null

    init {
        viewModelScope.launch { repository.changes.collect { refresh() } }
        viewModelScope.launch { settings.changes.collect { value ->
            selectedSettings = value
            logger.speechDebugEnabled = value.diagnosticLogging
            mutableState.update { it.copy(selected = value.httpSource, offlineSelected = value.offlineVoice != null) }
        } }
        viewModelScope.launch { repository.deniedOrigins.collect { value -> mutableState.update { it.copy(deniedOrigins = value) } } }
    }

    fun select(id: String?) = work {
        require(id == null || state.value.sources.any { it.definition.id == id && it.isConfigured && it.available })
        settings.update { it.copy(httpSource = id, offlineVoice = null) }
    }

    fun addToPool(group: String, voices: List<SpeechVoiceRef>, onAdded: (Int) -> Unit) = work {
        var added = 0
        val references = catalog.packageReferences()
        settings.update { current ->
            val existing = current.voiceItems.filter { it.group == group }
                .map { it.value.resolvePackageReference(references).canonicalKey }.toSet()
            val items = voices.map { it.resolvePackageReference(references) }
                .distinctBy { it.canonicalKey }.filterNot { it.canonicalKey in existing }
                .map { SpeechVoiceItem(group = group, value = it, name = it.label) }
            added = items.size
            current.copy(voiceItems = current.voiceItems + items)
        }
        onAdded(added)
    }

    fun previewVoice(id: String?, text: String) {
        val selected = selectedSettings ?: return
        if (state.value.busy || id != null && state.value.sources.none { it.definition.id == id && it.isConfigured && it.available }) return
        val label = if (id == null) selected.voice.ifBlank { context.getString(R.string.tts_system_speech) }
            else state.value.sources.first { it.definition.id == id }.definition.name
        controller.previewVoice(text, selected.copy(httpSource = id, offlineVoice = null), label)
    }

    fun configure(source: SavedHttpSpeechSource, credentials: Map<String, String>) = work {
        val engine = state.value.sources.find { it.definition.id == (source.engineReference ?: source.definition.id) } ?: return@work
        val related = state.value.sources.filter { it.group == engine.group && it.credentialKeys == engine.credentialKeys }
            .map { it.definition.id }.toSet()
        val affected = state.value.sources.filter { it.definition.id in related || it.engineReference in related }
            .map { it.definition.id }.toSet()
        if (isInUse(affected))
            controller.command(SpeechAction.Stop)
        repository.configure(source.definition.id, credentials)
        mutableState.update { it.copy(configurationSaved = it.configurationSaved + 1) }
    }

    fun preview(uri: Uri, onPackage: (Uri) -> Unit) = work(R.string.tts_import_failed, SpeechLogStage.Import) {
        packageUri = null
        val preview = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }.orEmpty()
            val input = resolver.openInputStream(uri)?.buffered() ?: error("Unreadable import")
            input.use {
                input.mark(4)
                val zip = input.read() == 0x50 && input.read() == 0x4b
                input.reset()
                if (zip || name.substringAfterLast('.').lowercase() in setOf("yaml", "yml")) return@withContext null
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 4 * 1024 * 1024)
                    output.write(buffer, 0, count)
                }
                previewHttpSpeech(output.toString(Charsets.UTF_8.name()))
            }
        }
        if (preview == null) {
            onPackage(uri)
            return@work
        }
        val sources = preview.sources.map { SavedHttpSpeechSource(it, SourceOriginCandidates.speech(it.raw).map { site -> site.origin }.distinct()) }
        mutableState.update { it.copy(pending = sources, invalid = preview.issues.size) }
        if (preview.issues.isNotEmpty()) logger.recordSpeech(SpeechLogRecord(SpeechLogStage.Import,
            SpeechLogEvent.Failed, SpeechError.UnsupportedContent))
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

    fun confirmImport() = work(stage = SpeechLogStage.Import) {
        val sources = state.value.pending ?: return@work
        val affected = state.value.sources.filter { old -> sources.any { it.definition.id == old.definition.id ||
            it.packageIdentity() != null && it.packageIdentity() == old.packageIdentity() } }.map { it.definition.id }.toSet()
        if (isInUse(affected)) controller.command(SpeechAction.Stop)
        withContext(NonCancellable) {
            val uri = packageUri
            if (uri == null) repository.save(sources) else withContext(Dispatchers.IO) {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    repository.save(sources, java.io.File("/proc/self/fd/${descriptor.fd}"))
                } ?: error("Unreadable package")
            }
        }
        mutableState.update { it.copy(pending = null, invalid = 0) }
        logger.recordSpeech(SpeechLogRecord(SpeechLogStage.Import, SpeechLogEvent.Completed))
    }

    fun delete(source: SavedHttpSpeechSource) = delete(listOf(source))

    fun order(ids: List<String>) = work { repository.orderVoices(ids) }

    fun refresh() {
        refreshing?.cancel()
        refreshing = viewModelScope.launch {
            try { refreshSources() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = R.string.tts_error_storage, loaded = true) } }
        }
    }

    fun refreshCatalog() = work {
        val error = engineCatalog.refresh()
        refreshSources()
        if (error != null) {
            logger.recordSpeech(SpeechLogRecord(SpeechLogStage.Configuration, SpeechLogEvent.Failed, error))
            mutableState.update { it.copy(error = error.messageResource) }
        }
    }

    fun enable(sources: List<SavedHttpSpeechSource>, enabled: Boolean) = work {
        operations.enableHttp(sources, enabled)
    }

    fun delete(sources: List<SavedHttpSpeechSource>) = work {
        operations.deleteHttp(sources)
    }

    fun sites(source: SavedHttpSpeechSource, text: String) = work(R.string.tts_source_sites_invalid) {
        val origins = text.lineSequence().map(String::trim).filter(String::isNotEmpty).map {
            requireNotNull(sourceOrigin(it))
        }.distinct().toList()
        require(origins.size <= 32)
        if (isInUse(setOf(source.definition.id))) controller.command(SpeechAction.Stop)
        repository.saveVoice(source.copy(origins = origins))
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
        if (isInUse(setOf(source.definition.id))) controller.command(SpeechAction.Stop)
        val updated = preview.sources.single()
        repository.saveVoice(source.copy(definition = updated))
        references.replaceReference(SpeechVoiceRef(httpSource = source.definition.id, externalId = source.packageVoiceId()),
            SpeechVoiceRef(httpSource = updated.id, label = updated.name, externalId = source.packageVoiceId()))
        mutableState.update { it.copy(configurationSaved = it.configurationSaved + 1) }
    }

    private suspend fun isInUse(ids: Set<String>): Boolean {
        val request = controller.state.value.request ?: return false
        val active = request.previewSettings ?: settings.forBook(request.bookId)
        return active.references().any { it.httpSource in ids }
    }

    private suspend fun refreshSources() {
        val sources = repository.sources()
        mutableState.update { it.copy(sources = sources, loaded = true) }
        val avatars = withContext(Dispatchers.IO) { sources.filter { it.packageAvatar != null }.mapNotNull { source -> repository.avatar(source)?.path?.let { source.definition.id to it } }.toMap() }
        mutableState.update { it.copy(avatars = avatars) }
    }

    private fun work(error: Int = R.string.tts_error_storage, stage: SpeechLogStage = SpeechLogStage.Configuration, action: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                logger.recordSpeech(SpeechLogRecord(stage, SpeechLogEvent.Failed, failure.speechError(SpeechError.Storage)))
                mutableState.update { it.copy(error = error, loaded = true) }
            }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
}
