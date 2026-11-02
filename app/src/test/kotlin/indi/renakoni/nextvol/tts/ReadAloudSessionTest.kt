package indi.renakoni.nextvol.tts

import android.util.Log
import indi.renakoni.nextvol.tts.matcha.OfflineVoiceRef
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReadAloudSessionTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun highlightingUsesActualPlaybackAndKeepsTheLastRangeWhileBuffering() = runTest {
        val env = Environment(this)
        env.player.startBuffered = true
        env.session.start(SpeechRequest("book", "one"))
        runCurrent()
        assertNull(env.session.state.value.position)
        assertTrue(env.synthesized.size > 1)
        env.player.resume()
        val first = env.player.started.single()
        val position = SpeechPosition("book", "one", first.chapter.fingerprint, first.segment.start, first.segment.end)
        assertEquals(position, env.session.state.value.position)
        env.session.pause()
        assertEquals(position, env.session.state.value.position)
        env.player.resume()
        env.player.finish()
        runCurrent()
        assertEquals(SpeechPhase.Buffering, env.session.state.value.phase)
        assertEquals(position, env.session.state.value.position)
        env.player.resume()
        assertEquals(env.player.started.last().segment.start, env.session.state.value.position?.start)
        env.stop()
        assertNull(env.session.state.value.position)
    }

    @Test fun prefetchIsBoundedAndCannotAdvanceTheListeningPosition() = runTest {
        val env = Environment(this)
        val loaded = mutableListOf<String>()
        env.load = { book, chapter ->
            loaded += chapter
            SpeechChapter(book, chapter, "Book", chapter, "一小段。", nextId = (chapter.toInt() + 1).toString())
        }
        runCurrent()
        assertTrue(loaded.isEmpty())
        env.session.start(SpeechRequest("book", "1"))
        runCurrent()
        assertEquals(listOf("1", "2", "3", "4"), loaded)
        assertEquals(1, env.player.started.size)
        assertEquals(4, env.synthesized.size)
        assertEquals(4, env.audioFiles())
        assertEquals(0, env.progress.saved.getValue("book").offset)
        assertEquals("1", env.progress.saved.getValue("book").chapterId)
        val first = env.player.started.single()
        env.player.finish()
        runCurrent()
        assertEquals(2, env.player.started.size)
        assertEquals("2", env.progress.saved.getValue("book").chapterId)
        assertEquals(env.player.started.last().segment.start, env.progress.saved.getValue("book").offset)
        assertFalse(first.file.exists())
        assertTrue(env.audioFiles() <= 4)
        env.stop()
        assertEquals(0, env.audioFiles())
    }

    @Test fun pauseDuringPreparationStaysPausedWhenAudioArrives() = runTest {
        val gate = CompletableDeferred<Unit>()
        val env = Environment(this)
        env.beforeSynthesis = { gate.await() }
        env.session.start(SpeechRequest("book", "one"))
        runCurrent()
        env.session.pause()
        gate.complete(Unit)
        runCurrent()
        assertEquals(SpeechPhase.Paused, env.session.state.value.phase)
        assertFalse(env.player.playing)
        assertEquals(0, env.progress.saved.getValue("book").offset)
        env.session.resume()
        assertEquals(SpeechPhase.Playing, env.session.state.value.phase)
        assertTrue(env.player.playing)
        env.stop()
    }

    @Test fun lateSynthesisAfterStopCannotPlayAndItsFilesAreRemoved() = runTest {
        val gate = CompletableDeferred<Unit>()
        val env = Environment(this)
        env.beforeSynthesis = { withContext(NonCancellable) { gate.await() } }
        env.session.start(SpeechRequest("book", "one"))
        runCurrent()
        env.session.stop()
        gate.complete(Unit)
        runCurrent()
        assertTrue(env.player.started.isEmpty())
        assertEquals(SpeechPhase.Stopped, env.session.state.value.phase)
        assertEquals(0, env.audioFiles())
    }

    @Test fun aThirdBookStillWaitsForTheOriginalSessionsCleanup() = runTest {
        val gate = CompletableDeferred<Unit>()
        val env = Environment(this)
        env.beforeSynthesis = { withContext(NonCancellable) { gate.await() } }
        try {
            env.session.start(SpeechRequest("old", "one"))
            runCurrent()
            env.session.start(SpeechRequest("middle", "one"))
            // Replace again before the waiting coroutine can be dispatched.
            env.session.start(SpeechRequest("new", "one"))
            runCurrent()
            assertEquals("The original engine still owns its audio directory", 1, env.opened.size)
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf("new"), env.player.started.map { it.chapter.bookId })
            assertEquals("new", env.session.state.value.request?.bookId)
            assertFalse(env.progress.saved.containsKey("old"))
            assertEquals(2, env.opened.size)
        } finally {
            gate.complete(Unit)
            env.stop()
        }
    }

    @Test fun cancellationAndCloseFailuresCannotEscapeReplacementOrStop() = runTest {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        val env = Environment(this)
        var closed = 0
        env.onCancel = { throw IOException("private source credentials") }
        env.onClose = { closed++; throw IOException("private source credentials") }
        try {
            env.session.start(SpeechRequest("old", "one"))
            runCurrent()
            val oldDirectory = env.player.started.single().file.parentFile!!
            env.session.start(SpeechRequest("new", "one"))
            runCurrent()
            assertEquals(1, closed)
            assertFalse(oldDirectory.exists())
            assertEquals("new", env.player.started.last().chapter.bookId)
            assertEquals(SpeechPhase.Playing, env.session.state.value.phase)
            assertNull(env.session.state.value.error)
            env.stop()
            assertEquals(2, closed)
            assertEquals(SpeechPhase.Stopped, env.session.state.value.phase)
            assertNull(env.session.state.value.error)
            assertTrue(env.directory.listFiles()!!.isEmpty())
        } finally { env.stop(); unmockkStatic(Log::class) }
    }

    @Test fun closeFailureAfterPlaybackRemovesFilesAndAllowsRetry() = runTest {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        val env = Environment(this)
        env.onClose = { throw IOException("close failed") }
        try {
            env.session.start(SpeechRequest("", "", previewText = "Preview."))
            runCurrent()
            env.player.finish()
            runCurrent()
            assertEquals(SpeechPhase.Failed, env.session.state.value.phase)
            assertEquals(SpeechError.SynthesisFailed, env.session.state.value.error)
            assertTrue(env.directory.listFiles()!!.isEmpty())
            env.onClose = {}
            env.session.resume()
            runCurrent()
            assertEquals(2, env.player.started.size)
            env.player.finish()
            runCurrent()
            assertEquals(SpeechPhase.Completed, env.session.state.value.phase)
            assertNull(env.session.state.value.error)
        } finally { env.stop(); unmockkStatic(Log::class) }
    }

    @Test fun stoppingDuringABookmarkWriteCannotStartAudioAfterTheWriteFinishes() = runTest {
        val gate = CompletableDeferred<Unit>()
        val env = Environment(this)
        env.progress.beforeSave = { gate.await() }
        try {
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            assertTrue(env.player.started.isEmpty())
            env.session.stop()
            gate.complete(Unit)
            runCurrent()
            assertTrue("Stopping while saving must not allow late playback", env.player.started.isEmpty())
            assertEquals(SpeechPhase.Stopped, env.session.state.value.phase)
            assertEquals(0, env.audioFiles())
        } finally {
            gate.complete(Unit)
            env.stop()
        }
    }

    @Test fun failedFutureSegmentDoesNotInterruptCurrentAudioAndRetryDoesNotSkipIt() = runTest {
        val env = Environment(this)
        var fail = true
        env.beforeSynthesis = { if (env.synthesized.size == 2 && fail) throw SpeechException(SpeechError.Network) }
        env.session.start(SpeechRequest("book", "one"))
        runCurrent()
        assertEquals(SpeechPhase.Playing, env.session.state.value.phase)
        assertEquals(1, env.player.started.size)
        val plan = env.player.started.single().plan
        env.player.finish()
        runCurrent()
        assertEquals(SpeechError.Network, env.session.state.value.error)
        val expected = plan.segments[1]
        assertEquals(expected.start, env.progress.saved.getValue("book").offset)
        assertEquals(SpeechPosition("book", "one", plan.chapter.fingerprint, expected.start, expected.end), env.session.state.value.position)
        assertEquals(plan.segments.size, env.session.state.value.segmentCount)
        fail = false
        env.session.resume()
        runCurrent()
        assertEquals(expected.text, env.player.started.last().segment.text)
        assertEquals(1, env.player.started.last().index)
        assertSame(plan, env.player.started.last().plan)
        env.stop()
    }

    @Test fun crossChapterProgressFollowsPlaybackAndFailureIsRetryableAtTheNextChapter() = runTest {
        val env = Environment(this)
        var sourceFails = true
        env.load = { book, chapter ->
            if (chapter == "two" && sourceFails) throw SpeechException(SpeechError.SourceUnavailable)
            SpeechChapter(book, chapter, "Book", chapter, "Chapter $chapter.", nextId = if (chapter == "one") "two" else null)
        }
        env.session.start(SpeechRequest("book", "one"))
        runCurrent()
        assertEquals("one", env.session.state.value.request?.chapterId)
        assertEquals("one", env.session.state.value.position?.chapterId)
        env.player.finish()
        runCurrent()
        assertEquals("two", env.session.state.value.request?.chapterId)
        assertEquals(SpeechError.SourceUnavailable, env.session.state.value.error)
        assertEquals("two", env.progress.saved.getValue("book").chapterId)
        sourceFails = false
        env.session.resume()
        runCurrent()
        assertEquals("two", env.player.started.last().chapter.id)
        assertEquals("two", env.session.state.value.position?.chapterId)
        env.player.finish()
        runCurrent()
        assertEquals(SpeechPhase.Completed, env.session.state.value.phase)
        assertNull(env.session.state.value.position)
        assertTrue(env.progress.saved.getValue("book").completed)
        env.stop()
    }

    @Test fun changedTextRestartsSafelyAndPreviewNeverOverwritesBookProgress() = runTest {
        val env = Environment(this)
        val original = SpeechBookmark("book", "one", "old-text", 400)
        env.progress.saved["book"] = original
        env.session.start(SpeechRequest("book", "one"))
        runCurrent()
        assertEquals(0, env.player.started.single().index)
        val saved = env.progress.saved.getValue("book")
        env.session.start(SpeechRequest("", "", "Voice preview."))
        runCurrent()
        env.player.estimatedAnchorCallback!!(4)
        assertNull(env.session.state.value.position)
        env.player.finish()
        runCurrent()
        assertEquals(saved, env.progress.saved.getValue("book"))
        assertEquals(setOf("book"), env.progress.saved.keys)
        env.stop()
    }

    @Test fun enteringABookResumesItsListeningChapterAfterTheCompletedSegment() = runTest {
        val env = Environment(this)
        val text = listOf("第一段" + "甲".repeat(28) + "。", "第二段" + "乙".repeat(28) + "。",
            "第三段" + "丙".repeat(28) + "。").joinToString("\n")
        env.configuration = SpeechSettings(text = SpeechTextSettings(maxSentenceLength = 32))
        env.load = { book, chapter -> SpeechChapter(book, chapter, "Book", chapter, text) }
        val chapter = env.load("book", "two")
        val segments = prepareSpeechPlan(chapter, env.configuration) {}.segments
        env.progress.saved["book"] = SpeechBookmark("book", "two", chapter.fingerprint, segments.first().end)
        try {
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            val clip = env.player.started.single()
            assertEquals("two", clip.chapter.id)
            assertEquals(segments[1].start, clip.segment.start)
            // An explicit chapter command still starts at that chapter, not the saved location.
            env.session.start(SpeechRequest("book", "one"), resumeSaved = false)
            runCurrent()
            assertEquals("one", env.player.started.last().chapter.id)
            assertEquals(0, env.player.started.last().segment.start)
        } finally { env.stop() }
    }

    @Test fun changingVoiceWhilePausedPreservesThePauseAndCurrentBoundary() = runTest {
        val env = Environment(this)
        env.session.start(SpeechRequest("book", "one"))
        runCurrent()
        env.player.finish()
        runCurrent()
        val index = env.player.started.last().index
        val oldPlan = env.player.started.last().plan
        env.session.pause()
        env.configuration = SpeechSettings(voice = "new-voice")
        env.session.updateSettings(env.configuration)
        runCurrent()
        assertEquals(index, env.player.started.last().index)
        assertEquals(SpeechPhase.Paused, env.session.state.value.phase)
        assertEquals("new-voice", env.opened.last().voice)
        assertNotSame(oldPlan, env.player.started.last().plan)
        assertEquals("new-voice", env.player.started.last().plan.synthesis.voice)
        env.stop()
    }

    @Test fun changingVoiceAfterTextChangesDoesNotSkipNewText() = runTest {
        val env = Environment(this)
        env.session.start(SpeechRequest("book", "one"))
        runCurrent()
        env.player.finish()
        runCurrent()
        assertTrue(env.player.started.last().index > 0)
        val oldPlan = env.player.started.last().plan
        env.load = { book, chapter -> SpeechChapter(book, chapter, "Book", "Edited", "New introduction. " + env.text) }
        env.session.reloadSettings()
        runCurrent()
        assertEquals(0, env.player.started.last().index)
        assertTrue(env.player.started.last().segment.text.startsWith("New introduction."))
        assertNotSame(oldPlan, env.player.started.last().plan)
        env.stop()
    }

    @Test fun failedNextChapterClearsThePreviousChaptersControlsAndText() = runTest {
        val env = Environment(this)
        env.load = { book, chapter ->
            if (chapter == "two") throw SpeechException(SpeechError.SourceUnavailable)
            SpeechChapter(book, chapter, "Book", "First", "First chapter.", nextId = "two")
        }
        env.session.start(SpeechRequest("book", "one"))
        runCurrent()
        env.player.finish()
        runCurrent()
        val state = env.session.state.value
        assertEquals(SpeechPhase.Failed, state.phase)
        assertEquals("Book", state.bookTitle)
        assertEquals(0, state.segmentCount)
        assertEquals("", state.currentText)
        assertNull(state.previousChapterId)
        assertNull(state.nextChapterId)
        assertNull(state.position)
        assertEquals("Book", env.progress.saved.getValue("book").bookTitle)
        env.stop()
    }

    @Test fun nextPassageAtTheEndDoesNotRestartTheCurrentPassage() = runTest {
        val env = Environment(this)
        env.load = { book, chapter -> SpeechChapter(book, chapter, "Book", "Last", "The end.") }
        env.session.start(SpeechRequest("book", "one"))
        runCurrent()
        env.session.skipSegment(1)
        runCurrent()
        assertEquals(1, env.player.started.size)
        env.stop()
    }

    @Test fun aNewSessionRemovesAudioLeftByADeadProcess() = runTest {
        val env = Environment(this)
        val stale = File(env.directory, UUID.randomUUID().toString()).apply { mkdir() }
        File(stale, "audio.wav.part").writeText("incomplete audio")
        env.session.start(SpeechRequest("book", "one"))
        runCurrent()
        assertFalse(stale.exists())
        assertTrue(env.audioFiles() <= 4)
        env.stop()
    }

    @Test fun waitingForTheNextChapterShowsBufferingWithoutAdvancingProgress() = runTest {
        val env = Environment(this)
        val gate = CompletableDeferred<Unit>()
        env.load = { book, chapter ->
            if (chapter == "two") gate.await()
            SpeechChapter(book, chapter, "Book", chapter, "Chapter $chapter.", nextId = if (chapter == "one") "two" else null)
        }
        env.session.start(SpeechRequest("book", "one"))
        runCurrent()
        env.player.finish()
        runCurrent()
        assertEquals(SpeechPhase.Buffering, env.session.state.value.phase)
        assertEquals("one", env.progress.saved.getValue("book").chapterId)
        gate.complete(Unit)
        runCurrent()
        assertEquals("two", env.player.started.last().chapter.id)
        env.stop()
    }

    @Test fun reopeningAfterStoppingAtACompletedChapterDoesNotReplayThatChapter() = runTest {
        val env = Environment(this)
        val gate = CompletableDeferred<Unit>()
        env.load = { book, chapter ->
            if (chapter == "two") gate.await()
            SpeechChapter(book, chapter, "Book", chapter, "Chapter $chapter.", nextId = if (chapter == "one") "two" else null)
        }
        try {
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            env.player.finish()
            runCurrent()
            assertEquals(SpeechPhase.Buffering, env.session.state.value.phase)
            assertEquals("Chapter one.".length, env.progress.saved.getValue("book").offset)
            env.stop()
            gate.complete(Unit)
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            assertEquals("two", env.player.started.last().chapter.id)
        } finally { gate.complete(Unit); env.stop() }
    }

    @Test fun realPlaybackBoundariesHighlightASentenceAndKeepItsMovingAnchorAcrossPause() = runTest {
        val env = Environment(this)
        env.inputLimit = 9
        env.load = { book, chapter -> SpeechChapter(book, chapter, "Book", "Chapter", "甲乙丙。丁戊己。\n庚辛壬。") }
        env.timings = listOf(SpeechTiming(0, 0, 1))
        try {
            env.session.start(SpeechRequest("book", "chapter"))
            runCurrent()
            val firstBoundary = env.player.rangeCallback!!
            firstBoundary(SpeechTiming(1000, 4, 5))
            val position = env.session.state.value.position!!
            assertEquals(4, position.start)
            assertEquals(8, position.end)
            firstBoundary(SpeechTiming(1400, 6, 7))
            assertEquals(position.copy(anchor = 6), env.session.state.value.position)
            assertEquals(0, env.progress.saved.getValue("book").offset)
            env.session.pause()
            assertEquals(position.copy(anchor = 6), env.session.state.value.position)
            env.session.resume()
            env.player.finish()
            runCurrent()
            val next = env.session.state.value.position
            assertEquals(9, next?.start)
            firstBoundary(SpeechTiming(1800, 7, 8))
            assertEquals(next, env.session.state.value.position)
            env.stop()
            firstBoundary(SpeechTiming(1900, 7, 8))
            assertNull(env.session.state.value.position)
        } finally { env.stop() }
    }

    @Test fun estimatedFollowingKeepsTheClipHighlightAndNeverChangesDurableProgress() = runTest {
        val env = Environment(this)
        env.inputLimit = 10
        env.load = { book, chapter -> SpeechChapter(book, chapter, "Book", "Chapter", "甲乙丙丁戊己庚辛。\n下一段还有正文。") }
        try {
            env.session.start(SpeechRequest("book", "chapter"))
            runCurrent()
            val first = env.session.state.value.position!!
            val firstCallback = env.player.estimatedAnchorCallback!!
            firstCallback(6)
            assertEquals(first.copy(anchor = 6), env.session.state.value.position)
            assertEquals(0, env.progress.saved.getValue("book").offset)
            env.session.pause()
            assertEquals(first.copy(anchor = 6), env.session.state.value.position)
            env.session.resume()
            env.player.finish()
            runCurrent()
            val next = env.session.state.value.position!!
            firstCallback(7)
            assertEquals(next, env.session.state.value.position)
            env.player.estimatedAnchorCallback!!(3)
            assertEquals(next.copy(anchor = next.start + 3), env.session.state.value.position)
            assertEquals(next.start, env.progress.saved.getValue("book").offset)
            env.stop()
            firstCallback(8)
            assertNull(env.session.state.value.position)
        } finally { env.stop() }
    }

    @Test fun speedChangesKeepPausedAudioAndPrefetchAndSurvivePassageAndChapterTransitions() = runTest {
        val env = Environment(this)
        env.inputLimit = 15
        env.configuration = SpeechSettings(rate = 1.2f, pitch = 0.8f, playbackSpeed = 1.5f)
        env.load = { book, chapter ->
            SpeechChapter(book, chapter, "Book", chapter,
                if (chapter == "one") "First passage.\nSecond passage." else "Last passage.",
                nextId = if (chapter == "one") "two" else null)
        }
        try {
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            assertEquals(1.5f, env.player.playbackSpeed)
            assertEquals(3, env.synthesized.size)
            val playing = env.session.state.value
            env.session.updateSettings(env.configuration.copy(playbackSpeed = 2f))
            assertEquals(playing, env.session.state.value)
            assertTrue(env.player.playing)
            env.session.pause()
            val paused = env.session.state.value
            env.session.updateSettings(env.configuration.copy(playbackSpeed = 0.7f))
            env.session.updateSettings(env.configuration.copy(playbackSpeed = 2f))
            runCurrent()
            assertEquals(2f, env.player.playbackSpeed)
            assertEquals(paused, env.session.state.value)
            assertFalse(env.player.playing)
            assertEquals(1, env.player.started.size)
            assertEquals(3, env.synthesized.size)
            assertEquals(listOf(env.configuration.copy(playbackSpeed = 1f)), env.opened)
            env.session.resume()
            repeat(2) { env.player.finish(); runCurrent() }
            assertEquals(listOf(1.5f, 2f, 2f), env.player.startSpeeds)
            assertEquals("two", env.player.started.last().chapter.id)
            assertEquals(3, env.synthesized.size)
            env.player.finish()
            runCurrent()
            assertEquals(SpeechPhase.Completed, env.session.state.value.phase)
        } finally { env.stop() }
    }

    @Test fun subsequentStartsUseNewlyStoredSettingsBeforeTheirNotificationArrives() = runTest {
        val env = Environment(this)
        try {
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            env.configuration = SpeechSettings(voice = "new-voice", playbackSpeed = 2f)
            env.session.start(SpeechRequest("book", "two"), playing = false)
            runCurrent()
            assertEquals(env.configuration.copy(playbackSpeed = 1f), env.opened.last())
            assertEquals(2f, env.player.playbackSpeed)
            assertEquals(SpeechPhase.Paused, env.session.state.value.phase)
            val clip = env.player.started.last()
            val requests = env.synthesized.size
            env.session.updateSettings(env.configuration)
            runCurrent()
            assertEquals(2, env.opened.size)
            assertSame(clip, env.player.started.last())
            assertEquals(requests, env.synthesized.size)
            assertFalse(env.player.playing)
        } finally { env.stop() }
    }

    @Test fun settingsReceivedDuringStartupOverrideTheOlderReadWithoutRestartingSynthesis() = runTest {
        val env = Environment(this)
        val read = CompletableDeferred<Unit>()
        try {
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            env.beforeSettings = { read.await() }
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            env.session.updateSettings(SpeechSettings(playbackSpeed = 1.5f))
            env.session.pause()
            env.session.updateSettings(SpeechSettings(playbackSpeed = 2f))
            read.complete(Unit)
            runCurrent()
            assertEquals(2f, env.player.playbackSpeed)
            assertEquals(2, env.opened.size)
            assertEquals(1f, env.opened.last().playbackSpeed)
            assertEquals(SpeechPhase.Paused, env.session.state.value.phase)
            assertFalse(env.player.playing)
        } finally { read.complete(Unit); env.stop() }
    }

    @Test fun previewReturnsToTheAudibleBoundaryWithTheOriginalVoiceAndPauseIntent() = runTest {
        for (playing in listOf(true, false)) {
            val env = Environment(this)
            env.configuration = SpeechSettings(voice = "original", playbackSpeed = 1.5f)
            try {
                env.session.start(SpeechRequest("book", "one"))
                runCurrent()
                env.player.finish()
                runCurrent()
                val original = env.player.started.last()
                if (!playing) env.session.pause()
                val bookmark = env.progress.saved.getValue("book")
                env.session.start(SpeechRequest("", "", "Sample.", SpeechSettings(voice = "candidate", playbackSpeed = 2f)))
                runCurrent()
                assertEquals("candidate", env.opened.last().voice)
                assertEquals(2f, env.player.playbackSpeed)
                assertEquals(mapOf("book" to bookmark), env.progress.saved)
                env.player.finish()
                runCurrent()
                assertTrue(env.session.restoreAfterPreview())
                runCurrent()
                assertEquals(original.chapter, env.player.started.last().chapter)
                assertEquals(original.segment, env.player.started.last().segment)
                assertSame(original.plan, env.player.started.last().plan)
                assertEquals(env.configuration.copy(playbackSpeed = 1f), env.opened.last())
                assertEquals(playing, env.player.playing)
                assertEquals(if (playing) SpeechPhase.Playing else SpeechPhase.Paused, env.session.state.value.phase)
                assertFalse(env.session.restoreAfterPreview())
            } finally { env.stop() }
        }
    }

    @Test fun repeatedPreviewsKeepTheFirstBookAndDiscardLateSynthesis() = runTest {
        val env = Environment(this)
        val late = CompletableDeferred<Unit>()
        try {
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            val bookmark = env.progress.saved.getValue("book")
            env.beforeSynthesis = { if (env.synthesized.last() == "Old sample.") withContext(NonCancellable) { late.await() } }
            env.session.start(SpeechRequest("", "", "Old sample."))
            runCurrent()
            env.session.start(SpeechRequest("", "", "Middle sample."))
            env.session.start(SpeechRequest("", "", "Latest sample."))
            runCurrent()
            assertEquals(1, env.player.started.size)
            late.complete(Unit)
            runCurrent()
            assertEquals(listOf("Latest sample."), env.player.started.drop(1).map { it.segment.text })
            assertEquals(mapOf("book" to bookmark), env.progress.saved)
            env.player.finish()
            runCurrent()
            assertTrue(env.session.restoreAfterPreview())
            runCurrent()
            assertEquals("book", env.player.started.last().chapter.bookId)
            assertEquals(bookmark.offset, env.player.started.last().segment.start)
        } finally { late.complete(Unit); env.stop() }
        assertTrue(env.directory.listFiles()!!.isEmpty())
    }

    @Test fun failedPreviewDuringPreparationRestoresTheExplicitChapterWithoutLoadingAnOlderBookmark() = runTest {
        val env = Environment(this)
        val pending = CompletableDeferred<Unit>()
        env.progress.saved["book"] = SpeechBookmark("book", "old", "old fingerprint", 50, "Book", "Old")
        env.beforeSynthesis = { if (env.synthesized.last() == "Sample.") throw SpeechException(SpeechError.Network) else pending.await() }
        try {
            env.session.start(SpeechRequest("book", "new"), resumeSaved = false, playing = false)
            runCurrent()
            env.session.start(SpeechRequest("", "", "Sample."))
            runCurrent()
            assertEquals(SpeechError.Network, env.session.state.value.error)
            assertTrue(env.session.restoreAfterPreview())
            pending.complete(Unit)
            runCurrent()
            assertEquals("new", env.player.started.single().chapter.id)
            assertEquals(0, env.player.started.single().segment.start)
            assertFalse(env.player.playing)
        } finally { pending.complete(Unit); env.stop() }
    }

    @Test fun selectingAVoiceDuringPreviewAppliesOnReturnWithoutChangingTheSample() = runTest {
        val env = Environment(this)
        try {
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            env.session.start(SpeechRequest("", "", "Sample.", SpeechSettings(voice = "candidate", playbackSpeed = 2f)))
            runCurrent()
            val selected = SpeechSettings(voice = "chosen", playbackSpeed = 1.2f)
            env.configuration = selected
            env.session.updateSettings(selected)
            runCurrent()
            assertEquals(2, env.opened.size)
            assertEquals(2f, env.player.playbackSpeed)
            env.player.finish()
            runCurrent()
            assertTrue(env.session.restoreAfterPreview())
            runCurrent()
            assertEquals(selected.copy(playbackSpeed = 1f), env.opened.last())
        } finally { env.stop() }
    }

    @Test fun stopReplacementAndSystemInterruptionPreventPreviewFromRestoringTheOldBook() = runTest {
        for (action in listOf("stop", "replace", "interrupt")) {
            val env = Environment(this)
            try {
                env.session.start(SpeechRequest("book", "one"))
                runCurrent()
                env.session.start(SpeechRequest("", "", "Sample."))
                runCurrent()
                when (action) {
                    "stop" -> env.session.stop()
                    "replace" -> env.session.start(SpeechRequest("new", "one"))
                    else -> { env.session.interrupt(); env.session.resume(); env.player.finish() }
                }
                runCurrent()
                assertFalse(env.session.restoreAfterPreview())
                assertEquals(1, env.player.started.count { it.chapter.bookId == "book" })
            } finally { env.stop() }
        }
    }

    @Test fun standalonePreviewDoesNotCreateABookOrSaveProgress() = runTest {
        val env = Environment(this)
        try {
            env.session.start(SpeechRequest("", "", "Sample.", SpeechSettings(voice = "candidate")))
            runCurrent()
            env.player.finish()
            runCurrent()
            assertFalse(env.session.restoreAfterPreview())
            assertEquals(SpeechPhase.Completed, env.session.state.value.phase)
            assertTrue(env.progress.saved.isEmpty())
        } finally { env.stop() }
    }

    @Test fun previewFreezesTimerUntilBookPlaybackResumes() = runTest {
        val env = Environment(this)
        val late = CompletableDeferred<Unit>()
        val timer = SpeechSleepTimer(this, { testScheduler.currentTime }, {}, env.session::stop)
        try {
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            timer.set(1, playing = true)
            advanceTimeBy(20_000)
            timer.setPlaying(false)
            env.beforeSynthesis = { withContext(NonCancellable) { late.await() } }
            env.session.start(SpeechRequest("", "", "Sample."))
            runCurrent()
            assertNull(timer.deadline)
            assertEquals(40_000L, timer.remainingMillis)
            advanceTimeBy(40_000)
            runCurrent()
            late.complete(Unit)
            runCurrent()
            env.player.finish()
            runCurrent()
            assertTrue(env.session.restoreAfterPreview())
            runCurrent()
            timer.setPlaying(true)
            assertEquals(100_000L, timer.deadline)
            advanceTimeBy(40_000)
            runCurrent()
            assertEquals(SpeechPhase.Stopped, env.session.state.value.phase)
            assertFalse(env.session.restoreAfterPreview())
            assertTrue(env.directory.listFiles()!!.isEmpty())
        } finally { timer.cancel(); late.complete(Unit); env.stop() }
    }

    @Test fun skippingAndResumingUseThePlayingPlanEvenWhenAFutureChapterHasBeenPrepared() = runTest {
        val env = Environment(this)
        val text = listOf("𠀀第一段" + "甲".repeat(25) + "。", "第二段" + "乙".repeat(28) + "。",
            "第三段" + "丙".repeat(28) + "。").joinToString("\n")
        env.configuration = SpeechSettings(text = SpeechTextSettings(maxSentenceLength = 32))
        var loaded = 0
        env.load = { book, chapter ->
            loaded++
            SpeechChapter(book, chapter, "Book", chapter, text,
                nextId = if (chapter == "one") "two" else null)
        }
        try {
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            val first = env.player.started.single()
            assertEquals(2, loaded)
            env.session.pause()
            env.session.skipSegment(1)
            runCurrent()
            val second = env.player.started.last()
            assertSame(first.plan, second.plan)
            assertSame(first.plan.segments[1], second.segment)
            assertEquals(second.segment.start, env.progress.saved.getValue("book").offset)
            assertEquals(SpeechPhase.Paused, env.session.state.value.phase)
            assertEquals(SpeechPosition("book", "one", first.chapter.fingerprint, second.segment.start, second.segment.end),
                env.session.state.value.position)
            env.session.resume()
            env.player.finish()
            runCurrent()
            assertSame(first.plan, env.player.started.last().plan)
            assertEquals(2, env.player.started.last().index)
        } finally { env.stop() }
    }

    @Test fun offlineResourceAndRateChangesDiscardOldAudioButInactiveParametersAndPlaybackSpeedDoNot() = runTest {
        val env = Environment(this)
        val reference = OfflineVoiceRef("a".repeat(64), "b".repeat(64))
        env.configuration = SpeechSettings(offlineVoice = reference)
        try {
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            env.session.pause()
            val first = env.player.started.single()
            val synthesized = env.synthesized.size
            env.configuration = env.configuration.copy(engine = "unused", voice = "unused", pitch = 2f, rate = 1f, playbackSpeed = 2f)
            env.session.updateSettings(env.configuration)
            runCurrent()
            assertEquals(synthesized, env.synthesized.size)
            assertEquals(1, env.opened.size)
            assertTrue(first.file.exists())
            assertEquals(2f, env.player.playbackSpeed)
            env.configuration = env.configuration.copy(offlineVoice = reference.copy(revision = "c".repeat(64)))
            env.session.updateSettings(env.configuration)
            runCurrent()
            val replacement = env.player.started.last()
            assertNotSame(first.plan, replacement.plan)
            assertEquals(env.configuration.offlineVoice, replacement.plan.synthesis.offlineVoice)
            assertFalse(first.file.parentFile!!.exists())
            assertFalse(env.player.playing)
            env.configuration = env.configuration.copy(rate = 1.5f)
            env.session.updateSettings(env.configuration)
            runCurrent()
            assertNotSame(replacement.plan, env.player.started.last().plan)
            assertEquals(1.5f, env.opened.last().rate)
            assertFalse(replacement.file.parentFile!!.exists())
            assertTrue(env.audioFiles() <= 4)
        } finally { env.stop() }
    }

    @Test fun replacementsSharePlaybackMappingAcrossOnlineAndOfflineAndInvalidatePrefetchWhilePaused() = runTest {
        val env = Environment(this)
        val suffix = "字".repeat(28) + "。"
        val paragraph = "甲😀乙$suffix"
        env.load = { book, chapter -> SpeechChapter(book, chapter, "Book", "Chapter", "$paragraph\n$paragraph") }
        val rule = SpeechReplacementRule(id = "expand", source = "甲", target = "一二三")
        env.configuration = SpeechSettings(httpSource = "online", replacements = listOf(rule),
            text = SpeechTextSettings(maxSentenceLength = 32))
        try {
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            assertEquals("一二三😀乙$suffix\n", env.player.started.single().segment.text)
            env.player.estimatedAnchorCallback!!.invoke(4)
            assertEquals(1, env.session.state.value.position!!.anchor)
            assertEquals(0, env.session.state.value.position!!.start)
            assertEquals(paragraph.length + 1, env.session.state.value.position!!.end)
            env.session.skipSegment(1)
            runCurrent()
            assertEquals(1, env.player.started.last().index)
            assertEquals(paragraph.length + 1, env.progress.saved.getValue("book").offset)
            env.session.pause()
            val previous = env.player.started.last()
            env.configuration = env.configuration.copy(httpSource = null,
                offlineVoice = OfflineVoiceRef("a".repeat(64), "b".repeat(64)), replacements = listOf(rule.copy(target = "四")))
            env.session.updateSettings(env.configuration)
            runCurrent()
            val current = env.player.started.last()
            assertEquals("四😀乙$suffix", current.segment.text)
            assertEquals(paragraph.length + 1, current.segment.start)
            assertFalse(env.player.playing)
            assertFalse(previous.file.exists())
            assertNotSame(previous.plan, current.plan)
        } finally { env.stop() }
    }

    @Test fun aDeletedChapterSendsNoEmptyAudioAndContinuesWithoutLosingTheOriginalBookmark() = runTest {
        val env = Environment(this)
        env.load = { book, chapter -> SpeechChapter(book, chapter, "Book", chapter,
            if (chapter == "one") "广告" else "正文", nextId = if (chapter == "one") "two" else null) }
        env.configuration = SpeechSettings(replacements = listOf(SpeechReplacementRule(source = "广告", target = "")))
        try {
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            assertEquals(listOf("正文"), env.synthesized)
            assertEquals("two", env.player.started.single().chapter.id)
            assertEquals("two", env.progress.saved.getValue("book").chapterId)
            env.player.finish()
            runCurrent()
            assertTrue(env.progress.saved.getValue("book").completed)
        } finally { env.stop() }
    }

    @Test fun failingReplacementNamesTheEntryBeforeAnyAudioIsSynthesized() = runTest {
        val env = Environment(this)
        env.configuration = SpeechSettings(replacements = listOf(SpeechReplacementRule(id = "invalid", source = "(", regex = true)))
        try {
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            assertEquals(SpeechPhase.Failed, env.session.state.value.phase)
            assertEquals(SpeechError.ReplacementRule, env.session.state.value.error)
            assertEquals("invalid", env.session.state.value.replacementFailure?.ruleId)
            assertTrue(env.synthesized.isEmpty())
        } finally { env.stop() }
    }

    @Test fun contextFollowsConsumedPlansThroughPrefetchSkipBookSwitchAndFreshStart() = runTest {
        val env = Environment(this)
        env.configuration = SpeechSettings(text = SpeechTextSettings(dialogue = true, continueDialogue = true, recordText = true))
        env.load = { book, chapter -> SpeechChapter(book, chapter, "Book", chapter,
            when (chapter) { "one" -> "“没说完"; "two" -> "接着说完。” “另一句。”"; else -> "旁白。" },
            nextId = when (chapter) { "one" -> "two"; "two" -> "three"; else -> null }) }
        try {
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            val first = env.player.started.single()
            assertTrue(first.plan.nextContext.openDialogue)
            assertFalse(env.progress.saved.getValue("book").context.openDialogue)
            env.player.finish()
            runCurrent()
            val second = env.player.started.last()
            assertEquals("two", second.chapter.id)
            assertTrue(second.plan.context.openDialogue)
            assertFalse(second.segment.changeVoice)
            env.session.skipSegment(1)
            runCurrent()
            assertSame(second.plan, env.player.started.last().plan)
            assertEquals(1, env.player.started.last().index)
            assertTrue(env.progress.saved.getValue("book").context.openDialogue)
            env.session.start(SpeechRequest("other", "three"))
            runCurrent()
            assertEquals(SpeechContext(), env.player.started.last().plan.context)
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            assertEquals("two", env.player.started.last().chapter.id)
            assertTrue(env.player.started.last().plan.context.openDialogue)
            assertEquals(1, env.player.started.last().index)
            env.stop()
            env.session.resume()
            runCurrent()
            assertTrue(env.player.started.last().plan.context.openDialogue)
            env.session.start(SpeechRequest("book", "two"), resumeSaved = false)
            runCurrent()
            assertEquals(SpeechContext(), env.player.started.last().plan.context)
            env.session.start(SpeechRequest("book", "one"), resumeSaved = false)
            runCurrent()
            env.session.skipSegment(1)
            runCurrent()
            assertEquals("two", env.player.started.last().chapter.id)
            assertTrue(env.player.started.last().plan.context.openDialogue)
        } finally { env.stop() }
    }

    @Test fun retryAndPreviewReturnRestoreTheChapterInputContext() = runTest {
        val env = Environment(this)
        env.configuration = SpeechSettings(text = SpeechTextSettings(dialogue = true, continueDialogue = true))
        var failLoad = true
        env.load = { book, chapter ->
            if (failLoad && chapter == "two") throw IOException("load")
            SpeechChapter(book, chapter, "Book", chapter,
                if (chapter == "one") "“没说完" else "还在说", nextId = if (chapter == "one") "two" else null)
        }
        var fail = true
        env.beforeSynthesis = { if (fail && env.synthesized.last() == "还在说") throw IOException("synthesis") }
        try {
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            env.player.finish()
            runCurrent()
            assertEquals(SpeechPhase.Failed, env.session.state.value.phase)
            assertTrue(env.progress.saved.getValue("book").context.openDialogue)
            failLoad = false
            env.session.resume()
            runCurrent()
            assertEquals(SpeechPhase.Failed, env.session.state.value.phase)
            assertTrue(env.progress.saved.getValue("book").context.openDialogue)
            fail = false
            env.session.resume()
            runCurrent()
            val resumed = env.player.started.last()
            assertTrue(resumed.plan.context.openDialogue)
            assertTrue(resumed.segment.dialogue)
            env.session.start(SpeechRequest("", "", previewText = "独立试听"))
            runCurrent()
            assertEquals(SpeechContext(), env.player.started.last().plan.context)
            assertFalse(env.player.started.last().segment.dialogue)
            env.player.finish()
            runCurrent()
            assertTrue(env.session.restoreAfterPreview())
            runCurrent()
            assertSame(resumed.plan, env.player.started.last().plan)
            assertTrue(env.player.started.last().plan.context.openDialogue)
        } finally { env.stop() }
    }

    @Test fun mixedVoicesReachSynthesisAndRevisionChangesPreservePausedPreviewReturn() = runTest {
        val env = Environment(this)
        val narration = SpeechVoiceRef(engine = "system", voice = "narration")
        val first = SpeechVoiceRef(httpSource = "person-a")
        val second = SpeechVoiceRef(offlineVoice = OfflineVoiceRef("person-b", "model-v1"))
        val third = SpeechVoiceRef(offlineVoice = OfflineVoiceRef("person-c", "model-v1"))
        env.configuration = SpeechSettings(voiceRoutingConfigured = true, narrationVoices = listOf(narration),
            dialogueVoices = listOf(first), speakerBookId = "book",
            speakerVoices = mapOf("甲" to first, "乙" to second, "丙" to third), voiceRevision = "v1",
            text = SpeechTextSettings(dialogue = true, separateEnglish = false))
        env.load = { book, chapter -> SpeechChapter(book, chapter, "Book", "Chapter",
            "序。“<<甲>>第一句。”“<<乙>>第二句。”“<<丙>>第三句。”“<<甲>>重逢。”") }
        try {
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            repeat(3) { env.player.finish(); runCurrent() }
            assertEquals(listOf(narration.key, first.key, second.key, third.key, first.key),
                env.synthesizedSettings.map { it.voiceReference().key })
            assertEquals(third.key, env.session.state.value.voiceLabel)
            assertEquals(listOf(narration.key, first.key, second.key, third.key),
                env.player.started.map { it.segment.selectedVoice!!.reference.key })
            env.session.pause()
            val previous = env.player.started.last()
            val revised = third.copy(offlineVoice = third.offlineVoice!!.copy(revision = "model-v2"))
            env.configuration = env.configuration.copy(voiceRevision = "v2",
                speakerVoices = env.configuration.speakerVoices + ("丙" to revised))
            env.session.updateSettings(env.configuration)
            runCurrent()
            val current = env.player.started.last()
            assertNotSame(previous.plan, current.plan)
            assertEquals(previous.segment.start, current.segment.start)
            assertEquals(revised.offlineVoice, current.segment.selectedVoice!!.settings.offlineVoice)
            assertFalse(previous.file.parentFile!!.exists())
            assertEquals(SpeechPhase.Paused, env.session.state.value.phase)
            assertFalse(env.player.playing)
            val bookmark = env.progress.saved.getValue("book")
            val openedBeforePreview = env.opened.size
            env.session.start(SpeechRequest("", "", "“<<甲>>试听。”", env.configuration.copy(httpSource = "audition")))
            runCurrent()
            assertEquals(listOf("audition"), env.opened.drop(openedBeforePreview).map { it.httpSource })
            assertTrue(env.opened.drop(openedBeforePreview).none { it.hasVoiceRouting })
            assertEquals("“<<甲>>试听。”", env.player.started.last().segment.text)
            assertEquals(bookmark, env.progress.saved.getValue("book"))
            env.player.finish()
            runCurrent()
            assertTrue(env.session.restoreAfterPreview())
            runCurrent()
            assertSame(current.plan, env.player.started.last().plan)
            assertEquals(revised.offlineVoice, env.player.started.last().segment.selectedVoice!!.settings.offlineVoice)
            assertFalse(env.player.playing)
            assertEquals(SpeechPhase.Paused, env.session.state.value.phase)
            assertTrue(env.audioFiles() <= 4)
        } finally { env.stop() }
    }

    @Test fun failedRoleKeepsItsPersonAndClearsAfterRepairAndStop() = runTest {
        val env = Environment(this)
        val voice = SpeechVoiceRef(httpSource = "person")
        val role = SpeechRoleRule(id = "family", name = "家人", regex = "小乙", isAbstract = true)
        env.configuration = SpeechSettings(voiceRoutingConfigured = true, narrationVoices = listOf(voice),
            dialogueVoices = listOf(voice), roleRules = listOf(role),
            text = SpeechTextSettings(dialogue = true, separateEnglish = false))
        env.load = { book, chapter -> SpeechChapter(book, chapter, "Book", "Chapter", "“<<小乙>>你好。”") }
        try {
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            assertEquals(SpeechError.MissingVoice, env.session.state.value.error)
            assertEquals(SpeechRoleMatch("小乙", role.id, role.name, true, null), env.session.state.value.failedRole)
            assertTrue(env.synthesized.isEmpty())
            env.configuration = env.configuration.copy(roleRules = listOf(role.copy(speaker = voice)))
            env.session.updateSettings(env.configuration)
            assertNull(env.session.state.value.failedRole)
            runCurrent()
            assertEquals(SpeechPhase.Playing, env.session.state.value.phase)
            assertEquals(voice.key, env.player.started.single().segment.selectedVoice!!.reference.key)
            assertNull(env.session.state.value.failedRole)
            assertNull(env.session.state.value.failedVoice)
            env.configuration = env.configuration.copy(roleRules = listOf(role))
            env.session.updateSettings(env.configuration)
            runCurrent()
            assertNotNull(env.session.state.value.failedRole)
            env.stop()
            assertNull(env.session.state.value.failedRole)
            assertNull(env.session.state.value.failedVoice)
        } finally { env.stop() }
    }

    @Test fun cancellingDuringAVoiceSwitchWaitsForOldSynthesisBeforeOpeningTheReplacement() = runTest {
        val env = Environment(this)
        val gate = CompletableDeferred<Unit>()
        val first = SpeechVoiceRef(httpSource = "first")
        val second = SpeechVoiceRef(offlineVoice = OfflineVoiceRef("second", "model-v1"))
        val narration = SpeechVoiceRef(engine = "system", voice = "new-book")
        env.configuration = SpeechSettings(voiceRoutingConfigured = true, narrationVoices = listOf(narration),
            dialogueVoices = listOf(first), speakerBookId = "old", speakerVoices = mapOf("甲" to first, "乙" to second),
            text = SpeechTextSettings(dialogue = true))
        env.load = { book, chapter -> SpeechChapter(book, chapter, "Book", "Chapter",
            if (book == "old") "“<<甲>>第一句。”“<<乙>>第二句。”“不该继续。”" else "新书正文。") }
        val events = mutableListOf<String>()
        env.onOpen = { events += "open:${it.voiceReference().key}" }
        env.onClose = { events += "close" }
        env.beforeSynthesis = {
            if (env.synthesizedSettings.last().voiceReference().key == second.key) {
                withContext(NonCancellable) { gate.await() }
                events += "old-finished"
            }
        }
        try {
            env.session.start(SpeechRequest("old", "one"))
            runCurrent()
            assertEquals(first.key, env.player.started.single().segment.selectedVoice!!.reference.key)
            env.session.start(SpeechRequest("middle", "one"))
            env.session.start(SpeechRequest("new", "one"))
            runCurrent()
            assertEquals(listOf("open:${first.key}", "open:${second.key}"), events)
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf("open:${first.key}", "open:${second.key}", "old-finished", "close",
                "open:${narration.key}"), events)
            assertEquals(listOf("old", "new"), env.player.started.map { it.chapter.bookId })
            assertEquals(listOf(first.key, second.key, narration.key), env.synthesizedSettings.map { it.voiceReference().key })
            assertEquals("new", env.session.state.value.request?.bookId)
            assertFalse(env.progress.saved.containsKey("middle"))
        } finally { gate.complete(Unit); env.stop() }
        assertEquals(0, env.audioFiles())
    }

    private inner class Environment(val scope: TestScope) {
        val text = "这是正常的小说内容，保留顺序并且持续朗读。".repeat(100)
        var load: suspend (String, String) -> SpeechChapter = { book, chapter -> SpeechChapter(book, chapter, "Book", "Chapter", text) }
        var beforeSynthesis: suspend () -> Unit = {}
        var beforeSettings: suspend () -> Unit = {}
        var onOpen: suspend (SpeechSettings) -> Unit = {}
        var onCancel: () -> Unit = {}
        var onClose: () -> Unit = {}
        var timings = emptyList<SpeechTiming>()
        var configuration = SpeechSettings()
        var inputLimit = Int.MAX_VALUE
        val synthesized = mutableListOf<String>()
        val synthesizedSettings = mutableListOf<SpeechSettings>()
        val opened = mutableListOf<SpeechSettings>()
        val directory = files.newFolder()
        val progress = MemoryProgress()
        val player = FakePlayback()
        val session = ReadAloudSession(scope, SpeechChapterSource { book, chapter -> load(book, chapter) },
            { val snapshot = configuration; beforeSettings(); snapshot }, progress, {
                object : SpeechSynthesizer {
                    private var activeSettings = SpeechSettings()
                    override val maxInputLength get() = inputLimit
                    override suspend fun open(settings: SpeechSettings): String {
                        opened += settings
                        activeSettings = settings
                        onOpen(settings)
                        return settings.voiceReference().key
                    }
                    override suspend fun synthesize(text: String, output: File): List<SpeechTiming> {
                        synthesized += text
                        synthesizedSettings += activeSettings
                        beforeSynthesis()
                        output.writeText(text)
                        return timings
                    }
                    override fun cancel() = onCancel()
                    override fun close() = onClose()
                }
            }, player, directory, StandardTestDispatcher(scope.testScheduler))
        fun audioFiles() = directory.walkTopDown().count { it.isFile }
        fun stop() { session.stop(); scope.runCurrent() }
    }

    private class MemoryProgress : SpeechProgressStore {
        val saved = mutableMapOf<String, SpeechBookmark>()
        var beforeSave: suspend () -> Unit = {}
        override suspend fun load(bookId: String) = saved[bookId]
        override suspend fun save(bookmark: SpeechBookmark) { beforeSave(); saved[bookmark.bookId] = bookmark }
    }

    private class FakePlayback : SpeechPlayback {
        val started = mutableListOf<SpeechClip>()
        val startSpeeds = mutableListOf<Float>()
        var playbackSpeed = 1f
            private set
        var playing = false
        var startBuffered = false
        private var pending: CompletableDeferred<Unit>? = null
        private var callback: ((SpeechPhase, Boolean) -> Unit)? = null
        var rangeCallback: ((SpeechTiming) -> Unit)? = null
        var estimatedAnchorCallback: ((Int) -> Unit)? = null
        override suspend fun play(clip: SpeechClip, playWhenReady: Boolean, onRange: (SpeechTiming) -> Unit,
            onEstimatedAnchor: (Int) -> Unit,
            onState: (SpeechPhase, Boolean) -> Unit) {
            val complete = CompletableDeferred<Unit>()
            pending = complete
            callback = onState
            rangeCallback = onRange
            estimatedAnchorCallback = onEstimatedAnchor
            started += clip
            startSpeeds += playbackSpeed
            playing = playWhenReady && !startBuffered
            onState(when {
                !playWhenReady -> SpeechPhase.Paused
                playing -> SpeechPhase.Playing
                else -> SpeechPhase.Buffering
            }, playWhenReady)
            try { complete.await() } finally { if (pending === complete) { pending = null; callback = null } }
        }
        fun finish() { check(playing); pending!!.complete(Unit) }
        override fun setSpeed(speed: Float) { playbackSpeed = speed }
        override fun pause() { playing = false; callback?.invoke(SpeechPhase.Paused, false) }
        override fun resume() { playing = true; callback?.invoke(SpeechPhase.Playing, true) }
        override fun stop() { pending?.cancel(); pending = null; callback = null; playing = false }
    }
}
