package indi.renakoni.nextvol.tts.matcha

import androidx.annotation.Keep
import android.os.Build
import indi.renakoni.nextvol.BuildConfig

internal interface MatchaNativeApi {
    fun create(configuration: MatchaNativeConfig): Long
    fun generate(handle: Long, text: String, speed: Float, path: String, cancellation: MatchaCancellation)
    fun destroy(handle: Long)
}

/** JNI reads these named fields; keep the shared serialized package schema independent of JNI. */
@Keep
internal class MatchaNativeConfig(resources: MatchaResources) {
    private val selected = resources.configuration
    val modelType = selected.modelType
    val model = resources.path(selected.model)
    val vocoder = resources.path(selected.vocoder)
    val voices = resources.path(selected.voices)
    val tokens = resources.path(selected.tokens)
    val lexicon = selected.lexicons.joinToString(",") { resources.path(it) }
    val dataDir = resources.path(selected.dataDir)
    val dictDir = resources.path(selected.dictDir)
    val ruleFsts = selected.ruleFsts.joinToString(",") { resources.path(it) }
    val ruleFars = selected.ruleFars.joinToString(",") { resources.path(it) }
    val lang = selected.lang
    val numThreads = selected.numThreads
    val provider = selected.provider
    val speakerId = selected.speakerId
    val noiseScale = selected.noiseScale
    val noiseScaleW = selected.noiseScaleW
    val lengthScale = selected.lengthScale
    val speed = selected.speed
    val pitch = selected.pitch
    val volume = selected.volume
}

@Keep
internal fun interface MatchaCancellation {
    fun isCancelled(): Boolean
}

/** Checking support loads code only; it never initializes a model or synthesizes audio. */
@Keep
internal object MatchaNative : MatchaNativeApi {
    val available: Boolean = if (BuildConfig.MATCHA_NATIVE && Build.VERSION.SDK_INT >= 26 && "arm64-v8a" in Build.SUPPORTED_ABIS) {
        try { System.loadLibrary("nextvol-matcha"); true }
        catch (_: LinkageError) { false }
        catch (_: SecurityException) { false }
    } else false

    fun requireAvailable() {
        check(available)
    }
    external override fun create(configuration: MatchaNativeConfig): Long
    external override fun generate(handle: Long, text: String, speed: Float, path: String, cancellation: MatchaCancellation)
    external override fun destroy(handle: Long)
}

fun matchaAvailable(): Boolean = MatchaNative.available
