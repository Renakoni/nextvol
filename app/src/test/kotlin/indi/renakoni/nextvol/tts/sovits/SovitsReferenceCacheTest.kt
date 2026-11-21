package indi.renakoni.nextvol.tts.sovits

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SovitsReferenceCacheTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun reopenedVoiceReusesOnlyIntactMatchingAudioFeatures() {
        val recording = files.newFile("reference.wav").apply { writeBytes(byteArrayOf(1, 2)) }
        val voice = GptSovitsVoice(files.root, GptSovitsVersion.V2ProPlus, "speaker", recording, "prompt", files.newFolder("runtime"))
        check(voice.model.mkdirs())
        val features = FloatArray(1024 + 512 + 768) { it.toFloat() }
        SovitsReferenceCache(voice).write(features)
        assertArrayEquals(features, SovitsReferenceCache(voice.copy(referenceText = "another transcript")).read(), 0f)
        assertNull(SovitsReferenceCache(voice.copy(runtime = files.newFolder("other-runtime"))).read())
        recording.writeBytes(byteArrayOf(1, 3))
        assertNull(SovitsReferenceCache(voice).read())
        SovitsReferenceCache(voice).write(features)
        val stored = voice.model.listFiles()!!.single()
        val bytes = stored.readBytes()
        bytes[bytes.lastIndex] = (bytes.last() + 1).toByte()
        stored.writeBytes(bytes)
        assertNull(SovitsReferenceCache(voice).read())
    }
}
