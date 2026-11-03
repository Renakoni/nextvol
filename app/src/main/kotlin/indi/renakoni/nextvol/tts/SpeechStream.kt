package indi.renakoni.nextvol.tts

import kotlinx.coroutines.channels.Channel

/** Processed mono PCM16. Backpressure stops decoding when playback has four blocks queued. */
class SpeechStream internal constructor(val sampleRate: Int) {
    internal val blocks = Channel<ByteArray>(4)
    @Volatile var durationMs: Long = -1
        private set
    @Volatile var failure: Throwable? = null
        private set
    private var frames = 0L

    internal suspend fun append(bytes: ByteArray) {
        blocks.send(bytes)
        frames += bytes.size / 2
    }

    internal fun finish() {
        durationMs = frames * 1000 / sampleRate
        blocks.close()
    }

    internal fun fail(error: Throwable) {
        failure = error
        blocks.close(error)
    }

    internal fun cancel() = blocks.cancel()
}
