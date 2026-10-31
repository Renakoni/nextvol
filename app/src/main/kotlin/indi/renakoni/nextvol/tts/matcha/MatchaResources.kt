package indi.renakoni.nextvol.tts.matcha

import java.io.File
import hnovel.speech.SherpaVoiceConfig
import hnovel.speech.safeSpeechPackagePath

/** Resolves the selected model's resources inside its managed installation. */
class MatchaResources(val directory: File, val configuration: SherpaVoiceConfig) {
    internal fun path(relative: String): String {
        if (relative.isEmpty()) return ""
        require(safeSpeechPackagePath(relative))
        val file = File(directory, relative)
        require(file.canonicalFile == file.absoluteFile)
        return file.path
    }

    internal fun validate(): File {
        val root = directory.canonicalFile
        require(root == directory.absoluteFile && root.isDirectory && ',' !in root.path && '\u0000' !in root.path)
        require(configuration.model.isNotBlank() && configuration.tokens.isNotBlank())
        val files = listOf(configuration.model, configuration.vocoder, configuration.voices, configuration.tokens) +
            configuration.lexicons + configuration.ruleFsts + configuration.ruleFars
        for (name in files.filter(String::isNotEmpty)) {
            val file = File(path(name))
            require(file.isFile && file.canRead() && file.length() > 0) {
                "Missing or unreadable offline resource: $name"
            }
        }
        for (name in listOf(configuration.dataDir, configuration.dictDir).filter(String::isNotEmpty)) {
            require(File(path(name)).isDirectory) { "Missing offline resource directory: $name" }
        }
        return root
    }
}
