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

internal enum class SpeechRuntimePack(val id: String) {
    Vendor("vendor"), Sovits("sovits"), SovitsModels("sovits-models");

    private val spec by lazy {
        SpeechLibraries.context.assets.open("speech-runtimes/$id.json").bufferedReader().use { JSONObject(it.readText()) }
    }
    internal data class Archive(val sha256: String, val bytes: Long, val urls: List<String>, val path: String? = null)

    val archives: List<Archive> by lazy {
        if (this == SovitsModels) {
            val files = SpeechLibraries.context.assets.open("gpt-sovits/runtime-models.json").bufferedReader().use { JSONArray(it.readText()) }
            return@lazy List(files.length()) { index ->
                val file = files.getJSONObject(index)
                val urls = file.getJSONArray("urls")
                Archive(file.getString("sha256"), file.getLong("bytes"), List(urls.length(), urls::getString), file.getString("path"))
            }
        }
        val github = spec.getString("url")
        val asset = github.substringAfter("/releases/download/")
        listOf(Archive(spec.getString("sha256"), spec.getLong("size"),
            listOf(github, "${ProjectLinks.DOWNLOADS}/releases/$asset")))
    }
    val archiveBytes get() = archives.sumOf { it.bytes }
    val root get() = File(SpeechLibraries.context.noBackupFilesDir, "speech-runtimes/$id")
    private val revision by lazy {
        val hashes = archives.joinToString("-") { it.sha256 }
        if (this == SovitsModels) MessageDigest.getInstance("SHA-256").digest(hashes.toByteArray())
            .joinToString("") { "%02x".format(it) } else hashes
    }
    val directory get() = File(root, revision)
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

    // Startup probes and synthesis share the APK's inference backends.
    fun loadSovitsBridge() {
        System.loadLibrary("nextvol-sovits")
    }
}
