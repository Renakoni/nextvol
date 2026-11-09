package indi.renakoni.nextvol.data.localbook

import com.google.re2j.PatternSyntaxException
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.util.zip.ZipException

enum class LocalBookImportReason {
    EmptyFile, UnsupportedFormat, FileAccess, Storage,
    InvalidEncoding, UnsupportedEncoding, InvalidRule, RuleTooLong, TooManyChapters,
    NoContent, CorruptEpub, EncryptedEpub, UnsupportedEpub, EpubLimit,
    SessionExpired, InvalidTitle, ShelfChanged, Unknown,
}

/** Stable classification for user messages; the original cause/detail is retained for diagnostics. */
class LocalBookImportException(
    val reason: LocalBookImportReason,
    detail: String,
    cause: Throwable? = null,
) : IllegalArgumentException(detail, cause)

data class LocalBookImportFailure(val reason: LocalBookImportReason, val details: String) {
    companion object {
        fun from(failure: Exception): LocalBookImportFailure = LocalBookImportFailure(
            when (failure) {
                is LocalBookImportException -> failure.reason
                is PatternSyntaxException -> LocalBookImportReason.InvalidRule
                is CharacterCodingException -> LocalBookImportReason.InvalidEncoding
                is ZipException -> LocalBookImportReason.CorruptEpub
                is IOException, is SecurityException -> LocalBookImportReason.FileAccess
                else -> LocalBookImportReason.Unknown
            },
            failure.stackTraceToString(),
        )
    }
}

internal inline fun requireImport(value: Boolean, reason: LocalBookImportReason, detail: () -> String) {
    if (!value) throw LocalBookImportException(reason, detail())
}

internal inline fun <T : Any> requireImportNotNull(value: T?, reason: LocalBookImportReason, detail: () -> String): T =
    value ?: throw LocalBookImportException(reason, detail())
