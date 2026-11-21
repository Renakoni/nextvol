package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.data.logging.SpeechLogEvent
import indi.renakoni.nextvol.data.logging.SpeechLogRecord
import indi.renakoni.nextvol.data.logging.SpeechLogStage
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.SpeechClipProducer.Step
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class SpeechClip(val plan: SpeechPlan, val index: Int, val file: File, val timings: List<SpeechTiming> = emptyList(),
    val voiceLabel: String = "", val stream: SpeechStream? = null,
    val textStart: Int = 0, val segment: SpeechSegment = plan.slice(index, textStart, plan.segments[index].text.length),
    val durationMs: Long = 0) {
    val chapter get() = plan.chapter
    val textEnd get() = textStart + segment.text.length
    val count get() = plan.segments.size
    internal val finishesSegment get() = textEnd == plan.segments[index].text.length
    internal fun position(start: Int, end: Int, anchor: Int) =
        plan.position(index, textStart + start, textStart + end, textStart + anchor)
    internal fun startBookmark() = plan.bookmark(segment.start, index, textStart.takeIf { it > 0 })
    internal fun endBookmark() = if (finishesSegment) plan.bookmark(segment.end, index + 1)
        else plan.bookmark(segment.end, index, textEnd)
    internal fun dispose() { stream?.cancel(); file.delete() }
}

interface SpeechPlayback {
    /** Completes when this audio has actually ended, never when synthesis or buffering finishes. */
    suspend fun play(clip: SpeechClip, playWhenReady: Boolean, onRange: (SpeechTiming) -> Unit,
        onEstimatedAnchor: (Int) -> Unit = {},
        nextClip: Flow<SpeechClip?> = emptyFlow(), // The immediate successor, or null to withdraw it.
        onState: (SpeechPhase, Boolean) -> Unit)
    fun positionMs(clip: SpeechClip): Long = 0
    fun pause()
    fun resume()
    fun stop()
}

