package indi.renakoni.nextvol.tts.offline

/** App-internal PCM boundary for the offline SDKs. No Android TTS service is exported. */
internal interface OfflineEngine : AutoCloseable {
    val sampleRate: Int
    fun synthesize(text: String, speed: Float, emit: (ByteArray, Int) -> Unit)
    fun cancel()
}
