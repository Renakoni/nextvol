package indi.renakoni.nextvol.tts.sovits

import android.app.Application
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class SovitsMandarinTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var mandarin: SovitsMandarin

    @Before fun loadMandarin() {
        val tokenizer = File(temporary.root, "roberta-wwm-ext-large-onnx/roberta_tokenizer/tokenizer.json")
        tokenizer.parentFile!!.mkdirs()
        tokenizer.writeText("""{"model":{"vocab":{"[CLS]":101,"[SEP]":102,"[UNK]":100,"书":1,"三":2,"!":3}}}""")
        val dictionary = File("../native/sovits/assets/gpt-sovits/chinese_g2p")
        mandarin = SovitsMandarin(dictionary, temporary.root)
    }

    @Test fun mandarinKeepsPhonesAlignedWithNormalizedCharacters() {
        val encoded = mandarin.encode("你好，書３！")
        assertArrayEquals(longArrayOf(101, 100, 100, 100, 1, 2, 3, 102), encoded.tokens)
        assertArrayEquals(longArrayOf(2, 2, 1, 2, 2, 1), encoded.repeats)
        assertEquals(GenieSymbols.symbolToId.getValue("i2").toLong(), encoded.phones[1])
    }

    @Test fun mixedTextKeepsEnglishLetterNamesBetweenMandarinFeatureSpans() {
        val english = temporary.newFolder("english")
        File(english, "dictionary.tsv").writeText("ay\tEY1\ni\tAY1\n")
        SovitsFrontend({ mandarin }, temporary.root).use { frontend ->
            val mixed = frontend.encode("她说 ＡＩ 重 3kg")
            val left = mandarin.encode("她说")
            val right = mandarin.encode("重三千克")
            val letters = listOf("EY1", "AY1").map { GenieSymbols.symbolToId.getValue(it).toLong() }.toLongArray()
            assertArrayEquals(left.phones + letters + right.phones, mixed.phones)
            assertArrayEquals(longArrayOf(left.tokens.size.toLong(), -2, right.tokens.size.toLong()), mixed.bertRuns)
            assertArrayEquals(left.tokens + right.tokens, mixed.tokens)
            assertArrayEquals(left.repeats + right.repeats, mixed.repeats)
        }
    }
}
