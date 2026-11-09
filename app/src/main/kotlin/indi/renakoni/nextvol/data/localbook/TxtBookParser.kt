package indi.renakoni.nextvol.data.localbook

import com.google.re2j.Pattern
import org.mozilla.universalchardet.UniversalDetector
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Line-based recognition; a match marks a boundary and never discards body text. */
object TxtBookParser {
    private const val NUMBERS = "0-9０-９〇零一二两兩三四五六七八九十百千万萬壹贰貳叁參肆伍陆陸柒捌玖拾佰仟"
    const val MAX_TITLE_LENGTH = 120
    const val MAX_CHAPTER_CHARACTERS = 64_000
    const val MAX_CHAPTER_BLOCKS = 1_000
    const val MAX_CHAPTERS = 10_000
    val DEFAULT_RULE = "^(?:(?:正文\\s*)?第\\s*[$NUMBERS]{1,16}\\s*[章回节節话話卷部篇册冊集].*|" +
        "(?:序章|序幕|楔子|引子|前言|尾声|尾聲|后记|後記|番外|幕间|幕間|间章|間章|终章|終章)(?:\\s.*|[:：—-].*)?|" +
        "(?i:(?:chapter|chap\\.?|part|volume)\\s+[0-9ivxlcdm]+(?:\\s.*|[.:：—-].*)?|" +
        "(?:prologue|epilogue)(?:\\s.*|[:：].*)?))$"
    private val numbered = Pattern.compile("^(?:正文\\s*)?第\\s*[$NUMBERS]{1,16}\\s*([章回节節话話卷部篇册冊集])(.*)$")
    private val volume = Pattern.compile("^(?:(?:正文\\s*)?第\\s*[$NUMBERS]{1,16}\\s*[卷部篇册冊集].*|(?i:(?:part|volume)\\s+[0-9ivxlcdm]+.*))$")
    val encodings: List<String> by lazy { Charset.availableCharsets().keys.toList() }

