package indi.renakoni.nextvol.tts.offline

import android.content.Context
import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.BuildConfig
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
import indi.renakoni.nextvol.tts.matcha.OfflineVoiceRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class OfflineAvailability(
    val message: Int = R.string.tts_offline_unverified,
    val detail: String = "",
    val canRun: Boolean = true,
)

/** Only reads package/library metadata. Never links an SDK, opens a model or accesses the network. */
@Singleton
class OfflineAvailabilityChecker internal constructor(
    private val device: OfflineDevice,
    private val nativeDirectory: File,
    private val matchaEnabled: Boolean,
    manifest: () -> JSONObject?,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        OfflineDevice(Build.VERSION.SDK_INT,
            (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS).toList(),
            runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(0)),
        File(context.applicationInfo.nativeLibraryDir), BuildConfig.MATCHA_NATIVE, {
        runCatching { context.assets.open("offline-native/runtime-manifest.json").bufferedReader().use {
            JSONObject(it.readText()).getJSONObject("libraries")
        } }.getOrNull()
    })
    private val libraries by lazy(manifest)
    private val inspections = ConcurrentHashMap<String, OfflineAvailability>()
    private data class Observation(val reference: OfflineVoiceRef, val configuration: OfflineVoiceConfig)
    private val observations = mutableMapOf<Observation, OfflineAvailability>()
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()

    internal fun installed(voice: InstalledOfflineVoice, repository: OfflineSpeechRepository): OfflineAvailability {
        repository.problem(voice)?.let { return OfflineAvailability(R.string.tts_offline_missing, it, false) }
        val configuration = repository.configuration(voice)
        val directory = repository.directory(voice.reference)
        val result = inspect(configuration, voice.sizes.keys) { path -> File(directory, path).inputStream() }
        if (!result.canRun) return result
        return synchronized(observations) { observations[Observation(voice.reference, configuration)] } ?: result
    }

    internal fun record(reference: OfflineVoiceRef, configuration: OfflineVoiceConfig, message: Int) {
        synchronized(observations) {
            val key = Observation(reference, configuration)
            if (observations[key]?.message == message) return
            // Revisions/edited parameters cannot inherit the previous voice's successful trial.
            observations.keys.removeAll { it.reference.id == reference.id }
            observations[key] = OfflineAvailability(message)
        }
        mutableChanges.update { it + 1 }
    }

    internal fun inspect(configuration: OfflineVoiceConfig, paths: Set<String>, open: (String) -> InputStream): OfflineAvailability {
        val required = offlineLibraries(configuration)
            ?: return OfflineAvailability(R.string.tts_offline_model, canRun = false)
        if (configuration.engine == "audio") return OfflineAvailability()
        if (configuration.engine == "sherpa" && !matchaEnabled)
            return OfflineAvailability(R.string.tts_offline_runtime_missing, "libnextvol-matcha.so", false)
        val minimumApi = if (configuration.engine in setOf("google", "sherpa")) 26 else 24
        if (device.api < minimumApi) return OfflineAvailability(R.string.tts_offline_android, minimumApi.toString(), false)
        val pending = ArrayDeque(required.map { "lib$it.so" })
        val visited = mutableSetOf<String>()
        var incomplete = device.pageSize <= 0
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (!visited.add(name)) continue
            val file = File(nativeDirectory, name)
            if (!file.isFile) return if ("arm64-v8a" !in device.abis)
                OfflineAvailability(R.string.tts_offline_abi, "arm64-v8a", false)
            else OfflineAvailability(R.string.tts_offline_runtime_missing, name, false)
            val inspection = inspections.getOrPut(name) {
                runCatching { file.inputStream().use { inspectOfflineLibrary(it, device, name) } }
                    .getOrDefault(OfflineAvailability(R.string.tts_offline_check_incomplete))
            }
            if (!inspection.canRun) return inspection
            incomplete = incomplete || inspection.message == R.string.tts_offline_check_incomplete
            libraries?.optJSONObject(name)?.let { metadata ->
                if (metadata.optBoolean("requires_api_26") && device.api < 26)
                    return OfflineAvailability(R.string.tts_offline_android, "26", false)
                val dependencies = metadata.optJSONArray("needed")
                for (index in 0 until (dependencies?.length() ?: 0)) {
                    val dependency = dependencies!!.getString(index)
                    if (dependency !in platformLibraries) pending.add(dependency)
                }
            } ?: run {
                // The two source-built Sherpa libraries and the Microsoft client AAR are not in the vendor inventory.
                if (name !in separatelyBuiltLibraries) incomplete = true
            }
        }
        val packageLibrary = when (configuration.engine) {
            "larktts" -> if (configuration.parameters == "1") "libspeaker_xiaowu.so" else "libTtsVoiceCommon.so"
            "samsung" -> device.abis.asSequence().flatMap { abi -> sequenceOf(
                "${configuration.code}/lib/$abi/libsamsungtts.so", "${configuration.code}/lib/$abi/libsamsungttse.so")
            }.firstOrNull { it in paths }
                ?: return OfflineAvailability(R.string.tts_offline_package_abi, device.abis.joinToString(), false)
            else -> null
        }
        if (packageLibrary != null) {
            if (packageLibrary !in paths) return OfflineAvailability(R.string.tts_offline_missing, packageLibrary, false)
            val inspection = runCatching { open(packageLibrary).use { inspectOfflineLibrary(it, device, packageLibrary) } }.getOrNull()
            if (inspection != null && !inspection.canRun) return inspection
            incomplete = incomplete || inspection?.message == R.string.tts_offline_check_incomplete || inspection == null
        }
        return OfflineAvailability(if (incomplete) R.string.tts_offline_check_incomplete else R.string.tts_offline_unverified)
    }

    private companion object {
        val separatelyBuiltLibraries = setOf("libnextvol-matcha.so", "libonnxruntime.so",
            "libMicrosoft.CognitiveServices.Speech.java.bindings.so", "libMicrosoft.CognitiveServices.Speech.core.so")
        val platformLibraries = setOf("libc.so", "libm.so", "libdl.so", "liblog.so", "libstdc++.so", "libz.so",
            "libandroid.so", "libaaudio.so", "libEGL.so", "libGLESv2.so", "libnativewindow.so", "libsync.so")
    }
}

