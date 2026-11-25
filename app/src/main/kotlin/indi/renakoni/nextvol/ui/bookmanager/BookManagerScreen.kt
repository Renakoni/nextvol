package indi.renakoni.nextvol.ui.bookmanager

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.download.DownloadItem
import indi.renakoni.nextvol.data.download.DownloadStage
import indi.renakoni.nextvol.data.download.DownloadTaskStatus
import indi.renakoni.nextvol.data.download.DownloadType
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPillTabRow
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuHeader
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.ui.components.downloadStatusText
import indi.renakoni.nextvol.utils.formTime
import indi.renakoni.nextvol.utils.navigationBarSpacer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookManagerScreen(
    onClickBack: () -> Unit,
    downloadItemIdList: List<DownloadItem>,
    uiState: LocalBookManagerUiState,
    onClickCancel: (DownloadItem) -> Unit,
    onClickRetry: (DownloadItem) -> Unit,
    onClickClearCompleted: () -> Unit,
    onOpenDownload: (String) -> Unit = {},
    initialLocalTab: Boolean = false,
) {
    val tokens = NextVolDesign.tokens
    var tabIndex by rememberSaveable { mutableIntStateOf(if (initialLocalTab) 1 else 0) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var orphanDialogVisible by remember { mutableStateOf(false) }
    val selectingLocal = tabIndex == 1 && uiState.isSelecting

    BackHandler(enabled = selectingLocal) {
        uiState.exitSelection()
    }

    if (orphanDialogVisible) {
        AlertDialog(
            onDismissRequest = { orphanDialogVisible = false },
            shape = RoundedCornerShape(tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
            title = { Text(stringResource(R.string.book_manager_clear_orphaned_title)) },
            text = {
                Text(
                    text = stringResource(R.string.book_manager_clear_orphaned_content),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            dismissButton = {
                TextButton(onClick = { orphanDialogVisible = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        orphanDialogVisible = false
                        uiState.clearOrphanedData()
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            if (selectingLocal) {
                SelectingAppBar(uiState = uiState)
            } else {
                AppBar(
                    onClickBack = onClickBack,
                    tabIndex = tabIndex,
                    sortMenuExpanded = sortMenuExpanded,
                    onSortMenuExpandedChange = { sortMenuExpanded = it },
                    onOpenOrphanDialog = { orphanDialogVisible = true },
                    uiState = uiState,
                )
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding()),
        ) {
            AnimatedVisibility(
                visible = !selectingLocal,
                enter = fadeIn(tween(180)) + expandVertically(tween(200)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(180)),
            ) {
                EditorialPillTabRow(
                    tabs = listOf(
                        stringResource(R.string.book_manager_tab_downloads),
                        stringResource(R.string.book_manager_tab_local_books),
                    ),
                    selectedTabIndex = tabIndex,
                    onTabSelected = { nextIndex ->
                        if (uiState.isSelecting) uiState.exitSelection()
                        tabIndex = nextIndex
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                if (tabIndex == 0) {
                    DownloadManagerContent(
                        downloadItemIdList = downloadItemIdList,
                        onClickCancel = onClickCancel,
                        onClickRetry = onClickRetry,
                        onOpenDownload = onOpenDownload,
                        onClickClearCompleted = onClickClearCompleted,
                    )
                } else {
                    LocalBookManagerContent(
                        uiState = uiState,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppBar(
    onClickBack: () -> Unit,
    tabIndex: Int,
    sortMenuExpanded: Boolean,
    onSortMenuExpandedChange: (Boolean) -> Unit,
    onOpenOrphanDialog: () -> Unit,
    uiState: LocalBookManagerUiState,
) {
    EditorialTopBar(
        title = stringResource(R.string.book_manager_title),
        hazeState = null,
        frosted = false,
        navigationIcon = {
            EditorialIconButton(
                onClick = onClickBack,
                painter = painterResource(id = R.drawable.arrow_back_24px),
                contentDescription = stringResource(R.string.sources_back),
            )
        },
        actions = {
            if (tabIndex == 1) {
                Box {
                    EditorialIconButton(
                        enabled = uiState.bookList.isNotEmpty(),
                        onClick = { onSortMenuExpandedChange(true) },
                        painter = painterResource(id = R.drawable.filter_list_24px),
                        contentDescription = stringResource(R.string.action_sort),
                    )
                    EditorialPopupMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { onSortMenuExpandedChange(false) },
                    ) {
                        EditorialPopupMenuHeader(text = stringResource(R.string.action_sort))
                        LocalBookSortMenuOptions.optionList.forEach { option ->
                            val sort = LocalBookSort.valueOf(option.key)
                            EditorialPopupMenuItem(
                                text = stringResource(option.nameId),
                                selected = uiState.sort == sort,
                                onClick = {
                                    uiState.setSort(sort)
                                    onSortMenuExpandedChange(false)
                                },
                            )
                        }
                        EditorialHairlineDivider(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        EditorialPopupMenuItem(
                            text = stringResource(R.string.book_manager_sort_reverse),
                            selected = uiState.sortReverse,
                            onClick = {
                                uiState.setReverse(!uiState.sortReverse)
                            },
                        )
                    }
                }

                EditorialIconButton(
                    enabled = uiState.bookList.isNotEmpty(),
                    onClick = { uiState.enterSelection(null) },
                    painter = painterResource(id = R.drawable.checklist_24px),
                    contentDescription = stringResource(R.string.action_select),
                )

                EditorialIconButton(
                    onClick = onOpenOrphanDialog,
                    painter = painterResource(id = R.drawable.database_24px),
                    contentDescription = stringResource(R.string.book_manager_clear_orphaned),
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectingAppBar(
    uiState: LocalBookManagerUiState,
) {
    EditorialTopBar(
        title = stringResource(R.string.book_manager_selected_count, uiState.selectedIds.size),
        hazeState = null,
        frosted = false,
        showBottomDivider = true,
        navigationIcon = {
            EditorialIconButton(
                onClick = uiState.exitSelection,
                painter = painterResource(id = R.drawable.close_24px),
                contentDescription = stringResource(R.string.cancel),
            )
        },
    )
}

@Composable
private fun DownloadManagerContent(
    downloadItemIdList: List<DownloadItem>,
    onClickCancel: (DownloadItem) -> Unit,
    onClickRetry: (DownloadItem) -> Unit,
    onClickClearCompleted: () -> Unit,
    onOpenDownload: (String) -> Unit,
) {
    val itemList = downloadItemIdList.distinctBy { it.type to it.bookId }
    if (itemList.isEmpty()) {
        EmptyPage(
            modifier = Modifier.navigationBarsPadding(),
            icon = painterResource(id = R.drawable.download_24px),
            title = stringResource(R.string.nothing_here),
            description = stringResource(R.string.nothing_here_desc_book_manager),
        )
        return
    }
    val unfinished = remember(itemList) { itemList.filter { it.progress < 1f }.reversed() }
    val completed = remember(itemList) { itemList.filter { it.progress >= 1f }.reversed() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (unfinished.isNotEmpty()) {
            item(key = "header_unfinished") {
                SectionHeader(
                    modifier = Modifier
                        .padding(start = 4.dp, top = 4.dp, bottom = 2.dp)
                        .animateItem(),
                    text = stringResource(R.string.download_task_unfinished),
                )
            }
            items(
                items = unfinished,
                key = { "${it.type.name}_${it.bookId}" },
            ) { downloadItem ->
                DownloadTaskCard(
                    modifier = Modifier.animateItem(),
                    downloadItem = downloadItem,
                    onOpen = if (downloadItem.type == DownloadType.CACHE) {
                        { onOpenDownload(downloadItem.bookId) }
                    } else {
                        null
                    },
                    onClickCancel = { onClickCancel(downloadItem) },
                    onClickRetry = { onClickRetry(downloadItem) },
                )
            }
        }
        if (completed.isNotEmpty()) {
            item(key = "header_completed") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = if (unfinished.isNotEmpty()) 8.dp else 4.dp)
                        .animateItem(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader(text = stringResource(R.string.completed))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClickClearCompleted) {
                        Text(
                            text = stringResource(R.string.clear_all),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.W600,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            items(
                items = completed,
                key = { "${it.type.name}_${it.bookId}" },
            ) { downloadItem ->
                DownloadTaskCard(
                    modifier = Modifier.animateItem(),
                    downloadItem = downloadItem,
                    onOpen = if (downloadItem.type == DownloadType.CACHE) {
                        { onOpenDownload(downloadItem.bookId) }
                    } else {
                        null
                    },
                    onClickCancel = { onClickCancel(downloadItem) },
                    onClickRetry = { onClickRetry(downloadItem) },
                )
            }
        }
        navigationBarSpacer()
    }
}

@Composable
private fun DownloadTaskCard(
    modifier: Modifier = Modifier,
    downloadItem: DownloadItem,
    onOpen: (() -> Unit)?,
    onClickCancel: () -> Unit,
    onClickRetry: () -> Unit,
) {
    val progressAnim by animateFloatAsState(
        targetValue = downloadItem.progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "downloadProgress",
    )
    val result by downloadItem.bookInformationFlow.collectAsStateWithLifecycle(null)
    val bookInformation = result?.get()
    val title = bookInformation?.title ?: stringResource(
        R.string.download_task_unknown,
        BookIdentity.book(downloadItem.bookId).fileKey.take(8),
    )
    val manageLabel = stringResource(R.string.download_manage_selection)
    val statusFailed = downloadItem.progress < 0f
    val statusDone = downloadItem.progress >= 1f
    val statusTint = when {
        statusFailed -> MaterialTheme.colorScheme.error
        statusDone -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    EditorialCard(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onOpen != null) {
                    Modifier.semantics {
                        onClick(label = manageLabel, action = null)
                    }
                } else {
                    Modifier
                },
            ),
        contentPadding = PaddingValues(14.dp),
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (bookInformation != null) {
                Cover(
                    bookId = bookInformation.id,
                    width = 58.dp,
                    height = 84.dp,
                    uri = bookInformation.coverUri,
                    title = bookInformation.title,
                    author = bookInformation.author,
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.W600,
                )
                if (!bookInformation?.author.isNullOrBlank()) {
                    Text(
                        text = bookInformation.author,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.W500,
                        letterSpacing = 0.15.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        tint = statusTint,
                        modifier = Modifier.size(15.dp),
                        painter = when {
                            statusDone -> painterResource(R.drawable.done_outline_24px)
                            downloadItem.progress >= 0 -> painterResource(downloadItem.type.icon)
                            else -> painterResource(R.drawable.error_24px)
                        },
                        contentDescription = null,
                    )
                    Text(
                        text =
                            if (downloadItem.status != null) downloadStatusText(downloadItem.status!!)
                            else if (downloadItem.sourceError == io.nightfish.lightnovelreader.api.error.WebRequestErrorKind.AuthenticationRequired)
                                stringResource(R.string.source_login_download_paused)
                            else if (downloadItem.sourceError == io.nightfish.lightnovelreader.api.error.WebRequestErrorKind.VerificationRequired)
                                stringResource(R.string.source_verification_download_paused)
                            else if (downloadItem.progress >= 0 && downloadItem.progress < 1)
                                stringResource(
                                    R.string.download_item_progress,
                                    formTime(downloadItem.startTime),
                                    (downloadItem.progress * 100).toInt(),
                                )
                            else if (downloadItem.progress > 0)
                                stringResource(R.string.download_item_finished, stringResource(downloadItem.type.typeNameRes))
                            else stringResource(R.string.download_item_failed, stringResource(downloadItem.type.typeNameRes)),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.W500,
                        color = statusTint,
                    )
                }
                if (downloadItem.progress >= 0 && downloadItem.progress < 1) {
                    val task = downloadItem.status?.task
                    Spacer(Modifier.height(2.dp))
                    if (task?.status == DownloadTaskStatus.Queued ||
                        task?.stage in setOf(DownloadStage.Details, DownloadStage.Directory)
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(CircleShape),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(CircleShape),
                            progress = { progressAnim },
                        )
                    }
                }
            }
            val verifying = downloadItem.status?.task?.status == DownloadTaskStatus.WaitingVerification
            val canRetry = downloadItem.type == DownloadType.CACHE &&
                downloadItem.status?.task?.active == false &&
                (verifying || downloadItem.progress < 1f)
            if (canRetry || downloadItem.progress < 1 || downloadItem.type == DownloadType.CACHE) {
                Spacer(Modifier.width(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (canRetry) {
                        FilledTonalButton(
                            onClick = onClickRetry,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    if (verifying) R.string.download_task_verify else R.string.book_download_continue,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    if (downloadItem.progress < 1 || downloadItem.type == DownloadType.CACHE) {
                        EditorialIconButton(
                            onClick = onClickCancel,
                            painter = painterResource(R.drawable.cancel_24px),
                            contentDescription = stringResource(
                                if (downloadItem.type == DownloadType.CACHE) R.string.download_task_remove
                                else R.string.cancel,
                            ),
                        )
                    }
                }
            }
        }
    }
}
