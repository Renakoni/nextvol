package indi.renakoni.nextvol.tts.sovits

import indi.renakoni.nextvol.tts.runtime.installTestRuntime
import indi.renakoni.nextvol.tts.runtime.SpeechRuntimePack
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import indi.renakoni.nextvol.tts.SpeechChapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
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

    /** Opt-in acceptance with local weights; the test never downloads a model. */
    @Test fun installedVoiceWritesAChapter() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val character = args.getString("sovitsCharacter")
        assumeNotNull(character)
        if (args.getString("installSpeechRuntime") == "true")
            installTestRuntime(SpeechRuntimePack.Sovits)
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
}