internal data class OfflineDevice(val api: Int, val abis: List<String>, val pageSize: Long)

/** The same variants chosen by the adapters, including explicit loads absent from DT_NEEDED. */
internal fun offlineLibraries(voice: OfflineVoiceConfig): List<String>? = when (voice.engine) {
    "audio" -> emptyList()
    "sherpa" -> listOf("nextvol-matcha", "onnxruntime")
    "bdetts" -> listOf("c++_shared", "BDSpeechDecoder_V1", when (voice.parameters.substringBefore(',').trim().ifEmpty { "90013" }) {
        "90013" -> "bd_ettm"; "9294715" -> "bd_ettf"; "37111357" -> "bd_ettl"; else -> "bd_etts"
    })
    "isstts" -> if (voice.parameters.split(',').getOrNull(1)?.trim() == "new") listOf("issxtts40", "issauth")
        else listOf("issxtts30", "issavth")
    "larktts" -> listOf(if (voice.parameters == "1") "synthesizer" else "Aisound")
    "samsung" -> listOf("smtloader")
    "microsoft" -> listOf("Microsoft.CognitiveServices.Speech.java.bindings", "Microsoft.CognitiveServices.Speech.core",
        "Microsoft.CognitiveServices.Speech.extension.telemetry", "Microsoft.CognitiveServices.Speech.extension.embedded.tts") +
        if (voice.parameters.lines().getOrNull(1)?.trim().orEmpty().let { it.isEmpty() || it in setOf("M\$LICENSE", "M\$LICENSE2") })
            listOf("speexdsp") else emptyList()
    "acapela" -> listOf("acattsandroid", "ttsacattsandroidextended")
    "cereproc" -> listOf("cerevoice_eng")
    "google" -> listOf("google_speech_sbg_tts_jni")
    "msctts" -> listOf("msc")
    "sougou" -> listOf("omp", "evalite_uapi", "sgsplit", "sgtts", "sgproxy_lib", "sgtts_lib")
    "vcstts" -> listOf("bl_vcs")
    "vocalizer" -> listOf("NuanceVocalizer")
    "weread" -> listOf("weread-tts", "hwTTS")
    "xiaomi" -> listOf("tts-offline")
    else -> null
}

/** Bounded ELF header inspection, not a model-content check. Unknown layouts remain unverified. */
internal fun inspectOfflineLibrary(input: InputStream, device: OfflineDevice, name: String): OfflineAvailability {
    val bytes = ByteArray(64 * 1024)
    var length = 0
    while (length < bytes.size) {
        val count = input.read(bytes, length, bytes.size - length)
        if (count <= 0) break
        length += count
    }
    fun unknown() = OfflineAvailability(R.string.tts_offline_check_incomplete)
    if (length < 64 || bytes[0] != 0x7f.toByte() || bytes[1] != 69.toByte() || bytes[2] != 76.toByte() || bytes[3] != 70.toByte()) return unknown()
    if (bytes[4].toInt() !in 1..2 || bytes[5].toInt() !in 1..2) return unknown()
    val data = ByteBuffer.wrap(bytes, 0, length).order(if (bytes[5].toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
    val wide = bytes[4].toInt() == 2
    val abi = when (data.getShort(18).toInt()) { 183 -> "arm64-v8a"; 40 -> "armeabi-v7a"; 62 -> "x86_64"; 3 -> "x86"; else -> return unknown() }
    if (abi !in device.abis) return OfflineAvailability(R.string.tts_offline_abi, abi, false)
    val offset = if (wide) data.getLong(32) else data.getInt(28).toLong() and 0xffffffffL
    val size = data.getShort(if (wide) 54 else 42).toInt() and 0xffff
    val count = data.getShort(if (wide) 56 else 44).toInt() and 0xffff
    if (offset < 0 || size < (if (wide) 56 else 32) || count == 0 || offset > length || count.toLong() * size > length - offset) return unknown()
    var loads = 0
    repeat(count) { index ->
        val start = (offset + index * size).toInt()
        if (data.getInt(start) == 1) {
            loads++
            val alignment = if (wide) data.getLong(start + 48) else data.getInt(start + 28).toLong() and 0xffffffffL
            val fileOffset = if (wide) data.getLong(start + 8) else data.getInt(start + 4).toLong() and 0xffffffffL
            val address = if (wide) data.getLong(start + 16) else data.getInt(start + 8).toLong() and 0xffffffffL
            if (device.pageSize > 0 && (alignment < device.pageSize || (fileOffset - address) % device.pageSize != 0L))
                return OfflineAvailability(R.string.tts_offline_pages, "$name · ${device.pageSize / 1024} KiB", false)
        }
    }
    return if (loads == 0 || device.pageSize <= 0) unknown() else OfflineAvailability()
}
