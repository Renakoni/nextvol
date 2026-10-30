package indi.renakoni.nextvol.tts.matcha

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.speech.*
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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.CRC32
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/** Stable voice identity plus an immutable resource revision, also consumed by future speech plans. */
@Serializable
data class OfflineVoiceRef(val id: String, val revision: String)

@Serializable
data class InstalledOfflineVoice(val reference: OfflineVoiceRef, val name: String, val sizes: Map<String, Long>)

enum class OfflineInstallError { ChangedPackage, InvalidArchive, UnsupportedModel }
class OfflineInstallException(val error: OfflineInstallError) : Exception(error.name)

/** Catalog publication is atomic. Leases keep superseded/deleted resources alive through native close. */
@Singleton
class OfflineSpeechRepository internal constructor(
    root: File,
    private val modelHashes: Map<String, String> = ChineseMatchaProfile.models,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, "offline-speech"))

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
        ChineseMatchaProfile.files.keys.firstOrNull { name ->
            val file = File(directory, name)
            file.canonicalFile != file.absoluteFile || !file.isFile || !file.canRead() || file.length() != voice.sizes[name]
        }
    }

    suspend fun install(file: File, plan: MatchaPackagePlan): InstalledOfflineVoice = withContext(Dispatchers.IO) {
        lock.withLock { read() }
        installing.withLock {
            val task = currentCoroutineContext()
            root.mkdirs()
            val stage = File(root, ".install-${UUID.randomUUID()}")
            check(stage.mkdir())
            try {
                val hashes = linkedMapOf<String, String>()
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
                    val actual = preview.engines.flatMap { engine -> engine.voices.mapNotNull { voice ->
                        previewMatchaVoice(preview, engine, voice).plan
                    } }.singleOrNull { it.id == plan.id }
                    if (actual != plan) throw OfflineInstallException(OfflineInstallError.ChangedPackage)
                    val buffer = ByteArray(64 * 1024)
                    for (resource in plan.resources) {
                        task.ensureActive()
                        val relative = resource.path.removePrefix(plan.directory + "/")
                        val limit = ChineseMatchaProfile.files.getValue(relative)
                        val entry = zip.getEntry(resource.path)
                        if (entry == null || entry.size != resource.bytes || entry.crc != resource.crc) {
                            throw OfflineInstallException(OfflineInstallError.ChangedPackage)
                        }
                        val destination = File(stage, relative)
                        destination.parentFile!!.mkdirs()
                        val hash = MessageDigest.getInstance("SHA-256")
                        val crc = CRC32()
                        var total = 0L
                        zip.getInputStream(entry).use { input -> destination.outputStream().use { output ->
                            while (true) {
                                task.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > limit || total > resource.bytes) throw OfflineInstallException(OfflineInstallError.InvalidArchive)
                                hash.update(buffer, 0, count)
                                crc.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        } }
                        if (total != resource.bytes || crc.value != resource.crc) throw OfflineInstallException(OfflineInstallError.InvalidArchive)
                        hashes[relative] = hash.digest().hex()
                        modelHashes[relative]?.let { expected ->
                            if (hashes[relative] != expected) throw OfflineInstallException(OfflineInstallError.UnsupportedModel)
                        }
                    }
                }
                MatchaResources(stage).validate()
                val revision = MessageDigest.getInstance("SHA-256").digest(hashes.entries.joinToString("\n") { "${it.key}:${it.value}" }.toByteArray()).hex()
                val voice = InstalledOfflineVoice(OfflineVoiceRef(plan.id, revision), plan.name,
                    plan.resources.associate { it.path.removePrefix(plan.directory + "/") to it.bytes })
                task.ensureActive()
                lock.withLock {
                    task.ensureActive()
                    val previous = read()
                    val target = directory(voice.reference)
                    target.parentFile!!.mkdirs()
                    val exists = target.exists()
                    if (exists) {
                        // A repeated import is idempotent. Never overwrite files held by a synthesizer.
                        check(problem(voice) == null) { "Delete the damaged installation before importing again" }
                        for ((name, expected) in hashes) {
                            val hash = MessageDigest.getInstance("SHA-256")
                            File(target, name).inputStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    task.ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    hash.update(buffer, 0, count)
                                }
                            }
                            check(hash.digest().hex() == expected) { "Delete the damaged installation before importing again" }
                        }
                    } else check(stage.renameTo(target))
                    try { write(previous.filterNot { it.reference.id == plan.id } + voice) }
                    catch (error: Exception) { if (!exists) target.deleteRecursively(); throw error }
                    retired -= voice.reference
                    previous.filter { it.reference.id == plan.id && it.reference != voice.reference }.forEach { retire(it.reference) }
                    mutableChanges.value++
                }
                voice
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
        leases[reference] = (leases[reference] ?: 0) + 1
        Lease(voice, MatchaResources(directory(reference)))
    }

    internal inner class Lease(val voice: InstalledOfflineVoice, val resources: MatchaResources) : AutoCloseable {
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

    private fun directory(reference: OfflineVoiceRef): File {
        require(listOf(reference.id, reference.revision).all { it.matches(Regex("[0-9a-f]{64}")) })
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
                else if (group.name.matches(Regex("[0-9a-f]{64}"))) group.listFiles().orEmpty().forEach { revision ->
                    if (revision.name.matches(Regex("[0-9a-f]{64}")) && revision.canonicalFile == revision.absoluteFile && revision !in installed)
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
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
