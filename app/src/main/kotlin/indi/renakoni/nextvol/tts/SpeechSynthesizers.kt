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
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
import indi.renakoni.nextvol.tts.offline.OfflineSpeechSynthesizer
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
) {
    /** Development entry for an already installed Sherpa model. */
    fun matcha(resources: MatchaResources): SpeechSynthesizer = MatchaSpeechSynthesizer(resources)

    fun synthesizer(): SpeechSynthesizer = object : SpeechSynthesizer {
        @Volatile private var selected: SpeechSynthesizer? = null
        override suspend fun open(settings: SpeechSettings): String {
            withContext(NonCancellable + Dispatchers.IO) { close() }
            currentCoroutineContext().ensureActive()
            val provider = when {
                settings.offlineVoice != null -> offline()
                settings.httpSource != null -> http()
                else -> system.synthesizer()
            }
            selected = provider
            return provider.open(settings)
        }
        override suspend fun synthesize(text: String, output: File) = checkNotNull(selected).synthesize(text, output)
        override fun cancel() { selected?.cancel() }
        override fun close() {
            val previous = selected
            selected = null
            previous?.close()
        }
    }

    private fun offline(): SpeechSynthesizer = object : SpeechSynthesizer {
        private var lease: OfflineSpeechRepository.Lease? = null
        @Volatile private var delegate: SpeechSynthesizer? = null
        override suspend fun open(settings: SpeechSettings): String {
            try {
                withContext(Dispatchers.IO) {
                    lease = offline.acquire(requireNotNull(settings.offlineVoice))
                    val active = checkNotNull(lease)
                    delegate = if (active.configuration.engine == "sherpa") MatchaSpeechSynthesizer(active.resources)
                        else OfflineSpeechSynthesizer(context, active.directory, active.configuration)
                }
                delegate!!.open(settings.copy(httpSource = null, voice = "", pitch = null))
                return lease!!.voice.name
            } catch (error: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { close() }
                if (error is CancellationException || error is SpeechException) throw error
                throw SpeechException(SpeechError.OfflineUnavailable).apply { initCause(error) }
            }
        }
        override suspend fun synthesize(text: String, output: File) = checkNotNull(delegate).synthesize(text, output)
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
        @Volatile private var owner: SourceBroker? = null
        @Volatile private var client: HttpSpeechClient? = null
        private var rate: Float? = null
        private var sourceId: String? = null

        override suspend fun open(settings: SpeechSettings): String {
            withContext(NonCancellable + Dispatchers.IO) { close() }
            currentCoroutineContext().ensureActive()
            rate = settings.rate
            return repository.withSource(requireNotNull(settings.httpSource)) { source, runtime ->
                val definition = source.playbackDefinition()
                sourceId = source.definition.id
                val broker = SourceBroker(runtime.toPath(), cipher = cipher)
                owner = broker
                client = HttpSpeechClient(definition, broker, source.origins.map { NetworkGrant(it) }, authority, runner::execute)
                source.definition.name
            } ?: throw SpeechException(SpeechError.HttpSourceUnavailable)
        }

        override suspend fun synthesize(text: String, output: File): List<SpeechTiming> {
            val active = client ?: throw SpeechException(SpeechError.HttpSourceUnavailable)
            val partial = File(output.parentFile, output.name + ".part")
            try {
                // Token refresh may replace a regional HTTP session and close its TLS pool.
                val audio = withContext(Dispatchers.IO) { active.synthesize(text, rate) }
                withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    partial.writeBytes(audio)
                    validateHttpSpeechAudio(partial)
                    currentCoroutineContext().ensureActive()
                    if (!partial.renameTo(output)) throw SpeechException(SpeechError.Storage)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: HttpSpeechException) {
                sourceId?.let { repository.recordDeniedOrigins(it, failure.deniedOrigins) }
                throw SpeechException(when (failure.error) {
                HttpSpeechError.PermissionDenied -> SpeechError.HttpPermission
                HttpSpeechError.UnsupportedDependency, HttpSpeechError.Script -> SpeechError.HttpUnsupported
                HttpSpeechError.LoginRequired -> SpeechError.HttpLogin
                HttpSpeechError.Network -> SpeechError.Network
                HttpSpeechError.Timeout -> SpeechError.SynthesisTimeout
                HttpSpeechError.InvalidAudio, HttpSpeechError.TooLarge -> SpeechError.InvalidAudio
                HttpSpeechError.InvalidSource -> SpeechError.HttpSourceUnavailable
            }).apply { initCause(failure) } }
            catch (failure: SpeechException) { throw failure }
            catch (_: java.io.IOException) { throw SpeechException(SpeechError.Storage) }
            finally { partial.delete() }
            return emptyList()
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
