package indi.renakoni.nextvol.tts.sovits

import android.app.Application
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
class SovitsMandarinTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun mandarinKeepsPhonesAlignedWithNormalizedCharacters() {
        val tokenizer = File(temporary.root, "roberta-wwm-ext-large-onnx/roberta_tokenizer/tokenizer.json")
        tokenizer.parentFile!!.mkdirs()
        tokenizer.writeText("""{"model":{"vocab":{"[CLS]":101,"[SEP]":102,"[UNK]":100,"书":1,"三":2,"!":3}}}""")
        val dictionary = File("../native/sovits/assets/gpt-sovits/chinese_g2p")
        val encoded = SovitsMandarin(dictionary, temporary.root).encode("你好，書３！")
        assertArrayEquals(longArrayOf(101, 100, 100, 100, 1, 2, 3, 102), encoded.tokens)
        assertArrayEquals(longArrayOf(2, 2, 1, 2, 2, 1), encoded.repeats)
        assertEquals(GenieSymbols.symbolToId.getValue("i2").toLong(), encoded.phones[1])
    }
}
