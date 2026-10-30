package hnovel.speech

import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipException

/** Reads metadata and the resource index only. No archive entry is installed or executed. */
fun previewSpeechPackage(
    file: File,
    displayName: String = file.name,
    checkCancelled: () -> Unit = {},
): SpeechPackagePreview {
    return try {
        checkCancelled()
        val zip = RandomAccessFile(file, "r").use { input ->
            input.length() >= 4 && input.readUnsignedByte() == 0x50 && input.readUnsignedByte() == 0x4b
        }
        if (zip) readZip(file, displayName, checkCancelled)
        else {
            packageCheck(displayName.substringAfterLast('.').lowercase() in setOf("yaml", "yml"), SpeechPackageError.UnsupportedFile)
            val text = file.inputStream().use { readYamlText(it, checkCancelled) }
            describeSpeechPackage(displayName, listOf(SpeechPackageDocument(displayName, readSpeechPackageYaml(text, checkCancelled))), emptyList(), checkCancelled)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: SpeechPackageFailure) { SpeechPackagePreview(displayName, error = failure.error) }
    catch (_: ZipException) { SpeechPackagePreview(displayName, error = SpeechPackageError.InvalidArchive) }
    catch (_: Exception) { SpeechPackagePreview(displayName, error = SpeechPackageError.ReadFailed) }
}

private val metadataNames = setOf("engines.yaml", "engines.yml", "config.yaml", "config.yml", "roles.yaml", "roles.yml",
    "replaces.yaml", "replaces.yml", "voice_pool.yaml", "voice_pool.yml")

private fun readZip(file: File, name: String, checkCancelled: () -> Unit): SpeechPackagePreview {
    return ZipFile(file).use { zip ->
        val documents = mutableListOf<SpeechPackageDocument>()
        val resources = mutableListOf<SpeechPackageResource>()
        val seen = mutableSetOf<String>()
        val entries = zip.entries()
        var count = 0
        while (entries.hasMoreElements()) {
            checkCancelled()
            packageCheck(++count <= 100_000, SpeechPackageError.InvalidArchive)
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val path = entry.name
            if (path.lowercase() in metadataNames) {
                packageCheck(seen.add(path), SpeechPackageError.DuplicatePath)
                val bytes = zip.getInputStream(entry).use { readYamlBytes(it, checkCancelled) }
                packageCheck(bytes.size.toLong() == entry.size && CRC32().apply { update(bytes) }.value == entry.crc, SpeechPackageError.InvalidArchive)
                documents += SpeechPackageDocument(path, readSpeechPackageYaml(decodeYaml(bytes), checkCancelled))
            } else resources += SpeechPackageResource(path, entry.size, entry.crc)
        }
        describeSpeechPackage(name, documents.toList(), resources.toList(), checkCancelled)
    }
}

private fun readYamlBytes(input: InputStream, checkCancelled: () -> Unit): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        checkCancelled()
        val count = input.read(buffer)
        if (count < 0) break
        packageCheck(output.size() + count <= 4 * 1024 * 1024, SpeechPackageError.InvalidYaml)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun readYamlText(input: InputStream, checkCancelled: () -> Unit) = decodeYaml(readYamlBytes(input, checkCancelled))
private fun decodeYaml(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: Exception) { throw SpeechPackageFailure(SpeechPackageError.InvalidYaml) }
