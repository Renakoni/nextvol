package indi.renakoni.nextvol.tts.online

import com.microsoft.cognitiveservices.speech.ResultReason
import com.microsoft.cognitiveservices.speech.SpeechConfig
import com.microsoft.cognitiveservices.speech.SpeechSynthesisCancellationDetails
import com.microsoft.cognitiveservices.speech.SpeechSynthesisOutputFormat
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import hnovel.speech.AzureSpeechConfiguration
import hnovel.speech.HttpSpeechError
import hnovel.speech.HttpSpeechException
import hnovel.speech.OnlineSpeechBackend
import hnovel.speech.speechPcmWave
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The owner authorizes the regional origin before entering the SDK's own network transport. */
internal class AzureSpeechBackend(
    metadata: JsonObject,
    private val execute: suspend (BrokerRequest) -> BrokerResponse,
) : OnlineSpeechBackend {
    private val configuration = AzureSpeechConfiguration(metadata)
    private val operation = Any()
    private val lifecycle = Any()
    private var speechConfig: SpeechConfig? = null
    private var synthesizer: SpeechSynthesizer? = null
    private var closed = false
    @Volatile private var cancelled = false

    override suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            try {
                synchronized(operation) {
                    if (!continuation.isActive) return@synchronized
                    check(!closed)
                    if (cancelled) throw CancellationException("Azure synthesis cancelled")
                    // Native configuration creation may block; cancel never waits for this initialization.
                    val current = synthesizer ?: createSynthesizer().also {
                        synchronized(lifecycle) { synthesizer = it }
                    }
                    if (!continuation.isActive) return@synchronized
                    val synthesis = synchronized(lifecycle) {
                        if (cancelled) throw CancellationException("Azure synthesis cancelled")
                        current.SpeakSsmlAsync(configuration.ssml(text, rate, volume))
                    }
                    continuation.invokeOnCancellation { stopSpeaking() }
                    synthesis.get().use { result ->
                        if (!continuation.isActive) return@use
                        if (cancelled) throw CancellationException("Azure synthesis cancelled")
                        if (result.reason != ResultReason.SynthesizingAudioCompleted) {
                            val error = SpeechSynthesisCancellationDetails.fromResult(result).errorCode.toString()
                            throw HttpSpeechException(when (error) {
                                "AuthenticationFailure" -> HttpSpeechError.Authentication
                                "Forbidden" -> HttpSpeechError.PermissionDenied
                                "BadRequest" -> HttpSpeechError.InvalidSource
                                "ServiceTimeout" -> HttpSpeechError.Timeout
                                else -> HttpSpeechError.Network
                            })
                        }
                        val audio = result.audioData
                        if (audio.isEmpty()) throw HttpSpeechException(HttpSpeechError.InvalidAudio)
                        continuation.resume(speechPcmWave(audio, 24000))
                    }
                }
            } catch (_: LinkageError) {
                if (continuation.isActive) continuation.resumeWithException(HttpSpeechException(HttpSpeechError.UnsupportedDependency))
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }
    }

    private fun createSynthesizer(): SpeechSynthesizer {
        val config = SpeechConfig.fromSubscription(configuration.apiKey, configuration.region)
        try {
            config.setSpeechSynthesisVoiceName(configuration.voiceName)
            config.setSpeechSynthesisOutputFormat(SpeechSynthesisOutputFormat.Raw24Khz16BitMonoPcm)
            return SpeechSynthesizer(config, null as AudioConfig?).also { speechConfig = config }
        } catch (failure: Throwable) { config.close(); throw failure }
    }

    override suspend fun voices(): List<JsonObject> = configuration.voices(execute)

    override fun cancel() {
        cancelled = true
        stopSpeaking()
    }

    private fun stopSpeaking() { synchronized(lifecycle) { synthesizer?.StopSpeakingAsync() } }

    override fun close() {
        try { cancel() } finally {
            synchronized(operation) {
                synchronized(lifecycle) {
                    closed = true
                    try { synthesizer?.close() } finally {
                        synthesizer = null
                        try { speechConfig?.close() } finally { speechConfig = null }
                    }
                }
            }
        }
    }
}