/** Main-thread session owner. Source, synthesis and actual playback have separate lifetimes. */
class ReadAloudSession(
    private val scope: CoroutineScope,
    private val chapters: SpeechChapterSource,
    private val settings: suspend (SpeechRequest) -> SpeechSettings,
    private val progress: SpeechProgressStore,
    private val synthesizer: () -> SpeechSynthesizer,
    private val playback: SpeechPlayback,
    private val cacheRoot: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val preparePlan: suspend (SpeechChapter, SpeechSettings, SpeechContext, Int) -> SpeechPlan = { chapter, synthesis, context, limit ->
        val task = currentCoroutineContext()
        prepareSpeechPlan(chapter, synthesis, context = context, maxInputLength = limit) { task.ensureActive() }
    },
    private val diagnostics: (SpeechLogRecord) -> Unit = {},
    private val detailedLogging: () -> Boolean = { false },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutableState = MutableStateFlow(ReadAloudState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var work: Job? = null
    private var engine: SpeechSynthesizer? = null
    private var currentClip: SpeechClip? = null
    private var audibleTextAnchor = 0
    private var currentPlan: SpeechPlan? = null
    private var wantsPlay = true
    private var currentSettings: SpeechSettings? = null
    private var currentBoundary: SpeechBookmark? = null
    private var resumeSavedProgress = true
    private data class SuspendedBook(val request: SpeechRequest, val boundary: SpeechBookmark?,
        val resumeSaved: Boolean, val playing: Boolean, val sleepTimerChapters: Int?, val plan: SpeechPlan?)
    private var suspendedBook: SuspendedBook? = null

    fun start(request: SpeechRequest, resumeSaved: Boolean = true, boundary: SpeechBookmark? = null, playing: Boolean = true,
        sleepTimerChapters: Int? = null, initialContext: SpeechContext = SpeechContext()) {
        val current = state.value
        if (!request.isPreview) suspendedBook = null
        else if (current.request?.isPreview == false && (current.isActive || current.phase == SpeechPhase.Paused)) {
            suspendedBook = SuspendedBook(current.request, audibleBoundary(), resumeSavedProgress, wantsPlay, current.sleepTimerChapters, currentPlan)
        }
        if (current.request?.bookId != request.bookId || current.request.isPreview != request.isPreview) currentSettings = null
        val previous = work
        val reusablePlan = currentPlan
        val token = ++generation
        cancelEngine()
        previous?.cancel()
        playback.stop()
        currentClip = null
        currentPlan = null
        currentBoundary = boundary
        resumeSavedProgress = resumeSaved
        wantsPlay = playing
        mutableState.value = ReadAloudState(request, if (playing) SpeechPhase.Preparing else SpeechPhase.Paused,
            sleepTimerChapters = sleepTimerChapters)
        work = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Preserve the cleanup chain even if another start cancels this waiting replacement.
            withContext(NonCancellable) { previous?.join() }
            currentCoroutineContext().ensureActive()
            val directory = File(cacheRoot, UUID.randomUUID().toString())
            val provider = synthesizer()
            engine = provider
            var openingVoice: SpeechVoiceRef? = null
            try {
                withContext(ioDispatcher) {
                    // The previous session has finished; UUID directories now belong to dead sessions.
                    cacheRoot.listFiles()?.filter { child ->
                        runCatching { UUID.fromString(child.name).toString() == child.name }.getOrDefault(false)
                    }?.forEach { if (!it.deleteRecursively()) throw SpeechException(SpeechError.Storage) }
                    if (!directory.mkdirs()) throw SpeechException(SpeechError.Storage)
                }
                // Candidate parameters belong only to this sample, never to the selected settings.
                val selected = request.previewSettings?.takeIf { request.isPreview }?.validated() ?: run {
                    val beforeRead = currentSettings
                    val stored = settings(request).validated()
                    // A settings emission received during the read takes precedence over its snapshot.
                    (currentSettings?.takeIf { it !== beforeRead } ?: stored).also { currentSettings = it }
                }
                diagnostics(SpeechLogRecord(SpeechLogStage.Configuration, SpeechLogEvent.Completed,
                    details = listOf(R.string.tts_diagnostic_scheme to
                        "${request.bookId} / ${selected.schemeName.ifBlank { selected.schemeId }} (${selected.schemeId})")))
                val synthesis = if (request.isPreview) selected.forVoice(selected.voiceReference()) else selected.synthesisSettings()
                val opened = synthesis.takeUnless { it.hasVoiceRouting }
                openingVoice = opened?.voiceReference()
                val voice = opened?.let { provider.open(it) }.orEmpty()
                val limit = if (opened == null) Int.MAX_VALUE else provider.maxInputLength
                currentCoroutineContext().ensureActive()
                val saved = if (resumeSaved && !request.isPreview) progress.load(request.bookId)
                    ?.takeIf { it.bookId == request.bookId && !it.completed } else null
                // The book entry resumes listening independently of the chapter open in the reader.
                val target = if (boundary == null && saved != null) request.copy(chapterId = saved.chapterId) else request
                currentCoroutineContext().ensureActive()
                currentBoundary = boundary ?: saved
                publish(token) { it.copy(request = target) }
                stream(token, target, provider, directory, boundary ?: saved, synthesis, reusablePlan,
                    if (request.isPreview) SpeechContext() else initialContext, opened, voice, limit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (token == generation) playback.stop()
                val error = failure.speechError(SpeechError.SynthesisFailed)
                val failedVoice = currentClip?.segment?.selectedVoice?.reference ?: state.value.failedVoice ?: openingVoice
                diagnostics(SpeechLogRecord(if (error == SpeechError.Playback) SpeechLogStage.Playback else SpeechLogStage.Initialization,
                    SpeechLogEvent.Failed, error, failedVoice, subject = currentClip?.segment?.roleMatch?.ruleName,
                    processId = currentPlan?.logId, segment = currentClip?.index?.plus(1)))
                publish(token) { it.copy(phase = SpeechPhase.Failed, error = error, failedVoice = failedVoice) }
            } finally {
                if (engine === provider) engine = null
                val reportFailure = currentCoroutineContext().isActive
                withContext(NonCancellable) {
                    try { withContext(ioDispatcher) { provider.close() } }
                    catch (failure: Exception) { cleanupFailed(token, SpeechLogStage.Initialization, failure, reportFailure) }
                    finally {
                        try {
                            withContext(ioDispatcher) {
                                if (!directory.deleteRecursively()) throw SpeechException(SpeechError.Storage)
                            }
                        } catch (failure: Exception) { cleanupFailed(token, SpeechLogStage.Configuration, failure, reportFailure) }
                    }
                }
            }
        }
    }

    fun pause() {
        wantsPlay = false
        playback.pause()
        if (state.value.isActive) mutableState.value = state.value.copy(phase = SpeechPhase.Paused)
    }

    fun resume() {
        val current = state.value
        if (current.request == null) return
        wantsPlay = true
        if (work?.isActive == true && current.phase == SpeechPhase.Paused) {
            playback.resume()
            if (currentClip == null) mutableState.value = current.copy(phase = SpeechPhase.Buffering)
        } else if (current.phase in setOf(SpeechPhase.Failed, SpeechPhase.Stopped, SpeechPhase.Completed)) {
            start(current.request, sleepTimerChapters = current.sleepTimerChapters)
        }
    }

    fun stop() {
        suspendedBook = null
        generation++
        cancelEngine()
        work?.cancel()
        playback.stop()
        currentClip = null
        currentPlan = null
        wantsPlay = false
        mutableState.value = state.value.copy(phase = SpeechPhase.Stopped, error = null, position = null, sleepTimerChapters = null,
            replacementFailure = null, roleFailure = null, failedRole = null, failedVoice = null)
    }

    fun setChapterSleepTimer(chapters: Int?) {
        val current = state.value
        mutableState.value = current.copy(sleepTimerChapters = chapters)
        // Count the current chapter even if its audio has ended while the next one is loading.
        if (currentClip == null && current.segmentCount > 0 && currentBoundary?.segmentIndex == current.segmentCount) {
            finishTimerChapter()
        }
    }

    private fun finishTimerChapter(): Boolean {
        val remaining = state.value.sleepTimerChapters ?: return false
        if (remaining == 1) {
            stop()
            return true
        }
        mutableState.value = state.value.copy(sleepTimerChapters = remaining - 1)
        return false
    }

    /** A noisy route or permanent focus loss must not resurrect the book behind a sample. */
    fun interrupt() {
        suspendedBook = null
        pause()
    }

    /** Restores the book after its playback timer was frozen for the sample. */
    fun restoreAfterPreview(): Boolean {
        val current = state.value
        if (current.request?.isPreview != true || current.phase !in setOf(SpeechPhase.Completed, SpeechPhase.Failed)) return false
        val book = suspendedBook ?: return false
        currentPlan = book.plan
        start(book.request, book.resumeSaved, book.boundary, book.playing, book.sleepTimerChapters)
        return true
    }

    private fun cancelEngine() {
        try { engine?.cancel() }
        catch (failure: Exception) { diagnostics(SpeechLogRecord(SpeechLogStage.Request, SpeechLogEvent.Failed,
            failure.speechError(SpeechError.SynthesisFailed))) }
    }

    private fun cleanupFailed(token: Long, stage: SpeechLogStage, failure: Exception, reportFailure: Boolean) {
        diagnostics(SpeechLogRecord(stage, SpeechLogEvent.Failed,
            failure.speechError(if (stage == SpeechLogStage.Configuration) SpeechError.Storage else SpeechError.SynthesisFailed)))
        if (reportFailure) publish(token) {
            if (it.error == null) it.copy(phase = SpeechPhase.Failed, error = failure.speechError(SpeechError.SynthesisFailed)) else it
        }
    }

    fun changeChapter(next: Boolean) {
        val current = state.value
        val id = (if (next) current.nextChapterId else current.previousChapterId) ?: return
        val request = current.request ?: return
        val context = currentPlan?.takeIf { next && it.chapter.bookId == request.bookId &&
            it.chapter.id == request.chapterId }?.nextContext ?: SpeechContext()
        start(request.copy(chapterId = id), resumeSaved = false, playing = wantsPlay, initialContext = context)
    }

    fun skipSegment(delta: Int) {
        val clip = currentClip ?: return
        val request = state.value.request ?: return
        val target = clip.plan.segments.getOrNull(clip.index + delta)
        if (target == null) {
            if (delta > 0 || clip.chapter.previousId != null) changeChapter(delta > 0)
            else start(request, resumeSaved = false, boundary = clip.plan.bookmark(clip.plan.segments.first().start, 0), playing = wantsPlay,
                sleepTimerChapters = state.value.sleepTimerChapters)
        } else start(request, resumeSaved = false, boundary = clip.plan.bookmark(target.start, clip.index + delta), playing = wantsPlay,
            sleepTimerChapters = state.value.sleepTimerChapters)
    }

    fun updateSettings(settings: SpeechSettings) {
        val value = settings.validated()
        val previous = currentSettings
        currentSettings = value
        // A deliberate selection still persists during preview, and takes effect on return.
        if (state.value.request?.isPreview == true) return
        if (previous != null && previous.synthesisSettings() != value.synthesisSettings())
            reloadSettings(resetContext = previous.textProcessingKey(Int.MAX_VALUE) != value.textProcessingKey(Int.MAX_VALUE))
    }

    /** Voice changes resynthesize from the current boundary, preserving a deliberate pause. */
    fun reloadSettings(resetContext: Boolean = false) {
        val current = state.value
        if (current.request == null || current.request.isPreview || current.phase in setOf(SpeechPhase.Stopped, SpeechPhase.Completed)) return
        val context = if (resetContext) SpeechContext() else currentPlan?.context ?: currentBoundary?.context ?: SpeechContext()
        val boundary = audibleBoundary()
        currentPlan = null
        start(current.request, boundary = if (resetContext) boundary?.copy(context = SpeechContext(), replacementKey = "") else boundary, playing = wantsPlay,
            sleepTimerChapters = current.sleepTimerChapters, initialContext = context)
    }

    private fun audibleBoundary(): SpeechBookmark? = currentClip?.let { clip ->
        val position = state.value.position?.takeIf { it.bookId == clip.chapter.bookId && it.chapterId == clip.chapter.id &&
            it.anchor in clip.segment.start until clip.segment.end }
        if (position == null) clip.startBookmark() else clip.plan.bookmark(position.anchor, clip.index,
            clip.textStart + audibleTextAnchor)
    } ?: currentBoundary

    private suspend fun stream(token: Long, request: SpeechRequest, provider: SpeechSynthesizer,
        directory: File, saved: SpeechBookmark?, synthesis: SpeechSettings, reusablePlan: SpeechPlan?,
        initialContext: SpeechContext, opened: SpeechSettings?, initialLabel: String, maxInputLength: Int) = coroutineScope {
        val processingKey = synthesis.textProcessingKey(maxInputLength)
        // Ordinarily two queued files suffice. Short SoVITS dialogue may need several to cover six seconds.
        val queue = Channel<Step>(capacity = 6, onUndeliveredElement = { (it as? Step.Ready)?.clip?.dispose() })
        // Producer and consumer share the session dispatcher. Only finished audio earns buffer credit.
        var readyMs = 0L
        val queued = MutableStateFlow(0)
        fun remainingMs(): Long {
            val clip = currentClip ?: return readyMs
            return readyMs - playback.positionMs(clip).coerceIn(0, clip.durationMs)
        }
        launch {
            var previous: Step.Ready? = null
            try {
                SpeechClipProducer(chapters, provider, ioDispatcher, preparePlan, diagnostics, detailedLogging, nowMs)
                    .produce(request, directory, saved, synthesis, reusablePlan, initialContext, opened, initialLabel,
                        maxInputLength, processingKey, ::remainingMs) { step ->
                        queued.first { count -> count < 2 ||
                            step is Step.Ready && step.prefetchShortClips && count < 6 && remainingMs() < 6000 }
                        queued.value++
                        val ready = step as? Step.Ready
                        if (ready != null) readyMs += ready.clip.durationMs
                        queue.send(step)
                        previous?.successor?.complete(ready?.clip)
                        previous = ready
                    }
            } finally {
                previous?.successor?.complete(null)
                queue.close()
            }
        }
        try {
            for (step in queue) {
                queued.value--
                currentCoroutineContext().ensureActive()
                when (step) {
                    is Step.Skipped -> {
                        val skipped = step.plan
                        currentPlan = skipped
                        currentClip = null
                        currentBoundary = skipped.bookmark(skipped.chapter.text.length, 0)
                        if (!request.isPreview) save(currentBoundary!!)
                        publish(token) { it.copy(request = request.copy(chapterId = skipped.chapter.id),
                            bookTitle = skipped.chapter.bookTitle, chapterTitle = skipped.chapter.title,
                            segmentIndex = 0, segmentCount = 0, currentText = "", position = null,
                            planContext = skipped.context,
                            previousChapterId = skipped.chapter.previousId, nextChapterId = skipped.chapter.nextId) }
                        if (!request.isPreview && finishTimerChapter()) return@coroutineScope
                    }
                    is Step.Failed -> {
                        currentClip = null
                        currentPlan = step.plan
                        val failed = step.chapter
                        val segment = step.clip?.segment
                        val bookTitle = failed?.bookTitle ?: state.value.bookTitle
                        currentBoundary = step.clip?.startBookmark()
                            ?: currentBoundary?.takeIf { it.chapterId == failed?.id && it.fingerprint == failed.fingerprint }
                            ?: SpeechBookmark(request.bookId,
                            step.request.chapterId, failed?.fingerprint.orEmpty(), 0, bookTitle, failed?.title.orEmpty(),
                            replacementKey = processingKey, context = step.context)
                        if (!request.isPreview) save(currentBoundary!!)
                        publish(token) { it.copy(request = step.request, bookTitle = bookTitle, chapterTitle = failed?.title.orEmpty(),
                            segmentIndex = step.clip?.index ?: 0, segmentCount = step.plan?.segments?.size ?: 0, currentText = segment?.text.orEmpty(),
                            position = if (request.isPreview || failed == null || segment == null) null else SpeechPosition(
                                failed.bookId, failed.id, failed.fingerprint, segment.start, segment.end),
                            previousChapterId = failed?.previousId, nextChapterId = failed?.nextId,
                            phase = SpeechPhase.Failed, error = step.error, replacementFailure = step.replacementFailure,
                            roleFailure = step.roleFailure, failedRole = segment?.roleMatch,
                            planContext = step.plan?.context ?: step.context, failedVoice = segment?.selectedVoice?.reference,
                            voiceLabel = segment?.selectedVoice?.label.orEmpty()) }
                        return@coroutineScope
                    }
                    is Step.Ready -> {
                        val clip = step.clip
                        currentClip = clip
                        audibleTextAnchor = 0
                        currentPlan = clip.plan
                        val silent = clip.segment.pauseMs != null
                        currentBoundary = clip.startBookmark()
                        publish(token) { it.copy(
                            request = request.copy(chapterId = clip.chapter.id), bookTitle = clip.chapter.bookTitle,
                            chapterTitle = clip.chapter.title, segmentIndex = clip.index, segmentCount = clip.count,
                            currentText = if (silent) it.currentText else clip.segment.text, previousChapterId = clip.chapter.previousId,
                            voiceLabel = if (silent) it.voiceLabel else clip.voiceLabel, planContext = clip.plan.context, failedVoice = null, failedRole = null,
                            nextChapterId = clip.chapter.nextId,
                            phase = if (wantsPlay) SpeechPhase.Buffering else SpeechPhase.Paused, error = null, replacementFailure = null, roleFailure = null,
                        ) }
                        if (!request.isPreview) save(currentBoundary!!)
                        val sentences = if (clip.timings.isEmpty()) emptyList() else speechSentences(clip.segment.text)
                        var audibleStart = sentences.firstOrNull()?.start ?: 0
                        var audibleEnd = sentences.firstOrNull()?.end ?: clip.segment.text.length
                        audibleTextAnchor = audibleStart
                        try {
                            if (detailedLogging()) diagnostics(SpeechLogRecord(SpeechLogStage.Playback, SpeechLogEvent.Started,
                                voice = clip.segment.selectedVoice?.reference, processId = clip.plan.logId, segment = clip.index + 1))
                            playback.play(clip, wantsPlay, nextClip = nextClip(step), onRange = { range ->
                                if (token == generation && currentClip === clip && !request.isPreview && !silent) {
                                    val sentence = sentences.firstOrNull { range.start in it.start until it.end }
                                    audibleStart = sentence?.start ?: range.start
                                    audibleEnd = maxOf(sentence?.end ?: range.end, range.end)
                                    audibleTextAnchor = range.start
                                    publish(token) { it.copy(position = clip.position(audibleStart, audibleEnd, audibleTextAnchor)) }
                                }
                            }, onEstimatedAnchor = { anchor ->
                                if (token == generation && currentClip === clip && !request.isPreview && !silent && clip.timings.isEmpty()) {
                                    // Keep the reliably audible clip highlighted; only viewport following is estimated.
                                    audibleTextAnchor = anchor
                                    publish(token) { it.copy(position = clip.position(audibleStart, audibleEnd, audibleTextAnchor)) }
                                }
                            }) { phase, playing ->
                                if (token == generation && currentClip === clip) {
                                    wantsPlay = playing
                                    publish(token) { it.copy(phase = phase,
                                        position = if (!request.isPreview && !silent && phase in setOf(SpeechPhase.Playing, SpeechPhase.Paused))
                                            clip.position(audibleStart, audibleEnd, audibleTextAnchor)
                                        else it.position,
                                    ) }
                                }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { throw SpeechException(failure.speechError(SpeechError.Playback)) }
                        finally { clip.dispose() }
                        currentCoroutineContext().ensureActive()
                        // Only actual playback completion advances the durable offset.
                        currentBoundary = clip.endBookmark()
                        if (!request.isPreview) save(currentBoundary!!)
                        readyMs -= clip.durationMs
                        currentClip = null
                        if (!request.isPreview && clip.finishesSegment && clip.index == clip.count - 1 && finishTimerChapter()) {
                            return@coroutineScope
                        }
                        publish(token) { it.copy(phase = if (wantsPlay) SpeechPhase.Buffering else SpeechPhase.Paused) }
                    }
                }
            }
            currentClip = null
            currentPlan = null
            if (!request.isPreview) {
                progress.load(request.bookId)?.let { save(it.copy(completed = true)) }
            }
            publish(token) { it.copy(phase = SpeechPhase.Completed, position = null, sleepTimerChapters = null) }
        } finally { queue.cancel() }
    }

    private fun nextClip(step: Step.Ready): Flow<SpeechClip?> = flow {
        val next = step.successor.await() ?: return@flow
        if (next.plan === step.clip.plan) emit(next)
        // A chapter timer can be set or cleared while the final clip is playing.
        // Only a replayable source can be withdrawn from the playlist and added again.
        else if (next.stream == null) emitAll(state.map { if (it.sleepTimerChapters == 1) null else next }.distinctUntilChanged())
    }

    private suspend fun save(bookmark: SpeechBookmark) {
        try { withContext(NonCancellable) { progress.save(bookmark) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw SpeechException(failure.speechError(SpeechError.Storage)) }
        // Finishing a durable write must not resume playback after Stop or a replacement request.
        currentCoroutineContext().ensureActive()
    }

    private inline fun publish(token: Long, update: (ReadAloudState) -> ReadAloudState) {
        if (token == generation) mutableState.value = update(state.value)
    }
}

internal fun Exception.speechError(fallback: SpeechError) = when (this) {
    is SpeechException -> error
    is SpeechReplacementException -> SpeechError.ReplacementRule
    is SpeechRoleException -> SpeechError.RoleRule
    else -> fallback
}
