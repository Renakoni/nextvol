package indi.renakoni.nextvol.ui.home.settings.logcat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.logging.LogEntry
import indi.renakoni.nextvol.data.logging.LogLevel
import indi.renakoni.nextvol.ui.components.SettingsMenuEntry
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar
import indi.renakoni.nextvol.ui.home.settings.data.SettingsMenuOptions
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.showSnackbar
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogcatScreen(
    uiState: LogcatUiState,
    logFiles: List<String>,
    logEntries: List<LogEntry>,
    logLevelKey: String,
    onLogLevelChange: (String) -> Unit,
    onClickBack: () -> Unit,
    onClickShareLogs: () -> Unit,
    onClickClearLogs: () -> Boolean,
    onSelectLogFile: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHost.current
    val restartToApplyText = stringResource(R.string.restart_to_apply_changes)
    val deletedText = stringResource(R.string.log_deleted)
    val deleteFailedText = stringResource(R.string.log_delete_failed)
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.selectedLogFile) {
        listState.scrollToItem(0)
    }
    LaunchedEffect(logEntries.size, uiState.isFileMode) {
        if (!uiState.isFileMode && logEntries.isNotEmpty()) {
            listState.scrollToItem(logEntries.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.logs_title, onClickBack)
        SettingsCategory {
            SettingsMenuEntry(
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
                painter = painterResource(R.drawable.bug_report_24px),
                title = stringResource(R.string.settings_app_log_level),
                description = stringResource(R.string.settings_app_log_level_desc),
                options = SettingsMenuOptions.LogLevelOptions,
                selectedOptionKey = logLevelKey,
                onOptionChange = { option ->
                    onLogLevelChange(option)
                    showSnackbar(coroutineScope, snackbarHostState, restartToApplyText) { }
                }
            )
        }

        Box(Modifier.weight(1f)) {
            if (logEntries.isEmpty()) {
                EmptyLogListContent(uiState.isFileMode, logLevelKey)
            } else {
                LogListContent(logEntries, listState)
            }
        }

        if (logFiles.any { it != LIVE_LOG_OPTION } || logEntries.isNotEmpty()) {
            LogToolbar(
                uiState = uiState,
                logFiles = logFiles,
                hasEntries = logEntries.isNotEmpty(),
                onSelectLogFile = onSelectLogFile,
                onShare = onClickShareLogs,
                onDelete = { confirmClear = true },
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(painterResource(R.drawable.delete_forever_24px), null) },
            title = { Text(stringResource(R.string.log_clear)) },
            text = { Text(stringResource(R.string.log_clear_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    val deleted = onClickClearLogs()
                    confirmClear = false
                    showSnackbar(coroutineScope, snackbarHostState, if (deleted) deletedText else deleteFailedText)
                }) { Text(stringResource(R.string.confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogToolbar(
    uiState: LogcatUiState,
    logFiles: List<String>,
    hasEntries: Boolean,
    onSelectLogFile: (String) -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var sourcesExpanded by remember { mutableStateOf(false) }
    val sourceDescription = stringResource(R.string.log_source)
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = logFiles.size > 1) { sourcesExpanded = true }
                        .semantics { contentDescription = sourceDescription }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LogSourceLabel(uiState.selectedLogFile, Modifier.weight(1f))
                    if (logFiles.size > 1) {
                        Spacer(Modifier.width(8.dp))
                        ExposedDropdownMenuDefaults.TrailingIcon(sourcesExpanded)
                    }
                }
                DropdownMenu(expanded = sourcesExpanded, onDismissRequest = { sourcesExpanded = false }) {
                    logFiles.forEach { file ->
                        DropdownMenuItem(
                            text = { LogSourceLabel(file) },
                            leadingIcon = {
                                Icon(painterResource(if (file == LIVE_LOG_OPTION) R.drawable.outline_schedule_24px else R.drawable.article_24px), null)
                            },
                            trailingIcon = {
                                if (file == uiState.selectedLogFile) Icon(painterResource(R.drawable.check_24px), null)
                            },
                            onClick = { onSelectLogFile(file); sourcesExpanded = false },
                        )
                    }
                }
            }
            IconButton(onClick = onShare, enabled = uiState.isFileMode || hasEntries) {
                Icon(painterResource(R.drawable.ios_share_24px), stringResource(R.string.export_and_share))
            }
            IconButton(onClick = onDelete) {
                Icon(painterResource(R.drawable.delete_forever_24px), stringResource(R.string.log_clear))
            }
        }
    }
}

@Composable
private fun LogSourceLabel(fileName: String, modifier: Modifier = Modifier) {
    val (label, timestamp) = logFileLabel(fileName)
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (timestamp != null) {
            Text(
                timestamp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun logFileLabel(fileName: String): Pair<String, String?> {
    if (fileName == LIVE_LOG_OPTION) return stringResource(R.string.log_live) to null
    val prefix = when {
        fileName.startsWith("lnr_export_") -> stringResource(R.string.log_shared)
        fileName.startsWith("lnr_panic_") -> stringResource(R.string.log_crash)
        else -> return fileName to null
    }
    val timestamp = runCatching {
        LocalDateTime.parse(fileName.removePrefix("lnr_export_").removePrefix("lnr_panic_").removeSuffix(".log"), DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
    }.getOrNull() ?: return fileName to null
    return prefix to timestamp
}

@Composable
private fun EmptyLogListContent(isFileMode: Boolean, logLevelKey: String) {
    val title = if (!isFileMode && logLevelKey == "none") R.string.log_recording_off else R.string.log_empty_list
    val description = when {
        isFileMode -> R.string.log_empty_file_desc
        logLevelKey == "none" -> R.string.log_recording_off_desc
        else -> R.string.log_empty_current_desc
    }
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.article_24px), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun colorOf(logLevel: LogLevel): Color {
    return when (logLevel.level) {
        2 -> MaterialTheme.colorScheme.error
        4 -> Color(0xFFF7B400)
        6 -> MaterialTheme.colorScheme.onSurface
        8 -> MaterialTheme.colorScheme.outline
        10 -> MaterialTheme.colorScheme.outlineVariant
        else -> MaterialTheme.colorScheme.outline
    }
}

@Composable
fun LogListContent(
    logEntries: List<LogEntry>,
    listState: LazyListState
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        contentPadding = PaddingValues(2.dp)
    ) {

        logEntries.forEach {
            item {
                Text(
                    text = it.text,
                    color = colorOf(it.logLevel),
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.sp,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    softWrap = true
                )
            }
        }
    }
}
