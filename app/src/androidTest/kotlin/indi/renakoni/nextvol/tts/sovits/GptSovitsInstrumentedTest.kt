package indi.renakoni.nextvol.tts.sovits

import indi.renakoni.nextvol.tts.runtime.installTestRuntime
import indi.renakoni.nextvol.tts.runtime.SpeechRuntimePack
import androidx.test.filters.SdkSuppress
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import indi.renakoni.nextvol.reader.ReaderLayoutTestActivity
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

@SdkSuppress(minSdkVersion = 29)
class GptSovitsInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

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
