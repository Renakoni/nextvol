package indi.renakoni.nextvol.tts.matcha

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.speech.*
import indi.renakoni.nextvol.tts.SpeechEngineRepository
import indi.renakoni.nextvol.tts.orderSpeechVoices
import indi.renakoni.nextvol.tts.offline.googleVoiceCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/** Stable voice identity plus an immutable resource revision, also consumed by future speech plans. */
@Serializable
data class OfflineVoiceRef(val id: String, val revision: String)

@Serializable
data class OfflineSpeechResources(val reference: OfflineVoiceRef, val sizes: Map<String, Long>, val avatar: String? = null)

@Serializable
data class InstalledOfflineVoice(
    val reference: OfflineVoiceRef,
    val name: String,
    val sizes: Map<String, Long>,
    val sourceKey: String = "",
    val configuration: OfflineVoiceConfig? = null,
    val resourceReference: OfflineVoiceRef = reference,
    val enabled: Boolean = true,
    val metadata: OfflineVoiceMetadata = OfflineVoiceMetadata(),
    val defaults: OfflineVoiceDefaults? = null,
    val packageAvatar: String? = null,
)

@Serializable
data class OfflineVoiceDefaults(val name: String, val configuration: OfflineVoiceConfig, val metadata: OfflineVoiceMetadata)

data class OfflineVoiceRuntimeInfo(val configuration: OfflineVoiceConfig, val speakers: Int, val sampleRate: Int)

enum class OfflineInstallError { ChangedPackage, InvalidArchive }
class OfflineInstallException(val error: OfflineInstallError) : Exception(error.name)

/** Catalog publication is atomic. Leases keep superseded/deleted resources alive through native close. */
@Singleton
class OfflineSpeechRepository internal constructor(root: File, private val engines: SpeechEngineRepository? = null) {
    @Inject constructor(@ApplicationContext context: Context, engines: SpeechEngineRepository) :
        this(File(context.noBackupFilesDir, "offline-speech"), engines)

    private val root = root.canonicalFile
    private val catalog = AtomicFile(File(root, "voices.json"))
    private val onlineResources = AtomicFile(File(root, "online-resources.json"))
    private val lock = ReentrantLock()
    private val installing = Mutex()
    private val leases = mutableMapOf<OfflineVoiceRef, Int>()
    private val retired = mutableSetOf<OfflineVoiceRef>()
    private var recovered = false
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()

    /** A mixed engine can keep its installed model while the speaker uses its online type. */
    internal fun retainOnlineResources(references: Set<OfflineVoiceRef>, keepPrevious: Boolean = false) = lock.withLock {
        val previous = readOnlineResources()
        val next = if (keepPrevious) previous + references else references
        if (next == previous) return@withLock
        root.mkdirs()
        val output = onlineResources.startWrite()
        try { output.write(Json.encodeToString(next).toByteArray()); onlineResources.finishWrite(output) }
        catch (error: Exception) { onlineResources.failWrite(output); throw error }
        (previous - next).forEach(::retire)
    }

    private fun readOnlineResources(): Set<OfflineVoiceRef> = try {
        onlineResources.openRead().use { Json.decodeFromString(it.bufferedReader().readText()) }
    } catch (_: FileNotFoundException) { emptySet() }

