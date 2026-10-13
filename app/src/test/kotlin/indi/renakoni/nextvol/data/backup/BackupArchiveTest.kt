package indi.renakoni.nextvol.data.backup

import android.app.Application
import indi.renakoni.nextvol.data.backup.model.AppLocalData
import indi.renakoni.nextvol.data.backup.model.LocalData
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalSerializationApi::class)
class BackupArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val data = AppLocalData(localDataList = listOf(LocalData.empty()), globalLocalData = LocalData.empty())
    private val manifest = BackupArchive.manifest(BackupKind.USER_DATA, setOf(BackupContent.BOOKSHELF, BackupContent.BOOKMARKS))
    private fun payload() = Cbor.encodeToByteArray(data)
    private fun metadata() = Json.encodeToString(manifest).toByteArray()

    private fun archive(vararg entries: Pair<String, ByteArray>): File = temporary.newFile().apply {
        ZipOutputStream(outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun expectFailure(reason: BackupFailure, file: File) {
        try {
            BackupArchive.read(file)
            fail("Expected $reason for ${file.name}")
        } catch (failure: BackupException) {
            assertEquals(reason, failure.reason)
        }
    }

    private fun editCentralDirectory(file: File, fieldOffset: Int, value: (Int) -> Int) {
        val bytes = file.readBytes()
        val index = (0 until bytes.size - 3).first {
            bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() &&
                bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte()
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(index + fieldOffset, value(buffer.getInt(index + fieldOffset)))
        file.writeBytes(bytes)
    }

    @Test fun bothExportKindsRoundTripWithExplicitManifest() {
        for (kind in BackupKind.entries) {
            val expected = if (kind == BackupKind.BOOKSHELF)
                BackupArchive.manifest(kind, setOf(BackupContent.BOOKSHELF)) else manifest
            val file = temporary.newFile("$kind.${BackupArchive.EXTENSION}")
            BackupArchive.write(file, data, expected)
            assertEquals(data, BackupArchive.read(file))
            ZipFile(file).use { zip ->
                assertEquals(listOf("manifest.json", "data.cbor"), zip.entries().asSequence().map { it.name }.toList())
                val text = zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() }
                assertEquals(expected, Json.decodeFromString<BackupManifest>(text))
                assertFalse(text.contains("original-files"))
            }
        }
    }

    @Test fun formatDetectionDoesNotDependOnFileSuffixOrEntryOrder() {
        val file = archive("data.cbor" to payload(), "manifest.json" to metadata())
        val renamed = temporary.root.resolve("renamed.txt")
        assertTrue(file.renameTo(renamed))
        assertEquals(data, BackupArchive.read(renamed))
    }

    @Test fun oldSingleDataEntryAndHistoricalFixtureRemainReadable() {
        assertEquals(data, BackupArchive.read(archive("data" to payload())))
        val fixture = temporary.newFile("before-nextvol.lnr")
        requireNotNull(javaClass.getResourceAsStream("/backups/before-nextvol.lnr")).use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        assertTrue(BackupArchive.read(fixture).localDataList.isNotEmpty())
    }

    @Test fun emptyNonZipAndMalformedPayloadAreRejected() {
        expectFailure(BackupFailure.INVALID, temporary.newFile())
        expectFailure(BackupFailure.INVALID, temporary.newFile().apply { writeText("{}") })
        expectFailure(BackupFailure.INVALID, archive())
        expectFailure(BackupFailure.INVALID, archive("data" to byteArrayOf()))
        expectFailure(BackupFailure.INVALID, archive("manifest.json" to metadata(), "data.cbor" to byteArrayOf()))
    }

    @Test fun missingUnexpectedAndMixedFormatEntriesAreRejected() {
        val invalid = listOf(
            archive("manifest.json" to metadata()),
            archive("data.cbor" to payload()),
            archive("other" to payload()),
            archive("../data" to payload()),
            archive("data/" to byteArrayOf()),
            archive("data" to payload(), "manifest.json" to metadata()),
            archive("manifest.json" to metadata(), "data.cbor" to payload(), "extra" to byteArrayOf()),
        )
        invalid.forEach { expectFailure(BackupFailure.INVALID, it) }
    }

    @Test fun duplicateEntriesAreRejected() {
        val file = archive("data" to payload(), "dupe" to payload())
        file.writeBytes(file.readBytes().toString(Charsets.ISO_8859_1)
            .replace("dupe", "data").toByteArray(Charsets.ISO_8859_1))
        expectFailure(BackupFailure.INVALID, file)
    }

    @Test fun brokenNewManifestNeverFallsBackToLegacyReading() {
        val invalid = listOf(
            "{}",
            "not json",
            "{\"format\":\"other\",\"version\":1,\"kind\":\"user-data\",\"contents\":[]}",
            "{\"format\":\"nextvol-backup\",\"kind\":\"user-data\",\"contents\":[]}",
            "{\"format\":\"nextvol-backup\",\"version\":\"1\",\"kind\":\"user-data\",\"contents\":[]}",
            Json.encodeToString(manifest.copy(kind = BackupKind.BOOKSHELF)),
        )
        invalid.forEach { text ->
            expectFailure(BackupFailure.INVALID, archive("manifest.json" to text.toByteArray(), "data.cbor" to payload()))
        }
    }

    @Test fun futureVersionIsReportedBeforeDecodingFutureFieldsOrPayload() {
        val future = "{\"format\":\"nextvol-backup\",\"version\":99,\"kind\":\"future-kind\",\"future-field\":true}"
        expectFailure(BackupFailure.UNSUPPORTED_VERSION, archive("manifest.json" to future.toByteArray(), "data.cbor" to byteArrayOf()))
    }

    @Test fun deeplyNestedManifestIsRejectedWithoutCrashing() {
        val nested = listOf(
            "[".repeat(3900) + "0" + "]".repeat(3900),
            """{"x":""".repeat(1000) + "0" + "}".repeat(1000),
        )
        nested.forEach { value ->
            val text = """{"format":"nextvol-backup","version":1,"kind":"user-data","contents":[],"padding":$value}"""
            assertTrue(text.toByteArray().size < 8192)
            expectFailure(BackupFailure.INVALID, archive("manifest.json" to text.toByteArray(), "data.cbor" to payload()))
        }
    }

    @Test fun quotedBracketsAndEscapesDoNotCountAsContainers() {
        val quote = '"'
        val slash = '\\'
        val values = listOf(
            "[{".repeat(100), "]}".repeat(100), "$quote[{".repeat(100),
            "$slash[{".repeat(100), "$slash$quote[{".repeat(100), "$slash$slash", "$slash",
        )
        values.forEach { value ->
            val text = """{"format":"nextvol-backup","version":99,"kind":${Json.encodeToString(value)},"contents":[]}"""
            expectFailure(BackupFailure.UNSUPPORTED_VERSION, archive("manifest.json" to text.toByteArray(), "data.cbor" to byteArrayOf()))
        }
    }

    @Test fun escapedBackslashesCannotHideFollowingContainers() {
        for (slashes in 1..4) {
            val quoted = Json.encodeToString("\\".repeat(slashes))
            val nested = "[".repeat(16) + "0" + "]".repeat(16)
            val text = """{"format":"nextvol-backup","version":99,"label":$quoted,"padding":$nested}"""
            expectFailure(BackupFailure.INVALID, archive("manifest.json" to text.toByteArray(), "data.cbor" to byteArrayOf()))
        }
    }

    @Test fun truncatedCentralDirectoryAndIncorrectCrcAreRejected() {
        val truncated = archive("data" to payload())
        truncated.writeBytes(truncated.readBytes().dropLast(22).toByteArray())
        expectFailure(BackupFailure.INVALID, truncated)
        val corrupt = archive("data" to payload())
        editCentralDirectory(corrupt, 16) { it xor 1 }
        expectFailure(BackupFailure.INVALID, corrupt)
    }

    @Test fun manifestAndAdvertisedPayloadSizeAreBounded() {
        expectFailure(BackupFailure.TOO_LARGE, archive("manifest.json" to ByteArray(8193) { 32 }, "data.cbor" to payload()))
        val oversized = archive("data" to payload())
        editCentralDirectory(oversized, 24) { BackupArchive.MAX_PAYLOAD_BYTES.toInt() + 1 }
        expectFailure(BackupFailure.TOO_LARGE, oversized)
    }

    @Test fun compressedFileSizeIsBoundedBeforeOpeningZip() {
        val oversized = temporary.newFile()
        RandomAccessFile(oversized, "rw").use { it.setLength(BackupArchive.MAX_ARCHIVE_BYTES + 1) }
        expectFailure(BackupFailure.TOO_LARGE, oversized)
    }

    @Test fun actualDecompressedBytesAreBoundedEvenWithForgedSize() {
        val file = temporary.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("data"))
            val block = ByteArray(DEFAULT_BUFFER_SIZE)
            repeat((BackupArchive.MAX_PAYLOAD_BYTES / block.size).toInt() + 1) { zip.write(block) }
            zip.closeEntry()
        }
        editCentralDirectory(file, 24) { 1 }
        expectFailure(BackupFailure.TOO_LARGE, file)
    }
}
