package indi.renakoni.nextvol.tts

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.network.StorageCipher
import hnovel.speech.SpeechEngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/** Owns offline and shared HTTP/offline engine settings, including engine-wide credentials. */
@Singleton
class SpeechEngineRepository @Inject constructor(@ApplicationContext context: Context, private val cipher: StorageCipher) {
    private val file = AtomicFile(File(context.filesDir, "offline-engines.enc"))
    private val lock = ReentrantLock()
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()

    suspend fun all(): List<SpeechEngineConfig> = withContext(Dispatchers.IO) { lock.withLock { read() } }

    fun get(code: String): SpeechEngineConfig? = lock.withLock { read().find { it.code == code } }

    /** Importing another voice from an engine preserves the user's existing engine settings. */
    suspend fun register(configuration: SpeechEngineConfig) = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = read()
            if (current.none { it.code == configuration.code }) write(current + configuration)
        }
    }

    suspend fun save(configuration: SpeechEngineConfig) = withContext(Dispatchers.IO) {
        lock.withLock {
            require(configuration.name.isNotBlank())
            val current = read()
            val previous = current.single { it.code == configuration.code }
            require(configuration.type == previous.type)
            write(current.map { if (it.code == configuration.code) configuration else it })
        }
    }

    suspend fun order(ranks: Map<String, Int>) = withContext(Dispatchers.IO) {
        lock.withLock { write(read().map { it.copy(order = ranks["inner:${it.code}"] ?: it.order) }) }
    }

    /** Mixed engines share one record, even when only offline voices have been imported. */
    internal suspend fun sharedHttpEngines(): List<SpeechEngineConfig> = all().filter(::isSharedWithHttp)

    internal fun httpRecords(configurations: List<SpeechEngineConfig>): List<SpeechEngineConfig> =
        configurations.filterNot(::isSharedWithHttp)

    /** Called after the HTTP store is published, before its authorization and resource cleanup. */
    internal suspend fun updateSharedHttpEngines(previous: List<SpeechEngineConfig>, updated: List<SpeechEngineConfig>) {
        updated.filter { isSharedWithHttp(it) && it !in previous }.forEach { engine ->
            if (previous.any { it.code == engine.code }) save(engine)
            else register(engine)
        }
    }

    private fun isSharedWithHttp(configuration: SpeechEngineConfig) = configuration.code in setOf("msctts", "microsoft")

    private fun read(): List<SpeechEngineConfig> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val bytes = file.openRead().use { it.readBytes() }
        return Json.decodeFromString(cipher.open(bytes, IDENTITY).toString(Charsets.UTF_8))
    }

    private fun write(configurations: List<SpeechEngineConfig>) {
        val bytes = cipher.seal(Json.encodeToString(configurations).toByteArray(Charsets.UTF_8), IDENTITY)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
        mutableChanges.value++
    }

    companion object {
        private const val IDENTITY = "offline-speech-engines-v1"
    }
}
