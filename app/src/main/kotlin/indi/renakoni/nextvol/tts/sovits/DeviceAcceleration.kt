package indi.renakoni.nextvol.tts.sovits

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import indi.renakoni.nextvol.BuildConfig
import java.io.File

internal object DeviceAcceleration {
    // Houdini on MuMu advertises arm64 but cannot resolve the ARM64 ELF TLS used by this ORT build.
    private val usesMuMuHoudini by lazy {
        File("/system/lib64/libnb.so").isFile &&
            File("/proc/self/maps").useLines { lines -> lines.any { it.endsWith("/libhoudini.so") } }
    }

    @ChecksSdkIntAtLeast(api = 29)
    fun supportsRuntime(context: Context): Boolean =
        BuildConfig.MATCHA_NATIVE && Build.VERSION.SDK_INT >= 29 && Process.is64Bit() &&
            (Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a" ||
                (File(context.applicationInfo.nativeLibraryDir).name == "arm64" && !usesMuMuHoudini))

    private var checked = false
    private var htp: File? = null

    /** Called on an IO worker after first draw, or by an earlier speech request. */
    @Synchronized fun detect(context: Context): File? {
        if (checked) return htp
        checked = true
        val manufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else Build.HARDWARE
        if (!isQualcomm(manufacturer, Build.HARDWARE) || !supportsRuntime(context)) return null
        val runtime = File(context.applicationInfo.nativeLibraryDir)
        val updated = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val key = "${Build.FINGERPRINT}\n$updated"
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

    private fun cachedCapability(cache: File, key: String, probe: () -> Boolean): Boolean {
        when (runCatching { cache.readText() }.getOrNull()) {
            "true\n$key" -> return true
            "false\n$key" -> return false
        }
        return probe().also { available ->
            // An interrupted or unwritable cache only causes a fresh probe next time.
            runCatching { cache.writeText("$available\n$key") }
        }
    }

    private fun isQualcomm(manufacturer: String, hardware: String): Boolean =
        manufacturer.contains("qualcomm", ignoreCase = true) || manufacturer.equals("qti", ignoreCase = true) ||
            manufacturer.equals("qcom", ignoreCase = true) ||
            hardware.equals("qcom", ignoreCase = true)
}
