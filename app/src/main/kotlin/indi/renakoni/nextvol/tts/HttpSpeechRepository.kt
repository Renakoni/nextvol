package indi.renakoni.nextvol.tts

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.execution.BridgeWire
import hnovel.execution.ExecutionAuthority
import hnovel.network.StorageCipher
import hnovel.network.sourceOrigin
import hnovel.speech.HttpSpeechDefinition
import hnovel.speech.SpeechPackageEngine
import hnovel.speech.SpeechPackageSupport
import hnovel.speech.SpeechPackageValue
import hnovel.speech.SpeechPackageVoice
import hnovel.speech.previewHttpSpeech
import hnovel.speech.previewHttpSpeechPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class SavedHttpSpeechSource(
    val definition: HttpSpeechDefinition,
    val origins: List<String>,
    val group: String? = null,
    val credentialKeys: List<String> = emptyList(),
    val credentials: Map<String, String> = emptyMap(),
) {
    val isBuiltIn: Boolean get() = group != null
    val importedEngine: JsonObject? get() = if (isBuiltIn) null else
        (definition.raw["multiTts"] as? JsonObject)?.get("engine") as? JsonObject
    val importedEngineCode: String? get() = importedEngine?.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "http" }
        ?.get("code")?.let { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    val category: String? get() = group ?: ((definition.raw["multiTts"] as? JsonObject)?.get("engine") as? JsonObject)
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

/** User-imported definitions can contain credentials. Persist them with the existing Keystore cipher. */
@Singleton
class HttpSpeechRepository @Inject constructor(@ApplicationContext context: Context, private val cipher: StorageCipher,
    private val authority: ExecutionAuthority) {
    private val file = AtomicFile(File(context.filesDir, "http-speech.enc"))
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
    private val mutableDeniedOrigins = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val deniedOrigins = mutableDeniedOrigins.asStateFlow()

    internal fun recordDeniedOrigins(id: String, origins: List<String>) {
        val safe = origins.filter { sourceOrigin(it) == it }.distinct().take(32)
        if (safe.isNotEmpty()) mutableDeniedOrigins.update { (it + (id to safe)).entries.toList().takeLast(512).associate { entry -> entry.toPair() } }
    }

    suspend fun sources(): List<SavedHttpSpeechSource> = withContext(Dispatchers.IO) { mutex.withLock { read() } }

    /** Serialize opening with replacement/removal so an old definition cannot issue a new ticket afterward. */
    internal suspend fun <T> withSource(id: String, open: (SavedHttpSpeechSource, File) -> T): T? = withContext(Dispatchers.IO) {
        mutex.withLock { read().find { it.definition.id == id }?.let { open(it, runtimeDirectory(id)) } }
    }

    private fun runtimeDirectory(id: String) = File(runtimeRoot, speechDigest(id))

    suspend fun save(sources: List<SavedHttpSpeechSource>) = change { current ->
        require(sources.none { it.definition.id.startsWith(BUILT_IN_PREFIX) })
        val incoming = sources.associateBy { it.definition.id }
        current.map { incoming[it.definition.id] ?: it } + sources.filter { item -> current.none { it.definition.id == item.definition.id } }
    }

    suspend fun delete(id: String) = change { it.filterNot { source -> source.definition.id == id } }

    /** Rebuild each voice's request from the shared template while preserving its identity and local settings. */
    suspend fun editImportedEngine(code: String, name: String, url: String, header: String) = change { current ->
        require(name.isNotBlank() && current.any { it.importedEngineCode == code })
        current.map { source ->
            if (source.importedEngineCode != code) source else {
                val metadata = source.definition.raw.getValue("multiTts").jsonObject
                val engine = JsonObject(requireNotNull(source.importedEngine) + mapOf(
                    "name" to JsonPrimitive(name.trim()), "url" to JsonPrimitive(url.trim()), "header" to JsonPrimitive(header)))
                val voice = metadata.getValue("voice").jsonObject
                val converted = previewHttpSpeechPackage(SpeechPackageEngine(code, name.trim(), "http", engine.packageValue(),
                    listOf(SpeechPackageVoice(voice.getValue("code").jsonPrimitive.content, source.definition.name, voice.packageValue())),
                    SpeechPackageSupport.HttpAdapter, emptyList(), emptyList()))
                require(converted.error == null && converted.sources.size == 1)
                val generated = converted.sources.single()
                val raw = source.definition.raw.toMutableMap().apply {
                    put("id", JsonPrimitive(source.definition.id))
                    put("url", generated.raw.getValue("url"))
                    if (header.isBlank()) remove("header") else put("header", JsonPrimitive(header))
                    put("multiTts", JsonObject(metadata + ("engine" to engine)))
                }
                source.copy(definition = previewHttpSpeech(JsonObject(raw).toString()).sources.single())
            }
        }
    }

    suspend fun configure(id: String, credentials: Map<String, String>) = change { current ->
        val source = builtIns.first { it.definition.id == id }
        require(source.credentialKeys.isNotEmpty() && credentials.keys.all { it in source.credentialKeys })
        require(credentials.values.all { it.length <= 4096 && it.none(Char::isISOControl) })
        val values = credentials.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        require(values.isEmpty() || source.credentialKeys.all { it in values })
        val related = builtIns.filter { it.group == source.group && it.credentialKeys == source.credentialKeys }
        val ids = related.map { it.definition.id }.toSet()
        current.filterNot { it.definition.id in ids } + if (values.isEmpty()) emptyList() else related.map { it.copy(credentials = values) }
    }

    private suspend fun change(update: (List<SavedHttpSpeechSource>) -> List<SavedHttpSpeechSource>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val stored = readStored()
            val previous = combined(stored)
            val updated = update(stored)
            require(updated.size <= 512 && updated.map { it.definition.id }.distinct().size == updated.size)
            val raw = JsonArray(updated.map { source ->
                require(source.origins.size <= 32 && source.origins.all { sourceOrigin(it) == it })
                buildJsonObject {
                    put("definition", source.definition.raw); put("origins", JsonArray(source.origins.map(::JsonPrimitive)))
                    if (source.credentials.isNotEmpty()) put("credentials", JsonObject(source.credentials.mapValues { JsonPrimitive(it.value) }))
                }
            }).toString().toByteArray(Charsets.UTF_8)
            require(raw.size <= MAX_BYTES)
            BridgeWire.validate(raw, MAX_BYTES)
            val encrypted = cipher.seal(raw, IDENTITY)
            val output = file.startWrite()
            try { output.write(encrypted); file.finishWrite(output) }
            catch (failure: Throwable) { file.failWrite(output); throw failure }
            val sources = combined(updated)
            previous.filter { old -> sources.none { it.definition.id == old.definition.id &&
                it.definition.revision == old.definition.revision && it.origins == old.origins && it.credentials == old.credentials } }.forEach { old ->
                authority.revokeSource(old.definition.id, "speech")
                val runtime = runtimeDirectory(old.definition.id)
                if (runtime.exists() && !runtime.deleteRecursively()) throw java.io.IOException("Speech runtime cleanup failed")
            }
            mutableDeniedOrigins.update { denied -> denied.mapNotNull { (id, origins) ->
                sources.find { it.definition.id == id }?.let { source -> id to (origins - source.origins.toSet()) }
            }.toMap() }
        }
    }

    private fun read() = combined(readStored())

    private fun combined(stored: List<SavedHttpSpeechSource>): List<SavedHttpSpeechSource> {
        val credentials = stored.associate { it.definition.id to it.credentials }
        return builtIns.map { it.copy(credentials = credentials[it.definition.id].orEmpty()) } +
            stored.filterNot { it.definition.id.startsWith(BUILT_IN_PREFIX) }
    }

    private fun readStored(): List<SavedHttpSpeechSource> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
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
        val entries = Json.parseToJsonElement(BridgeWire.validate(raw, MAX_BYTES)).jsonArray
        require(entries.size <= 512)
        val preview = previewHttpSpeech(JsonArray(entries.map { it.jsonObject.getValue("definition") }).toString())
        require(preview.issues.isEmpty() && preview.sources.size == entries.size)
        return preview.sources.zip(entries) { source, entry ->
            val origins = entry.jsonObject.getValue("origins").jsonArray.map { it.jsonPrimitive.content }
            require(origins.size <= 32 && origins.all { sourceOrigin(it) == it })
            val credentials = entry.jsonObject["credentials"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
            SavedHttpSpeechSource(source, origins, credentials = credentials)
        }
    }

    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024
        private const val IDENTITY = "http-speech-definitions-v1"
        private const val BUILT_IN_PREFIX = "nextvol:builtin:"
    }
}

private fun JsonObject.packageValue(): SpeechPackageValue.Mapping = SpeechPackageValue.Mapping(mapValues { (_, value) ->
    fun convert(element: JsonElement): SpeechPackageValue = when (element) {
        is JsonObject -> element.packageValue()
        is JsonArray -> SpeechPackageValue.Sequence(element.map(::convert), null)
        JsonNull -> SpeechPackageValue.Scalar("null", "tag:yaml.org,2002:null", true)
        is JsonPrimitive -> SpeechPackageValue.Scalar(element.content, null, false)
    }
    convert(value)
}, null)
