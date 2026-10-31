package indi.renakoni.nextvol.tts.matcha

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.speech.*
import indi.renakoni.nextvol.tts.offline.OfflineEngineRepository
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
import kotlinx.serialization.json.Json
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
)

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

    suspend fun voices(): List<InstalledOfflineVoice> = withContext(Dispatchers.IO) { lock.withLock { read() } }

    fun problem(voice: InstalledOfflineVoice): String? = lock.withLock {
        val directory = directory(voice.reference)
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
                    val voice = InstalledOfflineVoice(reference, plan.name,
                        plan.resources.associate { it.path.removePrefix(plan.directory + "/") to it.bytes },
                        plan.id, plan.configuration)
                    val target = directory(voice.reference)
                    target.parentFile!!.mkdirs()
                    check(!target.exists() && stage.renameTo(target))
                    try { write(previous.filterNot { it.sourceKey == plan.id } + voice) }
                    catch (error: Exception) { target.deleteRecursively(); throw error }
                    replaced.forEach { retire(it.reference) }
                    mutableChanges.value++
                    voice
                }
            } finally { stage.deleteRecursively() }
        }
    }

    suspend fun delete(reference: OfflineVoiceRef) = withContext(Dispatchers.IO) {
        lock.withLock {
            write(read().filterNot { it.reference == reference })
            retire(reference)
            mutableChanges.value++
        }
    }

    internal fun acquire(reference: OfflineVoiceRef): Lease = lock.withLock {
        val voice = read().single { it.reference == reference }
        check(problem(voice) == null)
        val configuration = configuration(voice)
        leases[reference] = (leases[reference] ?: 0) + 1
        Lease(voice, directory(reference), configuration)
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
                val remaining = leases.getValue(voice.reference) - 1
                if (remaining == 0) {
                    leases.remove(voice.reference)
                    if (voice.reference in retired) retire(voice.reference)
                } else leases[voice.reference] = remaining
            }
        }
    }

    private fun retire(reference: OfflineVoiceRef) {
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
            val installed = voices.map { directory(it.reference) }.toSet()
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
