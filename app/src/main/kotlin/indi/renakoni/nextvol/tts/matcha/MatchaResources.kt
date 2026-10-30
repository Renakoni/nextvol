package indi.renakoni.nextvol.tts.matcha

import java.io.File
import hnovel.speech.ChineseMatchaProfile

/** Validates the fixed Chinese profile inside a managed installation. */
class MatchaResources(val directory: File) {
    internal fun validate(): File {
        val root = directory.canonicalFile
        require(root.isDirectory && ',' !in root.path && '\u0000' !in root.path)
        for ((name, limit) in ChineseMatchaProfile.files) {
            val file = File(root, name)
            require(file.canonicalFile == file.absoluteFile && file.isFile && file.canRead() && file.length() in 1..limit) {
                "Missing, escaped or oversized Matcha resource: $name"
            }
        }
        return root
    }
}
