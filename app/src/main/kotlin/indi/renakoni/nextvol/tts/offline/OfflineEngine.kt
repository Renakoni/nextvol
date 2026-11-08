package indi.renakoni.nextvol.tts.offline

/** App-internal PCM boundary for the offline SDKs. No Android TTS service is exported. */
internal interface OfflineEngine : AutoCloseable {
    val sampleRate: Int
    /** Global MultiTTS controls, on their original 0..100 scale; speaker adjustments belong to PCM processing. */
    fun synthesize(text: String, speed: Float, volume: Float, emit: (ByteArray, Int) -> Unit)
    fun cancel()
}
