package indi.renakoni.nextvol.tts.runtime

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import indi.renakoni.nextvol.ProjectLinks
import org.json.JSONObject
import java.io.File

internal fun speechProcessAbis(context: Context): List<String> {
    if (!Process.is64Bit()) return Build.SUPPORTED_32_BIT_ABIS.toList()
    // A 64-bit emulator can advertise both ABIs but loads JNI for the selected app ABI.
    val abis = Build.SUPPORTED_64_BIT_ABIS
    if (abis.size <= 1) return abis.toList()
    return if (File(context.applicationInfo.nativeLibraryDir).name == "arm64") listOf("arm64-v8a") else abis.take(1)
}

internal enum class SpeechRuntimePack(val id: String) {
    Vendor("vendor"), Sovits("sovits");

    private val spec by lazy {
        SpeechLibraries.context.assets.open("speech-runtimes/$id.json").bufferedReader().use { JSONObject(it.readText()) }
    }
    internal data class Archive(val sha256: String, val bytes: Long, val urls: List<String>)

    val archives: List<Archive> by lazy {
        val github = spec.getString("url")
        val asset = github.substringAfter("/releases/download/")
        val core = Archive(spec.getString("sha256"), spec.getLong("size"),
            listOf(github, "${ProjectLinks.DOWNLOADS}/releases/$asset"))
        val extra = spec.optJSONObject("supplement")
        if (extra == null) listOf(core) else {
            val urls = extra.getJSONArray("urls")
            listOf(core, Archive(extra.getString("sha256"), extra.getLong("size"), List(urls.length(), urls::getString)))
        }
    }
    val archiveBytes get() = archives.sumOf { it.bytes }
    val root get() = File(SpeechLibraries.context.noBackupFilesDir, "speech-runtimes/$id")
    val directory get() = File(root, archives.joinToString("-") { it.sha256 })
    val libraries get() = File(directory, "jniLibs/arm64-v8a")
    val installed get() = directory.isDirectory
}

internal object SpeechLibraries {
    lateinit var context: Application
        private set

    fun initialize(application: Application) { context = application }

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

    // The bridge and its JNI signatures are built with the APK. ORT and accelerator libraries stay on demand.
    fun loadSovitsBridge() {
        System.load(File(SpeechRuntimePack.Sovits.libraries, "libortwebgpu.so").absolutePath)
        System.loadLibrary("nextvol-sovits")
    }

    // ORT's Java binding and NextVol's bridge link the same renamed WebGPU core.
    @JvmStatic fun loadSovits(name: String) {
        val directory = SpeechRuntimePack.Sovits.libraries
        System.load(File(directory, "libortwebgpu.so").absolutePath)
        System.load(File(directory, System.mapLibraryName(name)).absolutePath)
    }
}
