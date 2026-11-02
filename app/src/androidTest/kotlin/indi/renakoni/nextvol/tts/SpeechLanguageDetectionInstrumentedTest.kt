package indi.renakoni.nextvol.tts

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SpeechLanguageDetectionInstrumentedTest {
    @Test fun shippedDetectorsIdentifyChineseEnglishAndJapaneseAfterSwitchingAlgorithms() = runBlocking {
        val detector = SpeechLanguageDetection(ApplicationProvider.getApplicationContext())
        val samples = listOf(
            "en" to "The young reader opened the book and began to read the story about a distant city.",
            "zh" to "年轻人推开了书房的大门，拿起桌上的小说，开始阅读这个发生在遥远城市里的故事。",
            "ja" to "彼女は図書館で新しい本を見つけました。その物語を読みながら、静かな時間を楽しんでいました。")
        for (algorithm in listOf(300, 301, 300)) {
            val languages = setOf("en", "ja", if (algorithm == 300) "zh-CN" else "zh")
            for ((expected, text) in samples) {
                val actual = detector.detect(text, algorithm, languages, Locale.CHINA)
                assertEquals("Algorithm $algorithm", expected, actual?.substringBefore('-'))
            }
        }
    }
}
