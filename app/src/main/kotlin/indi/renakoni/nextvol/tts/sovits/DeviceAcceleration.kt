package indi.renakoni.nextvol.tts.sovits

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import indi.renakoni.nextvol.BuildConfig
import indi.renakoni.nextvol.tts.runtime.SpeechRuntimePack
import indi.renakoni.nextvol.tts.runtime.speechProcessAbis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.FloatBuffer

data class SovitsAccelerationStatus(
    val gpu: Boolean? = null,
    val npu: Boolean? = null,
    val gpuInfo: String? = null,
    val npuInfo: String? = null,
)

internal object DeviceAcceleration {
    private val mutableStatus = MutableStateFlow(SovitsAccelerationStatus())
    val status = mutableStatus.asStateFlow()

    private val snapdragonPart = Regex("""\bSM[0-9]{4}\b""", RegexOption.IGNORE_CASE)
    private var probedBridge = false
    private var npuRuntime: File? = null

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

    /** Platform facts need no bridge, installed voice or shared model weights. */
    @Synchronized fun detect(context: Context): File? {
        if (probedBridge) return npuRuntime
        val supported = supportsRuntime(context)
        val manufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else Build.HARDWARE
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.BOARD
        val device = soc.takeUnless { it.isBlank() || it == Build.UNKNOWN } ?: Build.MODEL
        val pm = context.packageManager
        val qualcomm = manufacturer.contains("Qualcomm", true) || manufacturer.equals("QTI", true) ||
            manufacturer.equals("qcom", true) || Build.HARDWARE.equals("qcom", true) ||
            soc.contains("Snapdragon", true) || snapdragonPart.containsMatchIn(soc) ||
            "libcdsprpc.so" in pm.systemSharedLibraryNames.orEmpty()
        val vulkan = pm.systemAvailableFeatures.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
        val gpuInfo = vulkan?.version?.let { "Vulkan ${it ushr 22}.${(it ushr 12) and 1023}" }
        var gpu: Boolean? = if (supported) null else false
        if (supported && SpeechRuntimePack.Sovits.installed) {
            val graph = File(SpeechRuntimePack.Sovits.directory, "assets/gpt-sovits/qnn-probe.onnx")
            gpu = probe("GPU") { probeGpu(graph) }
            val runtime = File(context.filesDir, "QualcommRuntime")
            if (gpu && qualcomm && File(runtime, "libonnxruntime-qnn.so").isFile &&
                probe("NPU") { QnnSession.probe(runtime, graph) }) npuRuntime = runtime
            probedBridge = true
        }
        mutableStatus.value = SovitsAccelerationStatus(gpu, qualcomm, gpuInfo, device)
        return npuRuntime
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

    private fun probeGpu(model: File) {
        val environment = OrtEnvironment.getEnvironment()
        val graph = model.readBytes()
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
