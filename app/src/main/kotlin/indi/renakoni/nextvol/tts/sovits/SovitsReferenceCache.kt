package indi.renakoni.nextvol.tts.sovits

import android.util.Log
import androidx.annotation.RequiresApi
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** One audio-conditioning record per immutable installed voice. Text and decoding controls are independent. */
@RequiresApi(26)
internal class SovitsReferenceCache(private val voice: GptSovitsVoice) {
    private val file = File(voice.model, ".reference-features-v1.bin")
    private val key by lazy {
        val hash = MessageDigest.getInstance("SHA-256")
        // Resource revisions scope checkpoint and base-model weights. Include audio content
        // so replacing a recording under the same filename cannot reuse its conditioning.
        hash.update((voice.model.absolutePath + "\u0000" + voice.runtime.absolutePath + "\u0000").encodeToByteArray())
        voice.referenceAudio.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        hash.digest()
    }

    private fun checksum(payload: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { update(key); digest(payload) }

    fun read(): FloatArray? {
        val size = file.length() - DIGEST_BYTES
        val frames = size - SPEAKER_BYTES
        if (frames < FRAME_BYTES || frames % FRAME_BYTES != 0L || size > MAX_BYTES) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val digest = ByteArray(DIGEST_BYTES).also(input::readFully)
                val payload = ByteArray(size.toInt()).also(input::readFully)
                if (input.read() != -1 || !digest.contentEquals(checksum(payload))) return null
                FloatArray(payload.size / Float.SIZE_BYTES).also {
                    ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it)
                }
            }
        } catch (_: IOException) { null }
    }

    fun write(features: FloatArray) {
        try {
            val payload = ByteBuffer.allocate(features.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            payload.asFloatBuffer().put(features)
            val partial = File.createTempFile(".reference-", ".part", voice.model)
            try {
                partial.outputStream().buffered().use {
                    it.write(checksum(payload.array()))
                    it.write(payload.array())
                }
                Files.move(partial.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { partial.delete() }
        } catch (failure: IOException) {
            Log.w("NextVolSoVITS", "Could not persist reference features", failure)
        }
    }

    private companion object {
        const val DIGEST_BYTES = 32
        const val SPEAKER_BYTES = (1024 + 512) * Float.SIZE_BYTES
        const val FRAME_BYTES = 768 * Float.SIZE_BYTES
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}
