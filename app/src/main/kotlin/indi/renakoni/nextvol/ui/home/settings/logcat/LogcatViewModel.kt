package indi.renakoni.nextvol.ui.home.settings.logcat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.logging.LogEntry
import indi.renakoni.nextvol.data.logging.LogFileSharer
import indi.renakoni.nextvol.data.logging.LoggerRepository
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import javax.inject.Inject

@HiltViewModel
class LogcatViewModel @Inject constructor (
    private val loggerRepository: LoggerRepository,
    userDataRepository: UserDataRepository,
    private val logFileSharer: LogFileSharer,
): ViewModel() {
    val logLevelUserData = userDataRepository.stringUserData(UserDataPath.Settings.Data.LogLevel.path)

    private val _uiState = MutableLogcatUiState()
    val uiState: LogcatUiState = _uiState
    var logFilenameList: List<String> by mutableStateOf(emptyList())
        private set

    init {
        refreshLogFiles()
        onSelectLogFile(logFilenameList.first())
    }

    fun startLogging() {
        loggerRepository.startLogging()
        refreshLogFiles()
        if (_uiState.selectedLogFile !in logFilenameList) onSelectLogFile(logFilenameList.first())
    }

    fun shareLogs() {
        val logFile = if (_uiState.isFileMode) {
            loggerRepository.prepareLogFile(_uiState.selectedLogFile)
        } else {
            loggerRepository.prepareLogFile()
        }
        if (logFile != null) logFileSharer.share(logFile)
        refreshLogFiles()
    }

    val displayedLogEntries: List<LogEntry>
        get() = if (_uiState.isFileMode) {
            loggerRepository.fileLogEntries
        } else {
            loggerRepository.realTimeLogEntries
        }

    fun deleteLogs(): Boolean {
        val deleted = loggerRepository.deleteLogs()
        refreshLogFiles()
        val selection = _uiState.selectedLogFile.takeIf { it in logFilenameList }
            ?: logFilenameList.first()
        onSelectLogFile(selection)
        return deleted
    }

    fun onSelectLogFile(fileName: String) {
        _uiState.isFileMode = fileName != LIVE_LOG_OPTION
        _uiState.selectedLogFile = fileName
        if (_uiState.isFileMode) loggerRepository.loadLogFile(fileName)
    }

    private fun refreshLogFiles() {
        logFilenameList = loggerRepository.getAvailableLogFiles() + LIVE_LOG_OPTION
    }
}
