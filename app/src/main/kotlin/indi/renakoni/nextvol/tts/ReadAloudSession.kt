package indi.renakoni.nextvol.tts

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

data class SpeechClip(val chapter: SpeechChapter, val segment: SpeechSegment, val index: Int, val count: Int, val file: File,
    val timings: List<SpeechTiming> = emptyList())

interface SpeechPlayback {
    /** Completes when this audio has actually ended, never when synthesis or buffering finishes. */
    suspend fun play(clip: SpeechClip, playWhenReady: Boolean, onRange: (SpeechTiming) -> Unit,
        onEstimatedAnchor: (Int) -> Unit = {},
        onState: (SpeechPhase, Boolean) -> Unit)
    fun pause()
    fun resume()
    fun stop()
    fun setSpeed(speed: Float)
}

/** Main-thread session owner. Source, synthesis and actual playback have separate lifetimes. */
class ReadAloudSession(
    private val scope: CoroutineScope,
    private val chapters: SpeechChapterSource,
    private val settings: suspend () -> SpeechSettings,
    private val progress: SpeechProgressStore,
    private val synthesizer: () -> SpeechSynthesizer,
    private val playback: SpeechPlayback,
    private val cacheRoot: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow(ReadAloudState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var work: Job? = null
    private var engine: SpeechSynthesizer? = null
    private var currentClip: SpeechClip? = null
    private var wantsPlay = true
    private var currentSettings: SpeechSettings? = null
    private var currentBoundary: SpeechBookmark? = null
    private var resumeSavedProgress = true
    private data class SuspendedBook(val request: SpeechRequest, val boundary: SpeechBookmark?,
        val resumeSaved: Boolean, val playing: Boolean, val sleepTimerChapters: Int?)
    private var suspendedBook: SuspendedBook? = null

    fun start(request: SpeechRequest, resumeSaved: Boolean = true, boundary: SpeechBookmark? = null, playing: Boolean = true,
        sleepTimerChapters: Int? = null) {
        val current = state.value
        if (!request.isPreview) suspendedBook = null
        else if (current.request?.isPreview == false && (current.isActive || current.phase == SpeechPhase.Paused)) {
            suspendedBook = SuspendedBook(current.request, currentBoundary, resumeSavedProgress, wantsPlay, current.sleepTimerChapters)
        }
        val previous = work
        val token = ++generation
        cancelEngine()
        previous?.cancel()
        playback.stop()
        currentClip = null
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
                    val stored = settings().validated()
                    // A settings emission received during the read takes precedence over its snapshot.
                    (currentSettings?.takeIf { it !== beforeRead } ?: stored).also { currentSettings = it }
                }
                playback.setSpeed(selected.playbackSpeed)
                val voice = provider.open(selected)
                currentCoroutineContext().ensureActive()
                publish(token) { it.copy(voiceLabel = voice) }
                val saved = if (resumeSaved && !request.isPreview) progress.load(request.bookId)
                    ?.takeIf { it.bookId == request.bookId && !it.completed } else null
                // The book entry resumes listening independently of the chapter open in the reader.
                val target = if (boundary == null && saved != null) request.copy(chapterId = saved.chapterId) else request
                currentCoroutineContext().ensureActive()
                currentBoundary = boundary ?: saved
                publish(token) { it.copy(request = target) }
                stream(token, target, provider, directory, boundary ?: saved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                publish(token) { it.copy(phase = SpeechPhase.Failed, error = failure.speechError(SpeechError.SynthesisFailed)) }
            } finally {
                if (engine === provider) engine = null
                val reportFailure = currentCoroutineContext().isActive
                withContext(NonCancellable) {
                    try { withContext(ioDispatcher) { provider.close() } }
                    catch (failure: Exception) { cleanupFailed(token, "Synthesizer close", failure, reportFailure) }
                    finally {
                        try {
                            withContext(ioDispatcher) {
                                if (!directory.deleteRecursively()) throw SpeechException(SpeechError.Storage)
                            }
                        } catch (failure: Exception) { cleanupFailed(token, "Audio cache cleanup", failure, reportFailure) }
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
        wantsPlay = false
        mutableState.value = state.value.copy(phase = SpeechPhase.Stopped, error = null, position = null, sleepTimerChapters = null)
    }

    fun setChapterSleepTimer(chapters: Int?) {
        val current = state.value
        mutableState.value = current.copy(sleepTimerChapters = chapters)
        // Count the current chapter even if its audio has ended while the next one is loading.
        if (currentClip == null && current.segmentCount > 0 && current.segmentIndex == current.segmentCount - 1) {
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

    /** Called by the service after checking its unchanged sleep deadline. */
    fun restoreAfterPreview(): Boolean {
        val current = state.value
        if (current.request?.isPreview != true || current.phase !in setOf(SpeechPhase.Completed, SpeechPhase.Failed)) return false
        val book = suspendedBook ?: return false
        start(book.request, book.resumeSaved, book.boundary, book.playing, book.sleepTimerChapters)
        return true
    }

    private fun cancelEngine() {
        try { engine?.cancel() }
        catch (failure: Exception) { Log.w("ReadAloud", "Synthesizer cancel failed: ${failure.javaClass.simpleName}") }
    }

    private fun cleanupFailed(token: Long, operation: String, failure: Exception, reportFailure: Boolean) {
        Log.w("ReadAloud", "$operation failed: ${failure.javaClass.simpleName}")
        if (reportFailure) publish(token) {
            if (it.error == null) it.copy(phase = SpeechPhase.Failed, error = failure.speechError(SpeechError.SynthesisFailed)) else it
        }
    }

    fun changeChapter(next: Boolean) {
        val current = state.value
        val id = (if (next) current.nextChapterId else current.previousChapterId) ?: return
        val request = current.request ?: return
        start(request.copy(chapterId = id), resumeSaved = false, playing = wantsPlay)
    }

    fun skipSegment(delta: Int) {
        val clip = currentClip ?: return
        val request = state.value.request ?: return
        val segments = speechSegments(clip.chapter.text)
        val target = segments.getOrNull(clip.index + delta)
        if (target == null) {
            if (delta > 0 || clip.chapter.previousId != null) changeChapter(delta > 0)
            else start(request, resumeSaved = false, boundary = clip.bookmark(clip.segment.start), playing = wantsPlay,
                sleepTimerChapters = state.value.sleepTimerChapters)
        } else start(request, resumeSaved = false, boundary = clip.bookmark(target.start), playing = wantsPlay,
            sleepTimerChapters = state.value.sleepTimerChapters)
    }

    fun updateSettings(settings: SpeechSettings) {
        val value = settings.validated()
        val previous = currentSettings
        currentSettings = value
        // A deliberate selection still persists during preview, and takes effect on return.
        if (state.value.request?.isPreview == true) return
        playback.setSpeed(value.playbackSpeed)
        if (previous != null && previous.copy(playbackSpeed = value.playbackSpeed) != value) reloadSettings()
    }

    /** Voice changes resynthesize from the current boundary, preserving a deliberate pause. */
    fun reloadSettings() {
        val current = state.value
        if (current.request == null || current.request.isPreview || current.phase in setOf(SpeechPhase.Stopped, SpeechPhase.Completed)) return
        start(current.request, boundary = currentClip?.let { it.bookmark(it.segment.start) }, playing = wantsPlay,
            sleepTimerChapters = current.sleepTimerChapters)
    }

    private sealed interface Step {
        data class Ready(val clip: SpeechClip) : Step
        data class Failed(val error: SpeechError, val request: SpeechRequest, val chapter: SpeechChapter?, val offset: Int) : Step
    }

    private suspend fun stream(token: Long, request: SpeechRequest, provider: SpeechSynthesizer,
        directory: File, saved: SpeechBookmark?) = coroutineScope {
        // At most one playing file, two buffered files and one synthesis in flight.
        val queue = Channel<Step>(capacity = 2, onUndeliveredElement = { (it as? Step.Ready)?.clip?.file?.delete() })
        launch {
            var target = request
            var chapter: SpeechChapter? = null
            var position = 0
            try {
                var first = true
                while (true) {
                    chapter = null
                    position = 0
                    chapter = if (target.isPreview) SpeechChapter("", "", "", "", target.previewText.orEmpty())
                        else loadChapter(target)
                    val segments = speechSegments(chapter.text)
                    if (segments.isEmpty()) throw SpeechException(SpeechError.EmptyText)
                    val restored = saved?.takeIf { first && it.bookId == chapter.bookId && it.chapterId == chapter.id &&
                        it.fingerprint == chapter.fingerprint && !it.completed && it.offset <= chapter.text.length }?.offset ?: 0
                    // A completed chapter can be durable while its successor is still loading.
                    val startIndex = if (restored >= segments.last().end && chapter.nextId != null) segments.size
                        else segments.indexOfLast { it.start <= restored }.coerceAtLeast(0)
                    for (index in startIndex..segments.lastIndex) {
                        val segment = segments[index]
                        position = segment.start
                        val file = File(directory, UUID.randomUUID().toString() + ".wav")
                        var delivered = false
                        try {
                            val timings = provider.synthesize(segment.text, file)
                            currentCoroutineContext().ensureActive()
                            queue.send(Step.Ready(SpeechClip(chapter, segment, index, segments.size, file, timings)))
                            delivered = true
                        } finally { if (!delivered) file.delete() }
                    }
                    val next = chapter.nextId ?: break
                    if (next == chapter.id) throw SpeechException(SpeechError.SourceUnavailable)
                    target = target.copy(chapterId = next)
                    first = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                queue.send(Step.Failed(failure.speechError(SpeechError.SynthesisFailed), target, chapter, position))
            } finally { queue.close() }
        }
        try {
            for (step in queue) {
                currentCoroutineContext().ensureActive()
                when (step) {
                    is Step.Failed -> {
                        currentClip = null
                        val failed = step.chapter
                        val segments = failed?.let { speechSegments(it.text) }.orEmpty()
                        val index = segments.indexOfLast { it.start <= step.offset }.coerceAtLeast(0)
                        val bookTitle = failed?.bookTitle ?: state.value.bookTitle
                        if (!request.isPreview) save(SpeechBookmark(request.bookId, step.request.chapterId,
                            failed?.fingerprint.orEmpty(), step.offset, bookTitle, failed?.title.orEmpty()))
                        publish(token) { it.copy(request = step.request, bookTitle = bookTitle, chapterTitle = failed?.title.orEmpty(),
                            segmentIndex = index, segmentCount = segments.size, currentText = segments.getOrNull(index)?.text.orEmpty(),
                            previousChapterId = failed?.previousId, nextChapterId = failed?.nextId,
                            phase = SpeechPhase.Failed, error = step.error) }
                        return@coroutineScope
                    }
                    is Step.Ready -> {
                        val clip = step.clip
                        currentClip = clip
                        currentBoundary = clip.bookmark(clip.segment.start)
                        publish(token) { it.copy(
                            request = request.copy(chapterId = clip.chapter.id), bookTitle = clip.chapter.bookTitle,
                            chapterTitle = clip.chapter.title, segmentIndex = clip.index, segmentCount = clip.count,
                            currentText = clip.segment.text, previousChapterId = clip.chapter.previousId,
                            nextChapterId = clip.chapter.nextId,
                            phase = if (wantsPlay) SpeechPhase.Buffering else SpeechPhase.Paused, error = null,
                        ) }
                        if (!request.isPreview) save(clip.bookmark(clip.segment.start))
                        val sentences = if (clip.timings.isEmpty()) emptyList() else speechSentences(clip.segment.text)
                        var audibleStart = clip.segment.start + (sentences.firstOrNull()?.start ?: 0)
                        var audibleEnd = clip.segment.start + (sentences.firstOrNull()?.end ?: clip.segment.text.length)
                        var audibleAnchor = audibleStart
                        try {
                            playback.play(clip, wantsPlay, onRange = { range ->
                                if (token == generation && currentClip === clip && !request.isPreview) {
                                    val sentence = sentences.firstOrNull { range.start in it.start until it.end }
                                    audibleStart = clip.segment.start + (sentence?.start ?: range.start)
                                    audibleEnd = clip.segment.start + maxOf(sentence?.end ?: range.end, range.end)
                                    audibleAnchor = clip.segment.start + range.start
                                    publish(token) { it.copy(position = SpeechPosition(clip.chapter.bookId, clip.chapter.id,
                                        clip.chapter.fingerprint, audibleStart, audibleEnd, audibleAnchor)) }
                                }
                            }, onEstimatedAnchor = { anchor ->
                                if (token == generation && currentClip === clip && !request.isPreview && clip.timings.isEmpty()) {
                                    // Keep the reliably audible clip highlighted; only viewport following is estimated.
                                    audibleAnchor = clip.segment.start + anchor
                                    publish(token) { it.copy(position = SpeechPosition(clip.chapter.bookId, clip.chapter.id,
                                        clip.chapter.fingerprint, audibleStart, audibleEnd, audibleAnchor)) }
                                }
                            }) { phase, playing ->
                                if (token == generation && currentClip === clip) {
                                    wantsPlay = playing
                                    publish(token) { it.copy(phase = phase,
                                        position = if (!request.isPreview && phase in setOf(SpeechPhase.Playing, SpeechPhase.Paused))
                                            SpeechPosition(clip.chapter.bookId, clip.chapter.id, clip.chapter.fingerprint,
                                                audibleStart, audibleEnd, audibleAnchor)
                                        else it.position,
                                    ) }
                                }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { throw SpeechException(failure.speechError(SpeechError.Playback)) }
                        finally { clip.file.delete() }
                        currentCoroutineContext().ensureActive()
                        // Only actual playback completion advances the durable offset.
                        if (!request.isPreview) save(clip.bookmark(clip.segment.end))
                        currentBoundary = clip.bookmark(clip.segment.end)
                        currentClip = null
                        if (!request.isPreview && clip.index == clip.count - 1 && finishTimerChapter()) {
                            return@coroutineScope
                        }
                        publish(token) { it.copy(phase = if (wantsPlay) SpeechPhase.Buffering else SpeechPhase.Paused) }
                    }
                }
            }
            currentClip = null
            if (!request.isPreview) {
                progress.load(request.bookId)?.let { save(it.copy(completed = true)) }
            }
            publish(token) { it.copy(phase = SpeechPhase.Completed, position = null, sleepTimerChapters = null) }
        } finally { queue.cancel() }
    }

    private suspend fun loadChapter(request: SpeechRequest): SpeechChapter = try {
        withTimeout(30_000) { chapters.load(request.bookId, request.chapterId) }
    } catch (_: TimeoutCancellationException) { throw SpeechException(SpeechError.SourceTimeout) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { throw SpeechException(failure.speechError(SpeechError.SourceUnavailable)) }

    private suspend fun save(bookmark: SpeechBookmark) {
        try { withContext(NonCancellable) { progress.save(bookmark) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw SpeechException(failure.speechError(SpeechError.Storage)) }
        // Finishing a durable write must not resume playback after Stop or a replacement request.
        currentCoroutineContext().ensureActive()
    }

    private fun SpeechClip.bookmark(offset: Int) = SpeechBookmark(chapter.bookId, chapter.id,
        chapter.fingerprint, offset, chapter.bookTitle, chapter.title)

    private inline fun publish(token: Long, update: (ReadAloudState) -> ReadAloudState) {
        if (token == generation) mutableState.value = update(state.value)
    }
}

internal fun Exception.speechError(fallback: SpeechError) = (this as? SpeechException)?.error ?: fallback
