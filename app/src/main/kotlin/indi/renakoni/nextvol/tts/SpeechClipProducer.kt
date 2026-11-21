package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.data.logging.SpeechLogEvent
import indi.renakoni.nextvol.data.logging.SpeechLogRecord
import indi.renakoni.nextvol.data.logging.SpeechLogStage
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.sovits.SovitsSegmentation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

/** Runs in the session's child task; playback, progress and provider lifetime remain with the session. */
internal class SpeechClipProducer(
    private val chapters: SpeechChapterSource,
    private val provider: SpeechSynthesizer,
    private val ioDispatcher: CoroutineDispatcher,
    private val preparePlan: suspend (SpeechChapter, SpeechSettings, SpeechContext, Int) -> SpeechPlan,
    private val diagnostics: (SpeechLogRecord) -> Unit,
    private val detailedLogging: () -> Boolean,
    private val nowMs: () -> Long,
) {
    sealed interface Step {
        class Ready(val clip: SpeechClip, val prefetchShortClips: Boolean = false) : Step {
            val successor = CompletableDeferred<SpeechClip?>()
        }
        data class Skipped(val plan: SpeechPlan) : Step
        data class Failed(val error: SpeechError, val request: SpeechRequest, val chapter: SpeechChapter?,
            val plan: SpeechPlan?, val clip: SpeechClip?, val context: SpeechContext,
            val replacementFailure: SpeechReplacementFailure? = null, val roleFailure: SpeechRoleFailure? = null) : Step
    }

    suspend fun produce(request: SpeechRequest, directory: File, saved: SpeechBookmark?, synthesis: SpeechSettings,
        reusablePlan: SpeechPlan?, initialContext: SpeechContext, opened: SpeechSettings?, initialLabel: String,
        maxInputLength: Int, processingKey: String, remainingMs: () -> Long, send: suspend (Step) -> Unit) {
        // Streaming PCM can call onReady on its decoder thread. Queue accounting belongs to the session.
        val sessionContext = currentCoroutineContext().minusKey(Job)
        var activeSettings = opened
        var voiceLabel = initialLabel
        var target = request
        var chapter: SpeechChapter? = null
        var plan: SpeechPlan? = null
        var pending: SpeechClip? = null
        var splitter: SovitsSegmentation? = null
        var previousVoice: SpeechResolvedVoice? = null
        var previousTempo = 1f
        var stage = SpeechLogStage.Analysis
        // A failed chapter load has no fingerprint yet; retain its input seed for another retry.
        var context = saved?.takeIf { !request.isPreview && it.bookId == request.bookId &&
            it.chapterId == request.chapterId && it.replacementKey == processingKey }
            ?.context ?: initialContext
        try {
            var first = true
            while (true) {
                chapter = null
                plan = null
                pending = null
                stage = SpeechLogStage.Analysis
                chapter = if (target.isPreview) SpeechChapter("", "", "", "", target.previewText.orEmpty())
                    else loadChapter(target)
                val loaded = chapter
                if (first) context = saved?.takeIf { !target.isPreview && it.bookId == loaded.bookId &&
                    it.chapterId == loaded.id && (it.fingerprint == loaded.fingerprint ||
                        it.fingerprint.isEmpty() && it.offset == 0) &&
                    it.replacementKey == processingKey }?.context ?: initialContext
                plan = reusablePlan?.takeIf { it.matches(loaded, synthesis, context, maxInputLength) } ?: withContext(ioDispatcher) {
                    preparePlan(loaded, synthesis, context, maxInputLength)
                }
                var (index, offset) = plan.resumeAt(saved.takeIf { first })
                if (plan.segments.isEmpty()) send(Step.Skipped(plan))
                while (index < plan.segments.size) {
                    val segment = plan.segments[index]
                    val selected = segment.selectedVoice
                    val pause = segment.pauseMs
                    val file = File(directory, UUID.randomUUID().toString() + ".wav")
                    var clip = SpeechClip(plan, index, file, textStart = offset)
                    pending = clip
                    var delivered = false
                    try {
                        if (pause != null) {
                            send(Step.Ready(clip.copy(durationMs = pause.toLong())))
                        } else {
                            stage = SpeechLogStage.Initialization
                            selected?.error?.let { throw SpeechException(it) }
                            val parameters = selected?.settings ?: synthesis
                            if (activeSettings != parameters) {
                                voiceLabel = provider.open(parameters)
                                activeSettings = parameters
                            }
                            if (previousVoice != selected || previousTempo != segment.audioParameters.speed) {
                                splitter = selected?.sovitsSpeed?.takeIf { it in .8f..1.2f }?.let(::SovitsSegmentation)
                                previousVoice = selected
                                previousTempo = segment.audioParameters.speed
                            }
                            // Opening a new model may have consumed the previous voice's buffered audio.
                            val policy = splitter
                            val bufferedMs = if (policy == null) 0L else remainingMs()
                            if (policy != null) {
                                val end = policy.nextEnd(segment.text, offset, bufferedMs)
                                if (end < clip.textEnd) clip = clip.copy(segment = plan.slice(index, offset, end))
                                pending = clip
                            }
                            stage = SpeechLogStage.Request
                            if (detailedLogging()) diagnostics(SpeechLogRecord(stage, SpeechLogEvent.Started,
                                voice = selected?.reference?.copy(label = voiceLabel),
                                processId = plan.logId, segment = index + 1,
                                details = speechParameterDetails(parameters, selected?.voiceParameters, provider.audioParameters * segment.audioParameters) +
                                    (R.string.tts_log_final_text to clip.segment.text)))
                            val started = nowMs()
                            val timings = provider.synthesize(clip.segment, file) { audio ->
                                withContext(sessionContext) {
                                    send(Step.Ready(clip.copy(voiceLabel = voiceLabel, stream = audio)))
                                    delivered = true
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            if (!delivered) {
                                val duration = provider.lastFileDurationMs
                                if (policy != null) {
                                    val elapsedMs = nowMs() - started
                                    val units = clip.segment.text.length
                                    val warmRtf = policy.record(units, elapsedMs, duration)
                                    if (detailedLogging()) android.util.Log.d("NextVolSoVITS",
                                        "segment units=$units bufferMs=$bufferedMs generationMs=$elapsedMs audioMs=$duration warmRtf=${warmRtf ?: "n/a"}")
                                }
                                send(Step.Ready(clip.copy(timings = timings, voiceLabel = voiceLabel, durationMs = duration),
                                    prefetchShortClips = policy != null))
                            }
                        }
                        delivered = true
                    } finally { if (!delivered) file.delete() }
                    if (clip.finishesSegment) {
                        index++
                        offset = 0
                    } else offset = clip.textEnd
                }
                val next = chapter.nextId ?: break
                if (next == chapter.id) throw SpeechException(SpeechError.SourceUnavailable)
                target = target.copy(chapterId = next)
                context = plan.nextContext
                first = false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val error = failure.speechError(SpeechError.SynthesisFailed)
            val failed = pending?.segment
            diagnostics(SpeechLogRecord(if (error == SpeechError.AudioProcessing || error == SpeechError.InvalidAudio)
                SpeechLogStage.Audio else stage, SpeechLogEvent.Failed, error, failed?.selectedVoice?.reference,
                subject = (failure as? SpeechReplacementException)?.failure?.ruleName
                    ?: (failure as? SpeechRoleException)?.failure?.ruleName ?: failed?.roleMatch?.ruleName,
                processId = plan?.logId, segment = pending?.index?.plus(1)))
            send(Step.Failed(error, target, chapter, plan, pending, context,
                (failure as? SpeechReplacementException)?.failure, (failure as? SpeechRoleException)?.failure))
        }
    }

    private suspend fun loadChapter(request: SpeechRequest): SpeechChapter = try {
        withTimeout(30_000) { chapters.load(request.bookId, request.chapterId) }
    } catch (_: TimeoutCancellationException) { throw SpeechException(SpeechError.SourceTimeout) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { throw SpeechException(failure.speechError(SpeechError.SourceUnavailable)) }
}
