package indi.renakoni.nextvol.tts.sovits

import android.util.Half
import androidx.annotation.RequiresApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** ORT reads external initializers relative to the graph. Publish each derived file only when complete. */
@RequiresApi(26)
internal suspend fun prepareSovitsFiles(voice: GptSovitsVoice): File {
    val task = currentCoroutineContext()
    fun publish(target: File, write: (File) -> Unit) {
        val partial = File.createTempFile(".sovits-", ".part", target.parentFile)
        try {
            write(partial)
            task.ensureActive()
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { partial.delete() }
    }
    fun expand(folder: File, sourceName: String, targetName: String) {
        val target = File(folder, targetName)
        if (target.isFile) return
        val source = File(folder, sourceName)
        require(source.isFile && source.length() > 0 && source.length() % 2 == 0L) { "Missing or invalid $sourceName" }
        publish(target) { partial ->
            DataInputStream(source.inputStream()).use { input ->
                partial.outputStream().use { output ->
                    val packed = ByteArray(64 * 1024)
                    val words = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val bytes = ByteArray(packed.size * 2)
                    val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    var left = source.length()
                    while (left > 0) {
                        task.ensureActive()
                        val count = minOf(left, packed.size.toLong()).toInt()
                        input.readFully(packed, 0, count)
                        for (i in 0 until count / 2) floats.put(i, Half.toFloat(words.get(i)))
                        output.write(bytes, 0, count * 2)
                        left -= count
                    }
                }
            }
        }
    }

    for (name in listOf("prompt_encoder", "t2s_shared", "vits"))
        expand(voice.model, "${name}_fp16.bin", "${name}_fp32.bin")
    expand(File(voice.runtime, "chinese-hubert-base"), "chinese-hubert-base_weights_fp16.bin", "chinese-hubert-base_weights.bin")

    val speaker = File(voice.runtime, "speaker_encoder.onnx")
    // The published single-file speaker encoder uses IR 9 operators but declares IR 10.
    // Only project the exact verified export for NextVol's source-built ORT 1.17.
    if (!speaker.inputStream().use { it.read() == 8 && it.read() == 10 }) return speaker
    val compatible = File(voice.runtime, "speaker_encoder_ort17.onnx")
    if (compatible.isFile && compatible.length() == speaker.length() && compatible.lastModified() == speaker.lastModified())
        return compatible
    publish(compatible) { partial ->
        val digest = MessageDigest.getInstance("SHA-256")
        speaker.inputStream().use { input ->
            partial.outputStream().use { output ->
                val header = byteArrayOf(8, 10)
                require(input.read() == 8 && input.read() == 10)
                digest.update(header)
                output.write(byteArrayOf(8, 9))
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    task.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } ==
            "31ed0858f7059785dfd58731efb0effdbe39d5b9175b2b36f5a3341adbc0bb67") {
            "Supply an ORT 1.17 compatible speaker encoder"
        }
        partial.setLastModified(speaker.lastModified())
    }
    return compatible
}
