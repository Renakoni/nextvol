package indi.renakoni.nextvol.tts.sovits

import android.content.Context
import android.os.Build
import android.util.Log
import indi.renakoni.nextvol.tts.runtime.SpeechRuntimePack
import java.io.File

internal object DeviceAcceleration {
    private var checked = false
    private var htp: File? = null

    /** Called on an IO worker after first draw, or by an earlier speech request. */
    @Synchronized fun detect(context: Context): File? {
        if (checked) return htp
        if (!SpeechRuntimePack.Sovits.installed) return null
        checked = true
        val manufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else Build.HARDWARE
        if (!isQualcomm(manufacturer, Build.HARDWARE)) return null
        val runtime = SpeechRuntimePack.Sovits.libraries
        val updated = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val key = "${Build.FINGERPRINT}\n$updated\n${SpeechRuntimePack.Sovits.sha256}"
        if (cachedCapability(File(context.noBackupFilesDir, "sovits-npu-capability"), key) {
            val probe = File(SpeechRuntimePack.Sovits.directory, "assets/gpt-sovits/qnn-probe.onnx")
            try {
                QnnSession.probe(runtime, probe)
                true
            } catch (failure: Exception) {
                Log.i("NextVolSoVITS", "HTP probe unavailable", failure)
                false
            } catch (failure: LinkageError) {
                Log.i("NextVolSoVITS", "HTP runtime unavailable", failure)
                false
            }
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
