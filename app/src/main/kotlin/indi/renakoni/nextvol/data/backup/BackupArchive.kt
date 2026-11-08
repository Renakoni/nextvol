package indi.renakoni.nextvol.data.backup

import indi.renakoni.nextvol.data.backup.model.AppLocalData
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@Serializable
enum class BackupKind {
    @SerialName("user-data") USER_DATA,
    @SerialName("bookshelf") BOOKSHELF,
}

@Serializable
enum class BackupContent {
    @SerialName("chapter-cache") CHAPTER_CACHE,
    @SerialName("bookshelf") BOOKSHELF,
    @SerialName("reading-data") READING_DATA,
    @SerialName("settings") SETTINGS,
    @SerialName("bookmarks") BOOKMARKS,
}

@Serializable
data class BackupManifest(
    val format: String,
    val version: Int,
    val kind: BackupKind,
    val contents: Set<BackupContent>,
)

enum class BackupFailure { INVALID, UNSUPPORTED_VERSION, TOO_LARGE }

class BackupException(val reason: BackupFailure, cause: Throwable? = null) : IOException(reason.name, cause) {
    companion object { const val ERROR_KEY = "backup_error" }
}

/** NextVol Backup envelope; the existing CBOR payload and restore transactions remain unchanged. */
@OptIn(ExperimentalSerializationApi::class)
object BackupArchive {
    const val EXTENSION = "nvbackup"
    const val USER_DATA_FILE_NAME = "NextVolData.$EXTENSION"
    const val BOOKSHELF_FILE_NAME = "NextVolBookshelfData.$EXTENSION"
    const val FORMAT = "nextvol-backup"
    const val VERSION = 1
    const val MAX_PAYLOAD_BYTES = 64L * 1024 * 1024
    const val MAX_ARCHIVE_BYTES = MAX_PAYLOAD_BYTES + 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 8L * 1024
    private const val MAX_MANIFEST_DEPTH = 16
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val DATA_ENTRY = "data.cbor"
    private const val LEGACY_ENTRY = "data"

    fun manifest(kind: BackupKind, contents: Set<BackupContent>) = BackupManifest(FORMAT, VERSION, kind, contents)

    fun read(file: File, checkCancelled: () -> Unit = {}): AppLocalData {
        checkCancelled()
        if (file.length() > MAX_ARCHIVE_BYTES) throw BackupException(BackupFailure.TOO_LARGE)
        try {
            // ZipFile requires a central directory, unlike reading only the first streaming entry.
            return ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().take(3).toList()
                val names = entries.map { it.name }
                val dataEntry = when {
                    names == listOf(LEGACY_ENTRY) -> entries.single()
                    names.size == 2 && names.toSet() == setOf(MANIFEST_ENTRY, DATA_ENTRY) -> {
                        val bytes = readEntry(zip, entries.single { it.name == MANIFEST_ENTRY }, MAX_MANIFEST_BYTES, checkCancelled)
                        readManifest(bytes)
                        entries.single { it.name == DATA_ENTRY }
                    }
                    else -> throw BackupException(BackupFailure.INVALID)
                }
                val bytes = readEntry(zip, dataEntry, MAX_PAYLOAD_BYTES, checkCancelled)
                checkCancelled()
                Cbor.decodeFromByteArray<AppLocalData>(bytes).also { checkCancelled() }
            }
        } catch (failure: BackupException) {
            throw failure
        } catch (failure: ZipException) {
            throw BackupException(BackupFailure.INVALID, failure)
        } catch (failure: EOFException) {
            throw BackupException(BackupFailure.INVALID, failure)
        } catch (failure: SerializationException) {
            throw BackupException(BackupFailure.INVALID, failure)
        } catch (failure: IllegalArgumentException) {
            throw BackupException(BackupFailure.INVALID, failure)
        }
    }

    fun write(file: File, data: AppLocalData, manifest: BackupManifest, checkCancelled: () -> Unit = {}) {
        validateManifest(manifest)
        checkCancelled()
        val payload = Cbor.encodeToByteArray(data)
        if (payload.size > MAX_PAYLOAD_BYTES) throw BackupException(BackupFailure.TOO_LARGE)
        val metadata = Json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        checkCancelled()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(metadata)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(DATA_ENTRY))
            payload.inputStream().use { copyLimited(it, zip, MAX_PAYLOAD_BYTES, checkCancelled) }
            zip.closeEntry()
        }
    }

    private fun readManifest(bytes: ByteArray) {
        val text = bytes.toString(Charsets.UTF_8)
        checkManifestDepth(text)
        val fields = Json.parseToJsonElement(text) as? JsonObject
            ?: throw BackupException(BackupFailure.INVALID)
        val format = fields["format"] as? JsonPrimitive
        if (format?.isString != true || format.content != FORMAT) throw BackupException(BackupFailure.INVALID)
        val version = fields["version"] as? JsonPrimitive
        if (version == null || version.isString || version.intOrNull == null) throw BackupException(BackupFailure.INVALID)
        // Inspect the envelope version before decoding version-specific fields or any business data.
        if (version.intOrNull != VERSION) throw BackupException(BackupFailure.UNSUPPORTED_VERSION)
        validateManifest(Json.decodeFromJsonElement<BackupManifest>(fields))
    }

    private fun checkManifestDepth(text: String) {
        // Bound recursion before the JSON tree parser; quoted brackets are not containers.
        var depth = 0
        var inString = false
        var escaped = false
        for (char in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{', '[' -> if (++depth > MAX_MANIFEST_DEPTH) throw BackupException(BackupFailure.INVALID)
                    '}', ']' -> if (--depth < 0) throw BackupException(BackupFailure.INVALID)
                }
            }
        }
    }

    private fun validateManifest(manifest: BackupManifest) {
        if (manifest.format != FORMAT) throw BackupException(BackupFailure.INVALID)
        if (manifest.version != VERSION) throw BackupException(BackupFailure.UNSUPPORTED_VERSION)
        if (manifest.kind == BackupKind.BOOKSHELF && manifest.contents != setOf(BackupContent.BOOKSHELF)) {
            throw BackupException(BackupFailure.INVALID)
        }
    }

    private fun readEntry(zip: ZipFile, entry: ZipEntry, limit: Long, checkCancelled: () -> Unit): ByteArray {
        if (entry.isDirectory || entry.size < 0 || entry.crc < 0) throw BackupException(BackupFailure.INVALID)
        if (entry.size > limit) throw BackupException(BackupFailure.TOO_LARGE)
        val bytes = ByteArrayOutputStream()
        val crc = CRC32()
        CheckedInputStream(zip.getInputStream(entry), crc).use { input ->
            // The actual decompressed bytes are bounded too; ZIP metadata is not trusted.
            copyLimited(input, bytes, limit, checkCancelled)
        }
        if (bytes.size().toLong() != entry.size || crc.value != entry.crc) throw BackupException(BackupFailure.INVALID)
        return bytes.toByteArray()
    }

    internal fun copyLimited(input: InputStream, output: OutputStream, limit: Long, checkCancelled: () -> Unit = {}) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw BackupException(BackupFailure.TOO_LARGE)
            output.write(buffer, 0, count)
        }
    }
}
