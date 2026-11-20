package indi.renakoni.nextvol.tts.sovits

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Locale

/** Genie CMU/name lexicons + NLTK POS disambiguation + checkpoint20 GRU for unknown words. */
internal class EnglishG2p(private val root: File) : AutoCloseable {
    private fun dictionary(name: String) = File(root, name).useLines { lines ->
        lines.associate { line -> line.substringBefore('\t') to line.substringAfter('\t').split(' ') }
    }
    private val cmu = dictionary("dictionary.tsv")
    private val names = dictionary("names.tsv")
    private val tagger by lazy { EnglishPosTagger(root) }
    private val predictor = lazy { EnglishPronunciationModel(root) }
    override fun close() { if (predictor.isInitialized()) predictor.value.close() }

    fun phonemes(text: String): List<String> {
        val normalized = normalize(text)
        val words = Regex("[A-Za-z]+(?:['-][A-Za-z]+)*|[^\\s]").findAll(normalized).map { it.value }.toList()
        val tags by lazy(LazyThreadSafetyMode.NONE) { tagger.tag(words) }
        return words.flatMapIndexed { i, original ->
            val word = original.lowercase(Locale.ROOT)
            when {
                word.none { it in 'a'..'z' } -> listOf(if (word == "'") "-" else word)
                word == "read" -> if (tags[i] == "VBD") listOf("R", "EH1", "D") else listOf("R", "IY1", "D")
                word == "complex" -> if (tags[i].startsWith("JJ")) listOf("K", "AH0", "M", "P", "L", "EH1", "K", "S")
                    else listOf("K", "AA1", "M", "P", "L", "EH0", "K", "S")
                word == "lead" -> if (tags[i].startsWith("NN")) listOf("L", "IY1", "D") else listOf("L", "EH1", "D")
                word == "presents" -> if (tags[i] == "VBZ") listOf("P", "R", "IY0", "Z", "EH1", "N", "T", "S")
                    else listOf("P", "R", "EH1", "Z", "AH0", "N", "T", "S")
                else -> query(original)
            }
        }.filterNot { it in setOf(" ", "<pad>", "UW", "</s>", "<s>") }.map { if (it == "<unk>") "UNK" else it }
    }

    private fun query(original: String): List<String> {
        val word = original.lowercase(Locale.ROOT)
        if (original == "A") return listOf("AH0")
        cmu[word]?.let { return it }
        if (original.firstOrNull()?.isUpperCase() == true) names[word]?.let { return it }
        if (word.endsWith("'s") && word.length > 2) {
            val base = query(word.dropLast(2))
            val suffix = when (base.lastOrNull()) {
                "S", "Z", "SH", "ZH", "CH", "JH" -> listOf("AH0", "Z")
                "P", "T", "K", "F", "TH" -> listOf("S")
                else -> listOf("Z")
            }
            return base + suffix
        }
        if ('-' in word) return word.split('-').filter { it.isNotEmpty() }.flatMap(::query)
        return predictor.value.predict(word)
    }

