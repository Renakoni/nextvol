package indi.renakoni.nextvol.tts.sovits

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** FP32 conditioning/output; bounded FP16 HTP convolutions with exact overlap. */
internal class V2HtpVocoder(
    private val environment: OrtEnvironment,
    private val session: (String) -> OrtSession,
    private val directory: File,
    runtime: File,
    appUpdatedAt: Long,
    private val stemChannels: Int,
) : AutoCloseable {
    private val bodyChannels = stemChannels / 32
    private val htp = QnnSession(runtime, File(directory, BODY), appUpdatedAt)
    private var workspace: Workspace? = null

    fun generate(latent: OnnxTensor, ge: OnnxTensorLike): FloatArray? {
        val frames = latent.info.shape.last()
        // Small utterances do not amortize the overlap and backend transition.
        if (frames < 192) return null
        require(frames <= MAX_SOVITS_PCM_SAMPLES / SCALE) { "HTP output exceeds the playback file limit" }
        val length = frames.toInt()
        val work = workspace ?: Workspace().also { workspace = it }
        val audio = FloatArray(length * SCALE)
        session(STEM).runCancellable(mapOf("latent" to latent, "ge" to ge)).use { result ->
            val hidden = (result.get(0) as OnnxTensor).floatBuffer
            val head = session(HEAD)
            for (window in V2VocoderWindows.plan(length)) {
                GenerationContext.checkCurrent()
                work.input.clear()
                for (channel in 0 until stemChannels) {
                    hidden.limit(hidden.capacity())
                    hidden.position(channel * length + window.start)
                    hidden.limit(hidden.position() + V2VocoderWindows.FRAMES)
                    work.input.put(hidden)
                }
                work.input.rewind()
                htp.step(work.input, work.output)
                head.runCancellable(mapOf("body" to work.headInput)).use { output ->
                    val pcm = (output.get(0) as OnnxTensor).floatBuffer
                    pcm.position((window.begin - window.start) * SCALE)
                    pcm.get(audio, window.begin * SCALE, (window.end - window.begin) * SCALE)
                }
            }
        }
        return audio
    }

    override fun close() {
        htp.close()
        workspace?.close()
    }

    private inner class Workspace : AutoCloseable {
        fun buffer(elements: Int): FloatBuffer = ByteBuffer.allocateDirect(elements * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val input = buffer(stemChannels * V2VocoderWindows.FRAMES)
        val output = buffer(bodyChannels * V2VocoderWindows.FRAMES * SCALE)
        val headInput = OnnxTensor.createTensor(environment, output,
            longArrayOf(1, bodyChannels.toLong(), (V2VocoderWindows.FRAMES * SCALE).toLong()))
        override fun close() = headInput.close()
    }

    companion object {
        const val STEM = "v2-htp-stem.onnx"
        const val BODY = "v2-htp-body.onnx"
        const val HEAD = "v2-htp-head.onnx"
        val GRAPHS = listOf(STEM, BODY, HEAD)
        private const val SCALE = 640
    }
}

internal object V2VocoderWindows {
    const val FRAMES = 64
    // Derived from all five upsampling/residual stages, including the output head.
    private const val HALO = 8
    data class Window(val start: Int, val begin: Int, val end: Int)

    fun plan(length: Int): List<Window> {
        require(length >= FRAMES)
        val result = mutableListOf<Window>()
        var cursor = 0
        while (cursor < length) {
            val start = (cursor - HALO).coerceIn(0, length - FRAMES)
            val end = if (start + FRAMES == length) length else start + FRAMES - HALO
            result.add(Window(start, cursor, end))
            cursor = end
        }
        return result
    }
}
