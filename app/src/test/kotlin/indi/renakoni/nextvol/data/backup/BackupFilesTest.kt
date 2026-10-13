package indi.renakoni.nextvol.data.backup

import android.app.Application
import indi.renakoni.nextvol.data.backup.model.AppLocalData
import indi.renakoni.nextvol.data.backup.model.LocalData
import indi.renakoni.nextvol.data.local.room.entity.UserDataEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class BackupFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val data = AppLocalData(localDataList = listOf(LocalData.empty()), globalLocalData = LocalData.empty())
    private val manifest = BackupArchive.manifest(BackupKind.USER_DATA, emptySet())

    @Test fun completedArchiveExistsBeforeDestinationOpensAndRoundTripCleansCache() = runBlocking {
        val cache = temporary.newFolder()
        val destination = temporary.newFile()
        BackupFiles.write(cache, data, manifest) {
            assertEquals(data, BackupArchive.read(cache.listFiles()!!.single()))
            destination.outputStream()
        }
        assertTrue(cache.listFiles()!!.isEmpty())
        assertEquals(data, BackupFiles.read(cache) { destination.inputStream() })
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun serializationFailureDoesNotOpenOrTruncateExistingDestination() = runBlocking {
        val cache = temporary.newFolder()
        val destination = temporary.newFile().apply { writeText("previous backup") }
        val failure = IllegalStateException("injected serialization failure")
        val broken = data.copy(localDataList = object : AbstractList<LocalData>() {
            override val size = 1
            override fun get(index: Int): LocalData = throw failure
        })
        var opened = false
        try {
            BackupFiles.write(cache, broken, manifest) { opened = true; destination.outputStream() }
            fail("Expected serialization failure")
        } catch (actual: IllegalStateException) {
            // Coroutine stack-trace recovery can copy an exception across Dispatchers.IO.
            assertEquals(failure.message, actual.message)
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
        }
        assertFalse(opened)
        assertEquals("previous backup", destination.readText())
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun oversizedExportDoesNotOpenDestination() = runBlocking {
        val cache = temporary.newFolder()
        val large = data.copy(globalLocalData = LocalData.empty().copy(userDataEntities = listOf(
            UserDataEntity("fixture", "fixture", "String", "x".repeat(BackupArchive.MAX_PAYLOAD_BYTES.toInt()))
        )))
        var opened = false
        try {
            BackupFiles.write(cache, large, manifest) { opened = true; ByteArrayOutputStream() }
            fail("Expected payload limit")
        } catch (failure: BackupException) {
            assertEquals(BackupFailure.TOO_LARGE, failure.reason)
        }
        assertFalse(opened)
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun failedDestinationWriteCleansCacheButNeverDeletesUserDocument() = runBlocking {
        val cache = temporary.newFolder()
        val destination = temporary.newFile()
        try {
            BackupFiles.write(cache, data, manifest) {
                object : FilterOutputStream(destination.outputStream()) {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        out.write(bytes, offset, minOf(7, length))
                        throw IOException("injected destination failure")
                    }
                }
            }
            fail("Expected destination failure")
        } catch (failure: IOException) {
            assertEquals("injected destination failure", failure.message)
        }
        assertTrue(destination.exists())
        assertEquals(7L, destination.length())
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun destinationCloseFailureIsNotReportedAsSuccessAndCleansCache() = runBlocking {
        val cache = temporary.newFolder()
        try {
            BackupFiles.write(cache, data, manifest) {
                object : ByteArrayOutputStream() {
                    override fun close() { throw IOException("close failure") }
                }
            }
            fail("Close failure must propagate")
        } catch (failure: IOException) {
            assertEquals("close failure", failure.message)
        }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun cancellationDuringTransferPropagatesAndCleansCache() = runBlocking {
        val cache = temporary.newFolder()
        var wrote = false
        val job = launch {
            val caller = currentCoroutineContext()
            BackupFiles.write(cache, data, manifest) {
                object : ByteArrayOutputStream() {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        super.write(bytes, offset, length)
                        wrote = true
                        caller.cancel(CancellationException("cancel during transfer"))
                    }
                }
            }
        }
        job.join()
        assertTrue(wrote)
        assertTrue(job.isCancelled)
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun readFailureAndCorruptArchiveBothCleanCache() = runBlocking {
        val cache = temporary.newFolder()
        try {
            BackupFiles.read(cache) { throw IOException("source unavailable") }
            fail("Expected source failure")
        } catch (failure: IOException) {
            assertEquals("source unavailable", failure.message)
        }
        assertTrue(cache.listFiles()!!.isEmpty())
        try {
            BackupFiles.read(cache) { byteArrayOf(1, 2, 3).inputStream() }
            fail("Expected invalid archive")
        } catch (failure: BackupException) {
            assertEquals(BackupFailure.INVALID, failure.reason)
        }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun cancellationPropagatesAndCleansCache() = runBlocking {
        val cache = temporary.newFolder()
        val cancelled = CancellationException("cancelled transfer")
        try {
            BackupFiles.write(cache, data, manifest) { throw cancelled }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertEquals(cancelled.message, actual.message)
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === cancelled })
        }
        assertTrue(cache.listFiles()!!.isEmpty())
        try {
            BackupFiles.read(cache) { throw cancelled }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertEquals(cancelled.message, actual.message)
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === cancelled })
        }
        assertTrue(cache.listFiles()!!.isEmpty())
    }
}
