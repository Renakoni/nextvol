package indi.renakoni.nextvol.tts.sovits

import org.junit.Assert.*
import org.junit.Test

class SovitsSegmentationTest {
    @Test fun shortNarrativePhraseStaysWithItsClauseAtStartAndOnResume() {
        val opening = "七月初，时当酷暑，傍晚，"
        val next = "有个年轻人从他向C巷二房东租来的一间小屋里走出来，"
        val text = opening + next + "慢慢地、犹豫不决似地朝K桥方向走去。"
        val policy = SovitsSegmentation(.9f)
        val start = policy.nextEnd(text, 0, 0)
        assertEquals(opening, text.take(start))
        assertEquals(next, text.substring(start, policy.nextEnd(text, start, 3160)))
        val resume = text.indexOf("傍晚")
        assertEquals("傍晚，" + next, text.substring(resume, policy.nextEnd(text, resume, 0)))
        val dialogue = "“嗯。”“接下来的事情由你决定。”"
        assertEquals("“嗯。”", dialogue.take(policy.nextEnd(dialogue, 0, 0)))
        val call = "\n“喂， 你这个戴德国帽的！”"
        val passage = call + "他停下了脚步。"
        assertEquals(call, passage.take(policy.nextEnd(passage, 0, 0)))
    }

    @Test fun budgetRespondsToReadyAudioAndMeasuredCost() {
        val text = "甲".repeat(120)
        val policy = SovitsSegmentation(1f)
        assertEquals(10, policy.nextEnd(text, 0, 0))
        val normal = policy.nextEnd(text, 0, 3000)
        assertTrue(normal > policy.nextEnd(text, 0, 500))
        assertNull(policy.record(10, elapsedMs = 7500, audioMs = 2000))
        assertEquals(normal, policy.nextEnd(text, 0, 3000))
        assertEquals(1.75, policy.record(10, elapsedMs = 3500, audioMs = 2000)!!, 0.0)
        assertTrue(policy.nextEnd(text, 0, 3000) < normal)
        assertEquals(20, policy.nextEnd(text, 0, 1000))
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
