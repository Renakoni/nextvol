package indi.renakoni.nextvol.tts.audio

import android.media.MediaDataSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

/** MultiTTS I6.b's seekable stream input. The broker owns the encoded-response byte limit. */
internal class StreamingAudioInput(private val job: Job) : MediaDataSource() {
    // The response byte limit bounds incoming + cached chunks. Playback pauses must not hold
    // a network callback open until its request timeout expires.
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val chunks = mutableListOf<ByteArray>()
    private var length = 0L
    private var ended = false
    @Volatile var failure: Throwable? = null
        private set

    fun receive(bytes: ByteArray, offset: Int, count: Int) {
        incoming.trySend(bytes.copyOfRange(offset, offset + count)).getOrThrow()
    }

    fun finish(error: Throwable? = null) {
        failure = error
        incoming.close(error)
    }

    override fun getSize(): Long = if (ended) length else -1

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        while (!ended && length < position + size) {
            val next = runBlocking(job) { incoming.receiveCatching() }
            next.exceptionOrNull()?.let { throw it }
            val bytes = next.getOrNull()
            if (bytes == null) ended = true else { chunks += bytes; length += bytes.size }
        }
        var start = 0L
        var copied = 0
        for (chunk in chunks) {
            val at = position + copied
            if (at < start + chunk.size) {
                val from = (at - start).toInt()
                val count = minOf(chunk.size - from, size - copied)
                chunk.copyInto(buffer, offset + copied, from, from + count)
                copied += count
                if (copied == size) break
            }
            start += chunk.size
        }
        return if (copied == 0) -1 else copied
    }

    override fun close() { incoming.cancel(); chunks.clear() }
}
