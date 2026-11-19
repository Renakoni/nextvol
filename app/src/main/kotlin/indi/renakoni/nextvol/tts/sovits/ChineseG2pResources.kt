package indi.renakoni.nextvol.tts.sovits

internal data class ChineseWordEntry(
    val frequency: Int,
    val tag: String,
    val pinyinCsv: String,
)

internal data class ChineseG2pResources(
    val polyphonicPinyin: Map<String, List<String>>,
    val opencpopByPinyin: Map<String, String>,
    val singleCharPinyin: Map<String, String>,
    val wordEntries: Map<String, ChineseWordEntry>,
    val traditionalToSimplified: Map<Char, String>,
    val maxWordLength: Int,
    val totalWordFrequency: Double,
) {
    fun wordFrequency(word: String): Int = wordEntries[word]?.frequency ?: 0

    fun wordTag(word: String): String = wordEntries[word]?.tag.orEmpty()

    fun wordPinyinFor(word: String): List<String>? = wordEntries[word]?.pinyinCsv?.takeIf { it.isNotEmpty() }?.split(',')
}
