package indi.renakoni.nextvol.tts.sovits

import android.content.Context
import android.os.Build
import android.util.Log
import indi.renakoni.nextvol.BuildConfig
import java.io.File

internal object DeviceAcceleration {
    private var checked = false
    private var htp: File? = null

    /** Called on an IO worker after first draw, or by an earlier speech request. */
    @Synchronized fun detect(context: Context): File? {
        if (checked) return htp
        checked = true
        val manufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else Build.HARDWARE
        if (!BuildConfig.MATCHA_NATIVE || Build.VERSION.SDK_INT < 29 ||
            !isQualcomm(manufacturer, Build.HARDWARE) || "arm64-v8a" !in Build.SUPPORTED_ABIS) return null
        val runtime = File(context.applicationInfo.nativeLibraryDir)
        if (!hasRuntimeFiles(runtime)) return null
        val updated = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val key = capabilityCacheKey(Build.FINGERPRINT, updated, runtime)
        if (cachedCapability(File(context.noBackupFilesDir, "sovits-npu-capability"), key) {
            val probe = File(context.cacheDir, "sovits-qnn-probe.onnx")
            try {
                context.assets.open("gpt-sovits/qnn-probe.onnx").use { input -> probe.outputStream().use(input::copyTo) }
                QnnSession.probe(runtime, probe)
                true
            } catch (failure: Exception) {
                Log.i("NextVolSoVITS", "HTP probe unavailable", failure)
                false
            } catch (failure: LinkageError) {
                Log.i("NextVolSoVITS", "HTP runtime unavailable", failure)
                false
            } finally { probe.delete() }
        }) htp = runtime
        return htp
    }

    internal fun capabilityCacheKey(system: String, appUpdatedAt: Long, runtime: File): String =
        "$system\n$appUpdatedAt\n" + runtime.listFiles().orEmpty().filter { it.isFile && (it.name.startsWith("libQnn") || it.name == "libonnxruntime-qnn.so") }
            .sortedBy { it.name }.joinToString("\n") { "${it.name}:${it.length()}:${it.lastModified()}" }

    internal fun cachedCapability(cache: File, key: String, probe: () -> Boolean): Boolean {
        when (runCatching { cache.readText() }.getOrNull()) {
            "true\n$key" -> return true
            "false\n$key" -> return false
        }
        return probe().also { available ->
            // An interrupted or unwritable cache only causes a fresh probe next time.
            runCatching { cache.writeText("$available\n$key") }
        }
    }

    internal fun isQualcomm(manufacturer: String, hardware: String): Boolean =
        manufacturer.contains("qualcomm", ignoreCase = true) || manufacturer.equals("qti", ignoreCase = true) ||
            manufacturer.equals("qcom", ignoreCase = true) ||
            hardware.equals("qcom", ignoreCase = true)

    internal fun hasRuntimeFiles(directory: File): Boolean {
        if (!listOf("libonnxruntime-qnn.so", "libQnnHtp.so", "libQnnHtpPrepare.so", "libQnnSystem.so")
                .all { File(directory, it).isFile }) return false
        return directory.listFiles().orEmpty().any { file ->
            file.isFile && file.name.matches(Regex("libQnnHtpV[0-9]+Stub\\.so")) &&
                File(directory, file.name.replace("Stub.so", "Skel.so")).isFile
        }
    }
}
