package indi.renakoni.nextvol.tts.sovits

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.util.AtomicFile
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import indi.renakoni.nextvol.BuildConfig
import indi.renakoni.nextvol.tts.runtime.speechProcessAbis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer

data class SovitsAccelerationStatus(val gpu: Boolean? = null, val npu: Boolean? = null)

internal object DeviceAcceleration {
    private val mutableStatus = MutableStateFlow(SovitsAccelerationStatus())
    val status = mutableStatus.asStateFlow()

    // This translator cannot resolve the ARM64 ELF TLS used by the inference backend.
    private val usesUnsupportedTranslation by lazy {
        File("/system/lib64/libnb.so").isFile &&
            File("/proc/self/maps").useLines { lines -> lines.any { it.endsWith("/libhoudini.so") } }
    }

    @ChecksSdkIntAtLeast(api = 29)
    fun supportsRuntime(context: Context): Boolean =
        BuildConfig.MATCHA_NATIVE && Build.VERSION.SDK_INT >= 29 && supportsProcess(speechProcessAbis(context))

    fun supportsProcess(abis: List<String>): Boolean = "arm64-v8a" in abis &&
        (Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a" || !usesUnsupportedTranslation)

    /** First-draw worker and synthesis share one probe. Results persist across app updates. */
    @Synchronized fun detect(context: Context): File? {
        val runtime = File(context.applicationInfo.nativeLibraryDir)
        if (status.value.gpu == null) {
            val cache = AtomicFile(File(context.noBackupFilesDir, "sovits-acceleration"))
            val saved = runCatching { cache.openRead().bufferedReader().use {
                val json = JSONObject(it.readText())
                SovitsAccelerationStatus(json.getBoolean("gpu"), json.getBoolean("npu"))
            } }.getOrNull()
            val result = if (!supportsRuntime(context)) SovitsAccelerationStatus(false, false)
            else if (saved != null) saved
            else {
                val gpu = probe("GPU") { probeGpu(context) }
                val manufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else Build.HARDWARE
                val qualcomm = manufacturer.contains("qualcomm", true) || manufacturer.equals("qti", true) ||
                    manufacturer.equals("qcom", true) || Build.HARDWARE.equals("qcom", true)
                val npu = gpu && qualcomm && probe("NPU") {
                    val graph = File(context.cacheDir, "sovits-npu-probe.onnx")
                    try {
                        context.assets.open("gpt-sovits/qnn-probe.onnx").use { input -> graph.outputStream().use(input::copyTo) }
                        QnnSession.probe(runtime, graph)
                    } finally { graph.delete() }
                }
                SovitsAccelerationStatus(gpu, npu).also { detected ->
                    try {
                        val output = cache.startWrite()
                        try {
                            output.write(JSONObject().put("gpu", detected.gpu).put("npu", detected.npu).toString().toByteArray())
                            cache.finishWrite(output)
                        } catch (failure: Exception) { cache.failWrite(output); throw failure }
                    } catch (failure: Exception) { Log.w("NextVolSoVITS", "Cannot cache acceleration", failure) }
                }
            }
            mutableStatus.value = result
            Log.i("NextVolSoVITS", "Acceleration GPU=${result.gpu} NPU=${result.npu} cached=${saved != null}")
        }
        return runtime.takeIf { status.value.npu == true }
    }

    private fun probe(name: String, execute: () -> Unit): Boolean = try {
        execute()
        true
    } catch (failure: Exception) {
        Log.i("NextVolSoVITS", "$name probe unavailable", failure)
        false
    } catch (failure: LinkageError) {
        Log.i("NextVolSoVITS", "$name runtime unavailable", failure)
        false
    }

    private fun probeGpu(context: Context) {
        val environment = OrtEnvironment.getEnvironment()
        val graph = context.assets.open("gpt-sovits/qnn-probe.onnx").use { it.readBytes() }
        OrtSession.SessionOptions().use { options ->
            options.addWebGPU(emptyMap())
            options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
            environment.createSession(graph, options).use { session ->
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(floatArrayOf(1f, 2f, 3f, 4f)), longArrayOf(1, 4)).use { input ->
                    session.run(mapOf("x" to input)).use { result ->
                        val output = (result[0] as OnnxTensor).floatBuffer
                        for (i in 0 until 4) check(kotlin.math.abs(output.get(i) - ((i + 1) * .5f + .25f)) < .01f)
                    }
                }
            }
        }
    }
}
