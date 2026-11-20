package indi.renakoni.nextvol.tts.sovits

import android.app.Application
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
import indi.renakoni.nextvol.ui.tts.VoiceDraft
import indi.renakoni.nextvol.ui.tts.from
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SovitsVoiceInstallationTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun cancelledPreparationIsInvisibleAndCompletedVoiceUsesNormalCopyAndLeaseLifecycle() = runBlocking {
        val root = files.newFolder()
        val repository = OfflineSpeechRepository(root)
        val started = CompletableDeferred<Unit>()
        val job = launch {
            repository.installSovits("Cancelled") { stage ->
                File(stage, "unfinished.bin").writeText("partial")
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        assertTrue(repository.voices().isEmpty())
        job.cancelAndJoin()
        assertTrue(root.listFiles()!!.isEmpty())
        val configuration = OfflineVoiceConfig("gpt-sovits", "v2ProPlus.voice", locale = "zh-CN", sampleRate = 32000)
        val installed = repository.installSovits("Voice") { stage ->
            val character = File(stage, "CharacterModels/v2ProPlus/voice")
            File(character, "prompt_wav").mkdirs()
            File(character, "prompt_wav/reference.wav").writeText("reference")
            File(character, "prompt_wav.json").writeText("""{"Normal":{"wav":"reference.wav","text":"参考原文"}}""")
            configuration
        }
        assertEquals(installed, OfflineSpeechRepository(root).voices().single())
        assertEquals(configuration.code, VoiceDraft.from(installed).fields().code)
        val lease = repository.acquire(installed.reference)
        val runtime = files.newFolder()
        val voice = installedSovitsVoice(lease.directory, lease.configuration, runtime)
        assertEquals(GptSovitsVersion.V2ProPlus, voice.version)
        assertEquals(runtime, voice.runtime)
        assertEquals("参考原文", voice.referenceText)
        assertEquals("reference", voice.referenceAudio.readText())
        val copy = repository.save(installed.copy(name = "Copy"), copy = true)
        assertEquals(installed.resourceReference, copy.resourceReference)
        assertThrows(IllegalArgumentException::class.java) {
            installedSovitsVoice(lease.directory, configuration.copy(code = "v5turbo.voice"), runtime)
        }
        repository.delete(setOf(installed.reference, copy.reference))
        assertTrue(repository.voices().isEmpty())
        assertTrue(voice.referenceAudio.isFile)
        lease.close()
        assertFalse(voice.referenceAudio.exists())
    }
}
