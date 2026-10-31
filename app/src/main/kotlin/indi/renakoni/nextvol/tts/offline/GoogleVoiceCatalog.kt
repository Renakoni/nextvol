package indi.renakoni.nextvol.tts.offline

import hnovel.speech.OfflineVoiceConfig
import hnovel.speech.safeSpeechPackagePath
import java.io.File
import java.util.Locale

/** The same installed textproto declarations used by Google's loader; no SDK initialization. */
internal fun googleVoiceCatalog(directory: File, sizes: Map<String, Long>): List<OfflineVoiceConfig> =
    sizes.keys.sorted().filter { it.endsWith("/backend_pipeline.textproto") }.flatMap { path ->
        require(safeSpeechPackagePath(path))
        val definition = File(directory, path)
        require(definition.canonicalPath.startsWith(directory.canonicalPath + File.separator))
        require(sizes.getValue(path) in 0..(2 * 1024 * 1024) && definition.length() == sizes[path])
        if (path.substringBeforeLast('/') + "/pipeline.pb" !in sizes) return@flatMap emptyList()
        val folder = path.substringBefore('/')
        val locale = Locale.forLanguageTag(folder.replaceFirst("cmn-", "zh-")).toLanguageTag()
        Regex("speakers\\s*\\{([^{}]*)}").findAll(definition.readText()).mapNotNull { block ->
            fun field(name: String) = Regex("\\b$name\\s*:\\s*\"([^\"]+)\"").find(block.groupValues[1])?.groupValues?.get(1)
            val speaker = field("speaker") ?: return@mapNotNull null
            val gender = field("gender") ?: return@mapNotNull null
            OfflineVoiceConfig("google", "$folder-x-$speaker", locale = locale, sampleRate = 24000,
                gender = if (gender == "male") 1 else 0, rawParameters = "")
        }.toList()
    }.distinctBy { it.code }
