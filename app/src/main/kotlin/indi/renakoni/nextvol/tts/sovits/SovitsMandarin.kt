package indi.renakoni.nextvol.tts.sovits

import android.content.Context
import android.icu.text.MessageFormat
import android.icu.text.Transliterator
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale

internal class SovitsText(val phones: LongArray, val tokens: LongArray, val repeats: LongArray)

/** The three sequences have different lengths. RoBERTa expands one token per character to its phones. */
@RequiresApi(29)
internal class SovitsMandarin(context: Context, runtime: File) {
    private val pinyin = Transliterator.getInstance("Han-Latin")
    private val simplified = Transliterator.getInstance("Traditional-Simplified")
    private val numbers = MessageFormat("{0,spellout}", Locale.SIMPLIFIED_CHINESE)
    private val lexicon = context.assets.open("gpt-sovits/mandarin-phones.txt").bufferedReader().useLines { lines ->
        lines.associate { row ->
            val fields = row.split(' ')
            fields.first() to fields.drop(1).map(String::toLong)
        }
    }
    private val vocab = JSONObject(File(runtime, "roberta-wwm-ext-large-onnx/roberta_tokenizer/tokenizer.json")
        .readText()).getJSONObject("model").getJSONObject("vocab")

    fun encode(text: String): SovitsText {
        val normal = simplified.transliterate(Normalizer.normalize(text, Normalizer.Form.NFKC))
        val expanded = Regex("[0-9]+(?:\\.[0-9]+)?").replace(normal) { match ->
            val value = match.value
            if (value.length > 15 || value.length > 1 && value[0] == '0' && value[1] != '.')
                value.map { if (it == '.') '点' else "零一二三四五六七八九"[it - '0'] }.joinToString("")
            else numbers.format(arrayOf<Any>(value.toBigDecimal()))
        }
        val phones = mutableListOf<Long>()
        val repeats = mutableListOf<Long>()
        val tokens = mutableListOf(vocab.getLong("[CLS]"))
        val unknown = vocab.getLong("[UNK]")
        fun append(character: String, ids: List<Long>) {
            phones.addAll(ids)
            repeats += ids.size.toLong()
            tokens += vocab.optLong(character, unknown)
        }
        for (match in Regex("\\p{IsHan}+|\\S").findAll(expanded)) {
            val word = match.value
            if (Character.UnicodeScript.of(word.codePointAt(0)) == Character.UnicodeScript.HAN) {
                val chars = word.codePoints().toArray().map { String(Character.toChars(it)) }
                val readings = pinyin.transliterate(word).trim().split(Regex("\\s+"))
                chars.forEachIndexed { index, char ->
                    val syllable = if (readings.size == chars.size) readings[index] else pinyin.transliterate(char)
                    val decomposed = Normalizer.normalize(syllable.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                    val tone = decomposed.firstNotNullOfOrNull { mark ->
                        when (mark) { '\u0304' -> 1; '\u0301' -> 2; '\u030c' -> 3; '\u0300' -> 4; else -> null }
                    } ?: 5
                    val base = decomposed.replace("u\u0308", "v").filter { it in 'a'..'z' }
                    val ids = requireNotNull(lexicon[base]) { "Unknown Mandarin syllable for $char" }
                    append(char, listOf(ids[0], ids[tone]))
                }
            } else {
                val symbol = when (word) {
                    "。", "." -> "."
                    "，", ",", "、", "；", ";", "：", ":" -> ","
                    "!", "?", "-" -> word
                    "—", "–" -> "-"
                    "…", "⋯" -> "…"
                    else -> {
                        require(!Character.isLetterOrDigit(word.codePointAt(0))) { "Mandarin text required: $word" }
                        continue
                    }
                }
                append(symbol, lexicon.getValue(symbol))
            }
        }
        require(phones.isNotEmpty() && tokens.size < 512) { "Empty or oversized GPT-SoVITS text" }
        tokens += vocab.getLong("[SEP]")
        return SovitsText(phones.toLongArray(), tokens.toLongArray(), repeats.toLongArray())
    }
}
