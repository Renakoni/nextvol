package indi.renakoni.nextvol.tts.runtime

import android.content.Context
import android.os.Build
import android.os.Process
import indi.renakoni.nextvol.BuildConfig
import org.json.JSONObject
import java.io.File

internal enum class SpeechRuntimePack(val id: String) {
    Vendor("vendor"), Sovits("sovits");

    val supported get() = Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_64_BIT_ABIS &&
        (this != Sovits || (Build.VERSION.SDK_INT >= 29 && BuildConfig.MATCHA_NATIVE))

    private val spec by lazy {
        SpeechLibraries.context.assets.open("speech-runtimes/$id.json").bufferedReader().use { JSONObject(it.readText()) }
    }
    val url get() = spec.getString("url")
    val sha256 get() = spec.getString("sha256")
    val root get() = File(SpeechLibraries.context.noBackupFilesDir, "speech-runtimes/$id")
    val directory get() = File(root, sha256)
    val libraries get() = File(directory, "jniLibs/arm64-v8a")
    val installed get() = supported && directory.isDirectory
}

internal object SpeechLibraries {
    lateinit var context: Context
        private set

    fun initialize(application: Context) { context = application }

    private val vendorManifest by lazy {
        requireVendor()
        File(SpeechRuntimePack.Vendor.directory, "assets/offline-native/runtime-manifest.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("libraries") }
    }

    fun requireVendor() {
        if (!SpeechRuntimePack.Vendor.installed) throw UnsatisfiedLinkError("Install the vendor speech runtime in speech settings")
    }

    private val loadedVendor = mutableSetOf<String>()

    @JvmStatic @Synchronized fun loadVendor(name: String) {
        fun load(fileName: String) {
            if (fileName in loadedVendor) return
            val dependencies = vendorManifest.getJSONObject(fileName).getJSONArray("needed")
            for (i in 0 until dependencies.length()) {
                val dependency = dependencies.getString(i)
                if (vendorManifest.has(dependency)) load(dependency)
            }
            System.load(File(SpeechRuntimePack.Vendor.libraries, fileName).absolutePath)
            loadedVendor.add(fileName)
        }
        load(System.mapLibraryName(name))
    }

    // ORT's Java binding and NextVol's bridge link the same renamed WebGPU core.
    @JvmStatic fun loadSovits(name: String) {
        val directory = SpeechRuntimePack.Sovits.libraries
        System.load(File(directory, "libortwebgpu.so").absolutePath)
        System.load(File(directory, System.mapLibraryName(name)).absolutePath)
    }
}
