package indi.renakoni.nextvol.tts.sovits

import indi.renakoni.nextvol.tts.SpeechError
import indi.renakoni.nextvol.tts.SpeechException
import indi.renakoni.nextvol.tts.runtime.SpeechRuntimePack
import java.io.File

enum class SovitsReferenceLanguage(val code: String) {
    Chinese("zh"), Japanese("ja"), English("en");

    companion object {
        fun detect(text: String): SovitsReferenceLanguage = when {
            text.any { it in '\u3040'..'\u30ff' || it in '\uff66'..'\uff9d' } -> Japanese
            text.any { it in '\u3400'..'\u9fff' } -> Chinese
            text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN } -> English
            else -> Chinese
        }
    }
}

internal class SovitsFrontend(mandarin: () -> SovitsMandarin, private val languages: File) : AutoCloseable {
    constructor(runtime: File) : this({ SovitsMandarin(runtime) }, File(SpeechRuntimePack.Sovits.directory, "assets/gpt-sovits/languages"))

    private val mandarin by lazy(mandarin)
    private val english = lazy { EnglishG2p(File(languages, "english")) }
    private val japanese = JapaneseTextFeatureExtractor { text ->
        OpenJtalk.labelsNative(File(languages, "japanese").absolutePath, text.encodeToByteArray()).asList()
    }

    fun encode(text: String, language: SovitsReferenceLanguage = SovitsReferenceLanguage.Chinese): SovitsText {
        // A reference recording is at most 30 seconds. Bound pasted text before G2P expansion.
        require(text.length <= 2048) { "Oversized GPT-SoVITS transcript" }
        val input = buildString(text.length) {
            for (ch in text) append(if (ch in 'Ａ'..'Ｚ' || ch in 'ａ'..'ｚ' || ch in '０'..'９') ch - 0xfee0 else ch)
        }
        return when (language) {
            SovitsReferenceLanguage.Chinese -> mixed(input)
            SovitsReferenceLanguage.Japanese -> unconditioned(japanese.phonemes(input))
            SovitsReferenceLanguage.English -> {
                if (input.any { it.isLetter() && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN })
                    throw SpeechException(SpeechError.UnsupportedContent)
                unconditioned(english.value.phonemes(input))
            }
        }
    }

    private fun mixed(text: String): SovitsText {
        val parts = mutableListOf<SovitsText>()
        fun chinese(start: Int, end: Int) {
            val chunk = text.substring(start, end)
            if (chunk.isNotBlank()) mandarin.encodeChunk(chunk)?.let(parts::add)
        }
        var start = -1
        var end = 0
        for (word in englishWord.findAll(text)) {
            val first = word.range.first
            var previous = first - 1
            while (previous >= 0 && text[previous].isWhitespace()) previous--
            val before = text.getOrNull(previous)
            if ((before?.isDigit() == true && ChineseTextNormalizer.isMeasure(word.value)) || (before == '°' && word.value == "C")) continue
            if (start < 0 || !text.substring(end, first).all(Char::isWhitespace)) {
                if (start >= 0) parts += unconditioned(english.value.phonemes(text.substring(start, end)))
                chinese(end, first)
                start = first
            }
            end = word.range.last + 1
        }
        if (start < 0) return mandarin.encode(text)
        parts += unconditioned(english.value.phonemes(text.substring(start, end)))
        chinese(end, text.length)
        return SovitsText.combine(parts)
    }

    private fun unconditioned(symbols: List<String>): SovitsText {
        val phones = symbols.mapNotNull { GenieSymbols.symbolToId[it]?.toLong() }.toLongArray()
        require(phones.isNotEmpty())
        return SovitsText(phones, longArrayOf(), longArrayOf(), longArrayOf(-phones.size.toLong()))
    }

    override fun close() { if (english.isInitialized()) english.value.close() }

    private companion object {
        private const val word = """\p{IsLatin}[\p{IsLatin}\p{M}]*"""
        val englishWord = Regex("""$word(?:['’-]$word)*""")
    }
}
