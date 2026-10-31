package indi.renakoni.nextvol.tts.offline.legacy

import android.content.Context
import com.microsoft.cognitiveservices.speech.AudioDataStream
import com.microsoft.cognitiveservices.speech.EmbeddedSpeechConfig
import com.microsoft.cognitiveservices.speech.ResultReason
import com.microsoft.cognitiveservices.speech.SpeechSynthesisCancellationDetails
import com.microsoft.cognitiveservices.speech.SpeechSynthesisOutputFormat
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.offline.OfflineEngine
import kotlinx.coroutines.CancellationException
import org.nobody.multitts.tts.jni.SpeexBridge
import java.io.File
import kotlin.math.roundToInt

internal class MicrosoftEngine(
    @Suppress("UNUSED_PARAMETER") context: Context,
    directory: File,
    private val voice: OfflineVoiceConfig,
) : OfflineEngine {
    private val lifecycle = Any()
    private var embeddedConfig: EmbeddedSpeechConfig? = null
    private var synthesizer: SpeechSynthesizer? = null
    @Volatile private var cancelled = false
    override val sampleRate = 24000

    init {
        require(voice.type == 0) { "This Microsoft voice uses an online subscription" }
        val model = File(directory, voice.code)
        require(model.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()) && model.isDirectory) {
            "Missing or escaped Microsoft voice directory: ${voice.code}"
        }
        val parameters = voice.parameters.lines().map(String::trim)
        val name = parameters.firstOrNull().orEmpty()
        require(name.isNotEmpty()) { "Microsoft voice parameters must specify its SDK voice name" }
        val suppliedLicense = parameters.getOrNull(1).orEmpty()
        val license = when {
            suppliedLicense.isEmpty() -> "Key:" + SpeexBridge.getLicense(0)
            suppliedLicense == "M\$LICENSE" -> SpeexBridge.getLicense(1)
            suppliedLicense == "M\$LICENSE2" -> SpeexBridge.getLicense(2)
            !suppliedLicense.startsWith("Key:") && suppliedLicense.length <= 300 -> "Key:$suppliedLicense"
            else -> suppliedLicense
        }
        val configuration = EmbeddedSpeechConfig.fromPath(model.absolutePath)
        try {
            configuration.setSpeechSynthesisVoice(name, license)
            configuration.setSpeechSynthesisOutputFormat(SpeechSynthesisOutputFormat.Raw24Khz16BitMonoPcm)
            synthesizer = SpeechSynthesizer(configuration, null as AudioConfig?)
            embeddedConfig = configuration
        } catch (failure: Throwable) {
            try { configuration.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    @Synchronized override fun synthesize(text: String, speed: Float, emit: (ByteArray, Int) -> Unit) {
        val current = checkNotNull(synthesizer) { "Microsoft engine is closed" }
        cancelled = false
        val rate = ((speed * voice.speed - 1f) * 100f).roundToInt()
        val volume = (voice.volume * 100f).roundToInt()
        val locale = voice.locale.ifEmpty { "en-US" }
        val ssml = "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"${locale.xml()}\">" +
            "<voice name=\"${voice.code.xml()}\"><prosody volume=\"$volume%\" rate=\"$rate%\" pitch=\"+0Hz\">" +
            text.xml() + "</prosody></voice></speak>"
        current.SpeakSsml(ssml).use { result ->
            if (cancelled) throw CancellationException("Microsoft synthesis cancelled")
            if (result.reason == ResultReason.Canceled) {
                val details = SpeechSynthesisCancellationDetails.fromResult(result)
                error("Microsoft synthesis failed (${details.errorCode}): ${details.errorDetails}")
            }
            check(result.reason == ResultReason.SynthesizingAudioCompleted) {
                "Microsoft synthesis did not complete: ${result.reason}"
            }
            AudioDataStream.fromResult(result).use { stream ->
                val buffer = ByteArray(4800)
                while (!cancelled) {
                    val length = stream.readData(buffer).toInt()
                    if (length <= 0) break
                    emit(buffer.copyOf(length), length)
                }
            }
        }
        if (cancelled) throw CancellationException("Microsoft synthesis cancelled")
    }

    override fun cancel() {
        cancelled = true
        synchronized(lifecycle) { synthesizer?.StopSpeakingAsync() }
    }

    @Synchronized override fun close() {
        synchronized(lifecycle) {
            try { synthesizer?.close() } finally {
                synthesizer = null
                try { embeddedConfig?.close() } finally { embeddedConfig = null }
            }
        }
    }

    private fun String.xml(): String = replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
