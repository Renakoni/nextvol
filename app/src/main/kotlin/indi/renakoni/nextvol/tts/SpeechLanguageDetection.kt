package indi.renakoni.nextvol.tts

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.languagedetector.LanguageDetector
import com.optimaize.langdetect.LanguageDetectorBuilder
import com.optimaize.langdetect.ngram.NgramExtractors
import com.optimaize.langdetect.profiles.LanguageProfileBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** O6.c: load only the selected detector and the languages used by active language rules. */
@Singleton
class SpeechLanguageDetection @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutex = Mutex()
    private data class Configuration(val algorithm: Int, val languages: Set<String>, val locale: String)
    private var configuration: Configuration? = null
    private var optimaize: com.optimaize.langdetect.LanguageDetector? = null
    private var mediaPipe: LanguageDetector? = null

    suspend fun release() = withContext(Dispatchers.Default) { mutex.withLock { clear() } }

    private fun clear() {
        mediaPipe?.close()
        mediaPipe = null
        optimaize = null
        configuration = null
    }

    suspend fun detect(text: String, algorithm: Int, languages: Set<String>, locale: Locale = Locale.getDefault()): String? =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                try {
                    val requested = Configuration(algorithm, languages, locale.toLanguageTag())
                    if (configuration != requested) {
                        clear()
                        when (algorithm) {
                            300 -> optimaize = createOptimaizeLanguageDetector(languages, locale)
                            301 -> mediaPipe = LanguageDetector.createFromOptions(context,
                                LanguageDetector.LanguageDetectorOptions.builder()
                                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("model/language_detector.tflite").build())
                                    .setCategoryAllowlist(mediaPipeLanguageCandidates(languages, locale))
                                    .setScoreThreshold(0.01f).setMaxResults(10).build())
                            else -> throw SpeechException(SpeechError.LanguageDetectorUnavailable)
                        }
                        configuration = requested
                    }
                    val language = when (algorithm) {
                        300 -> optimaize!!.detect(text).orNull()?.toString()
                        else -> mediaPipe!!.detect(text.trim { Character.isWhitespace(it) })
                            .languagesAndScores().firstOrNull()?.languageCode()
                    }
                    currentCoroutineContext().ensureActive()
                    language
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: SpeechException) { throw failure }
                catch (_: Exception) { throw SpeechException(SpeechError.LanguageDetectorUnavailable) }
                catch (_: LinkageError) { throw SpeechException(SpeechError.LanguageDetectorUnavailable) }
            }
        }
}

/** Same shipped Optimaize profiles; JSON parsing uses the app's existing parser. */
internal fun createOptimaizeLanguageDetector(languages: Set<String>, locale: Locale): com.optimaize.langdetect.LanguageDetector {
    val profiles = optimaizeLanguageCandidates(languages, locale).map { language ->
        val profile = checkNotNull(LanguageProfileBuilder::class.java.classLoader?.getResourceAsStream("languages/$language"))
            .bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
        LanguageProfileBuilder(profile.getValue("name").jsonPrimitive.content).apply {
            profile.getValue("freq").jsonObject.forEach { (gram, count) -> addGram(gram, count.jsonPrimitive.int) }
        }.build()
    }
    return LanguageDetectorBuilder.create(NgramExtractors.standard()).withProfiles(profiles).minimalConfidence(0.8).build()
}

internal fun optimaizeLanguageCandidates(languages: Set<String>, locale: Locale): Set<String> {
    val local = if (locale.language == "zh") "zh-${locale.country}" else locale.language
    val result = (languages + local).filterTo(linkedSetOf()) { it in speechDetectionLanguages(300) }
    if (result.size == 1) result += if (local == "en") "zh-CN" else "en"
    return result
}

internal fun mediaPipeLanguageCandidates(languages: Set<String>, locale: Locale): List<String> {
    val result = (languages + locale.language).filterTo(linkedSetOf()) { it in speechDetectionLanguages(301) }
    if ("en" in result) result += listOf("ar-Latn", "bg-Latn", "el-Latn", "hi-Latn", "ja-Latn", "ru-Latn", "zh-Latn")
    return result.toList()
}