    private fun normalize(text: String): String {
        var result = Normalizer.normalize(text, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        result = result.replace('’', '\'').replace('“', '"').replace('”', '"')
            .replace('，', ',').replace('。', '.').replace('！', '!').replace('？', '?')
        val abbreviations = mapOf("mr" to "mister", "mrs" to "missus", "dr" to "doctor", "prof" to "professor",
            "st" to "street", "co" to "company", "ltd" to "limited")
        result = Regex("\\b(Mr|Mrs|Dr|Prof|St|Co|Ltd)\\.", RegexOption.IGNORE_CASE).replace(result) {
            abbreviations.getValue(it.groupValues[1].lowercase(Locale.ROOT))
        }
        result = Regex("(?<=\\d),(?=\\d{3}(?:\\D|$))").replace(result, "")
        result = Regex("\\b(\\d+)(st|nd|rd|th)\\b").replace(result) {
            it.groupValues[1].toLongOrNull()?.let(EnglishNumbers::ordinal) ?: it.value
        }
        result = Regex("[0-9]+(?:\\.[0-9]+)?").replace(result) { match ->
            val parts = match.value.split('.')
            val integral = parts[0].toLongOrNull()?.takeIf { parts[0].length <= 15 }
            val words = if (integral != null) EnglishNumbers.cardinal(integral)
                else parts[0].map { EnglishNumbers.cardinal(it.digitToInt().toLong()) }.joinToString(" ")
            words + if (parts.size == 2) " point " + parts[1].map { EnglishNumbers.cardinal(it.digitToInt().toLong()) }.joinToString(" ") else ""
        }
        result = result.replace("%", " percent ").replace("&", " and ").replace("$", " dollars ")
            .replace(';', ',').replace(':', ',')
        result = Regex("\\b[A-Z]{2,}\\b").replace(result) { acronym ->
            // CMU's entry for "a" is the unstressed article; an initialism needs the letter name.
            acronym.value.toList().joinToString(" ") { if (it == 'A') "ay" else it.toString() }
        }
        return Regex("([,.!?])\\1+").replace(result, "$1").lowercase(Locale.ROOT)
    }
}

/** Port of NLTK AveragedPerceptronTagger feature extraction and greedy decoding. */
internal class EnglishPosTagger(root: File) {
    private val tagdict = JSONObject(File(root, "pos-tagdict.json").readText())
    private val weights = JSONObject(File(root, "pos-weights.json").readText())
    private val classes = JSONArray(File(root, "pos-classes.json").readText()).let { array ->
        List(array.length()) { array.getString(it) }
    }
    fun tag(words: List<String>): List<String> {
        fun normalize(word: String): String = when {
            '-' in word && !word.startsWith('-') -> "!HYPHEN"
            word.length == 4 && word.all(Char::isDigit) -> "!YEAR"
            word.firstOrNull()?.isDigit() == true -> "!DIGITS"
            else -> word.lowercase(Locale.ROOT)
        }
        val context = listOf("-START-", "-START2-") + words.map(::normalize) + listOf("-END-", "-END2-")
        var previous = "-START-"
        var previous2 = "-START2-"
        return words.mapIndexed { index, word ->
            val i = index + 2
            val tag = if (tagdict.has(word)) tagdict.getString(word) else {
                val features = listOf("bias", "i suffix ${word.takeLast(3)}", "i pref1 ${word.take(1)}",
                    "i-1 tag $previous", "i-2 tag $previous2", "i tag+i-2 tag $previous $previous2",
                    "i word ${context[i]}", "i-1 tag+i word $previous ${context[i]}",
                    "i-1 word ${context[i - 1]}", "i-1 suffix ${context[i - 1].takeLast(3)}",
                    "i-2 word ${context[i - 2]}", "i+1 word ${context[i + 1]}",
                    "i+1 suffix ${context[i + 1].takeLast(3)}", "i+2 word ${context[i + 2]}")
                val scores = mutableMapOf<String, Double>()
                features.forEach { feature -> weights.optJSONObject(feature)?.let { values ->
                    values.keys().forEach { label -> scores[label] = (scores[label] ?: 0.0) + values.getDouble(label) }
                } }
                classes.maxWith(compareBy<String> { scores[it] ?: 0.0 }.thenBy { it })
            }
            previous2 = previous
            previous = tag
            tag
        }
    }
}

/** The original checkpoint20 weights, exported without quantization. */
internal class EnglishPronunciationModel(private val root: File) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private fun session(file: String) = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(1)
        options.setInterOpNumThreads(1)
        environment.createSession(File(root, file).absolutePath, options)
    }
    private val encoder = session("encoder.onnx")
    private val decoder = try { session("decoder.onnx") } catch (failure: Throwable) { encoder.close(); throw failure }
    override fun close() { encoder.close(); decoder.close() }
    private val phones = ("<pad> <unk> <s> </s> AA0 AA1 AA2 AE0 AE1 AE2 AH0 AH1 AH2 AO0 AO1 AO2 " +
        "AW0 AW1 AW2 AY0 AY1 AY2 B CH D DH EH0 EH1 EH2 ER0 ER1 ER2 EY0 EY1 EY2 F G HH IH0 IH1 IH2 " +
        "IY0 IY1 IY2 JH K L M N NG OW0 OW1 OW2 OY0 OY1 OY2 P R S SH T TH UH0 UH1 UH2 UW UW0 UW1 UW2 V W Y Z ZH").split(' ')

    fun predict(word: String): List<String> {
        val ids = (word.map { if (it in 'a'..'z') (it - 'a' + 3).toLong() else 1L } + 2L).toLongArray()
        val state = FloatArray(256)
        OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), longArrayOf(ids.size.toLong())).use { input ->
            encoder.run(mapOf("tokens" to input)).use { (it[0] as OnnxTensor).floatBuffer.get(state) }
        }
        val result = mutableListOf<String>()
        var token = 2L
        repeat(20) {
            OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(token)), longArrayOf(1)).use { input ->
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(state), longArrayOf(1, 1, 256)).use { hidden ->
                    decoder.run(mapOf("tokens" to input, "hidden" to hidden)).use { outputs ->
                        (outputs[0] as OnnxTensor).floatBuffer.get(state)
                        token = (outputs[1] as OnnxTensor).longBuffer.get(0)
                    }
                }
            }
            if (token == 3L) return result
            result += phones[token.toInt()]
        }
        return result
    }
}
