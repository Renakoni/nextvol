package indi.renakoni.nextvol.tts.runtime

import androidx.test.platform.app.InstrumentationRegistry
import hnovel.speech.previewSpeechPackage
import hnovel.speech.previewOfflineVoice
import indi.renakoni.nextvol.tts.SpeechSettings
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
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
        val archive = File(folder, "sougou.zip")
        val preview = previewSpeechPackage(archive)
        val engine = preview.engines.single { it.code == "sougou" }
        val plan = previewOfflineVoice(preview, engine, engine.voices.single { it.code == "linglan" }).plan!!
        val repository = OfflineSpeechRepository(File(folder, "installed"))
        val voice = repository.install(archive, plan)
        assertFalse(availability.installed(voice, repository).canRun)
        installTestRuntime(SpeechRuntimePack.Vendor, availability)
        assertTrue(availability.installed(voice, repository).canRun)
        // IssTTS 4 and 3 must resolve different authorization libraries in the same process.
        Class.forName("com.iflytek.speech.libisstts")
        Class.forName("com.iflytek.speech.libxsstts")
        repository.acquire(voice.reference).use { lease ->
            val synth = OfflineSpeechSynthesizer(context, lease.directory, lease.configuration)
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
