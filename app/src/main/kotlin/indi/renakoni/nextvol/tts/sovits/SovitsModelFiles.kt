package indi.renakoni.nextvol.tts.sovits

import ai.onnxruntime.platform.Fp16Conversions
import androidx.annotation.RequiresApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** ORT reads external initializers relative to the graph. Publish each derived file only when complete. */
@RequiresApi(26)
internal suspend fun prepareSovitsFiles(voice: GptSovitsVoice) {
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
        val size = source.length()
        require(source.isFile && size > 0 && size % 2 == 0L) { "Missing or invalid $sourceName" }
        publish(target) { partial ->
            DataInputStream(source.inputStream()).use { input ->
                partial.outputStream().use { output ->
                    val packed = ByteArray(64 * 1024)
                    val words = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val bytes = ByteArray(packed.size * 2)
                    val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    var left = size
                    while (left > 0) {
                        task.ensureActive()
                        val count = minOf(left, packed.size.toLong()).toInt()
                        input.readFully(packed, 0, count)
                        for (i in 0 until count / 2) floats.put(i, Fp16Conversions.fp16ToFloat(words.get(i)))
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
}
