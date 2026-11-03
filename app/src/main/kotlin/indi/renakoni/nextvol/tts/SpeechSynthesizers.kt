package indi.renakoni.nextvol.tts

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.content.RuleTaskRunner
import hnovel.execution.ExecutionAuthority
import hnovel.network.NetworkGrant
import hnovel.network.SourceBroker
import hnovel.network.StorageCipher
import hnovel.speech.HttpSpeechClient
import hnovel.speech.HttpSpeechError
import hnovel.speech.HttpSpeechException
import indi.renakoni.nextvol.tts.matcha.MatchaResources
import indi.renakoni.nextvol.tts.matcha.MatchaSpeechSynthesizer
import indi.renakoni.nextvol.tts.matcha.modelKey
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
import indi.renakoni.nextvol.tts.matcha.OfflineVoiceRuntimeInfo
import indi.renakoni.nextvol.tts.offline.OfflineSpeechSynthesizer
import indi.renakoni.nextvol.tts.offline.OfflineAvailabilityChecker
import indi.renakoni.nextvol.tts.offline.offlineSpeechExclusiveGroup
import indi.renakoni.nextvol.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class SpeechSynthesizers @Inject constructor(
    private val system: SystemSpeechEngines,
    private val repository: HttpSpeechRepository,
    private val cipher: StorageCipher,
    private val authority: ExecutionAuthority,
    private val runner: RuleTaskRunner,
    private val offline: OfflineSpeechRepository,
    @ApplicationContext private val context: Context,
    private val availability: OfflineAvailabilityChecker,
    private val networkCheck: SpeechNetworkCheck,
    private val caches: SpeechSynthesizerCaches = SpeechSynthesizerCaches(),
) {
    /** Development entry for an already installed Sherpa model. */
    fun matcha(resources: MatchaResources): SpeechSynthesizer = MatchaSpeechSynthesizer(resources)

    fun synthesizer(): SpeechSynthesizer = ProcessingSpeechSynthesizer(CachedSpeechSynthesizer(caches, create = { settings ->
        when {
            settings.offlineVoice != null -> {
                val voice = offline.voices().find { it.reference == settings.offlineVoice }
                    ?: throw SpeechException(SpeechError.OfflineUnavailable)
                val configuration = offline.configuration(voice)
                if (configuration.engine == "sherpa") SpeechRuntime(offline(), "sherpa",
                    MatchaResources(offline.directory(voice.resourceReference), requireNotNull(configuration.sherpa)).modelKey)
                else SpeechRuntime(offline(), offlineSpeechExclusiveGroup(configuration.engine))
            }
            settings.httpSource != null -> SpeechRuntime(http())
            else -> SpeechRuntime(system.synthesizer())
        }
    }))

    private fun offline(): SpeechSynthesizer = object : SpeechSynthesizer {
        private var lease: OfflineSpeechRepository.Lease? = null
        @Volatile private var delegate: SpeechSynthesizer? = null
        override var audioParameters = SpeechAudioParameters()
            private set
        override val maxInputLength get() = checkNotNull(delegate).maxInputLength
        override suspend fun open(settings: SpeechSettings): String {
            var attempted = false
            try {
                withContext(Dispatchers.IO) {
                    lease = offline.acquire(requireNotNull(settings.offlineVoice))
                    val active = checkNotNull(lease)
                    audioParameters = offlineAudioParameters(active.configuration, settings)
                    val status = availability.inspect(active.configuration, active.voice.sizes.keys) {
                        File(active.directory, it).inputStream()
                    }
                    if (!status.canRun) throw SpeechException(SpeechError.OfflineUnavailable)
                    attempted = true
                    delegate = if (active.configuration.engine == "sherpa") MatchaSpeechSynthesizer(active.resources)
                        else OfflineSpeechSynthesizer(context, active.directory, active.configuration)
                }
                delegate!!.open(settings.copy(httpSource = null, voice = "", pitch = null))
                (delegate as? MatchaSpeechSynthesizer)?.let { synthesizer ->
                    withContext(Dispatchers.IO) {
                        val details = synthesizer.describe()
                        if (details.size == 2) lease?.let { active -> offline.recordRuntimeInfo(active.voice.reference,
                            OfflineVoiceRuntimeInfo(active.configuration.copy(engineParameters = ""), details[0], details[1])) }
                    }
                }
                return lease!!.voice.name
            } catch (error: Throwable) {
                if (attempted && error !is CancellationException && (error is Exception || error is LinkageError)) {
                    lease?.let { availability.record(it.voice.reference, it.configuration, R.string.tts_offline_initialization_failed) }
                }
                withContext(NonCancellable) { withContext(Dispatchers.IO) {
                    try { close() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
                } }
                if (error is CancellationException || error is SpeechException) throw error
                if (error is Exception || error is LinkageError)
                    throw SpeechException(SpeechError.OfflineUnavailable).apply { initCause(error) }
                throw error
            }
        }
        override suspend fun synthesize(text: String, output: File): List<SpeechTiming> {
            val active = checkNotNull(lease)
            try {
                val result = checkNotNull(delegate).synthesize(text, output)
                currentCoroutineContext().ensureActive()
                availability.record(active.voice.reference, active.configuration, R.string.tts_offline_verified)
                return result
            } catch (failure: Throwable) {
                if (failure !is CancellationException && (failure is Exception || failure is LinkageError))
                    availability.record(active.voice.reference, active.configuration, R.string.tts_offline_synthesis_failed)
                if (failure is LinkageError) throw SpeechException(SpeechError.OfflineUnavailable).apply { initCause(failure) }
                throw failure
            }
        }
        override fun cancel() { delegate?.cancel() }
        override fun close() {
            try { delegate?.close() } finally {
                delegate = null
                lease?.close()
                lease = null
            }
        }
    }

    private fun http(): SpeechSynthesizer = object : SpeechSynthesizer {
        override val maxInputLength = SPEECH_HTTP_INPUT_LIMIT
        @Volatile private var owner: SourceBroker? = null
        @Volatile private var client: HttpSpeechClient? = null
        override val supportsStreaming get() = client != null
        private var rate: Float? = null
        private var volume = 1f
        private var sourceId: String? = null
        private var selectedSource: SavedHttpSpeechSource? = null
        override var audioParameters = SpeechAudioParameters()
            private set

        override suspend fun open(settings: SpeechSettings): String {
            withContext(NonCancellable + Dispatchers.IO) { close() }
            currentCoroutineContext().ensureActive()
            rate = settings.rate
            volume = settings.httpVolume
            return repository.withSource(requireNotNull(settings.httpSource)) { source, runtime ->
                if (!source.available) throw SpeechException(SpeechError.HttpSourceUnavailable)
                val definition = source.playbackDefinition()
                audioParameters = httpAudioParameters(definition, settings)
                sourceId = source.definition.id
                selectedSource = source
                val broker = SourceBroker(runtime.toPath(), cipher = cipher)
                owner = broker
                client = HttpSpeechClient(definition, broker, source.origins.map { NetworkGrant(it) }, authority, runner::execute)
                source.definition.name
            } ?: throw SpeechException(SpeechError.HttpSourceUnavailable)
        }

        override suspend fun synthesize(text: String, output: File): List<SpeechTiming> {
            val partial = File(output.parentFile, output.name + ".part")
            try {
                requestAudio(text) { audio ->
                    withContext(Dispatchers.IO) {
                        currentCoroutineContext().ensureActive()
                        partial.writeBytes(audio)
                        validateHttpSpeechAudio(partial)
                        currentCoroutineContext().ensureActive()
                        if (!partial.renameTo(output)) throw SpeechException(SpeechError.Storage)
                    }
                }
            } finally { partial.delete() }
            return emptyList()
        }

        override suspend fun synthesizeEncoded(text: String, receive: (ByteArray, Int, Int) -> Unit) {
            requestAudio(text, receive) {}
        }

        private suspend fun requestAudio(text: String, receive: ((ByteArray, Int, Int) -> Unit)? = null,
            consume: suspend (ByteArray) -> Unit) {
            val active = client ?: throw SpeechException(SpeechError.HttpSourceUnavailable)
            try {
                networkCheck.check()
                // Token refresh may replace a regional HTTP session and close its TLS pool.
                val audio = withContext(Dispatchers.IO) { active.synthesize(text, rate, volume, receive) }
                consume(audio)
                selectedSource?.let { repository.recordSynthesis(it, R.string.tts_offline_verified) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: HttpSpeechException) {
                val error = when (failure.error) {
                    HttpSpeechError.PermissionDenied -> SpeechError.HttpPermission
                    HttpSpeechError.UnsupportedDependency -> SpeechError.HttpUnsupported
                    HttpSpeechError.InvalidSource, HttpSpeechError.Script -> SpeechError.HttpConfiguration
                    HttpSpeechError.Authentication -> SpeechError.HttpAuthentication
                    HttpSpeechError.LoginRequired -> SpeechError.HttpLogin
                    HttpSpeechError.Network -> SpeechError.Network
                    HttpSpeechError.Timeout -> SpeechError.SynthesisTimeout
                    HttpSpeechError.InvalidAudio, HttpSpeechError.TooLarge -> SpeechError.InvalidAudio
                }
                selectedSource?.let { repository.recordSynthesis(it, error.messageResource) }
                sourceId?.let { repository.recordDeniedOrigins(it, failure.deniedOrigins) }
                throw SpeechException(error).apply { initCause(failure) }
            }
            catch (failure: SpeechException) {
                selectedSource?.let { repository.recordSynthesis(it, failure.error.messageResource) }
                throw failure
            }
            catch (_: java.io.IOException) {
                selectedSource?.let { repository.recordSynthesis(it, R.string.tts_error_storage) }
                throw SpeechException(SpeechError.Storage)
            }
        }

        override fun cancel() { client?.cancel() }
        override fun close() {
            val previousClient = client
            val previousOwner = owner
            client = null
            owner = null
            try { previousClient?.close() } finally { previousOwner?.close() }
        }
    }
}

/** Inspect actual container/track data, not a service's often inaccurate Content-Type header. */
internal fun validateHttpSpeechAudio(file: File) {
    if (file.length() !in 12..(2L * 1024 * 1024)) throw SpeechException(SpeechError.InvalidAudio)
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw SpeechException(SpeechError.InvalidAudio)
        extractor.selectTrack(track)
        if (extractor.readSampleData(java.nio.ByteBuffer.allocate(64 * 1024), 0) <= 0)
            throw SpeechException(SpeechError.InvalidAudio)
    } catch (_: Exception) { throw SpeechException(SpeechError.InvalidAudio) }
    finally { extractor.release() }
}
