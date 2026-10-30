package indi.renakoni.nextvol.tts.matcha

import androidx.annotation.Keep
import android.os.Build
import indi.renakoni.nextvol.BuildConfig

internal interface MatchaNativeApi {
    fun create(directory: String): Long
    fun generate(handle: Long, text: String, speed: Float, path: String, cancellation: MatchaCancellation)
    fun destroy(handle: Long)
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
    external override fun create(directory: String): Long
    external override fun generate(handle: Long, text: String, speed: Float, path: String, cancellation: MatchaCancellation)
    external override fun destroy(handle: Long)
}

fun matchaAvailable(): Boolean = MatchaNative.available
