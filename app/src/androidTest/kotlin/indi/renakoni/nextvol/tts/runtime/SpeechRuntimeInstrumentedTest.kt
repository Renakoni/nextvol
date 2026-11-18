package indi.renakoni.nextvol.tts.runtime

import hnovel.speech.OfflinePackagePlan
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.speech.previewSpeechPackage
import hnovel.speech.previewOfflineVoice
import indi.renakoni.nextvol.tts.SpeechSettings
import indi.renakoni.nextvol.tts.offline.OfflineSpeechSynthesizer
import indi.renakoni.nextvol.data.update.UpdateArtifactDownloader
import indi.renakoni.nextvol.tts.offline.OfflineAvailabilityChecker
import indi.renakoni.nextvol.tts.sovits.DeviceAcceleration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in on a fresh arm64 install with reachable catalog URLs. */
internal suspend fun installTestRuntime(pack: SpeechRuntimePack, availability: OfflineAvailabilityChecker =
    OfflineAvailabilityChecker(InstrumentationRegistry.getInstrumentation().targetContext)) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assertFalse(pack.installed)
    if (pack == SpeechRuntimePack.Sovits) assertNull(DeviceAcceleration.detect(context))
    val downloads = SpeechRuntimeDownloads(UpdateArtifactDownloader(context), availability)
    downloads.start(pack)
    val result = withTimeout(300_000L) {
        downloads.changes.first { it[pack] is RuntimeDownload.Installed || it[pack] is RuntimeDownload.Failed }
    }
    assertEquals(result.toString(), RuntimeDownload.Installed, result[pack])
}

class SpeechRuntimeInstrumentedTest {
    @Test fun freshlyDownloadedVendorLibrariesServeJniCalls() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("installSpeechRuntime") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.filesDir, "vendor-test")
        val availability = OfflineAvailabilityChecker(context)
        val models = mapOf("sougou" to "linglan", "microsoft" to "zh-CN-XiaoxiaoNeural", "bytedance" to "BV001_24k")
        val voices = models.map { (id, code) ->
            // Device fixture: the original archive and its unpacked voice directory, already present before download.
            val preview = previewSpeechPackage(File(folder, "$id.zip"))
            val engine = preview.engines.single { it.code == id }
            previewOfflineVoice(preview, engine, engine.voices.single { it.code == code }).plan!!
        }
        fun canRun(voice: OfflinePackagePlan) =
            availability.inspect(voice.configuration, emptySet()) { error(it) }.canRun
        assertTrue(voices.none(::canRun))

        installTestRuntime(SpeechRuntimePack.Vendor, availability)
        for (voice in voices) {
            assertTrue(voice.name, canRun(voice))
            val synth = OfflineSpeechSynthesizer(context, File(folder, voice.directory), voice.configuration)
            val output = File(folder, "sample.wav")
            try {
                synth.open(SpeechSettings())
                synth.synthesize("我们继续读书。", output)
                val wav = output.readBytes()
                assertTrue(voice.name, (44 until wav.size).any { wav[it] != 0.toByte() })
            } finally {
                synth.close()
                output.delete()
            }
        }
    }
}
