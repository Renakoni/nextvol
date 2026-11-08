package indi.renakoni.nextvol.tts

import android.app.Application
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpeechInterruptionTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun disconnectingHeadphonesDuringSynthesisPausesBeforeTheFirstAudioArrives() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val player = ExoPlayer.Builder(context).build()
        val playbackLooper = shadowOf(player.playbackLooper)
        // Media3 registers the noisy receiver on its playback Looper. Hold that queue
        // so this test also covers registration delayed beyond the coroutine startup.
        playbackLooper.pause()
        player.setHandleAudioBecomingNoisy(true)
        val gate = CompletableDeferred<Unit>()
        lateinit var session: ReadAloudSession
        val playback = ExoSpeechPlayback(player, context) { session.interrupt() }
        session = ReadAloudSession(this,
            SpeechChapterSource { book, chapter -> SpeechChapter(book, chapter, "Book", "Chapter", "A quiet morning.") },
            { SpeechSettings() }, object : SpeechProgressStore {
                override suspend fun load(bookId: String): SpeechBookmark? = null
                override suspend fun save(bookmark: SpeechBookmark) = Unit
            }, {
                object : SpeechSynthesizer {
                    override suspend fun open(settings: SpeechSettings) = "Test voice"
                    override suspend fun synthesize(text: String, output: File): List<SpeechTiming> {
                        gate.await()
                        output.writeText(text)
                        return emptyList()
                    }
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            }, playback, files.newFolder(), StandardTestDispatcher(testScheduler))
        try {
            session.start(SpeechRequest("book", "chapter"))
            runCurrent()
            assertEquals(SpeechPhase.Preparing, session.state.value.phase)
            assertFalse(shadowOf(context).registeredReceivers.any {
                it.intentFilter.hasAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            })
            playbackLooper.idle()
            assertTrue("The real receiver must be registered before sending a system broadcast",
                shadowOf(context).registeredReceivers.any {
                    it.intentFilter.hasAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                })
            playbackLooper.unPause()
            context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            assertEquals("Disconnecting during synthesis must pause the listening session", SpeechPhase.Paused, session.state.value.phase)
            gate.complete(Unit)
            runCurrent()
            assertFalse("Late audio must not start on the speaker", player.playWhenReady)
            assertEquals(SpeechPhase.Paused, session.state.value.phase)
            session.resume()
            assertTrue("An explicit resume still starts the prepared audio", player.playWhenReady)
        } finally {
            session.stop()
            gate.complete(Unit)
            runCurrent()
            playbackLooper.unPause()
            player.release()
        }
    }

    @Test fun systemInterruptionsBetweenChaptersKeepTheNextAudioPaused() = runTest {
        for (reason in listOf(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)) {
            val listeners = mutableSetOf<Player.Listener>()
            var ready = false
            var state = Player.STATE_IDLE
            val player = mockk<ExoPlayer>(relaxed = true) {
                every { addListener(any()) } answers { listeners += firstArg<Player.Listener>() }
                every { removeListener(any()) } answers { listeners -= firstArg<Player.Listener>() }
                every { playWhenReady } answers { ready }
                every { playWhenReady = any() } answers { ready = firstArg() }
                every { playbackState } answers { state }
                every { isPlaying } answers { ready && state == Player.STATE_READY }
                every { playbackSuppressionReason } returns Player.PLAYBACK_SUPPRESSION_REASON_NONE
                every { duration } returns 1000L
                every { pause() } answers { ready = false }
                every { play() } answers { ready = true }
            }
            val gate = CompletableDeferred<Unit>()
            lateinit var session: ReadAloudSession
            val playback = ExoSpeechPlayback(player, RuntimeEnvironment.getApplication()) { session.interrupt() }
            session = ReadAloudSession(this, SpeechChapterSource { book, chapter ->
                if (chapter == "two") gate.await()
                SpeechChapter(book, chapter, "Book", chapter, "A quiet morning.", nextId = if (chapter == "one") "two" else null)
            }, { SpeechSettings() }, object : SpeechProgressStore {
                override suspend fun load(bookId: String): SpeechBookmark? = null
                override suspend fun save(bookmark: SpeechBookmark) = Unit
            }, {
                object : SpeechSynthesizer {
                    override suspend fun open(settings: SpeechSettings) = "Test voice"
                    override suspend fun synthesize(text: String, output: File): List<SpeechTiming> {
                        output.writeText(text)
                        return emptyList()
                    }
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            }, playback, files.newFolder(), StandardTestDispatcher(testScheduler))
            try {
                session.start(SpeechRequest("book", "one"))
                runCurrent()
                state = Player.STATE_READY
                listeners.toList().forEach { it.onEvents(player, mockk()) }
                assertEquals(SpeechPhase.Playing, session.state.value.phase)
                listeners.toList().forEach { it.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) }
                assertEquals("A temporary suppression must retain the resume intent", SpeechPhase.Playing, session.state.value.phase)
                state = Player.STATE_ENDED
                listeners.toList().forEach { it.onEvents(player, mockk()) }
                runCurrent()
                assertEquals(SpeechPhase.Buffering, session.state.value.phase)
                ready = false
                listeners.toList().forEach { it.onPlayWhenReadyChanged(false, reason) }
                assertEquals(SpeechPhase.Paused, session.state.value.phase)
                gate.complete(Unit)
                runCurrent()
                assertEquals("two", session.state.value.request?.chapterId)
                assertFalse("The next chapter must preserve a system pause", ready)
                assertEquals(SpeechPhase.Paused, session.state.value.phase)
                session.resume()
                assertTrue(ready)
                session.stop()
                listeners.toList().forEach { it.onPlayWhenReadyChanged(false, reason) }
                assertEquals(SpeechPhase.Stopped, session.state.value.phase)
                assertNull(session.state.value.position)
            } finally {
                session.stop()
                gate.complete(Unit)
                runCurrent()
            }
        }
    }
}
