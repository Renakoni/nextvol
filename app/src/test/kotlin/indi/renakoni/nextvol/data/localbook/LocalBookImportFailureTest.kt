package indi.renakoni.nextvol.data.localbook

import org.junit.Assert.*
import org.junit.Test
import java.io.FileNotFoundException
import java.util.zip.ZipException

class LocalBookImportFailureTest {
    private fun reason(block: () -> Unit): LocalBookImportReason {
        val failure = assertThrows(Exception::class.java, block)
        return LocalBookImportFailure.from(failure).reason
    }

    @Test fun txtValidationAndParserFailuresKeepSpecificReasons() {
        assertEquals(LocalBookImportReason.EmptyFile, reason { parseTxt(byteArrayOf()) })
        assertEquals(LocalBookImportReason.NoContent, reason { parseTxt("\n  ".toByteArray()) })
        assertEquals(LocalBookImportReason.UnsupportedEncoding, reason { parseTxt(byteArrayOf(65), encoding = "unknown") })
        assertEquals(LocalBookImportReason.InvalidEncoding, reason { parseTxt(byteArrayOf(0xFF.toByte()), encoding = "UTF-8") })
        assertEquals(LocalBookImportReason.InvalidRule, reason { parseTxt(byteArrayOf(65), rule = "(") })
        assertEquals(LocalBookImportReason.RuleTooLong, reason { parseTxt(byteArrayOf(65), rule = "a".repeat(1025)) })
    }

    @Test fun exceptionTypesDetermineTheReasonWithoutMatchingDiagnosticText() {
        assertEquals(LocalBookImportReason.FileAccess, LocalBookImportFailure.from(FileNotFoundException("任意说明")).reason)
        assertEquals(LocalBookImportReason.CorruptEpub, LocalBookImportFailure.from(ZipException("任意说明")).reason)
        assertEquals(LocalBookImportReason.Unknown, LocalBookImportFailure.from(IllegalStateException("The text file is empty.")).reason)
        val cause = IllegalStateException("underlying detail")
        val failure = LocalBookImportFailure.from(LocalBookImportException(LocalBookImportReason.Storage, "诊断", cause))
        assertEquals(LocalBookImportReason.Storage, failure.reason)
        assertTrue(failure.details.contains("underlying detail"))
    }
}
