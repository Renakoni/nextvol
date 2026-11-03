package indi.renakoni.nextvol.tts

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BackgroundMusicPlaybackTest {
    private val book = SpeechRequest("book", "chapter")
    private val tracks = listOf("rain", "piano", "forest").map { BackgroundMusicTrack(it, it, selected = true) }

    @Test fun selectedTracksPlayOncePerRoundAndVolumeChangesKeepTheCurrentTrack() {
        val env = Environment()
        env.playback.use { playback ->
            val settings = BackgroundMusicSettings(tracks, enabled = true)
            playback.updateSettings(settings)
            playback.updateSession(book, SpeechPhase.Preparing)
            assertTrue(env.loaded.isEmpty())
            playback.updateSession(book, SpeechPhase.Playing)
            repeat(5) { env.listener.captured.onPlaybackStateChanged(Player.STATE_ENDED) }
            assertEquals(listOf(tracks.map { it.id }.toSet(), tracks.map { it.id }.toSet()),
                env.loaded.chunked(3).map { it.toSet() })
            playback.updateSettings(settings.copy(volume = 0.2f))
            assertEquals(0.2f, env.volume, 0f)
            assertEquals(6, env.loaded.size)
            assertTrue(env.ready)
            playback.updateSettings(settings.copy(enabled = false))
            assertNull(env.currentMediaId)
            playback.updateSettings(settings)
            assertTrue(env.ready)
        }
    }

    @Test fun deletingTheCurrentTrackAdvancesAndBrokenTracksWaitForRepair() {
        val env = Environment()
        env.playback.use { playback ->
            playback.updateSettings(BackgroundMusicSettings(tracks, enabled = true))
            playback.updateSession(book, SpeechPhase.Playing)
            val deleted = env.loaded.single()
            val kept = tracks.filterNot { it.id == deleted }
            playback.updateSettings(BackgroundMusicSettings(kept, enabled = true))
            assertEquals(2, env.loaded.size)
            assertNotEquals(deleted, env.loaded.last())
            repeat(kept.size) { env.listener.captured.onPlayerError(mockk()) }
            assertEquals(kept.map { it.id }.toSet(), env.unavailable.toSet())
            assertNull(env.currentMediaId)
            val attempted = env.loaded.toList()
            playback.updateSettings(BackgroundMusicSettings(kept, volume = 0.3f, enabled = true))
            playback.setSuppressed(true)
            playback.setSuppressed(false)
            assertEquals(attempted, env.loaded)
            val repaired = kept.first().copy(id = "repaired")
            playback.updateSettings(BackgroundMusicSettings(listOf(repaired) + kept.drop(1), enabled = true))
            assertEquals(repaired.id, env.loaded.last())
            assertTrue(env.ready)
        }
    }

    @Test fun oneChannelFollowsSessionPausesPreviewsFocusAndBookReplacement() {
        val env = Environment()
        env.playback.use { playback ->
            playback.updateSettings(BackgroundMusicSettings(tracks.take(1), enabled = true))
            playback.updateSession(book, SpeechPhase.Playing)
            playback.updateSession(book, SpeechPhase.Buffering)
            assertTrue(env.ready)
            playback.updateSession(book, SpeechPhase.Paused)
            assertFalse(env.ready)
            playback.updateSession(book, SpeechPhase.Playing)
            val preview = SpeechRequest("", "", previewText = "试听")
            playback.updateSession(preview, SpeechPhase.Preparing)
            playback.updateSession(preview, SpeechPhase.Playing)
            // The service restores the suspended book before forwarding the preview's terminal state.
            playback.updateSession(book, SpeechPhase.Preparing)
            playback.updateSession(book, SpeechPhase.Playing)
            assertEquals(1, env.loaded.size)
            assertTrue(env.ready)
            playback.setSuppressed(true)
            assertFalse(env.ready)
            playback.updateSession(book, SpeechPhase.Paused)
            playback.setSuppressed(false)
            assertFalse(env.ready)
            playback.updateSession(book, SpeechPhase.Playing)
            assertTrue(env.ready)
            val otherBook = SpeechRequest("other", "one")
            playback.updateSession(otherBook, SpeechPhase.Preparing)
            assertFalse(env.ready)
            playback.updateSession(otherBook, SpeechPhase.Playing)
            assertEquals(2, env.loaded.size)
            playback.updateSession(otherBook, SpeechPhase.Completed)
            assertFalse(env.ready)
        }
        verify(exactly = 1) { env.player.release() }
    }

    private class Environment {
        val listener = slot<Player.Listener>()
        val loaded = mutableListOf<String>()
        val unavailable = mutableListOf<String>()
        var currentMediaId: String? = null
        var ready = false
        var volume = 1f
        val player = mockk<Player>(relaxed = true) {
            every { addListener(capture(listener)) } returns Unit
            every { setMediaItem(any<MediaItem>()) } answers {
                currentMediaId = firstArg<MediaItem>().mediaId
                loaded += firstArg<MediaItem>().mediaId
            }
            every { clearMediaItems() } answers { currentMediaId = null }
            every { playWhenReady = any() } answers { ready = firstArg() }
            every { volume = any() } answers { this@Environment.volume = firstArg() }
        }
        val playback = BackgroundMusicPlayback(player, { File(it.id) }, unavailable::add)
    }
}
