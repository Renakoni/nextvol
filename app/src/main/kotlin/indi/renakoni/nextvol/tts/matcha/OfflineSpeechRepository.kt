package indi.renakoni.nextvol.tts.matcha

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.speech.*
import indi.renakoni.nextvol.tts.offline.OfflineEngineRepository
import indi.renakoni.nextvol.tts.offline.googleVoiceCatalog
import indi.renakoni.nextvol.tts.offline.legacy.prepareMicrosoftResources
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
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/** Stable voice identity plus an immutable resource revision, also consumed by future speech plans. */
@Serializable
data class OfflineVoiceRef(val id: String, val revision: String)

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
class OfflineSpeechRepository internal constructor(root: File, private val engines: OfflineEngineRepository? = null) {
    @Inject constructor(@ApplicationContext context: Context, engines: OfflineEngineRepository) :
        this(File(context.noBackupFilesDir, "offline-speech"), engines)

    private val root = root.canonicalFile
    private val catalog = AtomicFile(File(root, "voices.json"))
    private val lock = ReentrantLock()
    private val installing = Mutex()
    private val leases = mutableMapOf<OfflineVoiceRef, Int>()
    private val retired = mutableSetOf<OfflineVoiceRef>()
    private var recovered = false
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()

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
            if (refreshed != current) { write(refreshed); mutableChanges.value++ }
        }
    }

    fun avatar(voice: InstalledOfflineVoice): File? {
        val name = voice.packageAvatar ?: voice.metadata.avatar.takeIf { safeSpeechPackagePath(it) }?.let { "avatar/$it" } ?: return null
        if (!safeSpeechPackagePath(name)) return null
        return File(directory(voice.resourceReference), name).takeIf { it.canonicalFile == it.absoluteFile && it.isFile }
    }

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
                var avatarFile: String? = null
                ZipFile(file).use { zip ->
                    val seen = mutableSetOf<String>()
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        task.ensureActive()
                        val entry = entries.nextElement()
                        val path = entry.name.removeSuffix("/")
                        if (seen.size >= 100_000 || !safeSpeechPackagePath(path) || !seen.add(path)) {
                            throw OfflineInstallException(OfflineInstallError.InvalidArchive)
                        }
                    }
                    // Reopen the document and resolve the selection again before copying any resource.
                    val preview = previewSpeechPackage(file) { task.ensureActive() }
                    val actual = preview.engines.flatMap { engine -> engine.voices.filter { voice ->
                        engine.code + "\u0000" + voice.code == plan.id
                    }.mapNotNull { voice ->
                        task.ensureActive()
                        previewOfflineVoice(preview, engine, voice).plan
                    } }.singleOrNull { it.id == plan.id }
                    if (actual != plan) throw OfflineInstallException(OfflineInstallError.ChangedPackage)
                    val buffer = ByteArray(64 * 1024)
                    for (resource in plan.resources) {
                        task.ensureActive()
                        val relative = resource.path.removePrefix(plan.directory + "/")
                        if (!resource.path.startsWith(plan.directory + "/") || !safeSpeechPackagePath(relative)) {
                            throw OfflineInstallException(OfflineInstallError.InvalidArchive)
                        }
                        val entry = zip.getEntry(resource.path)
                        if (entry == null || entry.isDirectory || entry.size < 0 || entry.size != resource.bytes) {
                            throw OfflineInstallException(OfflineInstallError.ChangedPackage)
                        }
                        val destination = File(stage, relative)
                        destination.parentFile!!.mkdirs()
                        var total = 0L
                        zip.getInputStream(entry).use { input -> destination.outputStream().use { output ->
                            while (true) {
                                task.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > resource.bytes) throw OfflineInstallException(OfflineInstallError.InvalidArchive)
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        } }
                        if (total != resource.bytes) throw OfflineInstallException(OfflineInstallError.InvalidArchive)
                    }
                    plan.avatar?.let { avatar ->
                        val entry = zip.getEntry(avatar.path)
                        if (entry != null && !entry.isDirectory) {
                            val name = ".avatar-${UUID.randomUUID()}"
                            zip.getInputStream(entry).use { input -> File(stage, name).outputStream().use { output ->
                                while (true) {
                                    task.ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                            } }
                            avatarFile = name
                        }
                    }
                }
                if (plan.configuration.engine == "microsoft") {
                    prepareMicrosoftResources(plan.resources.map { File(stage, it.path.removePrefix(plan.directory + "/")) }) {
                        task.ensureActive()
                    }
                }
                plan.configuration.sherpa?.let { MatchaResources(stage, it).validate() }
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
                    try { write(previous.filterNot { it.sourceKey == plan.id } + voice) }
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

    suspend fun copyVoices(references: Set<OfflineVoiceRef>, name: (String) -> String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = read()
            val copies = current.filter { it.reference in references }.map { voice ->
                voice.copy(reference = OfflineVoiceRef(UUID.randomUUID().toString(), UUID.randomUUID().toString()),
                    name = name(voice.name), sourceKey = "", resourceReference = voice.resourceReference)
            }
            write(current + copies)
            mutableChanges.value++
        }
    }

    /** Copies only speaker settings; shared model files stay leased until their final speaker is removed. */
    suspend fun save(edited: InstalledOfflineVoice, copy: Boolean = false): InstalledOfflineVoice = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = read()
            val previous = current.single { it.reference == edited.reference }
            require(edited.name.isNotBlank())
            val configuration = normalizeOfflineVoice(requireNotNull(edited.configuration), previous.sizes.keys)
            val original = this@OfflineSpeechRepository.configuration(previous)
            require(configuration.engine == original.engine && safeSpeechPackagePath(configuration.code))
            val changedSynthesis = configuration != previous.configuration || edited.enabled != previous.enabled
            val reference = if (copy) OfflineVoiceRef(UUID.randomUUID().toString(), UUID.randomUUID().toString())
                else if (changedSynthesis) previous.reference.copy(revision = UUID.randomUUID().toString()) else previous.reference
            val saved = previous.copy(reference = reference, name = edited.name.trim(), configuration = configuration,
                resourceReference = previous.resourceReference, enabled = edited.enabled, metadata = edited.metadata,
                sourceKey = if (copy) "" else previous.sourceKey,
                defaults = previous.defaults ?: OfflineVoiceDefaults(previous.name, previous.configuration ?: original, previous.metadata))
            write(if (copy) current + saved else current.map { if (it.reference == previous.reference) saved else it })
            mutableChanges.value++
            saved
        }
    }

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
        check(problem(voice) == null)
        val configuration = configuration(voice)
        val resource = voice.resourceReference
        leases[resource] = (leases[resource] ?: 0) + 1
        Lease(voice, directory(resource), configuration)
    }

    internal fun configuration(voice: InstalledOfflineVoice): OfflineVoiceConfig {
        val stored = voice.configuration ?: OfflineVoiceConfig("sherpa", "", sherpa = discoverSherpaVoiceConfig(voice.sizes.keys))
        return stored.copy(engineParameters = engines?.get(stored.engine)?.parameters ?: stored.engineParameters)
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
        if (read().any { it.resourceReference == reference }) return
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
            val installed = voices.map { directory(it.resourceReference) }.toSet()
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
    val overrides = current.filter { (key, value) -> key !in setOf("parameters", "sherpa", "engineParameters") && value != baseline[key] }
    val configuration = Json.decodeFromJsonElement<OfflineVoiceConfig>(JsonObject(updated + overrides))
    return imported.copy(name = if (previous.name == defaults.name) imported.name else previous.name,
        enabled = previous.enabled, configuration = normalizeOfflineVoice(configuration, imported.sizes.keys),
        metadata = OfflineVoiceMetadata(
            if (previous.metadata.note == defaults.metadata.note) imported.metadata.note else previous.metadata.note,
            if (previous.metadata.description == defaults.metadata.description) imported.metadata.description else previous.metadata.description,
            if (previous.metadata.avatar == defaults.metadata.avatar) imported.metadata.avatar else previous.metadata.avatar))
}
