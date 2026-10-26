package indi.renakoni.nextvol.tts

import android.util.Log
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
        env.session.start(SpeechRequest("book", "one"))
        runCurrent()
        assertEquals(1, env.player.started.size)
        assertEquals(4, env.synthesized.size)
        assertEquals(4, env.audioFiles())
        assertEquals(0, env.progress.saved.getValue("book").offset)
        val first = env.player.started.single()
        env.player.finish()
        runCurrent()
        assertEquals(2, env.player.started.size)
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
        env.player.finish()
        runCurrent()
        assertEquals(SpeechError.Network, env.session.state.value.error)
        val expected = speechSegments(env.text)[1]
        assertEquals(expected.start, env.progress.saved.getValue("book").offset)
        fail = false
        env.session.resume()
        runCurrent()
        assertEquals(expected.text, env.player.started.last().segment.text)
        assertEquals(1, env.player.started.last().index)
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

    @Test fun enteringABookResumesItsListeningChapterIndependentlyOfTheReader() = runTest {
        val env = Environment(this)
        val chapter = SpeechChapter("book", "two", "Book", "Chapter two", env.text)
        val segment = speechSegments(env.text)[2]
        env.progress.saved["book"] = SpeechBookmark("book", "two", chapter.fingerprint, segment.start)
        try {
            env.session.start(SpeechRequest("book", "one"))
            runCurrent()
            val clip = env.player.started.single()
            assertEquals("two", clip.chapter.id)
            assertEquals(segment.start, clip.segment.start)
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
        env.session.pause()
        env.configuration = SpeechSettings(voice = "new-voice")
        env.session.updateSettings(env.configuration)
        runCurrent()
        assertEquals(index, env.player.started.last().index)
        assertEquals(SpeechPhase.Paused, env.session.state.value.phase)
        assertEquals("new-voice", env.opened.last().voice)
        env.stop()
    }

    @Test fun changingVoiceAfterTextChangesDoesNotSkipNewText() = runTest {
        val env = Environment(this)
        env.session.start(SpeechRequest("book", "one"))
        runCurrent()
        env.player.finish()
        runCurrent()
        assertTrue(env.player.started.last().index > 0)
        env.load = { book, chapter -> SpeechChapter(book, chapter, "Book", "Edited", "New introduction. " + env.text) }
        env.session.reloadSettings()
        runCurrent()
        assertEquals(0, env.player.started.last().index)
        assertTrue(env.player.started.last().segment.text.startsWith("New introduction."))
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
            assertEquals(listOf(env.configuration), env.opened)
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
            assertEquals(env.configuration, env.opened.last())
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
            assertEquals(2f, env.opened.last().playbackSpeed)
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
                assertEquals(env.configuration, env.opened.last())
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
            assertEquals(selected, env.opened.last())
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

    private inner class Environment(val scope: TestScope) {
        val text = "这是正常的小说内容，保留顺序并且持续朗读。".repeat(100)
        var load: suspend (String, String) -> SpeechChapter = { book, chapter -> SpeechChapter(book, chapter, "Book", "Chapter", text) }
        var beforeSynthesis: suspend () -> Unit = {}
        var beforeSettings: suspend () -> Unit = {}
        var onCancel: () -> Unit = {}
        var onClose: () -> Unit = {}
        var timings = emptyList<SpeechTiming>()
        var configuration = SpeechSettings()
        val synthesized = mutableListOf<String>()
        val opened = mutableListOf<SpeechSettings>()
        val directory = files.newFolder()
        val progress = MemoryProgress()
        val player = FakePlayback()
        val session = ReadAloudSession(scope, SpeechChapterSource { book, chapter -> load(book, chapter) },
            { val snapshot = configuration; beforeSettings(); snapshot }, progress, {
                object : SpeechSynthesizer {
                    override suspend fun open(settings: SpeechSettings): String { opened += settings; return "Test voice" }
                    override suspend fun synthesize(text: String, output: File): List<SpeechTiming> {
                        synthesized += text
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
