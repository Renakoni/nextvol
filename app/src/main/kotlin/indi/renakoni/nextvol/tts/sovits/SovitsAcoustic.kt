package indi.renakoni.nextvol.tts.sovits

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.DoubleBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.CancellationException

/** V2's autoregressive decoder stays on CPU. The acoustic stage owns its separate WebGPU runtime. */
internal class SovitsAcoustic(
    private val voice: GptSovitsVoice, private val ge: FloatArray, private val advanced: FloatArray,
) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val sessions = mutableMapOf<String, OrtSession>()
    private var split = false
    private var gpuAvailable = true
    private var htp: V2HtpVocoder? = null
    var lastProvider = "CPU"
        private set

    fun prepare(context: Context) {
        val directory = voice.model
        val runtime = DeviceAcceleration.detect(context)
        try {
            SplitVocoderAssets.prepare(context, voice, runtime != null)
        } catch (failure: java.io.IOException) {
            Log.i("NextVolSoVITS", "Using installed voice graphs", failure)
        }
        if (File(directory, "split-vits.json").isFile) {
            try {
                SplitVocoderAssets.validate(directory)
                split = true
            } catch (failure: Exception) {
                Log.w("NextVolSoVITS", "Ignoring incompatible split graphs", failure)
            }
        }
        if (runtime != null && split && V2HtpVocoder.GRAPHS.all { File(directory, it).isFile }) {
            val channels = if (voice.version == GptSovitsVersion.V2Pro) 512 else 768
            htp = V2HtpVocoder(environment, ::session, directory, { runtime }, "balanced", channels)
            htp?.prepare()
        }
    }

    private fun session(name: String): OrtSession = sessions.getOrPut(name) {
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(4)
            options.setInterOpNumThreads(1)
            options.addConfigEntry("session.intra_op.allow_spinning", "0")
            if (name == SplitVocoderAssets.HIFIGAN || name == FULL) options.addWebGPU(emptyMap())
            environment.createSession(File(voice.model, name).absolutePath, options)
        }
    }

    fun generate(phones: LongArray, tokens: LongArray): ByteArray? {
        lastProvider = "CPU"
        if (!gpuAvailable && htp == null) return null
        val inputs = mutableMapOf<String, OnnxTensor>()
        try {
            val encoder = session(if (split) SplitVocoderAssets.ENCODER else FULL)
            inputs["text_seq"] = OnnxTensor.createTensor(environment, LongBuffer.wrap(phones), longArrayOf(1, phones.size.toLong()))
            inputs["pred_semantic"] = OnnxTensor.createTensor(environment, LongBuffer.wrap(tokens), longArrayOf(1, 1, tokens.size.toLong()))
            fun condition(values: FloatArray) = OnnxTensor.createTensor(environment, FloatBuffer.wrap(values),
                longArrayOf(1, values.size.toLong(), 1))
            inputs["ge"] = condition(ge)
            inputs["ge_advanced"] = condition(advanced)
            if ("speed" in encoder.inputNames)
                inputs["speed"] = OnnxTensor.createTensor(environment, DoubleBuffer.wrap(doubleArrayOf(1.0)), longArrayOf(1))
            return encoder.runCancellable(inputs).use { result ->
                val value = result[0] as OnnxTensor
                if (split) {
                    val speaker = inputs.getValue("ge")
                    htp?.generate(value, speaker)?.let {
                        lastProvider = "QNN"
                        return@use SovitsNative.pcm(it)
                    }
                    if (!gpuAvailable) return@use null
                    session(SplitVocoderAssets.HIFIGAN).runCancellable(mapOf(SplitVocoderAssets.BOUNDARY to value, "ge" to speaker)).use {
                        pcm(it[0] as OnnxTensor)
                    }
                } else pcm(value)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (failure !is Exception && failure !is LinkageError) throw failure
            GenerationContext.checkCurrent()
            gpuAvailable = false
            sessions.remove(if (split) SplitVocoderAssets.HIFIGAN else FULL)?.close()
            Log.w("NextVolSoVITS", "WebGPU unavailable for this voice, using CPU", failure)
            return null
        } finally { inputs.values.forEach { it.close() } }
    }

    private fun pcm(value: OnnxTensor): ByteArray {
        val count = value.info.numElements
        require(count in 1..((8 * 1024 * 1024L - 44) / 2))
        val audio = FloatArray(count.toInt())
        value.floatBuffer.get(audio)
        return SovitsNative.pcm(audio).also { lastProvider = "WebGPU" }
    }

    override fun close() {
        htp?.close()
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    private companion object { const val FULL = "vits_fp32.onnx" }
}
