package indi.renakoni.nextvol.tts.sovits

import indi.renakoni.nextvol.tts.runtime.installTestRuntime
import indi.renakoni.nextvol.tts.runtime.SpeechRuntimePack
import androidx.test.filters.SdkSuppress
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import indi.renakoni.nextvol.reader.ReaderLayoutTestActivity
import indi.renakoni.nextvol.tts.SpeechChapter
import indi.renakoni.nextvol.tts.SpeechSettings
import indi.renakoni.nextvol.tts.SpeechDebugEntryPoint
import indi.renakoni.nextvol.tts.SpeechPhase
import indi.renakoni.nextvol.tts.SpeechAction
import android.net.Uri
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@SdkSuppress(minSdkVersion = 29)
class GptSovitsInstrumentedTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Opt-in acceptance with converted local weights. */
    @Test fun installedVoiceWritesAChapter() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val character = args.getString("sovitsCharacter")
        assumeNotNull(character)
        if (args.getString("installSpeechRuntime") == "true") {
            for (pack in listOf(SpeechRuntimePack.Sovits, SpeechRuntimePack.SovitsModels))
                if (!pack.installed) installTestRuntime(pack)
        }
        val root = File(context.filesDir, "sovits-test")
        val voice = GptSovitsVoice(root, GptSovitsVersion.valueOf(args.getString("sovitsVersion", "V2ProPlus")),
            requireNotNull(character), File(root, "reference.wav"), File(root, "reference.txt").readText().trim())
        val output = File(temporary.root, "chapter.wav")
        val engine = GptSovitsSynthesizer(context, voice)
        val text = if (args.getString("sovitsExpectedProvider") == "QNN")
            "我们今天一起沿着河岸慢慢走回远处的小镇然后坐在树荫下面听爷爷讲一个很长很长的故事。"
        else "我们继续读书。"
        engine.downloadChapter(
            SpeechChapter("book", "chapter", "", "", "欢迎收听。[[PAUSE:1500]]$text"), output)
        args.getString("sovitsExpectedProvider")?.let { assertEquals(it, engine.lastProvider) }
        val bytes = output.readBytes()
        val samples = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var silence = 0
        var heardSpeech = false
        while (samples.hasRemaining()) {
            if (samples.get() == 0.toShort()) {
                silence++
            } else {
                if (heardSpeech && silence >= 48000) return@runBlocking
                heardSpeech = true
                silence = 0
            }
        }
        fail("Expected two audible segments separated by the requested 1.5 s pause")
    }

    /** Checkpoints and reference audio are supplied in files/sovits-import-test on the target device. */
    @Test fun checkpointImportPlaysThroughReader() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sovitsImport") == "true")
        DeviceAcceleration.detect(context)
        assertEquals(true, DeviceAcceleration.status.value.gpu)
        for (pack in listOf(SpeechRuntimePack.Sovits, SpeechRuntimePack.SovitsModels))
            if (!pack.installed) installTestRuntime(pack)
        val root = File(context.filesDir, "sovits-import-test")
        fun input(name: String) = SovitsImportFile(Uri.fromFile(File(root, name)), name)
        val reference = root.listFiles()!!.single { it.extension == "wav" }
        val entry = EntryPointAccessors.fromApplication(context, SpeechDebugEntryPoint::class.java)
        val voice = SovitsWeightImport(context, entry.offlineVoices()).install(SovitsImportDraft(
            name = "Roxy", gpt = input("Roxy3.0.ckpt"), sovits = input("Roxy3.0.pth"),
            audio = input(reference.name), text = reference.nameWithoutExtension,
        )) {}
        assertTrue(voice.configuration!!.code.matches(Regex("v2Pro(?:Plus)?-[1-9][0-9]*")))
        val controller = entry.controller()
        ActivityScenario.launch(ReaderLayoutTestActivity::class.java).use {
            try {
                controller.previewVoice("我们今天一起沿着河岸慢慢走回远处的小镇。",
                    SpeechSettings(offlineVoice = voice.reference), voice.name)
                var played = false
                val terminal = withTimeout(600_000L) {
                    controller.state.first {
                        if (it.phase == SpeechPhase.Playing) played = true
                        it.phase == SpeechPhase.Completed || it.phase == SpeechPhase.Failed
                    }
                }
                assertEquals(terminal.toString(), SpeechPhase.Completed, terminal.phase)
                assertTrue("Audio must reach the reader's playback stage", played)
            } finally { controller.command(SpeechAction.Stop) }
        }
    }
}