    private data class Encoding(val charset: Charset, val bomBytes: Int) {
        fun reader(open: () -> java.io.InputStream): java.io.BufferedReader {
            val input = open()
            try {
                repeat(bomBytes) { input.read() }
                return java.io.InputStreamReader(input, charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)).buffered()
            } catch (failure: Exception) { input.close(); throw failure }
        }
    }

    private fun detect(open: () -> java.io.InputStream, selected: String?, checkActive: () -> Unit): Encoding {
        val sample = ByteArray(512 * 1024)
        var size = 0
        open().use { input ->
            while (size < sample.size) {
                checkActive()
                val count = input.read(sample, size, minOf(8192, sample.size - size))
                if (count < 0) break
                size += count
            }
        }
        requireImport(size > 0, LocalBookImportReason.EmptyFile) { "The text file is empty." }
        fun begins(vararg prefix: Int) = size >= prefix.size && prefix.indices.all { sample[it].toInt() and 255 == prefix[it] }
        val bom = when {
            begins(0x00, 0x00, 0xFE, 0xFF) -> "UTF-32BE" to 4
            begins(0xFF, 0xFE, 0x00, 0x00) -> "UTF-32LE" to 4
            begins(0xEF, 0xBB, 0xBF) -> "UTF-8" to 3
            begins(0xFF, 0xFE) -> "UTF-16LE" to 2
            begins(0xFE, 0xFF) -> "UTF-16BE" to 2
            else -> null
        }
        val name = selected ?: bom?.first ?: run {
            val utf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val validUtf8 = !utf8.decode(ByteBuffer.wrap(sample, 0, size), java.nio.CharBuffer.allocate(size), size < sample.size).isError
            if (validUtf8) "UTF-8" else UniversalDetector(null).let { detector ->
                detector.handleData(sample, 0, size)
                detector.dataEnd()
                detector.detectedCharset ?: "GB18030"
            }
        }
        val charset = try { Charset.forName(name) } catch (failure: IllegalArgumentException) {
            throw LocalBookImportException(LocalBookImportReason.UnsupportedEncoding, "Unsupported text encoding: $name", failure)
        }
        return Encoding(charset, if (bom?.first == charset.name()) bom.second else 0)
    }

    /** Two sequential cursors keep even an arbitrarily long logical line off the heap. */
    fun parse(open: () -> java.io.InputStream, title: String, encoding: String? = null,
        rule: String = DEFAULT_RULE, checkActive: () -> Unit = {}, onChapter: (LocalBookChapter) -> Unit): String {
        requireImport(rule.length <= 1_024, LocalBookImportReason.RuleTooLong) { "The chapter rule is too long." }
        val matcher = rule.takeIf { it.isNotBlank() }?.let(Pattern::compile)
        val detected = detect(open, encoding, checkActive)
        var currentVolume = ""
        var currentTitle = title
        var explicitChapter = false
        var part = 1L
        var length = 0
        var hasContent = false
        val blocks = mutableListOf<LocalBookBlock>()
        fun flush() {
            if (blocks.isEmpty() && !explicitChapter) return
            checkActive()
            onChapter(LocalBookChapter(if (part == 1L) currentTitle else "$currentTitle ($part)", currentVolume, blocks.toList()))
            blocks.clear()
            length = 0
            explicitChapter = false
        }
        detected.reader(open).use { scanner -> java.io.PushbackReader(detected.reader(open), 2).use { body ->
            val text = CharArray(MAX_CHAPTER_CHARACTERS)
            fun skip(count: Long) {
                var remaining = count
                while (remaining > 0) {
                    checkActive()
                    val read = body.read(text, 0, minOf(remaining, text.size.toLong()).toInt())
                    if (read < 0) throw java.io.EOFException("Text changed while parsing")
                    remaining -= read
                }
            }
            fun append(count: Long) {
                var remaining = count
                while (remaining > 0) {
                    checkActive()
                    if (length == MAX_CHAPTER_CHARACTERS || blocks.size == MAX_CHAPTER_BLOCKS) { flush(); part++ }
                    val requested = minOf(remaining, (MAX_CHAPTER_CHARACTERS - length).toLong()).toInt()
                    var read = 0
                    while (read < requested) {
                        val next = body.read(text, read, requested - read)
                        if (next < 0) throw java.io.EOFException("Text changed while parsing")
                        read += next
                    }
                    if (text[read - 1].isHighSurrogate() && remaining > read) {
                        val next = body.read()
                        if (next < 0) throw java.io.EOFException("Text changed while parsing")
                        body.unread(next)
                        if (next.toChar().isLowSurrogate()) { body.unread(text[--read].code) }
                    }
                    if (read == 0) { flush(); part++; continue }
                    blocks += LocalBookBlock.Text(text.concatToString(0, read))
                    length += read
                    remaining -= read
                }
            }
            var lineLength = 0L
            var leading = 0L
            var trimmedLength = 0L
            val heading = StringBuilder(MAX_TITLE_LENGTH)
            fun line() {
                val candidate = if (trimmedLength in 1..MAX_TITLE_LENGTH.toLong()) heading.substring(0, trimmedLength.toInt()) else ""
                val matched = candidate.isNotEmpty() && matcher?.matcher(candidate)?.matches() == true &&
                    (rule != DEFAULT_RULE || !ordinarySentence(candidate))
                if (matched) {
                    flush()
                    part = 1
                    if (volume.matcher(candidate).matches()) { currentVolume = candidate; currentTitle = candidate }
                    else { currentTitle = candidate; explicitChapter = true }
                    skip(lineLength)
                } else if (trimmedLength > 0) { append(lineLength); hasContent = true }
                else skip(lineLength)
                lineLength = 0
                leading = 0
                trimmedLength = 0
                heading.setLength(0)
            }
            val buffer = CharArray(8192)
            var afterCr = false
            while (true) {
                checkActive()
                val count = scanner.read(buffer)
                if (count < 0) break
                for (index in 0 until count) {
                    val char = buffer[index]
                    if (char == '\n' && afterCr) { skip(1); afterCr = false; continue }
                    afterCr = char == '\r'
                    if (char == '\r' || char == '\n') { line(); skip(1); continue }
                    lineLength++
                    if (!char.isWhitespace()) {
                        if (trimmedLength == 0L) leading = lineLength - 1
                        trimmedLength = lineLength - leading
                    }
                    if (trimmedLength > 0 && heading.length < MAX_TITLE_LENGTH) heading.append(char)
                }
            }
            line()
        } }
        flush()
        requireImport(hasContent, LocalBookImportReason.NoContent) { "The file has no readable text." }
        return detected.charset.name()
    }

    private fun ordinarySentence(line: String): Boolean {
        val match = numbered.matcher(line)
        if (!match.matches()) return false
        val suffix = match.group(2)
        return when (match.group(1)) {
            "节", "節" -> suffix.startsWith("课") || suffix.startsWith("課")
            "回" -> suffix.startsWith("来") || suffix.startsWith("來") || suffix.startsWith("事") || suffix.startsWith("合")
            "部" -> suffix.startsWith("分")
            "集" -> suffix.startsWith("合") || suffix.startsWith("和")
            else -> false
        }
    }
}
