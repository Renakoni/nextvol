package indi.renakoni.nextvol.data.backup

import indi.renakoni.nextvol.data.backup.model.AppLocalData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Stage in app-owned cache; never delete a caller-owned document after a failed transfer. */
object BackupFiles {
    suspend fun read(cacheDirectory: File, openSource: () -> InputStream): AppLocalData = withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val staging = File.createTempFile("nextvol-backup-", ".tmp", cacheDirectory)
        try {
            openSource().use { input ->
                staging.outputStream().use { output ->
                    BackupArchive.copyLimited(input, output, BackupArchive.MAX_ARCHIVE_BYTES) { caller.ensureActive() }
                }
            }
            BackupArchive.read(staging) { caller.ensureActive() }
        } finally {
            staging.delete()
        }
    }

    suspend fun write(
        cacheDirectory: File,
        data: AppLocalData,
        manifest: BackupManifest,
        openDestination: () -> OutputStream,
    ) = withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val staging = File.createTempFile("nextvol-backup-", ".tmp", cacheDirectory)
        try {
            BackupArchive.write(staging, data, manifest) { caller.ensureActive() }
            caller.ensureActive()
            // Do not open/truncate the user's document until serialization and ZIP finalization succeed.
            openDestination().use { output ->
                staging.inputStream().use { input ->
                    BackupArchive.copyLimited(input, output, BackupArchive.MAX_ARCHIVE_BYTES) { caller.ensureActive() }
                }
            }
        } finally {
            staging.delete()
        }
    }
}
