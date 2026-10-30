package indi.renakoni.nextvol.tts.matcha

import java.io.File

/** Already installed Chinese Matcha sample. Import and model selection belong to #794. */
class MatchaResources(val directory: File) {
    internal fun validate(): File {
        val root = directory.canonicalFile
        require(root.isDirectory && ',' !in root.path && '\u0000' !in root.path)
        val files = mapOf(
            "model-steps-3.onnx" to 128L * 1024 * 1024,
            "hifigan_v2.onnx" to 32L * 1024 * 1024,
            "tokens.txt" to 1024L * 1024,
            "lexicon.txt" to 32L * 1024 * 1024,
            "date.fst" to 32L * 1024 * 1024,
            "number.fst" to 32L * 1024 * 1024,
            "phone.fst" to 32L * 1024 * 1024,
        ) + listOf("jieba.dict.utf8", "hmm_model.utf8", "user.dict.utf8", "idf.utf8", "stop_words.utf8")
            .associate { "dict/$it" to 16L * 1024 * 1024 }
        for ((name, limit) in files) {
            val file = File(root, name)
            require(file.canonicalFile == file.absoluteFile && file.isFile && file.canRead() && file.length() in 1..limit) {
                "Missing, escaped or oversized Matcha resource: $name"
            }
        }
        return root
    }
}
