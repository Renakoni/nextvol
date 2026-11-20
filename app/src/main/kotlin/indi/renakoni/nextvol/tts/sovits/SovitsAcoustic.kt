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

internal const val MAX_SOVITS_PCM_SAMPLES = (8 * 1024 * 1024 - 44) / 2

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
        val layout = try {
            SplitVocoderAssets.prepare(voice, runtime != null)
        } catch (failure: Exception) {
            Log.w("NextVolSoVITS", "Using original VITS graph", failure)
            SplitVocoderAssets.Layout.Full
        }
        split = layout != SplitVocoderAssets.Layout.Full
        if (runtime != null && layout == SplitVocoderAssets.Layout.Htp) {
            val updated = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            val channels = if (voice.version == GptSovitsVersion.V2Pro) 512 else 768
            try {
                htp = V2HtpVocoder(environment, ::session, directory, runtime, updated, channels)
            } catch (failure: Exception) {
                Log.w("NextVolSoVITS", "HTP unavailable for this voice", failure)
            }
        }
    }

    private fun session(name: String): OrtSession = sessions.getOrPut(name) {
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(4)
            options.setInterOpNumThreads(1)
            options.addConfigEntry("session.intra_op.allow_spinning", "0")
            if (gpuAvailable && (name == SplitVocoderAssets.HIFIGAN || name == FULL)) options.addWebGPU(emptyMap())
            environment.createSession(File(voice.model, name).absolutePath, options)
        }
    }

    fun generate(phones: LongArray, tokens: LongArray): ByteArray? {
        lastProvider = "CPU"
        if (!gpuAvailable && !split) return null
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
                inputs["speed"] = OnnxTensor.createTensor(environment, DoubleBuffer.wrap(doubleArrayOf(voice.options.speed.toDouble())), longArrayOf(1))
            return encoder.runCancellable(inputs).use { result ->
                val value = result[0] as OnnxTensor
                if (split) {
                    val speaker = inputs.getValue("ge")
                    try {
                        htp?.generate(value, speaker)?.let {
                            return@use SovitsNative.pcm(it).also { lastProvider = "QNN" }
                        }
                    } catch (failed: Exception) {
                        GenerationContext.checkCurrent()
                        htp?.close()
                        htp = null
                        Log.w("NextVolSoVITS", "HTP failed for this voice", failed)
                    }
                    val vocoderInputs = mapOf(SplitVocoderAssets.BOUNDARY to value, "ge" to speaker)
                    fun render() = session(SplitVocoderAssets.HIFIGAN).runCancellable(vocoderInputs).use {
                        pcm(it[0] as OnnxTensor)
                    }
                    if (!gpuAvailable) return@use render()
                    try { render() } catch (failed: Exception) {
                        GenerationContext.checkCurrent()
                        gpuAvailable = false
                        sessions.remove(SplitVocoderAssets.HIFIGAN)?.close()
                        Log.w("NextVolSoVITS", "WebGPU failed, rendering the same latent on CPU", failed)
                        render()
                    }
                } else pcm(value)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (failure !is Exception && failure !is LinkageError) throw failure
            GenerationContext.checkCurrent()
            gpuAvailable = false
            split = false
            close()
            Log.w("NextVolSoVITS", "Using original CPU vocoder for this voice", failure)
            return null
        } finally { inputs.values.forEach { it.close() } }
    }

    private fun pcm(value: OnnxTensor): ByteArray {
        val count = value.info.numElements
        require(count in 1..MAX_SOVITS_PCM_SAMPLES.toLong())
        val audio = FloatArray(count.toInt())
        value.floatBuffer.get(audio)
        return SovitsNative.pcm(audio).also { lastProvider = if (gpuAvailable) "WebGPU" else "CPU" }
    }

    override fun close() {
        htp?.close()
        htp = null
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    private companion object { const val FULL = "vits_fp32.onnx" }
}
