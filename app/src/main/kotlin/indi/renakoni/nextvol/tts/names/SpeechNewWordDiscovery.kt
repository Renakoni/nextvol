package indi.renakoni.nextvol.tts.names

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Reader
import javax.inject.Inject

enum class NewWordAlgorithm { Native, Hanlp }

/** The native path returns scores; the HanLP path returns occurrence counts. */
data class NewWordAnalysis(val algorithm: NewWordAlgorithm, val candidates: List<NewWordCandidate>)

class SpeechNewWordDiscovery @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun defaultNamePattern(): String = withContext(Dispatchers.IO) {
        context.assets.open("speech/names/p6-default-person-name-regex.txt").bufferedReader().use { it.readText() }
    }

    suspend fun newSession(): SpeechNewWordSession = withContext(Dispatchers.IO) {
        SpeechNewWordSession(stopwords("p6-stopwords.txt"))
    }

    /** A fresh preview never contributes observations to a book session. */
    suspend fun preview(text: String, nameRegex: String? = null): List<RealtimeNameCandidate> = withContext(Dispatchers.Default) {
        val discoverer = RealtimeNameDiscoverer(nameRegex ?: defaultNamePattern(), stopwords("p6-stopwords.txt"))
        discoverer.submit(text, reverseWindows = false, checkCancelled = { ensureActive() })
    }

    /** Takes ownership of the reader, including when cancelled or when analysis fails. */
    suspend fun analyze(reader: Reader, algorithm: NewWordAlgorithm, onlyNew: Boolean): NewWordAnalysis =
        reader.use { input -> withContext(Dispatchers.Default) {
            val dictionary = if (onlyNew) {
                // AAPT expands the source .gz asset and removes its suffix in the APK.
                context.assets.open("speech/names/dictionary-frequency.tsv").bufferedReader().use { words ->
                    words.lineSequence().associate { line ->
                        ensureActive()
                        val split = line.lastIndexOf('\t')
                        line.substring(0, split) to line.substring(split + 1).toInt()
                    }
                }
            } else emptyMap()
            val result = when (algorithm) {
                NewWordAlgorithm.Native -> NativeNewWordDiscovery(stopwords("native-stopwords.txt"))
                    .discover(input, onlyNew, dictionary) { ensureActive() }
                NewWordAlgorithm.Hanlp -> HanlpNewWordDiscovery().discover(input, onlyNew, dictionary) { ensureActive() }
            }
            ensureActive()
            NewWordAnalysis(algorithm, result)
        } }

    private fun stopwords(file: String): Set<String> = context.assets.open("speech/names/$file").bufferedReader().use {
        it.lineSequence().toSet()
    }
}
