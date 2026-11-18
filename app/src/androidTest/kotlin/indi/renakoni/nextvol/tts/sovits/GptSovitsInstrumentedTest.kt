package indi.renakoni.nextvol.tts.sovits

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
import java.io.RandomAccessFile

@SdkSuppress(minSdkVersion = 29)
class GptSovitsInstrumentedTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun mandarinKeepsPhonesAlignedWithNormalizedCharacters() {
        val tokenizer = File(temporary.root, "roberta-wwm-ext-large-onnx/roberta_tokenizer/tokenizer.json")
        tokenizer.parentFile!!.mkdirs()
        tokenizer.writeText("""{"model":{"vocab":{"[CLS]":101,"[SEP]":102,"[UNK]":100,"书":1,"三":2,"!":3}}}""")
        val encoded = SovitsMandarin(context, temporary.root).encode("書３！")
        assertArrayEquals(longArrayOf(101, 1, 2, 3, 102), encoded.tokens)
        assertArrayEquals(longArrayOf(2, 2, 1), encoded.repeats)
        assertEquals(encoded.phones.size.toLong(), encoded.repeats.sum())
    }

    /** Opt-in acceptance with local weights; the test never downloads a model. */
    @Test fun installedVoiceWritesAChapter() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val character = args.getString("sovitsCharacter")
        assumeNotNull(character)
        val root = File(context.getExternalFilesDir(null), "sovits-test")
        val voice = GptSovitsVoice(root, GptSovitsVersion.valueOf(args.getString("sovitsVersion", "V2ProPlus")),
            requireNotNull(character), File(root, "reference.wav"), File(root, "reference.txt").readText().trim())
        val output = File(temporary.root, "chapter.wav")
        GptSovitsSynthesizer(context, voice).downloadChapter(
            SpeechChapter("book", "chapter", "", "", "欢迎收听。[[PAUSE:1500]]我们继续读书。"), output)
        RandomAccessFile(output, "r").use { wav ->
            assertEquals(0x52494646, wav.readInt())
            assertEquals(wav.length(), Integer.toUnsignedLong(Integer.reverseBytes(wav.readInt())) + 8)
            wav.seek(24)
            assertEquals(32000, Integer.reverseBytes(wav.readInt()))
            wav.seek(44)
            var silentFrames = 0
            var heardFirst = false
            var heardAfterPause = false
            while (wav.filePointer + 1 < wav.length()) {
                if (wav.readShort().toInt() == 0) silentFrames++ else {
                    if (heardFirst && silentFrames >= 48000) heardAfterPause = true
                    heardFirst = true
                    silentFrames = 0
                }
            }
            assertTrue("Expected two audible segments separated by the requested 1.5 s pause", heardAfterPause)
        }
    }
}
