package indi.renakoni.nextvol.tts.offline

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.network.StorageCipher
import hnovel.speech.OfflineEngineConfig
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

/** Engine-wide parameters are distinct from each voice's parameters and may contain credentials. */
@Singleton
class OfflineEngineRepository @Inject constructor(@ApplicationContext context: Context, private val cipher: StorageCipher) {
    private val file = AtomicFile(File(context.filesDir, "offline-engines.enc"))
    private val lock = ReentrantLock()
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()

    suspend fun all(): List<OfflineEngineConfig> = withContext(Dispatchers.IO) { lock.withLock { combined(read()) } }

    fun get(code: String): OfflineEngineConfig? = lock.withLock { combined(read()).find { it.code == code } }

    /** Importing another voice from an engine preserves the user's existing engine settings. */
    suspend fun register(configuration: OfflineEngineConfig) = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = read()
            if (current.none { it.code == configuration.code }) write(current + configuration)
        }
    }

    suspend fun save(configuration: OfflineEngineConfig) = withContext(Dispatchers.IO) {
        lock.withLock {
            require(configuration.name.isNotBlank())
            val current = read()
            val previous = combined(current).single { it.code == configuration.code }
            require(configuration.type == previous.type)
            write(current.filterNot { it.code == configuration.code } + configuration)
        }
    }

    private fun combined(stored: List<OfflineEngineConfig>): List<OfflineEngineConfig> {
        val overrides = stored.associateBy { it.code }
        return defaults.map { overrides[it.code] ?: it } + stored.filter { item -> defaults.none { it.code == item.code } }
    }

    private fun read(): List<OfflineEngineConfig> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val bytes = file.openRead().use { it.readBytes() }
        return Json.decodeFromString(cipher.open(bytes, IDENTITY).toString(Charsets.UTF_8))
    }

    private fun write(configurations: List<OfflineEngineConfig>) {
        val bytes = cipher.seal(Json.encodeToString(configurations).toByteArray(Charsets.UTF_8), IDENTITY)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
        mutableChanges.value++
    }

    companion object {
        private const val IDENTITY = "offline-speech-engines-v1"
        private val defaults = listOf("audio" to "Audio", "acapela" to "Acapela", "bdetts" to "BDeTTS",
            "cereproc" to "CereProc", "google" to "Google", "isstts" to "IssTTS", "larktts" to "LarkTTS",
            "msctts" to "MscTTS", "sherpa" to "Sherpa-ONNX", "vcstts" to "VcsTTS", "vocalizer" to "Vocalizer",
            "weread" to "WeRead", "samsung" to "Samsung", "xiaomi" to "Xiaomi", "microsoft" to "Microsoft",
            "sougou" to "Sogou").map { (code, name) -> OfflineEngineConfig(code, name) }
    }
}
