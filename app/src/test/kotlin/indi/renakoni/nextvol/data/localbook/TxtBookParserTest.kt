package indi.renakoni.nextvol.data.localbook

import com.google.re2j.PatternSyntaxException
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset

internal fun parseTxt(bytes: ByteArray, title: String = "Example", encoding: String? = null,
    rule: String = TxtBookParser.DEFAULT_RULE): ParsedLocalBook {
    val chapters = mutableListOf<LocalBookChapter>()
    val detected = TxtBookParser.parse({ bytes.inputStream() }, title, encoding, rule, onChapter = { chapters += it })
    return ParsedLocalBook(title, chapters, encoding = detected)
}

class TxtBookParserTest {
    private fun parse(text: String, rule: String = TxtBookParser.DEFAULT_RULE) =
        parseTxt(text.toByteArray(), rule = rule)

    private fun body(book: ParsedLocalBook) = book.chapters.flatMap { it.blocks }
        .filterIsInstance<LocalBookBlock.Text>().joinToString("\n") { it.value }

    @Test fun shortAdjacentChaptersDoNotRequireWhitespaceAfterTheirNumber() {
        val book = parse("第一章开端\n甲\n第２章旅程\n乙\n第叁章 终点\n丙")
        assertEquals(listOf("第一章开端", "第２章旅程", "第叁章 终点"), book.chapters.map { it.title })
        assertEquals("甲\n乙\n丙", body(book))
    }

    @Test fun volumeImmediatelyBeforeItsFirstChapterKeepsBothBoundaries() {
        val book = parse("第一卷 起点\n第一章开端\n甲\n第二章\n乙\n第二卷 归途\n第一章\n丙")
        assertEquals(listOf("第一章开端", "第二章", "第一章"), book.chapters.map { it.title })
        assertEquals(listOf("第一卷 起点", "第一卷 起点", "第二卷 归途"), book.chapters.map { it.volume })
        assertEquals("甲\n乙\n丙", body(book))
    }

    @Test fun prefaceAndSpecialChaptersAreKeptAsReadingContent() {
        val book = parse("开篇说明\n第一章\n正文\n番外 旅途\n番外正文\n后记 作者的话\n后记正文")
        assertEquals(listOf("Example", "第一章", "番外 旅途", "后记 作者的话"), book.chapters.map { it.title })
        assertEquals("开篇说明\n正文\n番外正文\n后记正文", body(book))
    }

    @Test fun ordinaryLessonAndReturnSentencesStayInTheBody() {
        val book = parse("第一章 开始\n第一节课开始了。\n第一回来的人站在那里。\n第一部分已经完成。\n第二章 结束\n结尾")
        assertEquals(2, book.chapters.size)
        assertTrue(body(book).contains("第一节课开始了。"))
        assertTrue(body(book).contains("第一回来的人站在那里。"))
        assertTrue(body(book).contains("第一部分已经完成。"))
    }

    @Test fun englishRomanNumeralsAndEpilogueAreRecognized() {
        val book = parse("Chapter I The beginning\nFirst.\nCHAPTER II: The road\nSecond.\nEpilogue\nLast.")
        assertEquals(3, book.chapters.size)
        assertEquals("First.\nSecond.\nLast.", body(book))
    }

    @Test fun unmatchedTextIsStillACompleteBook() {
        val book = parse("普通正文\n下一段\n最后一段")
        assertEquals(listOf("Example"), book.chapters.map { it.title })
        assertEquals("普通正文\n下一段\n最后一段", body(book))
    }

    @Test fun aCustomLineRuleChangesThePreviewWithoutRemovingText() {
        val book = parse("导言\n1. 开始\n正文甲\n2. 结束\n正文乙", "^[0-9]+[.] .+$")
        assertEquals(listOf("Example", "1. 开始", "2. 结束"), book.chapters.map { it.title })
        assertEquals("导言\n正文甲\n正文乙", body(book))
        assertEquals("第一章\n正文", body(parse("第一章\n正文", "")))
    }

    @Test fun unsupportedAndInvalidRulesFailBeforeImport() {
        for (rule in listOf("(", "(?<=chapter) [0-9]+", "(a)\\1")) {
            assertThrows(PatternSyntaxException::class.java) { parse("正文", rule) }
        }
    }

