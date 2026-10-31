package indi.renakoni.nextvol.tts

import android.app.ActivityManager
import android.content.ComponentName
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import indi.renakoni.nextvol.reader.ReaderLayoutTestActivity
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SystemSpeechInstrumentedTest {
    /** Run with speechEngine on a dedicated test device; exercises the actual service and media session. */
    @Suppress("DEPRECATION")
    @Test fun serviceHandlesTemporaryAndPermanentFocusLossAndStopReleasesItsResources() = runBlocking {
        val engine = InstrumentationRegistry.getArguments().getString("speechEngine")
        assumeTrue("Supply speechEngine for the separate real-engine acceptance run", !engine.isNullOrEmpty())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = UserDataRepository(NextVolDatabase.getInstance(context).userDataDao())
        val stored = data.stringUserData("tts.settings")
        val original = withContext(Dispatchers.IO) { stored.get() }
        val activity = ActivityScenario.launch(ReaderLayoutTestActivity::class.java)
        val commands = ReadAloudController(context)
        val audio = context.getSystemService(AudioManager::class.java)
        val competingFocus = AudioManager.OnAudioFocusChangeListener { }
        val activities = context.getSystemService(ActivityManager::class.java)
        fun service() = activities.getRunningServices(Int.MAX_VALUE)
            .find { it.service.className == ReadAloudService::class.java.name }
        suspend fun awaitState(condition: () -> Boolean) = withTimeout(10_000) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(50)
        }
        var media: MediaController? = null
        try {
            withContext(Dispatchers.IO) {
                stored.set(Json.encodeToString(SpeechSettings(engine = engine!!, rate = 0.7f)))
            }
            withContext(Dispatchers.Main) {
                commands.preview("A quiet morning begins a new chapter of our story. ".repeat(12))
            }
            val pending = withContext(Dispatchers.Main) {
                MediaController.Builder(context, SessionToken(context, ComponentName(context, ReadAloudService::class.java)))
                    .buildAsync()
            }
            val player = withContext(Dispatchers.IO) { pending.get(15, TimeUnit.SECONDS) }.also { media = it }
            awaitState { player.isPlaying && service()?.foreground == true }

            for (gain in listOf(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)) {
                withContext(Dispatchers.Main) {
                    assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                        audio.requestAudioFocus(competingFocus, AudioManager.STREAM_MUSIC, gain))
                }
                // Spoken content pauses for ducking too, then resumes when transient focus returns.
                awaitState { !player.isPlaying && player.playWhenReady }
                assertTrue(service()?.foreground == true)
                withContext(Dispatchers.Main) { audio.abandonAudioFocus(competingFocus) }
                awaitState { player.isPlaying }
            }

            withContext(Dispatchers.Main) {
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                    audio.requestAudioFocus(competingFocus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN))
            }
            awaitState { !player.isPlaying && !player.playWhenReady && service()?.foreground == false }
            withContext(Dispatchers.Main) { audio.abandonAudioFocus(competingFocus) }
            delay(300)
            withContext(Dispatchers.Main) {
                assertFalse("Permanent focus loss must wait for an explicit resume", player.playWhenReady)
                commands.command(SpeechAction.Resume)
            }
            awaitState { player.isPlaying && service()?.foreground == true }
            withContext(Dispatchers.Main) {
                commands.command(SpeechAction.Stop)
                player.release()
                media = null
            }
            awaitState { service() == null }
            awaitState { File(context.cacheDir, "read-aloud").listFiles().orEmpty().isEmpty() }
        } finally {
            withContext(Dispatchers.Main) {
                audio.abandonAudioFocus(competingFocus)
                commands.command(SpeechAction.Stop)
                media?.release()
            }
            awaitState { service() == null }
            withContext(Dispatchers.IO) {
                if (original == null) data.remove("tts.settings") else stored.set(original)
            }
            activity.close()
        }
    }

    @Test fun missingEngineIsReportedBeforeAnyAudioIsCreated() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engines = SystemSpeechEngines(context)
        val expected = if (engines.installed().isEmpty()) SpeechError.NoEngine else SpeechError.EngineUnavailable
        val provider = engines.synthesizer()
        try {
            try {
                provider.open(SpeechSettings(engine = "invalid.nextvol.speech.engine"))
                fail("A missing engine must not fall back to a different engine")
            } catch (failure: SpeechException) {
                assertEquals(expected, failure.error)
            }
        } finally { provider.close() }
    }

    /** Optional real-engine check: -e speechEngine com.reecedunn.espeak. Never installs an engine. */
    @Test fun installedEngineCreatesRealAudioAndPlaybackCanPauseResumeAndFinish() = runBlocking {
        val engine = InstrumentationRegistry.getArguments().getString("speechEngine")
        assumeTrue("Supply speechEngine for the separate real-engine acceptance run", !engine.isNullOrEmpty())
        withTimeout(60_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val engines = SystemSpeechEngines(context)
            assertTrue(engines.installed().any { it.packageName == engine })
            val catalog = engines.inspect(engine!!)
            assertTrue(catalog.voices.isNotEmpty())
            val provider = engines.synthesizer()
            val directory = File(context.cacheDir, "speech-device-test-${UUID.randomUUID()}").apply { mkdirs() }
            val audio = File(directory, "preview.wav")
            val activity = ActivityScenario.launch(ReaderLayoutTestActivity::class.java)
            val player = withContext(Dispatchers.Main) {
                ExoPlayer.Builder(context).build().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
                }
            }
            val playback = ExoSpeechPlayback(player, context)
            try {
                val voice = provider.open(SpeechSettings(engine = engine))
                assertTrue(voice.isNotBlank())
                val text = "The rain has stopped. A quiet morning opens a new chapter of our story."
                provider.synthesize(text, audio)
                validateSpeechWav(audio)
                assertTrue("Engine output must contain non-silent samples", audio.readBytes().drop(44).any { it.toInt() != 0 })
                assertFalse(File(directory, "preview.wav.part").exists())
                val started = CompletableDeferred<Unit>()
                val chapter = SpeechChapter("", "", "", "Preview", text)
                val clip = SpeechClip(prepareSpeechPlan(chapter, SpeechSettings()) {}, 0, audio)
                val playing = launch(Dispatchers.Main) {
                    playback.play(clip, true, onRange = {}) { phase, _ -> if (phase == SpeechPhase.Playing) started.complete(Unit) }
                }
                started.await()
                withContext(Dispatchers.Main) { playback.pause() }
                delay(150)
                val pausedAt = withContext(Dispatchers.Main) { player.currentPosition }
                delay(300)
                withContext(Dispatchers.Main) {
                    assertFalse(player.isPlaying)
                    assertTrue(kotlin.math.abs(player.currentPosition - pausedAt) < 30)
                    playback.resume()
                }
                playing.join()
                withContext(Dispatchers.Main) { assertEquals(androidx.media3.common.Player.STATE_ENDED, player.playbackState) }
            } finally {
                provider.close()
                withContext(Dispatchers.Main) { playback.stop(); player.release() }
                activity.close()
                directory.deleteRecursively()
            }
        }
    }
}
