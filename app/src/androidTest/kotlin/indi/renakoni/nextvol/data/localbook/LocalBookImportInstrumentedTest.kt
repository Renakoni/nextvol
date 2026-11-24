package indi.renakoni.nextvol.data.localbook

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.Charset

@RunWith(AndroidJUnit4::class)
class LocalBookImportInstrumentedTest {
    @Test fun androidCharsetsKeepChineseHeadingsAndBodyText() {
        val samples = listOf(
            "GB18030" to "第一章开端\n简体中文𠀀内容\n第二章旅途\n完整正文",
            "Big5" to "第一章開端\n繁體中文內容\n第二章旅途\n完整正文",
            "UTF-16LE" to "第一章开端\n中文与😀\n第二章旅途\n完整正文",
            "UTF-16BE" to "第一章开端\n中文与😀\n第二章旅途\n完整正文",
        )
        for ((encoding, text) in samples) {
            val chapters = mutableListOf<LocalBookChapter>()
            TxtBookParser.parse({ text.byteInputStream(Charset.forName(encoding)) }, "Book", encoding, onChapter = { chapters += it })
            assertEquals(encoding, 2, chapters.size)
            assertEquals(text.lineSequence().elementAt(1), (chapters.first().blocks.single() as LocalBookBlock.Text).value)
        }
    }
}
