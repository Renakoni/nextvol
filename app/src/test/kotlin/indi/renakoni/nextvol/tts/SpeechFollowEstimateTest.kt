package indi.renakoni.nextvol.tts

import org.junit.Assert.*
import org.junit.Test

class SpeechFollowEstimateTest {
    @Test fun returnsUtf16OffsetsWithoutSplittingSurrogatesOrCombiningCharacters() {
        val estimate = SpeechFollowEstimate("甲𠀀e\u0301乙")
        assertEquals(listOf(0, 1, 3, 5), (0L..3L).map { estimate.anchorAt(it * 1000, 4000) })
    }

    @Test fun durationRefinementDoesNotPullBackButSeekingAndANewClipCan() {
        val estimate = SpeechFollowEstimate("甲乙丙丁")
        assertEquals(2, estimate.anchorAt(600, 1000))
        assertEquals(2, estimate.anchorAt(650, 2000))
        assertEquals(0, estimate.anchorAt(100, 2000))
        assertEquals(0, SpeechFollowEstimate("下一段").anchorAt(0, 1000))
    }
}
