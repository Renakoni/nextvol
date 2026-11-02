package indi.renakoni.nextvol.tts

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.execution.BridgeWire
import hnovel.execution.ExecutionAuthority
import hnovel.network.StorageCipher
import hnovel.network.sourceOrigin
import hnovel.speech.SpeechEngineConfig
import hnovel.speech.HttpSpeechDefinition
import hnovel.speech.SpeechPackageValue
import hnovel.speech.previewHttpSpeech
import hnovel.speech.speechPackageAvatarPath
import hnovel.speech.safeSpeechPackagePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipException
import javax.inject.Inject
import javax.inject.Singleton

data class SavedHttpSpeechSource(
    val definition: HttpSpeechDefinition,
    val origins: List<String>,
    val group: String? = null,
    val credentialKeys: List<String> = emptyList(),
    val credentials: Map<String, String> = emptyMap(),
    val engineReference: String? = null,
    val speakerDefaults: JsonObject? = null,
    val isVoiceCopy: Boolean = false,
    val packageAvatar: String? = null,
    val engineEnabled: Boolean = true,
    val engineDeleted: Boolean = false,
) {
    val enabled: Boolean get() = (definition.raw["nextvolEnabled"] as? JsonPrimitive)?.booleanOrNull != false
    val available: Boolean get() = enabled && engineEnabled && !engineDeleted
    val isBuiltIn: Boolean get() = group != null
    val importedEngine: JsonObject? get() = if (isBuiltIn) null else
        (definition.raw["multiTts"] as? JsonObject)?.get("engine") as? JsonObject
    val importedEngineCode: String? get() = importedEngine?.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "http" }
        ?.get("code")?.let { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    val category: String? get() = group ?: (definition.raw["nextvolEngineName"] as? JsonPrimitive)?.contentOrNull
        ?: ((definition.raw["multiTts"] as? JsonObject)?.get("engine") as? JsonObject)
        ?.let { engine -> (engine["name"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: (engine["code"] as? JsonPrimitive)?.contentOrNull }
    val isConfigured: Boolean get() = credentialKeys.all { !credentials[it].isNullOrBlank() }

    override fun toString() = "SavedHttpSpeechSource(id=${definition.id}, builtIn=$isBuiltIn, configured=$isConfigured)"

    internal fun playbackDefinition(): HttpSpeechDefinition {
        if (!isConfigured) throw SpeechException(SpeechError.HttpLogin)
        if (credentialKeys.isEmpty()) return definition
        val values = JsonObject(credentials.mapValues { JsonPrimitive(it.value) })
        val raw = JsonObject(definition.raw + ("jsLib" to JsonPrimitive("var nextvolCredentials = $values;")))
        return previewHttpSpeech(raw.toString()).sources.single()
    }
}

internal data class HttpSynthesisResult(val source: SavedHttpSpeechSource, val message: Int)

private data class HttpSpeechStore(val sources: List<SavedHttpSpeechSource> = emptyList(), val engines: List<SpeechEngineConfig> = emptyList(),
    val voiceOrder: List<String> = emptyList())

/** User-imported definitions can contain credentials. Persist them with the existing Keystore cipher. */
@Singleton
class HttpSpeechRepository @Inject constructor(@ApplicationContext context: Context, private val cipher: StorageCipher,
    private val authority: ExecutionAuthority) {
    private val file = AtomicFile(File(context.filesDir, "http-speech.enc"))
    private val avatarRoot = File(context.filesDir, "speech-avatars")
    private val runtimeRoot = File(context.filesDir, "speech-runtime")
    private val builtIns by lazy {
        val entries = context.assets.open("speech/builtin-voices.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
        entries.map { entry ->
            val value = entry.jsonObject
            val definition = previewHttpSpeech(value.getValue("definition").toString()).sources.single()
            SavedHttpSpeechSource(definition, value.getValue("origins").jsonArray.map { it.jsonPrimitive.content },
                value.getValue("group").jsonPrimitive.content,
                value["credentials"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty())
        }.also { sources -> require(sources.map { it.definition.id }.distinct().size == sources.size) }
    }
    private val mutex = Mutex()
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()
    private val mutableDeniedOrigins = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val deniedOrigins = mutableDeniedOrigins.asStateFlow()
    private val mutableSynthesisResults = MutableStateFlow<Map<String, HttpSynthesisResult>>(emptyMap())
    internal val synthesisResults = mutableSynthesisResults.asStateFlow()

    internal fun recordSynthesis(source: SavedHttpSpeechSource, message: Int) {
        mutableSynthesisResults.update { it + (source.definition.id to HttpSynthesisResult(source, message)) }
    }

    internal fun recordDeniedOrigins(id: String, origins: List<String>) {
        val safe = origins.filter { sourceOrigin(it) == it }.distinct().take(32)
        if (safe.isNotEmpty()) mutableDeniedOrigins.update { (it + (id to safe)).entries.toList().takeLast(512).associate { entry -> entry.toPair() } }
    }

    suspend fun registerEngine(engine: SpeechEngineConfig) = change(listOf(engine)) { it }

    suspend fun engines(): List<SpeechEngineConfig> = withContext(Dispatchers.IO) { mutex.withLock { readStored().engines } }

    suspend fun saveEngine(engine: SpeechEngineConfig, creating: Boolean = false) = changeStore { store ->
        require(engine.type == "http" && engine.name.isNotBlank() && engine.code.isNotBlank() && engine.code.none(Char::isISOControl))
        val previous = store.engines.find { it.code == engine.code }
        require(!creating || previous != null || engine.code.startsWith("http_") && engine.code.length > 5)
        require(!creating || previous == null || previous.deleted)
        require(creating || previous != null)
        if (!engine.deleted && (previous == null || previous.url != engine.url || previous.header != engine.header))
            newHttpVoice(engine)
        val sources = store.sources.map { if (it.importedEngineCode == engine.code) it.withHttpEngine(engine) else it }
        store.copy(sources = sources, engines = if (previous == null) store.engines + engine else store.engines.map { if (it.code == engine.code) engine else it })
    }

    suspend fun orderEngines(ranks: Map<String, Int>) = changeStore { store ->
        store.copy(engines = store.engines.map { it.copy(order = ranks["http:${it.code}"] ?: it.order) })
    }

    suspend fun sources(): List<SavedHttpSpeechSource> = withContext(Dispatchers.IO) { mutex.withLock { read() } }

    suspend fun orderVoices(ids: List<String>) = changeStore { store ->
        val voices = combined(store.sources, store.engines).orderSpeechVoices(store.voiceOrder) { it.definition.id }
        store.copy(voiceOrder = voices.orderSpeechVoices(ids) { it.definition.id }.map { it.definition.id })
    }

    /** Serialize opening with replacement/removal so an old definition cannot issue a new ticket afterward. */
    internal suspend fun <T> withSource(id: String, open: (SavedHttpSpeechSource, File) -> T): T? = withContext(Dispatchers.IO) {
        mutex.withLock { read().find { it.definition.id == id }?.let { open(it, runtimeDirectory(id)) } }
    }

    private fun runtimeDirectory(id: String) = File(runtimeRoot, speechDigest(id))

    fun avatar(source: SavedHttpSpeechSource): File? = source.packageAvatar?.takeIf {
        safeSpeechPackagePath(it) && '/' !in it
    }?.let { File(avatarRoot, it).takeIf { file -> file.canonicalFile == file.absoluteFile && file.isFile } }

    suspend fun save(sources: List<SavedHttpSpeechSource>, archive: File? = null) = change(sources.mapNotNull { it.httpEngine() }) { current ->
        require(sources.none { it.definition.id.startsWith(BUILT_IN_PREFIX) })
        val merged = current.toMutableList()
        val avatars = if (archive == null) emptyMap() else importAvatars(archive, sources)
        sources.forEach { source ->
            val incoming = source.copy(packageAvatar = avatars[source.definition.id] ?: source.packageAvatar)
            val index = merged.indexOfFirst { it.definition.id == incoming.definition.id ||
                incoming.packageIdentity() != null && it.packageIdentity() == incoming.packageIdentity() }
            val imported = mergeHttpVoice(merged.getOrNull(index), incoming)
            if (index < 0) merged += imported else merged[index] = imported
        }
        merged
    }

    suspend fun saveVoice(source: SavedHttpSpeechSource) = change { current ->
        val previous = combined(current).find { it.definition.id == source.definition.id }
        require(previous != null || !source.definition.id.startsWith(BUILT_IN_PREFIX))
        current.filterNot { it.definition.id == source.definition.id } + source.copy(credentials = previous?.credentials ?: source.credentials)
    }

    suspend fun enable(ids: Set<String>, enabled: Boolean) = change { current ->
        val changed = combined(current).filter { it.definition.id in ids }.map { source ->
            source.copy(definition = previewHttpSpeech(JsonObject(source.definition.raw +
                ("nextvolEnabled" to JsonPrimitive(enabled))).toString()).sources.single())
        }
        current.filterNot { it.definition.id in ids } + changed
    }

    suspend fun delete(id: String) = change { it.filterNot { source -> source.definition.id == id } }

    suspend fun delete(ids: Set<String>) = change { current -> current.filterNot { it.definition.id in ids && !it.definition.id.startsWith(BUILT_IN_PREFIX) } }

    suspend fun configure(id: String, credentials: Map<String, String>) = change { current ->
        val requested = combined(current).first { it.definition.id == id }
        val source = builtIns.first { it.definition.id == (requested.engineReference ?: id) }
        require(source.credentialKeys.isNotEmpty() && credentials.keys.all { it in source.credentialKeys })
        require(credentials.values.all { it.length <= 4096 && it.none(Char::isISOControl) })
        val values = credentials.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        require(values.isEmpty() || source.credentialKeys.all { it in values })
        val related = builtIns.filter { it.group == source.group && it.credentialKeys == source.credentialKeys }
        val ids = related.map { it.definition.id }.toSet()
        current.filterNot { it.definition.id in ids } + combined(current).filter { it.definition.id in ids }.map { it.copy(credentials = values) }
    }

    private suspend fun change(imported: List<SpeechEngineConfig> = emptyList(), update: (List<SavedHttpSpeechSource>) -> List<SavedHttpSpeechSource>) = changeStore { store ->
        val changed = update(store.sources)
        val retained = store.engines.map { old ->
            if (old.deleted) imported.find { it.code == old.code }?.copy(order = old.order) ?: old else old
        }
        val engines = retained + imported
            .distinctBy { it.code }.filter { incoming -> store.engines.none { it.code == incoming.code } }
        store.copy(sources = changed.map { source -> engines.find { it.code == source.importedEngineCode }
            ?.let { source.withHttpEngine(it) } ?: source }, engines = engines)
    }

    private suspend fun changeStore(update: (HttpSpeechStore) -> HttpSpeechStore) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val stored = readStored()
            val previous = combined(stored.sources, stored.engines)
            val updatedStore = update(stored)
            val updated = updatedStore.sources
            val sources = combined(updated, updatedStore.engines)
            require(updated.size <= 512 && updated.map { it.definition.id }.distinct().size == updated.size)
            val entries = JsonArray(updated.map { source ->
                require(source.origins.size <= 32 && source.origins.all { sourceOrigin(it) == it })
                buildJsonObject {
                    put("definition", source.definition.raw); put("origins", JsonArray(source.origins.map(::JsonPrimitive)))
                    if (source.credentials.isNotEmpty()) put("credentials", JsonObject(source.credentials.mapValues { JsonPrimitive(it.value) }))
                    source.engineReference?.let { put("engineReference", it) }
                    source.speakerDefaults?.let { put("speakerDefaults", it) }
                    if (source.isVoiceCopy) put("isVoiceCopy", true)
                    source.packageAvatar?.let { put("packageAvatar", it) }
                }
            })
            val raw = buildJsonObject {
                put("sources", entries)
                put("engines", Json.encodeToJsonElement(updatedStore.engines))
                val ids = sources.map { it.definition.id }.toSet()
                put("voiceOrder", Json.encodeToJsonElement(updatedStore.voiceOrder.filter { it in ids }))
            }.toString().toByteArray(Charsets.UTF_8)
            BridgeWire.validate(raw, MAX_BYTES)
            val encrypted = cipher.seal(raw, IDENTITY)
            val output = file.startWrite()
            try { output.write(encrypted); file.finishWrite(output) }
            catch (failure: Throwable) { file.failWrite(output); throw failure }
            cleanAvatars(updated)
            previous.filter { old -> sources.none { it.definition.id == old.definition.id &&
                old.sameExecution(it) } }.forEach { old ->
                authority.revokeSource(old.definition.id, "speech")
                val runtime = runtimeDirectory(old.definition.id)
                if (runtime.exists() && !runtime.deleteRecursively()) throw java.io.IOException("Speech runtime cleanup failed")
            }
            mutableSynthesisResults.update { results -> results.filter { (id, result) ->
                sources.any { it.definition.id == id && result.source.sameExecution(it) }
            } }
            mutableDeniedOrigins.update { denied -> denied.mapNotNull { (id, origins) ->
                sources.find { it.definition.id == id }?.let { source -> id to (origins - source.origins.toSet()) }
            }.toMap() }
            mutableChanges.update { it + 1 }
        }
    }

    private var recoveredAvatars = false

    private fun read() = readStored().let {
        if (!recoveredAvatars) { cleanAvatars(it.sources); recoveredAvatars = true }
        combined(it.sources, it.engines).orderSpeechVoices(it.voiceOrder) { source -> source.definition.id }
    }

    private fun combined(stored: List<SavedHttpSpeechSource>, engines: List<SpeechEngineConfig> = emptyList()): List<SavedHttpSpeechSource> {
        val overrides = stored.associateBy { it.definition.id }
        val presets = builtIns.map { preset -> overrides[preset.definition.id]?.let {
            preset.copy(definition = it.definition, credentials = it.credentials, speakerDefaults = it.speakerDefaults, packageAvatar = it.packageAvatar)
        } ?: preset }
        return presets + stored.filterNot { it.definition.id.startsWith(BUILT_IN_PREFIX) }.map { source ->
            presets.find { it.definition.id == source.engineReference }?.let { engine ->
                source.copy(credentialKeys = engine.credentialKeys, credentials = engine.credentials)
            } ?: source.let {
                val engine = engines.find { engine -> engine.code == source.importedEngineCode }
                if (engine == null) it else it.copy(engineEnabled = engine.enabled, engineDeleted = engine.deleted)
            }
        }
    }

    private fun readStored(): HttpSpeechStore {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return HttpSpeechStore()
        val encrypted = file.openRead().use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= MAX_BYTES + 512)
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        }
        val raw = cipher.open(encrypted, IDENTITY)
        val document = Json.parseToJsonElement(BridgeWire.validate(raw, MAX_BYTES)).jsonObject
        val entries = document.getValue("sources").jsonArray
        require(entries.size <= 512)
        val preview = previewHttpSpeech(JsonArray(entries.map { it.jsonObject.getValue("definition") }).toString())
        require(preview.issues.isEmpty() && preview.sources.size == entries.size)
        val sources = preview.sources.zip(entries) { source, entry ->
            val origins = entry.jsonObject.getValue("origins").jsonArray.map { it.jsonPrimitive.content }
            require(origins.size <= 32 && origins.all { sourceOrigin(it) == it })
            val credentials = entry.jsonObject["credentials"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
            SavedHttpSpeechSource(source, origins, credentials = credentials,
                engineReference = entry.jsonObject["engineReference"]?.jsonPrimitive?.contentOrNull,
                speakerDefaults = entry.jsonObject["speakerDefaults"] as? JsonObject,
                isVoiceCopy = entry.jsonObject["isVoiceCopy"]?.jsonPrimitive?.booleanOrNull == true,
                packageAvatar = entry.jsonObject["packageAvatar"]?.jsonPrimitive?.contentOrNull)
        }
        val engines = Json.decodeFromJsonElement<List<SpeechEngineConfig>>(document.getValue("engines"))
        val order = document["voiceOrder"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        return HttpSpeechStore(sources, engines, order)
    }

    private fun cleanAvatars(sources: List<SavedHttpSpeechSource>) {
        val retained = sources.mapNotNull { it.packageAvatar }.toSet()
        avatarRoot.listFiles().orEmpty().filter { it.isFile && it.name !in retained }.forEach { it.delete() }
    }

    private fun importAvatars(archive: File, sources: List<SavedHttpSpeechSource>): Map<String, String> {
        val zip = try { ZipFile(archive) } catch (_: ZipException) { return emptyMap() }
        return zip.use {
            val copied = mutableMapOf<String, String>()
            sources.mapNotNull { source ->
                val engine = source.importedEngineCode ?: return@mapNotNull null
                val filename = (source.speakerData()["avatar"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val path = speechPackageAvatarPath(engine, filename) ?: return@mapNotNull null
                val entry = zip.getEntry(path)?.takeUnless { it.isDirectory } ?: return@mapNotNull null
                val stored = copied.getOrPut(path) {
                    avatarRoot.mkdirs()
                    val name = UUID.randomUUID().toString()
                    zip.getInputStream(entry).use { input -> File(avatarRoot, name).outputStream().use { output -> input.copyTo(output) } }
                    name
                }
                source.definition.id to stored
            }.toMap()
        }
    }

    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024
        private const val IDENTITY = "http-speech-definitions-v1"
        private const val BUILT_IN_PREFIX = "nextvol:builtin:"
    }
}

internal fun JsonObject.packageValue(): SpeechPackageValue.Mapping = SpeechPackageValue.Mapping(mapValues { (_, value) ->
    fun convert(element: JsonElement): SpeechPackageValue = when (element) {
        is JsonObject -> element.packageValue()
        is JsonArray -> SpeechPackageValue.Sequence(element.map(::convert), null)
        JsonNull -> SpeechPackageValue.Scalar("null", "tag:yaml.org,2002:null", true)
        is JsonPrimitive -> SpeechPackageValue.Scalar(element.content, null, false)
    }
    convert(value)
}, null)
