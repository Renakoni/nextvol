package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.data.logging.SpeechLogEvent
import indi.renakoni.nextvol.data.logging.SpeechLogRecord
import indi.renakoni.nextvol.data.logging.SpeechLogStage
import indi.renakoni.nextvol.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.SendChannel
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
) {
    sealed interface Step {
        data class Ready(val clip: SpeechClip) : Step
        data class Skipped(val plan: SpeechPlan) : Step
        data class Failed(val error: SpeechError, val request: SpeechRequest, val chapter: SpeechChapter?,
            val plan: SpeechPlan?, val index: Int, val context: SpeechContext,
            val replacementFailure: SpeechReplacementFailure? = null, val roleFailure: SpeechRoleFailure? = null) : Step
    }

    suspend fun produce(request: SpeechRequest, directory: File, saved: SpeechBookmark?, synthesis: SpeechSettings,
        reusablePlan: SpeechPlan?, initialContext: SpeechContext, opened: SpeechSettings?, initialLabel: String,
        maxInputLength: Int, processingKey: String, queue: SendChannel<Step>) {
        var activeSettings = opened
        var voiceLabel = initialLabel
        var target = request
        var chapter: SpeechChapter? = null
        var plan: SpeechPlan? = null
        var position = 0
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
                position = 0
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
                val (resumed, startIndex) = plan.resumeAt(saved.takeIf { first })
                plan = resumed
                val segments = plan.segments
                if (segments.isEmpty()) {
                    queue.send(Step.Skipped(plan))
                }
                for (index in startIndex..segments.lastIndex) {
                    val segment = segments[index]
                    position = index
                    val file = File(directory, UUID.randomUUID().toString() + ".wav")
                    var delivered = false
                    try {
                        if (segment.pauseMs != null) {
                            queue.send(Step.Ready(SpeechClip(plan, index, file)))
                            delivered = true
                            continue
                        }
                        stage = SpeechLogStage.Initialization
                        val selected = segment.selectedVoice
                        selected?.error?.let { throw SpeechException(it) }
                        val parameters = selected?.settings ?: synthesis
                        if (activeSettings != parameters) {
                            // The router reuses cached voices and releases conflicting backend slots before opening.
                            voiceLabel = provider.open(parameters)
                            activeSettings = parameters
                        }
                        stage = SpeechLogStage.Request
                        if (detailedLogging()) diagnostics(SpeechLogRecord(stage, SpeechLogEvent.Started,
                            voice = selected?.reference?.copy(label = voiceLabel),
                            processId = plan.logId, segment = index + 1,
                            details = speechParameterDetails(parameters, selected?.voiceParameters, provider.audioParameters * segment.audioParameters) +
                                (R.string.tts_log_final_text to segment.text)))
                        val activePlan = plan
                        val timings = provider.synthesize(segment, file) { audio ->
                            queue.send(Step.Ready(SpeechClip(activePlan, index, file, voiceLabel = voiceLabel, stream = audio)))
                            delivered = true
                        }
                        currentCoroutineContext().ensureActive()
                        if (!delivered) {
                            queue.send(Step.Ready(SpeechClip(plan, index, file, timings, voiceLabel)))
                            delivered = true
                        }
                    } finally { if (!delivered) file.delete() }
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
            val failed = plan?.segments?.getOrNull(position)
            diagnostics(SpeechLogRecord(if (error == SpeechError.AudioProcessing || error == SpeechError.InvalidAudio)
                SpeechLogStage.Audio else stage, SpeechLogEvent.Failed, error, failed?.selectedVoice?.reference,
                subject = (failure as? SpeechReplacementException)?.failure?.ruleName
                    ?: (failure as? SpeechRoleException)?.failure?.ruleName ?: failed?.roleMatch?.ruleName,
                processId = plan?.logId, segment = failed?.let { position + 1 }))
            queue.send(Step.Failed(failure.speechError(SpeechError.SynthesisFailed), target, chapter, plan, position, context,
                (failure as? SpeechReplacementException)?.failure, (failure as? SpeechRoleException)?.failure))
        } finally { queue.close() }
    }

    private suspend fun loadChapter(request: SpeechRequest): SpeechChapter = try {
        withTimeout(30_000) { chapters.load(request.bookId, request.chapterId) }
    } catch (_: TimeoutCancellationException) { throw SpeechException(SpeechError.SourceTimeout) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { throw SpeechException(failure.speechError(SpeechError.SourceUnavailable)) }
}
