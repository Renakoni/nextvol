package indi.renakoni.nextvol.tts.offline.legacy

import android.os.Build
import android.system.Os
import android.util.Xml
import hnovel.speech.safeSpeechPackagePath
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.nio.file.Files
import java.util.Locale

/** Reproduce the reference storage's filename casing without duplicating model data. */
internal fun prepareMicrosoftResources(files: List<File>, checkCancelled: () -> Unit) {
    fun link(source: File, name: String) {
        val alias = File(source.parentFile, name)
        if (!alias.exists()) {
            checkCancelled()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Files.createLink(alias.toPath(), source.toPath())
            else Os.link(source.path, alias.path)
        }
    }
    fun linkStem(source: File, stem: String) {
        for (extension in setOf(source.extension, source.extension.lowercase(Locale.ROOT), source.extension.uppercase(Locale.ROOT))) {
            link(source, if (extension.isEmpty()) stem else "$stem.$extension")
        }
    }
    val languageSuffix = Regex("(?<=\\.)[a-z]{2}[A-Z]{2}(?=\\.|$)")
    for (source in files) {
        checkCancelled()
        val stem = source.nameWithoutExtension
        linkStem(source, stem)
        // Extra-language model names use e.g. ZhCN even when the archive spells zhCN.
        linkStem(source, languageSuffix.replace(stem) { it.value.replaceFirstChar(Char::uppercaseChar) })
        // The SDK lowercases the domain suffix for NUSData, e.g. voiceAssistant -> voiceassistant.
        if (source.parentFile.name.equals("NUSData", ignoreCase = true) && '.' in stem) {
            linkStem(source, stem.substringBefore('.') + "." + stem.substringAfter('.').lowercase(Locale.ROOT))
        }
    }
    for (tokens in files.filter { it.name.equals("Tokens.xml", ignoreCase = true) }) {
        val references = mutableSetOf<String>()
        try {
            tokens.inputStream().use { input ->
                val parser = Xml.newPullParser().apply { setInput(input, null) }
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    checkCancelled()
                    if (parser.eventType != XmlPullParser.START_TAG || parser.name != "String") continue
                    val value = parser.getAttributeValue(null, "value") ?: continue
                    if (value.startsWith("[INSTALLDIR]")) references += value.removePrefix("[INSTALLDIR]")
                }
            }
        } catch (_: XmlPullParserException) {
            // Leave malformed model metadata to the SDK; aliases are compatibility aids.
        }
        for (path in references.filter { safeSpeechPackagePath(it) && '/' !in it }) {
            val stem = File(path).nameWithoutExtension
            files.filter { it.parentFile == tokens.parentFile && it.nameWithoutExtension.equals(stem, ignoreCase = true) }
                .forEach { linkStem(it, stem) }
        }
    }
}
