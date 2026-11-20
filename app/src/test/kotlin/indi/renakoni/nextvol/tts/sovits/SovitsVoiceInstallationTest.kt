package indi.renakoni.nextvol.tts.sovits

import android.app.Application
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
import indi.renakoni.nextvol.ui.tts.VoiceDraft
import indi.renakoni.nextvol.ui.tts.SovitsOptionsDraft
import indi.renakoni.nextvol.ui.tts.from
import indi.renakoni.nextvol.ui.tts.applyTo
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

    @Test fun numberedImportsAndLegacyCodeEditsRetainTheirModelAndAllSynthesisOptions() = runBlocking {
        val root = files.newFolder()
        val repository = OfflineSpeechRepository(root)
        suspend fun install(version: String, character: String) = repository.installSovits(character) { stage ->
            val directory = File(stage, "CharacterModels/$version/$character")
            File(directory, "prompt_wav").mkdirs()
            File(directory, "prompt_wav/reference.wav").writeText(character)
            File(directory, "prompt_wav.json").writeText("""{"Normal":{"wav":"reference.wav","text":"参考原文","language":"zh"}}""")
            OfflineVoiceConfig("gpt-sovits", "$version.$character", sampleRate = 32000)
        }
        val first = install("v2ProPlus", "imported-first")
        val second = install("v2ProPlus", "imported-second")
        val pro = install("v2Pro", "imported-third")
        assertEquals(listOf("v2ProPlus-1", "v2ProPlus-2", "v2Pro-1"),
            listOf(first, second, pro).map { it.configuration!!.code })

        // Existing installations used their code as the model locator and had no parameter JSON.
        val legacy = first.copy(configuration = OfflineVoiceConfig("gpt-sovits", "v2ProPlus.imported-first"))
        val draft = VoiceDraft.from(legacy).copy(code = "my-voice-123", speed = "1.65",
            sovits = SovitsOptionsDraft("0.3", "42", "0.7", "1.8"))
        val saved = repository.save(draft.fields().applyTo(legacy))
        assertEquals(first.resourceReference, saved.resourceReference)
        val copied = repository.save(VoiceDraft.from(saved).copy(code = "copy-1").fields().applyTo(saved), copy = true)
        val reopened = OfflineSpeechRepository(root)
        val runtime = files.newFolder()
        listOf(saved, copied).forEach { edited ->
            reopened.acquire(edited.reference).use { lease ->
                assertEquals(edited.configuration!!.code, lease.configuration.code)
                assertEquals(32000, lease.configuration.sampleRate)
                val voice = installedSovitsVoice(lease.directory, lease.configuration, runtime)
                assertEquals("imported-first", voice.character)
                assertEquals("imported-first", voice.referenceAudio.readText())
                assertEquals(SovitsSynthesisOptions(1.65f, .3f, 42, .7f, 1.8f), voice.options)
                assertArrayEquals(floatArrayOf(.3f, 42f, .7f, 1.8f), voice.options.samplingValues(), 0f)
            }
        }
    }

    @Test fun cancelledPreparationIsDiscardedAndPublishedVoiceCanBeReopened() = runBlocking {
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
        repository.installSovits("Voice") { stage ->
            val character = File(stage, "CharacterModels/v2ProPlus/voice")
            File(character, "prompt_wav").mkdirs()
            File(character, "prompt_wav/reference.wav").writeText("reference")
            File(character, "prompt_wav.json").writeText("""{"Normal":{"wav":"reference.wav","text":"参考原文","language":"ja"}}""")
            configuration
        }
        val reopened = OfflineSpeechRepository(root)
        val runtime = files.newFolder()
        reopened.acquire(reopened.voices().single().reference).use { lease ->
            val voice = installedSovitsVoice(lease.directory, lease.configuration, runtime)
            assertEquals(GptSovitsVersion.V2ProPlus, voice.version)
            assertEquals("参考原文", voice.referenceText)
            assertEquals(SovitsReferenceLanguage.Japanese, voice.referenceLanguage)
            assertEquals("reference", voice.referenceAudio.readText())
        }
    }
}
