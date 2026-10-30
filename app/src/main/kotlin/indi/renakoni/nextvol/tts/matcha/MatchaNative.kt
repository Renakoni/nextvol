package indi.renakoni.nextvol.tts.matcha

import androidx.annotation.Keep

internal interface MatchaNativeApi {
    fun create(directory: String): Long
    fun generate(handle: Long, text: String, speed: Float, path: String, cancellation: MatchaCancellation)
    fun destroy(handle: Long)
}

@Keep
internal fun interface MatchaCancellation {
    fun isCancelled(): Boolean
}

/** Loaded only when the controlled Matcha factory is used. */
@Keep
internal object MatchaNative : MatchaNativeApi {
    init {
        require(android.os.Build.VERSION.SDK_INT >= 26 && "arm64-v8a" in android.os.Build.SUPPORTED_ABIS)
        System.loadLibrary("nextvol-matcha")
    }
    external override fun create(directory: String): Long
    external override fun generate(handle: Long, text: String, speed: Float, path: String, cancellation: MatchaCancellation)
    external override fun destroy(handle: Long)
}
