package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.tts.names.*
import org.junit.Assert.*
import org.junit.Test

class NgrEncoderTest {
    @Test fun compoundSurnameAndLastThreeCharactersKeepTheOriginalFeaturePositions() {
        val encoder = NgrEncoder(mapOf("小" to -0.2f, "明" to 0.7f, "华" to 0.4f,
            "小明" to 0.98f, "明华" to 0.66f), setOf("佐", "佐佐", "佐佐木"))
        assertArrayEquals(floatArrayOf(-0.2f, 0.7f, 0f, 0.98f, 0f), encoder.encode("佐佐木小明").features, 0f)
        assertArrayEquals(floatArrayOf(-0.2f, 0.7f, 0.4f, 0.98f, 0.66f), encoder.encode("佐佐木大小明华").features, 0f)
    }

    @Test fun zeroScoreIsSupportedAndSurrogateHalvesDoNotBecomeKnownTokens() {
        val encoder = NgrEncoder(NgrEncoder.vocabulary(sequenceOf("明,0.5", "明,0")), emptySet())
        val known = encoder.encode("😀明")
        assertTrue(known.supported)
        assertArrayEquals(FloatArray(5), known.features, 0f)
        assertFalse(encoder.encode("😀").supported)
    }
}
