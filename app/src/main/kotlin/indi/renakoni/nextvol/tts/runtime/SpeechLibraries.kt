package indi.renakoni.nextvol.tts.runtime

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import indi.renakoni.nextvol.ProjectLinks
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

internal fun speechProcessAbis(context: Context): List<String> {
    if (!Process.is64Bit()) return Build.SUPPORTED_32_BIT_ABIS.toList()
    // A 64-bit emulator can advertise both ABIs but loads JNI for the selected app ABI.
    val abis = Build.SUPPORTED_64_BIT_ABIS
    if (abis.size <= 1) return abis.toList()
    return if (File(context.applicationInfo.nativeLibraryDir).name == "arm64") listOf("arm64-v8a") else abis.take(1)
}

internal data class RuntimeArchive(val sha256: String, val bytes: Long, val urls: List<String>, val path: String? = null)

internal class RuntimeFiles(id: String, val archives: List<RuntimeArchive>) {
    val root = File(SpeechLibraries.context.noBackupFilesDir, "speech-runtimes/$id")
    private val revision = if (archives.size == 1) archives.single().sha256 else {
        val hashes = archives.joinToString("-") { it.sha256 }
        MessageDigest.getInstance("SHA-256").digest(hashes.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    val directory = File(root, revision)
    val bytes = archives.sumOf { it.bytes }
    val installed get() = directory.isDirectory
}

internal enum class SpeechRuntimePack {
    Vendor, Sovits, SovitsModels;

    val files: List<RuntimeFiles> by lazy {
        when (this) {
            Vendor -> listOf(component("vendor", "vendor"))
            Sovits -> listOf(component("sovits-bridge", "sovits"))
            SovitsModels -> {
                val models = SpeechLibraries.context.assets.open("gpt-sovits/runtime-models.json").bufferedReader().use { JSONArray(it.readText()) }
                val weights = List(models.length()) { index ->
                    val model = models.getJSONObject(index)
                    val urls = model.getJSONArray("urls")
                    RuntimeArchive(model.getString("sha256"), model.getLong("bytes"),
                        List(urls.length(), urls::getString), model.getString("path"))
                }
                listOf(RuntimeFiles("sovits-models", weights), languageData)
            }
        }
    }
    val directory get() = files.first().directory
    val libraries get() = File(directory, "jniLibs/arm64-v8a")
    val installed get() = files.all { it.installed }
    val archiveBytes get() = files.sumOf { it.bytes }

    companion object {
        // Keep the published language data and weights in their existing content-addressed directories.
        val languageData by lazy { component("sovits", "sovits-data") }

        private fun component(id: String, catalog: String): RuntimeFiles {
            val spec = SpeechLibraries.context.assets.open("speech-runtimes/$catalog.json").bufferedReader().use { JSONObject(it.readText()) }
            val github = spec.getString("url")
            val mirror = "${ProjectLinks.DOWNLOADS}/releases/${github.substringAfter("/releases/download/")}"
            return RuntimeFiles(id, listOf(RuntimeArchive(spec.getString("sha256"), spec.getLong("size"), listOf(github, mirror))))
        }
    }
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

    @JvmStatic fun loadSovits(name: String) {
        val libraries = SpeechRuntimePack.Sovits.libraries
        System.load(File(libraries, "libortwebgpu.so").absolutePath)
        System.load(File(libraries, System.mapLibraryName(name)).absolutePath)
    }

    fun loadSovitsBridge() = loadSovits("nextvol-sovits")
}
