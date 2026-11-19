package indi.renakoni.nextvol.tts.sovits

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object ChineseG2pAssetRepository {
    private var cached = java.lang.ref.WeakReference<ChineseG2pResources>(null)

    @Synchronized fun load(context: Context): ChineseG2pResources =
        cached.get() ?: loadUncached(context).also { cached = java.lang.ref.WeakReference(it) }

    private fun loadUncached(context: Context): ChineseG2pResources {
        val polyphonicJson = context.assets.open("gpt-sovits/chinese_g2p/polyphonic.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val opencpopText = context.assets.open("gpt-sovits/chinese_g2p/opencpop-strict.txt")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val singleCharJson = context.assets.open("gpt-sovits/chinese_g2p/single_char_pinyin.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        val polyphonicObject = JSONObject(polyphonicJson)
        val singleCharObject = JSONObject(singleCharJson)
        val polyphonic = buildMap(polyphonicObject.length()) {
            for (key in polyphonicObject.keys()) {
                val value = polyphonicObject.get(key)
                put(key, jsonValueToStringList(value))
            }
        }
        val singleChar = buildMap(singleCharObject.length()) {
            for (key in singleCharObject.keys()) {
                put(key, singleCharObject.getString(key))
            }
        }
        val opencpop = opencpopText.lineSequence()
            .filter(String::isNotBlank)
            .associate { line ->
                val parts = line.split('\t')
                require(parts.size >= 2) { "Invalid opencpop line: $line" }
                parts[0] to parts[1]
            }

        val wordEntries = HashMap<String, ChineseWordEntry>()
        var maxWordLength = 1
        var totalFrequency = 0L
        context.assets.open("gpt-sovits/chinese_g2p/contextual_lexicon.tsv")
            .bufferedReader(Charsets.UTF_8)
            .useLines { lines ->
                lines.forEach { line ->
                    val parts = line.split('\t', limit = 4)
                    if (parts.size < 2) return@forEach
                    val frequency = parts[1].toIntOrNull() ?: return@forEach
                    maxWordLength = maxOf(maxWordLength, parts[0].length)
                    totalFrequency += frequency
                    wordEntries[parts[0]] = ChineseWordEntry(
                        frequency = frequency,
                        tag = parts.getOrNull(2).orEmpty(),
                        pinyinCsv = parts.getOrNull(3).orEmpty(),
                    )
                }
            }

        val traditionalToSimplified = context.assets.open("gpt-sovits/chinese_g2p/traditional_to_simplified.tsv")
            .bufferedReader(Charsets.UTF_8)
            .useLines { lines ->
                lines.mapNotNull { line ->
                    val parts = line.split('\t', limit = 2)
                    val traditional = parts.getOrNull(0)?.singleOrNull()
                    val simplified = parts.getOrNull(1)?.takeIf(String::isNotEmpty)
                    if (traditional != null && simplified != null) traditional to simplified else null
                }.toMap()
            }

        return ChineseG2pResources(
            polyphonicPinyin = polyphonic,
            opencpopByPinyin = opencpop,
            singleCharPinyin = singleChar,
            wordEntries = wordEntries,
            maxWordLength = maxWordLength,
            totalWordFrequency = totalFrequency.toDouble(),
            traditionalToSimplified = traditionalToSimplified,
        )
    }

    private fun jsonValueToStringList(value: Any): List<String> =
        when (value) {
            is JSONArray -> List(value.length()) { index -> value.getString(index) }
            is String -> listOf(value)
            else -> error("Unsupported polyphonic entry type: ${value::class.java.name}")
        }
}
