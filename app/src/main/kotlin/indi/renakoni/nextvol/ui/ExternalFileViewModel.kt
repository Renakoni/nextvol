package indi.renakoni.nextvol.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.ExternalFile
import indi.renakoni.nextvol.data.ExternalFiles
import indi.renakoni.nextvol.data.backup.BackupException
import indi.renakoni.nextvol.data.work.ImportDataWork
import indi.renakoni.nextvol.ui.components.backupFailureMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ExternalFileViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val workManager: WorkManager,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val intents = Channel<Intent>(Channel.UNLIMITED)
    val intentFlow = intents.receiveAsFlow()
    private var launched = false
    private val messages = Channel<Int>(Channel.BUFFERED)
    val messageFlow = messages.receiveAsFlow()
    var book by mutableStateOf<ExternalFile?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var backupName by mutableStateOf<String?>(savedState["backupName"])
        private set
    var restoring by mutableStateOf(savedState.get<String>("restoreWork") != null)
        private set

    init {
        savedState.get<String>("restoreWork")?.let { observeRestore(UUID.fromString(it)) }
    }

    fun accept(intent: Intent, initial: Boolean = false) {
        if (initial && launched) return
        launched = true
        // A restored task must not replay its old file, but a fresh external launch is a new import.
        if (initial && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0 &&
            savedState.get<Boolean>("launched") == true) return
        savedState["launched"] = true
        intents.trySend(intent)
    }

    fun checkAvailable(bookVisible: Boolean): Boolean {
        if (busy || backupName != null || book != null || bookVisible) {
            messages.trySend(R.string.external_file_busy)
            return false
        }
        return true
    }

    fun open(intent: Intent, bookVisible: Boolean) {
        if (ExternalFiles.uri(intent) == null || !checkAvailable(bookVisible)) return
        busy = true
        viewModelScope.launch {
            try {
                val file = ExternalFiles.resolve(context.contentResolver, intent)
                when {
                    file == null -> messages.send(R.string.external_file_unsupported)
                    file.bookFormat != null -> book = file
                    else -> {
                        backupName = ExternalFiles.stageBackup(context, file.uri).name
                        savedState["backupName"] = backupName
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                messages.send(backupFailureMessage(workDataOf(
                    BackupException.ERROR_KEY to (failure as? BackupException)?.reason?.name
                ), R.string.external_file_unreadable))
            } finally {
                busy = false
            }
        }
    }

    fun bookOpened() { book = null }

    fun dismissBackup() {
        if (restoring) return
        backupName?.let { ExternalFiles.backupFile(context, it).delete() }
        backupName = null
        savedState["backupName"] = null
    }

    fun restore(overwrite: Boolean) {
        val name = backupName ?: return
        if (restoring) return
        val request = OneTimeWorkRequestBuilder<ImportDataWork>()
            .setInputData(workDataOf(ExternalFiles.STAGED_BACKUP to name, "overwrite" to overwrite))
            .build()
        restoring = true
        savedState["restoreWork"] = request.id.toString()
        observeRestore(request.id, request)
    }

    private fun observeRestore(id: UUID, request: OneTimeWorkRequest? = null) {
        viewModelScope.launch {
            try {
                // A null record is only meaningful after enqueue has finished writing it.
                request?.let { workManager.enqueue(it).await() }
                val result = workManager.getWorkInfoByIdFlow(id).first { it == null || it.state.isFinished }
                restoring = false
                savedState["restoreWork"] = null
                if (result != null) dismissBackup()
                messages.send(if (result?.state == WorkInfo.State.SUCCEEDED) R.string.data_import_success
                    else backupFailureMessage(result?.outputData, R.string.data_import_failed))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                restoring = false
                savedState["restoreWork"] = null
                messages.send(R.string.data_import_failed)
            }
        }
    }

    override fun onCleared() {
        if (!restoring) dismissBackup()
    }
}
