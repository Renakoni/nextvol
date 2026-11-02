package indi.renakoni.nextvol.tts.matcha

import android.util.Log
import hnovel.speech.SherpaVoiceConfig
import indi.renakoni.nextvol.BuildConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class MatchaModelKey(val directory: File, val configuration: SherpaVoiceConfig)

internal val MatchaResources.modelKey get() = MatchaModelKey(directory.absoluteFile,
    configuration.copy(speakerId = 0, speed = 1f, pitch = 1f, volume = 1f))

/** MultiTTS t4.d shares the model while each speaker supplies its own request parameters. */
internal object MatchaModels {
    private val opening = Mutex()
    private val lifecycle = ReentrantLock()
    // eSpeak initialization/termination is process-global in the maintained native runtime.
    private val instances = Semaphore(1)
    private var current: Model? = null

    suspend fun acquire(resources: MatchaResources, native: () -> MatchaNativeApi,
        accept: (() -> Model?) -> Unit) = opening.withLock {
        val key = resources.modelKey
        var reused = false
        accept {
            lifecycle.withLock {
                current?.takeIf { it.key == key }?.also { it.references++; reused = true }
            }
        }
        if (reused) return@withLock
        instances.acquire()
        var transferred = false
        try {
            // The caller holds its lifecycle lock while claiming/creating the model, so close
            // cannot release its repository lease before a cancelled initializer has returned.
            accept {
                lifecycle.withLock {
                    resources.validate()
                    val api = native()
                    val start = System.nanoTime()
                    val handle = api.create(MatchaNativeConfig(resources))
                    check(handle != 0L)
                    Model(key, api, handle).also {
                        current = it
                        transferred = true
                        trace("model-create", it, start)
                    }
                }
            }
        } finally {
            if (!transferred) instances.release()
        }
    }

    fun release(model: Model) = lifecycle.withLock {
        if (--model.references == 0) {
            val start = System.nanoTime()
            try { model.use { native, handle -> native.destroy(handle) } }
            finally {
                current = null
                instances.release()
                trace("model-destroy", model, start)
            }
        }
    }

    private fun trace(event: String, model: Model, start: Long) {
        if (BuildConfig.DEBUG) Log.d("SpeechCache",
            "$event model=${model.key.hashCode()} handle=${model.handle} elapsedMs=${(System.nanoTime() - start) / 1_000_000}")
    }

    class Model(val key: MatchaModelKey, private val native: MatchaNativeApi, val handle: Long) {
        private val lock = ReentrantLock()
        var references = 1
        fun <T> use(block: (MatchaNativeApi, Long) -> T): T = lock.withLock { block(native, handle) }
    }
}
