package indi.renakoni.nextvol.ui.home.settings.logcat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.logging.LogEntry
import indi.renakoni.nextvol.data.logging.LogLevel
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.SettingsMenuEntry
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
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
    onSelectLogFile: (String) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
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

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            EditorialTopBar(
                title = stringResource(R.string.logs_title),
                scrollBehavior = scrollBehavior,
                hazeState = null,
                frosted = false,
                navigationIcon = {
                    EditorialIconButton(
                        onClick = onClickBack,
                        painter = painterResource(R.drawable.arrow_back_24px),
                        contentDescription = stringResource(R.string.sources_back),
                    )
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
        ) {
            Box(Modifier.padding(top = 6.dp)) {
                SettingsCategory {
                    SettingsMenuEntry(
                        painter = painterResource(R.drawable.bug_report_24px),
                        title = stringResource(R.string.settings_app_log_level),
                        description = stringResource(R.string.settings_app_log_level_desc),
                        options = SettingsMenuOptions.LogLevelOptions,
                        selectedOptionKey = logLevelKey,
                        onOptionChange = { option ->
                            onLogLevelChange(option)
                            showSnackbar(coroutineScope, snackbarHostState, restartToApplyText) { }
                        },
                    )
                }
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
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            shape = RoundedCornerShape(tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
            icon = { Icon(painterResource(R.drawable.delete_forever_24px), null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.log_clear), fontWeight = FontWeight.W600) },
            text = { Text(stringResource(R.string.log_clear_all_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        val deleted = onClickClearLogs()
                        confirmClear = false
                        showSnackbar(coroutineScope, snackbarHostState, if (deleted) deletedText else deleteFailedText)
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.confirm), fontWeight = FontWeight.W600)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
internal fun LogToolbar(
    uiState: LogcatUiState,
    logFiles: List<String>,
    hasEntries: Boolean,
    onSelectLogFile: (String) -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var sourcesExpanded by remember { mutableStateOf(false) }
    val sourceDescription = stringResource(R.string.log_source)
    EditorialCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = logFiles.size > 1) { sourcesExpanded = true }
                        .semantics { contentDescription = sourceDescription }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LogSourceLabel(uiState.selectedLogFile, Modifier.weight(1f))
                    if (logFiles.size > 1) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            painter = painterResource(R.drawable.search_expand_24px),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                EditorialPopupMenu(
                    expanded = sourcesExpanded,
                    onDismissRequest = { sourcesExpanded = false },
                ) {
                    logFiles.forEach { file ->
                        val (label, timestamp) = logFileLabel(file)
                        EditorialPopupMenuItem(
                            text = label,
                            supportingText = timestamp,
                            leadingIcon = painterResource(
                                if (file == LIVE_LOG_OPTION) R.drawable.outline_schedule_24px else R.drawable.article_24px,
                            ),
                            selected = file == uiState.selectedLogFile,
                            onClick = {
                                onSelectLogFile(file)
                                sourcesExpanded = false
                            },
                        )
                    }
                }
            }
            EditorialIconButton(
                onClick = onShare,
                enabled = uiState.isFileMode || hasEntries,
                painter = painterResource(R.drawable.ios_share_24px),
                contentDescription = stringResource(R.string.export_and_share),
            )
            EditorialIconButton(
                onClick = onDelete,
                painter = painterResource(R.drawable.delete_forever_24px),
                contentDescription = stringResource(R.string.log_clear),
                contentColor = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun LogSourceLabel(fileName: String, modifier: Modifier = Modifier) {
    val (label, timestamp) = logFileLabel(fileName)
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.W600),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (timestamp != null) {
            Text(
                text = timestamp,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
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
        LocalDateTime.parse(
            fileName.removePrefix("lnr_export_").removePrefix("lnr_panic_").removeSuffix(".log"),
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"),
        ).format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
    }.getOrNull() ?: return fileName to null
    return prefix to timestamp
}

@Composable
internal fun EmptyLogListContent(isFileMode: Boolean, logLevelKey: String, showDescription: Boolean = true) {
    val title = if (!isFileMode && logLevelKey == "none") R.string.log_recording_off else R.string.log_empty_list
    val description = when {
        isFileMode -> R.string.log_empty_file_desc
        logLevelKey == "none" -> R.string.log_recording_off_desc
        else -> R.string.log_empty_current_desc
    }
    EmptyPage(
        icon = painterResource(R.drawable.article_24px),
        title = stringResource(title),
        description = if (showDescription) stringResource(description) else "",
    )
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
    listState: LazyListState,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
) {
    LazyColumn(
        state = listState,
        verticalArrangement = verticalArrangement,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        itemsIndexed(logEntries, key = { index, _ -> index }) { _, entry ->
            Text(
                text = entry.text,
                color = colorOf(entry.logLevel),
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.sp,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                softWrap = true,
            )
        }
    }
}