    @Test fun utf8AndUtf16ByteOrderMarksAreHandledBeforeOtherEncodings() {
        val text = "第一章 开始\r\n完整正文"
        val cases = listOf(
            "UTF-8" to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            "UTF-16LE" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
            "UTF-16BE" to byteArrayOf(0xFE.toByte(), 0xFF.toByte()),
        )
        for ((encoding, prefix) in cases) {
            val decoded = parseTxt(prefix + text.toByteArray(Charset.forName(encoding)), rule = "")
            assertEquals(encoding, decoded.encoding)
            assertEquals(text.replace("\r\n", "\n"), body(decoded))
        }
        assertEquals(text.replace("\r\n", "\n"), body(parseTxt(text.toByteArray(), rule = "")))
    }

    @Test fun gb18030AndBig5CanBeDetectedAndExplicitlySelected() {
        val cases = listOf(
            "GB18030" to "这是一本中文小说，第一章介绍人物，第二章开始旅程。读者可以阅读完整正文。\n".repeat(40),
            "Big5" to "這是一本繁體中文小說，第一章介紹人物，第二章開始旅程。讀者可以閱讀完整正文。\n".repeat(40),
        )
        for ((encoding, text) in cases) {
            val bytes = text.toByteArray(Charset.forName(encoding))
            assertEquals(text.trimEnd('\n'), body(parseTxt(bytes, encoding = encoding, rule = "")))
            assertEquals(text.trimEnd('\n'), body(parseTxt(bytes, rule = "")))
        }
    }

    @Test fun explicitUtf16WithoutBomWorksAndMalformedInputIsNotReplaced() {
        val text = "完整正文"
        assertEquals(text, body(parseTxt(text.toByteArray(Charsets.UTF_16LE), encoding = "UTF-16LE")))
        assertThrows(CharacterCodingException::class.java) {
            parseTxt(byteArrayOf(0xE4.toByte(), 0xB8.toByte()), encoding = "UTF-8")
        }
        assertThrows(IllegalArgumentException::class.java) { parse("\n\t ") }
    }

    @Test fun detectedShiftJisAndTextWithFormFeedsAreAccepted() {
        val text = "これは日本語の小説です。登場人物たちは長い旅に出かけました。読者は物語を楽しみます。".repeat(40)
        val bytes = text.toByteArray(Charset.forName("Shift_JIS"))
        val automatic = parseTxt(bytes)
        assertEquals("Shift_JIS", automatic.encoding)
        assertEquals(text, body(automatic))
        assertEquals(text, body(parseTxt(bytes, encoding = "Shift_JIS")))
        assertEquals("正文\u000c下一页\u0000结尾", body(parse("正文\u000c下一页\u0000结尾")))
    }

    @Test fun chunkBoundariesAndLongLogicalLinesPreserveTextAndHeadingRules() {
        val prefix = "甲".repeat(8191) + "😀" + "乙".repeat(TxtBookParser.MAX_CHAPTER_CHARACTERS - 8194) +
            "😀" + "丙".repeat(TxtBookParser.MAX_CHAPTER_CHARACTERS)
        val text = prefix + "\r\n" + " ".repeat(70_000) + "Chapter II\t\r" + "尾文😀"
        val book = parse(text)
        assertEquals(prefix, book.chapters.dropLast(1).flatMap { it.blocks }.joinToString("") { (it as LocalBookBlock.Text).value })
        val blocks = book.chapters.flatMap { it.blocks }.filterIsInstance<LocalBookBlock.Text>()
        assertTrue(blocks.all { it.value.length <= TxtBookParser.MAX_CHAPTER_CHARACTERS &&
            it.value == it.value.toByteArray().toString(Charsets.UTF_8) })
        assertEquals("Chapter II", book.chapters.last().title)
        assertEquals("尾文😀", (book.chapters.last().blocks.single() as LocalBookBlock.Text).value)
    }

    @Test fun completedChaptersAreDeliveredBeforeTheRemainingBodyIsRead() {
        val text = buildString {
            repeat(12) { append("Chapter ${it + 1}\n").append("x".repeat(60_000)).append('\n') }
        }.toByteArray()
        val inputs = mutableListOf<java.io.ByteArrayInputStream>()
        var delivered = 0
        TxtBookParser.parse({ text.inputStream().also { inputs += it } }, "Book") { chapter ->
            if (delivered == 0) assertTrue(inputs.all { it.available() > 0 })
            assertEquals("Chapter ${++delivered}", chapter.title)
            assertEquals(60_000, (chapter.blocks.single() as LocalBookBlock.Text).value.length)
        }
        assertEquals(12, delivered)
    }
}