    /** Like MultiTTS, discover missing Sherpa choices only after the user actually opens the model. */
    internal fun recordRuntimeInfo(reference: OfflineVoiceRef, info: OfflineVoiceRuntimeInfo) = lock.withLock {
        val voices = read()
        val voice = voices.singleOrNull { it.reference == reference } ?: return@withLock
        val configuration = voice.configuration ?: return@withLock
        if (configuration != info.configuration || configuration.extendUI.isNotBlank() || info.sampleRate <= 0) return@withLock
        var updated = configuration.copy(sampleRate = info.sampleRate)
        if (info.speakers > 1) {
            val control = buildJsonObject {
                put("code", "sid"); put("name", "Speaker"); put("value", configuration.sherpa!!.speakerId.toString())
                put("candidate", JsonObject((0 until info.speakers).associate { it.toString() to JsonPrimitive(it.toString()) }))
            }
            val raw = configuration.rawParameters ?: configuration.parameters
            val parameters = if (raw.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(raw).jsonObject
            updated = updated.copy(extendUI = JsonArray(listOf(control)).toString(),
                rawParameters = JsonObject(parameters + ("speakerId" to JsonPrimitive("@sid"))).toString())
        }
        updated = normalizeOfflineVoice(updated, voice.sizes.keys)
        if (updated != configuration) {
            write(voices.map { if (it.reference == reference) it.copy(configuration = updated) else it })
            mutableChanges.value++
        }
    }

    suspend fun voices(): List<InstalledOfflineVoice> = withContext(Dispatchers.IO) { lock.withLock { read() } }

    /** A config-only import reuses the installed resource tree and the normal voice editor's mapping. */
    suspend fun previewConfiguration(engine: SpeechPackageEngine, voice: SpeechPackageVoice): InstalledOfflineVoice? = withContext(Dispatchers.IO) {
        val definition = readOfflineVoiceDefinition(engine, voice)
        lock.withLock {
            val configuration = definition.configuration
            val previous = read().singleOrNull { installed ->
                val original = installed.defaults?.configuration ?: installed.configuration
                installed.sourceKey.isNotEmpty() && original != null && original.engine == configuration.engine &&
                    original.code == configuration.code && original.type == configuration.type
            } ?: return@withLock null
            if (configuration.engine == "sherpa") {
                // modelDir chooses the archive extraction root; installed models use paths relative to it.
                fun folder(value: OfflineVoiceConfig) = resolveSherpaParameters(value.rawParameters ?: value.parameters, value.extendUI)
                    .get("modelDir")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: value.code
                val original = previous.defaults?.configuration ?: requireNotNull(previous.configuration)
                require(folder(configuration) == folder(original)) { "modelDir" }
            }
            val normalized = normalizeOfflineVoice(configuration, previous.sizes.keys)
            val updated = preserveOverrides(previous, previous.copy(name = voice.name, configuration = normalized, metadata = definition.metadata,
                defaults = OfflineVoiceDefaults(voice.name, normalized, definition.metadata)))
            val effective = requireNotNull(updated.configuration)
            if (effective.engine != "sherpa") offlineResources(effective, previous.sizes.map { (path, bytes) ->
                SpeechPackageResource("${effective.engine}/$path", bytes)
            })
            updated
        }
    }

    suspend fun importConfiguration(draft: InstalledOfflineVoice): InstalledOfflineVoice = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = read()
            val previous = current.single { it.reference == draft.reference }
            val defaults = requireNotNull(draft.defaults)
            val normalized = normalizeOfflineVoice(defaults.configuration, previous.sizes.keys)
            val imported = previous.copy(name = defaults.name, configuration = normalized, metadata = defaults.metadata,
                defaults = defaults.copy(configuration = normalized))
            val updated = preserveOverrides(previous, imported).copy(
                reference = previous.reference.copy(revision = UUID.randomUUID().toString()))
            write(current.map { if (it.reference == previous.reference) updated else it })
            mutableChanges.value++
            updated
        }
    }

    suspend fun orderVoices(ids: List<String>) = withContext(Dispatchers.IO) {
        lock.withLock {
            write(read().orderSpeechVoices(ids) { it.reference.id })
            mutableChanges.value++
        }
    }

    suspend fun refreshCatalog() = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = read()
            val refreshed = current.toMutableList()
            current.filter { it.configuration?.engine == "google" }.distinctBy { it.resourceReference }.forEach { installed ->
                googleVoiceCatalog(directory(installed.resourceReference), installed.sizes).forEach { configuration ->
                    val key = "google\u0000${configuration.code}"
                    // User copies have no package key and remain independent of directory refreshes.
                    if (refreshed.none { it.sourceKey == key }) {
                        val name = configuration.code.substringAfterLast("-x-")
                        refreshed += InstalledOfflineVoice(OfflineVoiceRef(UUID.randomUUID().toString(), UUID.randomUUID().toString()),
                            name, installed.sizes, key, configuration, resourceReference = installed.resourceReference,
                            defaults = OfflineVoiceDefaults(name, configuration, OfflineVoiceMetadata()))
                    }
                }
            }
            if (refreshed != current) write(refreshed)
            mutableChanges.value++
        }
    }

    fun avatar(voice: InstalledOfflineVoice): File? {
        val name = voice.packageAvatar ?: voice.metadata.avatar.takeIf { safeSpeechPackagePath(it) }?.let { "avatar/$it" } ?: return null
        if (!safeSpeechPackagePath(name)) return null
        return File(directory(voice.resourceReference), name).takeIf { it.canonicalFile == it.absoluteFile && it.isFile }
    }

    internal fun avatar(resources: OfflineSpeechResources): File? = resources.avatar?.takeIf(::safeSpeechPackagePath)
        ?.let { File(directory(resources.reference), it).takeIf { file -> file.canonicalFile == file.absoluteFile && file.isFile } }

    fun problem(voice: InstalledOfflineVoice): String? = lock.withLock {
        val directory = directory(voice.resourceReference)
        voice.sizes.keys.firstOrNull { name ->
            val file = File(directory, name)
            !safeSpeechPackagePath(name) || file.canonicalFile != file.absoluteFile ||
                !file.isFile || !file.canRead() || file.length() != voice.sizes[name]
        }
    }

    suspend fun install(file: File, plan: OfflinePackagePlan): InstalledOfflineVoice = withContext(Dispatchers.IO) {
        lock.withLock { read() }
        installing.withLock {
            val task = currentCoroutineContext()
            root.mkdirs()
            val stage = File(root, ".install-${UUID.randomUUID()}")
            check(stage.mkdir())
            try {
                val avatarFile = prepareOfflineSpeechPackage(file, plan, stage)
                task.ensureActive()
                engines?.register(plan.engineConfiguration)
                lock.withLock {
                    task.ensureActive()
                    val previous = read()
                    val replaced = previous.filter { it.sourceKey == plan.id }
                    // Re-import publishes a fresh installation without inspecting model content identities.
                    val reference = OfflineVoiceRef(replaced.firstOrNull()?.reference?.id ?: UUID.randomUUID().toString(),
                        UUID.randomUUID().toString())
                    val imported = InstalledOfflineVoice(reference, plan.name,
                        plan.resources.associate { it.path.removePrefix(plan.directory + "/") to it.bytes },
                        plan.id, plan.configuration, metadata = plan.metadata,
                        defaults = OfflineVoiceDefaults(plan.name, plan.configuration, plan.metadata), packageAvatar = avatarFile)
                    val voice = preserveOverrides(replaced.singleOrNull(), imported)
                    val target = directory(voice.reference)
                    target.parentFile!!.mkdirs()
                    check(!target.exists() && stage.renameTo(target))
                    try { write(if (replaced.isEmpty()) previous + voice else
                        previous.map { if (it.sourceKey == plan.id) voice else it }) }
                    catch (error: Exception) { target.deleteRecursively(); throw error }
                    replaced.map { it.resourceReference }.distinct().forEach { retire(it) }
                    mutableChanges.value++
                    voice
                }
            } finally { stage.deleteRecursively() }
        }
    }

    suspend fun delete(reference: OfflineVoiceRef) = delete(setOf(reference))

    suspend fun delete(references: Set<OfflineVoiceRef>) = withContext(Dispatchers.IO) {
        lock.withLock {
            val previous = read()
            write(previous.filterNot { it.reference in references })
            previous.filter { it.reference in references }.map { it.resourceReference }.distinct().forEach(::retire)
            mutableChanges.value++
        }
    }

    /** A new speaker belongs to an engine; matching installed resources are reused without copying another speaker's settings. */
    suspend fun create(draft: InstalledOfflineVoice, retained: OfflineSpeechResources? = null): InstalledOfflineVoice = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = read()
            val configuration = requireNotNull(draft.configuration)
            require(draft.name.isNotBlank() && safeSpeechPackagePath(configuration.code))
            val resources = retained?.takeIf { matchesResources(configuration, it.sizes) }
                ?: matchingResources(configuration, current)
            val sizes = resources?.sizes.orEmpty()
            val normalized = if (configuration.engine == "sherpa" && sizes.isEmpty()) configuration
                else normalizeOfflineVoice(configuration, sizes.keys)
            val voice = draft.copy(name = draft.name.trim(), sizes = sizes, configuration = normalized,
                resourceReference = resources?.reference ?: draft.reference, packageAvatar = resources?.avatar ?: draft.packageAvatar)
            root.mkdirs()
            write(current + voice)
            mutableChanges.value++
            voice
        }
    }

    /** Copies only speaker settings; shared model files stay leased until their final speaker is removed. */
    suspend fun save(edited: InstalledOfflineVoice, copy: Boolean = false, retained: OfflineSpeechResources? = null): InstalledOfflineVoice = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = read()
            val previous = current.single { it.reference == edited.reference }
            require(edited.name.isNotBlank())
            val requested = requireNotNull(edited.configuration)
            val resources = matchingResources(requested, listOf(previous) + current.filterNot { it.reference == previous.reference })
                ?: retained?.takeIf { matchesResources(requested, it.sizes) }
            val sizes = resources?.sizes ?: previous.sizes
            val configuration = if (requested.engine == "sherpa" && sizes.isEmpty()) requested
                else normalizeOfflineVoice(requested, sizes.keys)
            val original = this@OfflineSpeechRepository.configuration(previous)
            require(configuration.engine == original.engine && safeSpeechPackagePath(configuration.code))
            val changedSynthesis = configuration != previous.configuration || edited.enabled != previous.enabled ||
                resources?.reference?.let { it != previous.resourceReference } == true
            val reference = if (copy) OfflineVoiceRef(UUID.randomUUID().toString(), UUID.randomUUID().toString())
                else if (changedSynthesis) previous.reference.copy(revision = UUID.randomUUID().toString()) else previous.reference
            val saved = previous.copy(reference = reference, name = edited.name.trim(), configuration = configuration,
                resourceReference = resources?.reference ?: previous.resourceReference, sizes = sizes,
                packageAvatar = resources?.avatar ?: previous.packageAvatar, enabled = edited.enabled, metadata = edited.metadata,
                sourceKey = if (copy) "" else previous.sourceKey,
                defaults = previous.defaults ?: OfflineVoiceDefaults(previous.name, previous.configuration ?: original, previous.metadata))
            write(if (copy) current + saved else current.map { if (it.reference == previous.reference) saved else it })
            if (!copy && saved.resourceReference != previous.resourceReference) retire(previous.resourceReference)
            mutableChanges.value++
            saved
        }
    }

    private fun matchingResources(configuration: OfflineVoiceConfig, voices: List<InstalledOfflineVoice>): OfflineSpeechResources? =
        voices.firstOrNull { installed ->
            val original = installed.defaults?.configuration ?: installed.configuration
            original?.engine == configuration.engine &&
                (configuration.engine != "sherpa" || sherpaDirectory(configuration) == sherpaDirectory(original)) &&
                matchesResources(configuration, installed.sizes)
        }?.let { OfflineSpeechResources(it.resourceReference, it.sizes, it.packageAvatar) }

    internal fun matchesResources(configuration: OfflineVoiceConfig, sizes: Map<String, Long>): Boolean = runCatching {
        val normalized = normalizeOfflineVoice(configuration, sizes.keys)
        if (configuration.engine != "sherpa") offlineResources(normalized, sizes.map { (path, bytes) ->
            SpeechPackageResource("${configuration.engine}/$path", bytes)
        }).also { require(it.isNotEmpty()) }
    }.isSuccess

    private fun sherpaDirectory(configuration: OfflineVoiceConfig): String =
        resolveSherpaParameters(configuration.rawParameters ?: configuration.parameters, configuration.extendUI)
            .get("modelDir")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: configuration.code

    suspend fun enable(references: Set<OfflineVoiceRef>, enabled: Boolean): List<InstalledOfflineVoice> = withContext(Dispatchers.IO) {
        lock.withLock {
            val voices = read().map { voice ->
                if (voice.reference !in references || voice.enabled == enabled) voice
                else voice.copy(reference = voice.reference.copy(revision = UUID.randomUUID().toString()),
                    resourceReference = voice.resourceReference, enabled = enabled)
            }
            write(voices)
            mutableChanges.value++
            voices
        }
    }

    internal fun acquire(reference: OfflineVoiceRef): Lease = lock.withLock {
        val voice = read().single { it.reference == reference }
        check(voice.enabled)
        val engine = engineConfiguration(voice)
        check(engine?.enabled != false)
        check(problem(voice) == null)
        val configuration = configuration(voice, engine)
        val resource = voice.resourceReference
        leases[resource] = (leases[resource] ?: 0) + 1
        Lease(voice, directory(resource), configuration)
    }

    internal fun engineConfiguration(voice: InstalledOfflineVoice): SpeechEngineConfig? =
        engines?.get(voice.configuration?.engine ?: "sherpa")

    internal fun configuration(voice: InstalledOfflineVoice, engine: SpeechEngineConfig? = engineConfiguration(voice)): OfflineVoiceConfig {
        val stored = voice.configuration ?: OfflineVoiceConfig("sherpa", "", sherpa = discoverSherpaVoiceConfig(voice.sizes.keys))
        return stored.copy(engineParameters = engine?.parameters ?: stored.engineParameters)
    }

    internal inner class Lease(val voice: InstalledOfflineVoice, val directory: File, val configuration: OfflineVoiceConfig) : AutoCloseable {
        val resources get() = MatchaResources(directory, requireNotNull(configuration.sherpa))
        private var closed = false
        override fun close() = lock.withLock {
            if (!closed) {
                closed = true
                val remaining = leases.getValue(voice.resourceReference) - 1
                if (remaining == 0) {
                    leases.remove(voice.resourceReference)
                    if (voice.resourceReference in retired) retire(voice.resourceReference)
                } else leases[voice.resourceReference] = remaining
            }
        }
    }

    private fun retire(reference: OfflineVoiceRef) {
        if (reference in readOnlineResources() || read().any { it.resourceReference == reference }) return
        retired += reference
        if (reference !in leases && directory(reference).deleteRecursively()) retired -= reference
    }

    internal fun directory(reference: OfflineVoiceRef): File {
        require(listOf(reference.id, reference.revision).all { it.matches(installationName) })
        val result = File(root, "${reference.id}/${reference.revision}")
        check(result.canonicalFile == result.absoluteFile)
        return result
    }

    private fun read(): List<InstalledOfflineVoice> {
        val voices = try {
            catalog.openRead().use { Json.decodeFromString<List<InstalledOfflineVoice>>(it.bufferedReader().readText()) }
        } catch (_: FileNotFoundException) { emptyList() }
        if (!recovered) {
            recovered = true
            val installed = (voices.map { it.resourceReference } + readOnlineResources()).map(::directory).toSet()
            // A previous process may have died before publication or after retiring a revision.
            root.listFiles().orEmpty().filter { it.isDirectory && it.canonicalFile == it.absoluteFile }.forEach { group ->
                if (group.name.startsWith(".install-")) group.deleteRecursively()
                else if (group.name.matches(installationName)) group.listFiles().orEmpty().forEach { revision ->
                    if (revision.name.matches(installationName) && revision.canonicalFile == revision.absoluteFile && revision !in installed)
                        revision.deleteRecursively()
                }
            }
        }
        return voices
    }

    private fun write(voices: List<InstalledOfflineVoice>) {
        val stream = catalog.startWrite()
        try {
            stream.write(Json.encodeToString(voices).toByteArray())
            catalog.finishWrite(stream)
        } catch (error: Exception) { catalog.failWrite(stream); throw error }
    }

    private companion object {
        val installationName = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

/** Package defaults precede speaker overrides. Segment/role overrides will be applied by the plan layer. */
internal fun normalizeOfflineVoice(configuration: OfflineVoiceConfig, paths: Collection<String>): OfflineVoiceConfig {
    require(configuration.speed.isFinite() && configuration.speed >= 0 && configuration.pitch.isFinite() && configuration.pitch > 0)
    require(configuration.volume.isFinite() && configuration.volume >= 0 && configuration.sampleRate >= 0)
    val raw = configuration.rawParameters ?: configuration.parameters
    val parameters = resolveOfflineParameters(raw, configuration.extendUI)
    val sherpa = if (configuration.engine == "sherpa")
        discoverSherpaVoiceConfig(paths, resolveSherpaParameters(raw, configuration.extendUI)).copy(
            speed = configuration.speed, pitch = configuration.pitch, volume = configuration.volume)
        else null
    return configuration.copy(parameters = parameters, rawParameters = raw, sherpa = sherpa, engineParameters = "")
}

private fun preserveOverrides(previous: InstalledOfflineVoice?, imported: InstalledOfflineVoice): InstalledOfflineVoice {
    val defaults = previous?.defaults ?: return imported.copy(enabled = previous?.enabled ?: imported.enabled)
    val old = previous.configuration ?: return imported
    val json = Json { encodeDefaults = true }
    val baseline = json.encodeToJsonElement(defaults.configuration).jsonObject
    val current = json.encodeToJsonElement(old).jsonObject
    val updated = json.encodeToJsonElement(requireNotNull(imported.configuration)).jsonObject
    val overrides = current.filter { (key, value) -> key !in setOf("speed", "parameters", "sherpa", "engineParameters") && value != baseline[key] }
    val configuration = Json.decodeFromJsonElement<OfflineVoiceConfig>(JsonObject(updated + overrides))
    return imported.copy(name = if (previous.name == defaults.name) imported.name else previous.name,
        enabled = previous.enabled, configuration = normalizeOfflineVoice(configuration, imported.sizes.keys),
        metadata = OfflineVoiceMetadata(
            if (previous.metadata.note == defaults.metadata.note) imported.metadata.note else previous.metadata.note,
            if (previous.metadata.description == defaults.metadata.description) imported.metadata.description else previous.metadata.description,
            if (previous.metadata.avatar == defaults.metadata.avatar) imported.metadata.avatar else previous.metadata.avatar))
}
