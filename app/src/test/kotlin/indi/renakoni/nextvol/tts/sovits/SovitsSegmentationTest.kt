package indi.renakoni.nextvol.tts.sovits

import org.junit.Assert.*
import org.junit.Test

class SovitsSegmentationTest {
    @Test fun budgetRespondsToReadyAudioAndMeasuredCost() {
        val text = "甲".repeat(120)
        val policy = SovitsSegmentation(1f)
        assertEquals(10, policy.nextEnd(text, 0, 0))
        val normal = policy.nextEnd(text, 0, 3000)
        assertTrue(normal > policy.nextEnd(text, 0, 500))
        policy.record(10, elapsedMs = 3500, audioMs = 2000)
        assertTrue(policy.nextEnd(text, 0, 3000) < normal)
        assertEquals(60, policy.nextEnd(text, 0, 100_000))
    }

    @Test fun legalBoundaryKeepsShortSentencesQuotesAtomicMarksAndWordsTogether() {
        val policy = SovitsSegmentation(1f)
        fun first(text: String) = text.take(policy.nextEnd(text, 0, 0))
        assertEquals("来了。走吧！", first("来了。走吧！“现在就走，可以吗？”"))
        assertEquals("<<这个标记中间不能。断开>>", first("<<这个标记中间不能。断开>>甲乙丙丁。"))
        assertEquals("今天气温约为123.45，", first("今天气温约为123.45，明天预计会有小雨。"))
        assertEquals("甲乙丙丁，", first("甲乙丙丁，甲乙丙丁戊己庚辛壬癸甲乙丙丁"))
        val word = "extraordinary"
        assertEquals("$word！", first("$word！还有下一句。"))
        val rare = "甲".repeat(9) + "𠀀" + "乙".repeat(12)
        val end = policy.nextEnd(rare, 0, 0)
        assertFalse(rare[end - 1].isHighSurrogate())
    }
}
