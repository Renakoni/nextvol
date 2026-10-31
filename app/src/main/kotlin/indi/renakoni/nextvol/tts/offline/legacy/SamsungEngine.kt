package indi.renakoni.nextvol.tts.offline.legacy

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import com.samsung.SMT.engine.SmtTTS
import com.samsung.SMT.ref.VoiceDataInfo
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.offline.OfflineEngine
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class SamsungEngine(@Suppress("UNUSED_PARAMETER") context: Context, directory: File, private val voice: OfflineVoiceConfig) : OfflineEngine {
    private val engine = SmtTTS()
    private val lifecycle = Any()
    private var handle = 0L
    @Volatile private var cancelled = false
    private val language: String
    private val country: String
    override val sampleRate: Int

    init {
        val parts = voice.code.split('-', '_')
        require(parts.size >= 3 && parts[2].length >= 3) { "Invalid Samsung voice code" }
        val locale = Locale(parts[0], parts[1])
        language = locale.isO3Language
        country = locale.isO3Country
        val declaredType = parts[2].substring(0, 1)
        val voiceId = parts[2].substring(1, 3).toInt()
        val type = when (declaredType) {
            "s", "h", "x", "y" -> 2
            "f", "m", "l", "g", "F", "M", "p", "r", "t" -> 3
            "d" -> 1
            else -> 0
        }
        val voiceType = when (declaredType) { "s" -> "F"; "h" -> "M"; "d" -> "f"; else -> declaredType }
        val model = File(directory, voice.code)
        require(model.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()) && model.isDirectory) {
            "Missing or escaped Samsung voice directory"
        }
        val library = Build.SUPPORTED_ABIS.asSequence().flatMap { abi ->
            sequenceOf(File(model, "lib/$abi/libsamsungtts.so"), File(model, "lib/$abi/libsamsungttse.so"))
        }.firstOrNull(File::isFile) ?: error("Samsung voice has no library for this device ABI")
        require(library.canonicalFile.toPath().startsWith(model.canonicalFile.toPath())) { "Escaped Samsung voice library" }
        handle = engine.initialize(identities.getAndIncrement())
        check(handle != 0L) { "Samsung loader could not be initialized" }
        try {
            checkCode("load engine", engine.loadEngine(handle, library.canonicalPath))
            val files = model.listFiles().orEmpty().filter { it.isFile &&
                (it.name == "cfg" || it.name == "lng" || it.name.endsWith(".ivc") || it.name.endsWith(".tsv")) }.sortedBy { it.name }
            check(files.isNotEmpty()) { "Samsung voice contains no data files" }
            val descriptors = mutableListOf<ParcelFileDescriptor>()
            try {
                files.forEach { file ->
                    require(file.canonicalFile == file.absoluteFile) { "Escaped Samsung voice data" }
                    descriptors += ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                }
                val data = files.zip(descriptors).map { (file, descriptor) ->
                    val length = descriptor.statSize
                    VoiceDataInfo().apply {
                        // Native fdopen takes ownership during setLanguageWithFD.
                        setFDInfo(descriptor.detachFd(), 0, length)
                        setFileInfo(file.name)
                    }
                }.toTypedArray()
                val result = engine.setLanguageWithFD(handle, data, language, country, voiceType, voiceId, type,
                    voice.parameters.contains("fp16", ignoreCase = true))
                checkCode("load voice", result)
            } finally { descriptors.forEach { it.close() } }
            // Original Samsung adapter treats ending-silence control as optional.
            try { engine.setEndingSilence(handle, 0) }
            catch (_: Exception) { }
            catch (_: UnsatisfiedLinkError) { }
            sampleRate = engine.getSamplingRate(handle)
            check(sampleRate > 0) { "Samsung returned an invalid sample rate: $sampleRate" }
        } catch (failure: Throwable) {
            engine.terminate(handle)
            handle = 0L
            throw failure
        }
    }

    @Synchronized override fun synthesize(text: String, speed: Float, emit: (ByteArray, Int) -> Unit) {
        check(handle != 0L) { "Samsung engine is closed" }
        cancelled = false
        checkCode("parameters", engine.setParameters(handle, (speed * voice.speed * 100).toInt(),
            (voice.volume * 100).toInt(), voice.pitch * 100, 0))
        engine.setMaxBufferSize(handle, 64 * 1024)
        val outputFailure = AtomicReference<Throwable?>()
        engine.setListener { data ->
            if (!cancelled) try { emit(data, data.size) }
            catch (failure: Throwable) {
                outputFailure.compareAndSet(null, failure)
                cancelled = true
            }
        }
        try {
            val prepared = if (engine.isSupportMultiInstance(handle)) text else {
                engine.supervisor_InputText(handle, text, language, country)
                val utterance = arrayOfNulls<String>(3)
                buildString {
                    while (!cancelled && engine.supervisor_GetUtterance(handle, utterance, false)) append(utterance[0].orEmpty())
                }.ifEmpty { text }
            }
            if (!cancelled) {
                val result = engine.speak(handle, prepared)
                outputFailure.get()?.let { throw it }
                if (!cancelled) checkCode("synthesis", result)
            }
            if (cancelled) throw CancellationException("Samsung synthesis cancelled")
        } finally { engine.setListener(null) }
    }

    override fun cancel() = synchronized(lifecycle) {
        cancelled = true
        if (handle != 0L) engine.stopSpeak(handle)
    }

    @Synchronized override fun close() = synchronized(lifecycle) {
        if (handle != 0L) {
            val current = handle
            handle = 0L
            engine.setListener(null)
            try { engine.unloadEngine(current) }
            finally { engine.terminate(current) }
        }
    }

    private fun checkCode(operation: String, result: Int) {
        check(result == 0) { "Samsung $operation failed: $result" }
    }

    companion object { private val identities = AtomicInteger(1) }
}
