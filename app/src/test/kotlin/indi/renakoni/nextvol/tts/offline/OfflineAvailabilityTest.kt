package indi.renakoni.nextvol.tts.offline

import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OfflineAvailabilityTest {
    @get:Rule val files = TemporaryFolder()

    private fun checker(api: Int = 28, abis: List<String> = listOf("arm64-v8a"), pages: Long = 4096) =
        OfflineAvailabilityChecker(OfflineDevice(api, abis, pages), files.root, true) { null }

    private fun OfflineAvailabilityChecker.check(engine: String, parameters: String = "") =
        inspect(OfflineVoiceConfig(engine, "voice", parameters), emptySet()) { error("No model may be read") }

    @Test fun oldOrNonArmDevicesStillAllowAudioButExplainNativeRestrictions() {
        val old = checker(api = 24, abis = listOf("x86"))
        assertTrue(old.check("audio").canRun)
        assertEquals(R.string.tts_offline_unverified, old.check("audio").message)
        assertEquals(R.string.tts_offline_android, old.check("google").message)
        assertFalse(old.check("google").canRun)
        assertEquals(R.string.tts_offline_abi, old.check("sougou").message)
        assertEquals(R.string.tts_offline_runtime_missing, checker().check("sougou").message)
    }

    @Test fun baiduAndIssOnlyRequireTheSelectedVariant() {
        listOf("c++_shared", "BDSpeechDecoder_V1", "bd_ettm", "issxtts30", "issavth").forEach { library(it) }
        val device = checker()
        assertTrue(device.check("bdetts").canRun)
        val missing = device.check("bdetts", "37111357")
        assertFalse(missing.canRun)
        assertEquals("libbd_ettl.so", missing.detail)
        assertTrue(device.check("isstts", "1,old").canRun)
        assertFalse(device.check("isstts", "1,new").canRun)
        assertEquals(listOf("synthesizer"), offlineLibraries(OfflineVoiceConfig("larktts", "voice", "1")))
    }

    @Test fun pageSizeComesFromTheDeviceAndActualLibraryHeaders() {
        library("nextvol-matcha", alignment = 16384)
        library("onnxruntime", alignment = 16384)
        library("tts-offline", alignment = 4096)
        assertTrue(checker(pages = 4096).check("xiaomi").canRun)
        val largePages = checker(pages = 16384)
        assertEquals(R.string.tts_offline_pages, largePages.check("xiaomi").message)
        assertEquals(R.string.tts_offline_unverified, largePages.check("sherpa").message)
        assertEquals(R.string.tts_offline_check_incomplete, checker(pages = 0).check("sherpa").message)
        assertEquals(R.string.tts_offline_abi, checker(abis = listOf("x86_64")).check("sherpa").message)
    }

    @Test fun packageLibrariesUseTheSameInspectionWithoutInitializingAnEngine() {
        library("synthesizer")
        val device = checker()
        val voice = OfflineVoiceConfig("larktts", "voice", "1")
        val path = "libspeaker_xiaowu.so"
        assertEquals(R.string.tts_offline_missing, device.inspect(voice, emptySet()) { error("Missing library") }.message)
        val x86 = library("speaker", machine = 62)
        assertEquals(R.string.tts_offline_abi, device.inspect(voice, setOf(path)) { x86.inputStream() }.message)
        assertEquals(R.string.tts_offline_check_incomplete,
            device.inspect(voice, setOf(path)) { byteArrayOf(1, 2, 3).inputStream() }.message)
    }

    private fun library(name: String, alignment: Long = 4096, machine: Short = 183): File {
        val elf = ByteBuffer.allocate(120).order(ByteOrder.LITTLE_ENDIAN)
        elf.put(byteArrayOf(0x7f, 69, 76, 70, 2, 1))
        elf.putShort(18, machine)
        elf.putLong(32, 64)
        elf.putShort(54, 56)
        elf.putShort(56, 1)
        elf.putInt(64, 1)
        elf.putLong(112, alignment)
        return File(files.root, "lib$name.so").apply { writeBytes(elf.array()) }
    }
}
