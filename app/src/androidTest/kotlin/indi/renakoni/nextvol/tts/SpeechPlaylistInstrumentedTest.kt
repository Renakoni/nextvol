package indi.renakoni.nextvol.tts

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import indi.renakoni.nextvol.reader.ReaderLayoutTestActivity
import indi.renakoni.nextvol.tts.audio.SpeechWaveFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

class SpeechPlaylistInstrumentedTest {
    @Test fun readyFilesTransitionInOrderAndChapterTimerWithdrawsTheFollowingChapter() = ActivityScenario.launch(ReaderLayoutTestActivity::class.java).use {
        runBlocking(Dispatchers.Main.immediate) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(context.cacheDir, "playlist-${UUID.randomUUID()}")
            val player = ExoPlayer.Builder(context).build()
            val files = mutableMapOf<String, String>()
            val transitions = mutableListOf<String>()
            val followingChapterQueued = CompletableDeferred<Unit>()
            player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) transitions += files.getValue(mediaItem!!.mediaId)
                }
                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    if (player.mediaItemCount > 1 && files[player.getMediaItemAt(player.mediaItemCount - 1).mediaId] == "丁丁")
                        followingChapterQueued.complete(Unit)
                }
            })
            val chapters = listOf("甲甲乙乙", "丙丙", "丁丁")
            val synth = object : SpeechSynthesizer {
                override val maxInputLength = 2
                override suspend fun open(settings: SpeechSettings) = "Tone"
                override suspend fun synthesize(text: String, output: File): List<SpeechTiming> {
                    val frequency = 400 + 200 * files.size
                    files[output.name] = text
                    SpeechWaveFile(output, 16000).use { wav ->
                        wav.append(ShortArray(16000) { (3000 * sin(2 * PI * frequency * it / 16000)).toInt().toShort() })
                        wav.finish()
                    }
                    return emptyList()
                }
                override fun close() = Unit
                override fun cancel() = Unit
            }
            var saved: SpeechBookmark? = null
            val session = ReadAloudSession(this, SpeechChapterSource { book, id ->
                val index = id.toInt()
                SpeechChapter(book, id, "", id, chapters[index], nextId = (index + 1).takeIf { it < chapters.size }?.toString())
            }, { SpeechSettings() }, object : SpeechProgressStore {
                override suspend fun load(bookId: String) = saved
                override suspend fun save(bookmark: SpeechBookmark) {
                    assertNotEquals(saved, bookmark)
                    saved = bookmark
                }
            }, { synth }, ExoSpeechPlayback(player, context), directory)
            try {
                withTimeout(15_000) {
                    session.start(SpeechRequest("book", "0"))
                    session.state.first { it.currentText == "丙丙" && it.phase == SpeechPhase.Playing }
                    session.pause()
                    followingChapterQueued.await()
                    session.setChapterSleepTimer(1)
                    session.resume()
                    session.state.first { it.phase == SpeechPhase.Stopped }
                }
                assertEquals(listOf("乙乙", "丙丙"), transitions)
                assertEquals("1" to 2, saved?.let { it.chapterId to it.offset })
            } finally {
                session.stop()
                player.release()
                directory.deleteRecursively()
            }
        }
    }
}
