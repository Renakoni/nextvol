package indi.renakoni.nextvol.ui.bangumi

import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.SubcomposeAsyncImage
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.bangumi.BangumiSyncRecord
import indi.renakoni.nextvol.data.bangumi.BangumiSyncStatus
import indi.renakoni.nextvol.data.bangumi.BangumiVolumeMapping
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPillTabRow
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.utils.navigationBarSpacer
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import java.text.DateFormat
import java.util.Date

private enum class BangumiStage {
    Overview,
    Records,
    RecordDetails,
    MatchSearch,
    VolumeMapping,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BangumiScreen(
    state: BangumiUiState,
    bookId: String?,
    onBack: () -> Unit,
    onAccount: () -> Unit,
    onBook: (String) -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onMore: () -> Unit,
    onChoose: (Int) -> Unit,
    onSync: () -> Unit,
    onMapping: (String, String?) -> Unit,
    onComplete: (String, Boolean) -> Unit,
    onBaseline: (String, Boolean) -> Unit,
    onPrivate: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var token by remember { mutableStateOf("") }
    var disconnect by remember { mutableStateOf(false) }
    var showRecords by rememberSaveable { mutableStateOf(false) }
    var errorRecords by rememberSaveable { mutableStateOf(false) }
    var recordBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var historyAccountId by rememberSaveable { mutableStateOf(state.account.user?.id) }
    var changeToken by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val recordsListState = rememberLazyListState()
    val recordDetailsState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val recordGroups = remember(state.records) {
        state.records
            .sortedWith(compareByDescending<BangumiSyncRecord> { it.timestamp }.thenByDescending { it.id })
            .groupBy { it.bookId }
            .values
            .toList()
    }
    val bookRecords = recordGroups.find { it.first().bookId == recordBookId }.orEmpty()
    val backFromRecords = { if (recordBookId != null) recordBookId = null else showRecords = false }

    val stage = when {
        bookId == null && showRecords && recordBookId != null -> BangumiStage.RecordDetails
        bookId == null && showRecords -> BangumiStage.Records
        bookId == null -> BangumiStage.Overview
        state.preview != null -> BangumiStage.VolumeMapping
        else -> BangumiStage.MatchSearch
    }

    LaunchedEffect(bookId, state.preview?.subject?.id) { listState.scrollToItem(0) }
    LaunchedEffect(state.account.loaded, state.account.user?.id) {
        token = ""
        changeToken = false
        if (state.account.loaded && historyAccountId != state.account.user?.id) {
            showRecords = false
            errorRecords = false
            recordBookId = null
            historyAccountId = state.account.user?.id
        }
    }
    LaunchedEffect(state.busy, state.error) { if (!state.busy && state.error == null) changeToken = false }
    BackHandler(state.preview != null && !state.busy, onDismiss)
    BackHandler(showRecords, backFromRecords)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            EditorialTopBar(
                title = stringResource(
                    when (stage) {
                        BangumiStage.RecordDetails -> R.string.bangumi_record_details
                        BangumiStage.Records -> R.string.bangumi_records
                        BangumiStage.MatchSearch, BangumiStage.VolumeMapping -> R.string.bangumi_correct_match
                        BangumiStage.Overview -> R.string.bangumi_title
                    },
                ),
                scrollBehavior = scrollBehavior,
                hazeState = null,
                frosted = false,
                navigationIcon = {
                    EditorialIconButton(
                        onClick = {
                            when {
                                showRecords -> backFromRecords()
                                state.preview != null -> onDismiss()
                                else -> onBack()
                            }
                        },
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
                .padding(padding),
        ) {
            if (state.busy || !state.account.loaded) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.error?.let { error ->
                EditorialCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    cornerRadius = 16.dp,
                    containerColor = colors.errorContainer.copy(alpha = if (tokens.isDark) 0.30f else 0.40f),
                    borderColor = colors.error.copy(alpha = 0.40f),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.error,
                    )
                }
            }

            AnimatedContent(
                targetState = stage,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    fadeIn(tween(200, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(150))
                },
                label = "bangumiStageTransition",
            ) { currentStage ->
                when (currentStage) {
                    BangumiStage.RecordDetails -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = recordDetailsState,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            bookRecords.firstOrNull()?.let { latest ->
                                item(key = "details_header") {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = latest.bookTitle,
                                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.W700),
                                            color = colors.onSurface,
                                        )
                                        Text(
                                            text = stringResource(R.string.bangumi_record_count, bookRecords.size),
                                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                            color = colors.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            if (bookRecords.isEmpty()) {
                                item {
                                    EmptyPage(
                                        icon = painterResource(R.drawable.outline_schedule_24px),
                                        title = stringResource(R.string.bangumi_no_records),
                                        description = "",
                                    )
                                }
                            }
                            items(bookRecords, key = { it.id }) { record ->
                                EditorialCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    cornerRadius = 18.dp,
                                    contentPadding = PaddingValues(16.dp),
                                ) {
                                    RecordSummary(record)
                                }
                            }
                            navigationBarSpacer()
                        }
                    }

                    BangumiStage.Records -> {
                        val tabLabels = listOf(
                            stringResource(R.string.bangumi_records_success),
                            stringResource(R.string.bangumi_records_error),
                        )
                        val groups = recordGroups.filter { it.first().status.successful != errorRecords }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = recordsListState,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            item(key = "records_tabs") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    EditorialPillTabRow(
                                        tabs = tabLabels,
                                        selectedTabIndex = if (errorRecords) 1 else 0,
                                        onTabSelected = { idx ->
                                            errorRecords = idx == 1
                                            scope.launch { recordsListState.scrollToItem(0) }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(
                                        text = stringResource(R.string.bangumi_records_help),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                    )
                                }
                            }
                            if (groups.isEmpty()) {
                                item {
                                    EmptyPage(
                                        icon = painterResource(R.drawable.outline_schedule_24px),
                                        title = stringResource(R.string.bangumi_no_records),
                                        description = "",
                                    )
                                }
                            }
                            items(groups, key = { it.first().bookId }) { records ->
                                val record = records.first()
                                EditorialCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    cornerRadius = 18.dp,
                                    contentPadding = PaddingValues(16.dp),
                                    onClick = {
                                        recordBookId = record.bookId
                                        scope.launch { recordDetailsState.scrollToItem(0) }
                                    },
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(
                                                text = record.bookTitle,
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W600),
                                                color = colors.onSurface,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Icon(
                                                painter = painterResource(R.drawable.arrow_forward_ios_24px),
                                                contentDescription = stringResource(R.string.bangumi_record_details),
                                                modifier = Modifier.size(14.dp),
                                                tint = colors.onSurfaceVariant,
                                            )
                                        }
                                        RecordSummary(record)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = stringResource(R.string.bangumi_record_count, records.size),
                                                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                                color = colors.onSurfaceVariant,
                                            )
                                            if (state.bindings.any { it.bookId == record.bookId }) {
                                                if (record.status == BangumiSyncStatus.AUTH_REQUIRED) {
                                                    TextButton(
                                                        onClick = {
                                                            showRecords = false
                                                            recordBookId = null
                                                            changeToken = true
                                                        },
                                                    ) {
                                                        Text(stringResource(R.string.bangumi_change_token))
                                                    }
                                                } else {
                                                    TextButton(
                                                        onClick = { onBook(record.bookId) },
                                                        enabled = !state.busy,
                                                    ) {
                                                        Text(stringResource(R.string.bangumi_correct_match))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            navigationBarSpacer()
                        }
                    }

                    BangumiStage.Overview -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            item(key = "account_card") {
                                EditorialCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    cornerRadius = 22.dp,
                                    contentPadding = PaddingValues(18.dp),
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                        val user = state.account.user
                                        if (user != null) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(52.dp)
                                                        .clip(CircleShape)
                                                        .background(tokens.iconBadgeContainer)
                                                        .border(1.dp, tokens.activePillBorder, CircleShape),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    SubcomposeAsyncImage(
                                                        model = user.avatar.url.takeIf { it.isNotBlank() },
                                                        contentDescription = null,
                                                        modifier = Modifier
                                                            .size(52.dp)
                                                            .clip(CircleShape),
                                                        contentScale = ContentScale.Crop,
                                                        loading = {
                                                            Icon(
                                                                painterResource(R.drawable.public_24px),
                                                                null,
                                                                tint = colors.primary,
                                                                modifier = Modifier.size(22.dp),
                                                            )
                                                        },
                                                        error = {
                                                            Icon(
                                                                painterResource(R.drawable.public_24px),
                                                                null,
                                                                tint = colors.primary,
                                                                modifier = Modifier.size(22.dp),
                                                            )
                                                        },
                                                    )
                                                }
                                                Column(
                                                    modifier = Modifier.weight(1f),
                                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                                ) {
                                                    Text(
                                                        text = user.nickname.ifBlank { user.username },
                                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W700),
                                                        color = colors.onSurface,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    StatusPill(
                                                        text = stringResource(R.string.bangumi_connected),
                                                        containerColor = tokens.activePillFill,
                                                        contentColor = colors.primary,
                                                    )
                                                }
                                            }
                                            if (!changeToken) {
                                                EditorialHairlineDivider()
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                ) {
                                                    FilledTonalButton(
                                                        onClick = { changeToken = true },
                                                        enabled = !state.busy,
                                                        shape = RoundedCornerShape(12.dp),
                                                        modifier = Modifier.weight(1f),
                                                    ) {
                                                        Text(stringResource(R.string.bangumi_change_token))
                                                    }
                                                    OutlinedButton(
                                                        onClick = { disconnect = true },
                                                        enabled = !state.busy,
                                                        shape = RoundedCornerShape(12.dp),
                                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                                                        modifier = Modifier.weight(1f),
                                                    ) {
                                                        Text(stringResource(R.string.bangumi_disconnect))
                                                    }
                                                }
                                            }
                                        } else {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                EditorialIconBadge(
                                                    painter = painterResource(R.drawable.public_24px),
                                                    size = 42.dp,
                                                    iconSize = 21.dp,
                                                    cornerRadius = 13.dp,
                                                )
                                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Text(
                                                        text = stringResource(R.string.bangumi_connect),
                                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W700),
                                                        color = colors.onSurface,
                                                    )
                                                    Text(
                                                        text = stringResource(R.string.bangumi_description),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = colors.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                        if (state.account.unreadable) {
                                            Text(
                                                text = stringResource(R.string.bangumi_error_storage),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.error,
                                            )
                                        }
                                        if (user == null || changeToken) {
                                            OutlinedTextField(
                                                value = token,
                                                onValueChange = { token = it },
                                                label = { Text(stringResource(R.string.bangumi_token)) },
                                                visualTransformation = PasswordVisualTransformation(),
                                                keyboardOptions = KeyboardOptions(
                                                    keyboardType = KeyboardType.Password,
                                                    autoCorrectEnabled = false,
                                                ),
                                                singleLine = true,
                                                shape = RoundedCornerShape(tokens.controlRadius),
                                                modifier = Modifier.fillMaxWidth(),
                                                enabled = !state.busy && state.account.loaded,
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Button(
                                                    onClick = {
                                                        val value = token
                                                        token = ""
                                                        onConnect(value)
                                                    },
                                                    enabled = token.isNotBlank() && !state.busy && state.account.loaded,
                                                    shape = RoundedCornerShape(12.dp),
                                                ) {
                                                    Text(stringResource(R.string.bangumi_connect))
                                                }
                                                if (user != null) {
                                                    TextButton(
                                                        onClick = {
                                                            changeToken = false
                                                            token = ""
                                                        },
                                                        enabled = !state.busy,
                                                    ) {
                                                        Text(stringResource(R.string.cancel))
                                                    }
                                                }
                                            }
                                            TextButton(
                                                onClick = { uri.openUri("https://next.bgm.tv/demo/access-token") },
                                                contentPadding = PaddingValues(horizontal = 4.dp),
                                            ) {
                                                Text(stringResource(R.string.bangumi_open_token))
                                            }
                                        }
                                    }
                                }
                            }

                            if (state.account.user != null) {
                                item(key = "sync_overview") {
                                    val synced = state.bindings.count {
                                        it.binding().let { value -> value.status.successful && value.lastSyncedAt != null }
                                    }
                                    EditorialCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        cornerRadius = 22.dp,
                                        contentPadding = PaddingValues(18.dp),
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.bangumi_sync_state),
                                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W600),
                                                    color = colors.onSurface,
                                                )
                                                TextButton(onClick = { showRecords = true }) {
                                                    Text(stringResource(R.string.bangumi_records))
                                                }
                                            }
                                            EditorialHairlineDivider()
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                SyncCount(
                                                    count = synced,
                                                    label = stringResource(R.string.bangumi_synced_count),
                                                    accent = colors.primary,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                VerticalDivider(Modifier.height(40.dp), color = tokens.divider)
                                                SyncCount(
                                                    count = state.bindings.size - synced,
                                                    label = stringResource(R.string.bangumi_unsynced_count),
                                                    accent = colors.onSurface,
                                                    modifier = Modifier.weight(1f),
                                                )
                                            }
                                            if (state.bindings.isEmpty()) {
                                                Text(
                                                    text = stringResource(R.string.bangumi_no_reading_books),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = colors.onSurfaceVariant,
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                            }
                                            Button(
                                                onClick = onSync,
                                                enabled = !state.busy,
                                                shape = RoundedCornerShape(14.dp),
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.bangumi_sync),
                                                    fontWeight = FontWeight.W600,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            navigationBarSpacer()
                        }
                    }

                    BangumiStage.VolumeMapping -> {
                        val preview = state.preview ?: return@AnimatedContent
                        val count = state.mapping.mapNotNull { it.editionKey }.distinct().size
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            item(key = "mapping_header") {
                                EditorialCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    cornerRadius = 20.dp,
                                    contentPadding = PaddingValues(16.dp),
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = preview.subject.title,
                                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.W700),
                                            color = colors.onSurface,
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            StatusPill(
                                                text = stringResource(R.string.bangumi_mapping_summary, count, preview.subject.volumes),
                                                containerColor = tokens.activePillFill,
                                                contentColor = colors.primary,
                                            )
                                            StatusPill(
                                                text = stringResource(R.string.bangumi_baseline_count, state.baseline.size),
                                                containerColor = tokens.cardElevatedSurface,
                                                contentColor = colors.onSurfaceVariant,
                                            )
                                        }
                                        if (!preview.subject.series) {
                                            Text(
                                                text = stringResource(R.string.bangumi_single_help),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.onSurfaceVariant,
                                            )
                                        }
                                        Text(
                                            text = stringResource(R.string.bangumi_mapping_help),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                        )
                                        Text(
                                            text = stringResource(R.string.bangumi_baseline_help, preview.remote?.volumes ?: 0),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                        )
                                        if (preview.remote != null && preview.remote.type != 3) {
                                            Text(
                                                text = stringResource(R.string.bangumi_resume_warning),
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.W600),
                                                color = colors.tertiary,
                                            )
                                        }
                                    }
                                }
                            }
                            items(state.mapping, key = { it.volumeId }) { row ->
                                MappingCard(
                                    row = row,
                                    all = state.mapping,
                                    originalEditionKey = preview.mapping.first { it.volumeId == row.volumeId }.editionKey,
                                    baseline = row.editionKey in state.baseline,
                                    showBaseline = (preview.remote?.volumes ?: 0) > 0,
                                    enabled = !state.busy,
                                    onMapping = onMapping,
                                    onComplete = onComplete,
                                    onBaseline = onBaseline,
                                )
                            }
                            if (preview.remote == null) {
                                item(key = "private_option") {
                                    EditorialCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        cornerRadius = 16.dp,
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                    ) {
                                        Choice(
                                            label = stringResource(R.string.bangumi_private),
                                            checked = state.privateCollection,
                                            enabled = !state.busy,
                                            onChange = onPrivate,
                                        )
                                    }
                                }
                            }
                            item(key = "mapping_actions") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = onConfirm,
                                        enabled = !state.busy && count > 0 && (preview.subject.series || count == 1) &&
                                            state.baseline.size <= (preview.remote?.volumes ?: 0),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(stringResource(R.string.bangumi_confirm), fontWeight = FontWeight.W600)
                                    }
                                    TextButton(
                                        onClick = onDismiss,
                                        enabled = !state.busy,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                }
                            }
                            navigationBarSpacer()
                        }
                    }

                    BangumiStage.MatchSearch -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            state.book?.let { book ->
                                item(key = "book_header") {
                                    Text(
                                        text = book.title,
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.W700),
                                        color = colors.onSurface,
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                    )
                                }
                            }
                            state.bindings.find { it.bookId == bookId && it.subjectId != null }?.let { entity ->
                                item(key = "existing_binding") {
                                    EditorialCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        cornerRadius = 18.dp,
                                        contentPadding = PaddingValues(16.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Text(
                                                text = entity.binding().subjectTitle,
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W600),
                                                color = colors.onSurface,
                                                modifier = Modifier.weight(1f),
                                            )
                                            FilledTonalButton(
                                                onClick = { onChoose(requireNotNull(entity.subjectId)) },
                                                enabled = !state.busy,
                                                shape = RoundedCornerShape(12.dp),
                                            ) {
                                                Text(stringResource(R.string.bangumi_review_mapping))
                                            }
                                        }
                                    }
                                }
                            }
                            if (state.account.user == null) {
                                item(key = "need_account") {
                                    EditorialCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        cornerRadius = 18.dp,
                                        contentPadding = PaddingValues(16.dp),
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text(
                                                text = stringResource(R.string.bangumi_connect_before_binding),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = colors.onSurfaceVariant,
                                            )
                                            Button(onClick = onAccount, shape = RoundedCornerShape(12.dp)) {
                                                Text(stringResource(R.string.bangumi_connect))
                                            }
                                        }
                                    }
                                }
                            }
                            item(key = "search") {
                                EditorialCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    cornerRadius = 20.dp,
                                    contentPadding = PaddingValues(16.dp),
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        OutlinedTextField(
                                            value = state.query,
                                            onValueChange = onQuery,
                                            label = { Text(stringResource(R.string.bangumi_search_hint)) },
                                            singleLine = true,
                                            shape = RoundedCornerShape(tokens.controlRadius),
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = !state.busy,
                                        )
                                        Text(
                                            text = stringResource(R.string.bangumi_link_help),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = onSearch,
                                                enabled = state.query.isNotBlank() && !state.busy && !state.searching,
                                                shape = RoundedCornerShape(12.dp),
                                            ) {
                                                Text(stringResource(R.string.bangumi_search))
                                            }
                                            FilledTonalButton(
                                                onClick = {
                                                    val clip = context.getSystemService(ClipboardManager::class.java)
                                                    clip?.primaryClip
                                                        ?.takeIf { it.itemCount > 0 }
                                                        ?.getItemAt(0)
                                                        ?.coerceToText(context)
                                                        ?.toString()
                                                        ?.let(onQuery)
                                                },
                                                enabled = !state.busy,
                                                shape = RoundedCornerShape(12.dp),
                                            ) {
                                                Text(stringResource(R.string.bangumi_paste_link))
                                            }
                                        }
                                    }
                                }
                            }
                            if (state.searching) {
                                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                            }
                            if (state.searched && state.candidates.isEmpty() && !state.searching) {
                                item {
                                    EmptyPage(
                                        icon = painterResource(R.drawable.not_found_90dp),
                                        title = stringResource(R.string.bangumi_no_results),
                                        description = "",
                                    )
                                }
                            }
                            items(state.candidates, key = { it.subject.id }) { candidate ->
                                EditorialCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    cornerRadius = 18.dp,
                                    contentPadding = PaddingValues(16.dp),
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = candidate.subject.title,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W700),
                                            color = colors.onSurface,
                                        )
                                        if (candidate.subject.name != candidate.subject.title) {
                                            Text(
                                                text = candidate.subject.name,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.onSurfaceVariant,
                                            )
                                        }
                                        val authors = candidate.subject.values("作者").joinToString(" / ")
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            StatusPill(
                                                text = stringResource(
                                                    if (candidate.subject.series && candidate.subject.isNovel) R.string.bangumi_series
                                                    else R.string.bangumi_single,
                                                    candidate.subject.volumes,
                                                ),
                                                containerColor = tokens.cardElevatedSurface,
                                                contentColor = colors.onSurfaceVariant,
                                            )
                                            if (authors.isNotBlank()) {
                                                Text(
                                                    text = authors,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = colors.primary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            if (candidate.titleMatches) {
                                                StatusPill(
                                                    text = stringResource(R.string.bangumi_title_matches),
                                                    containerColor = tokens.activePillFill,
                                                    contentColor = colors.primary,
                                                )
                                            }
                                            if (candidate.authorMatches) {
                                                StatusPill(
                                                    text = stringResource(R.string.bangumi_author_matches),
                                                    containerColor = tokens.activePillFill,
                                                    contentColor = colors.primary,
                                                )
                                            }
                                            if (candidate.publisherMatches) {
                                                StatusPill(
                                                    text = stringResource(R.string.bangumi_publisher_matches),
                                                    containerColor = tokens.activePillFill,
                                                    contentColor = colors.primary,
                                                )
                                            }
                                            if (candidate.authorConflicts) {
                                                StatusPill(
                                                    text = stringResource(R.string.bangumi_author_check),
                                                    containerColor = colors.errorContainer.copy(alpha = 0.72f),
                                                    contentColor = colors.error,
                                                )
                                            }
                                        }
                                        EditorialHairlineDivider()
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                        ) {
                                            FilledTonalButton(
                                                onClick = { onChoose(candidate.subject.id) },
                                                enabled = state.account.user != null && !state.busy,
                                                shape = RoundedCornerShape(12.dp),
                                            ) {
                                                Text(stringResource(R.string.bangumi_review_mapping))
                                            }
                                        }
                                    }
                                }
                            }
                            if (state.hasMore) {
                                item {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        FilledTonalButton(
                                            onClick = onMore,
                                            enabled = !state.searching && !state.busy,
                                            shape = RoundedCornerShape(14.dp),
                                        ) {
                                            Text(stringResource(R.string.bangumi_more))
                                        }
                                    }
                                }
                            }
                            navigationBarSpacer()
                        }
                    }
                }
            }
        }
    }

    if (disconnect) {
        val radius = tokens.sheetRadius
        Dialog(onDismissRequest = { disconnect = false }) {
            Column(
                modifier = Modifier
                    .sizeIn(minWidth = 280.dp, maxWidth = 420.dp)
                    .squircleBackground(color = tokens.cardElevatedSurface, cornerRadius = radius)
                    .squircleBorder(width = 0.85.dp, color = tokens.cardBorder, cornerRadius = radius)
                    .squircleClip(radius)
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EditorialIconBadge(
                    painter = painterResource(R.drawable.link_off_24px),
                    size = 48.dp,
                    iconSize = 24.dp,
                    cornerRadius = 15.dp,
                    containerColor = colors.errorContainer.copy(alpha = 0.72f),
                    contentColor = colors.error,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.bangumi_disconnect),
                    style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.W600),
                    color = colors.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.bangumi_disconnect_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(22.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    TextButton(
                        onClick = { disconnect = false },
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = {
                            onDisconnect()
                            disconnect = false
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.error,
                            contentColor = colors.onError,
                        ),
                    ) {
                        Text(stringResource(R.string.confirm), fontWeight = FontWeight.W600)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.W600,
                fontFeatureSettings = "tnum",
            ),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RecordSummary(record: BangumiSyncRecord) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (record.httpStatus != null) {
            StatusPill(
                text = stringResource(
                    R.string.bangumi_http_failure,
                    record.httpStatus,
                    stringResource(bangumiHttpError(record.httpStatus)),
                ),
                containerColor = colors.errorContainer.copy(alpha = 0.72f),
                contentColor = colors.error,
            )
        } else {
            val isSuccess = record.status.successful
            StatusPill(
                text = stringResource(
                    when {
                        isSuccess -> R.string.bangumi_status_synced
                        record.status == BangumiSyncStatus.OFFLINE -> R.string.bangumi_no_response
                        record.status == BangumiSyncStatus.REQUEST_REJECTED -> R.string.bangumi_invalid_response
                        else -> statusText(record.status)
                    },
                ),
                containerColor = if (isSuccess) tokens.activePillFill else colors.errorContainer.copy(alpha = 0.72f),
                contentColor = if (isSuccess) colors.primary else colors.error,
            )
        }
        if (record.pendingConfirmation) {
            Text(
                text = stringResource(R.string.bangumi_pending_confirmation),
                style = MaterialTheme.typography.bodySmall,
                color = colors.tertiary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.bangumi_progress, record.target, record.remote),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = colors.onSurface,
            )
            Text(
                text = DateFormat.getDateTimeInstance().format(Date(record.timestamp)),
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SyncCount(count: Int, label: String, accent: Color, modifier: Modifier) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "$label $count" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.displayMedium.copy(
                fontWeight = FontWeight.W700,
                fontFeatureSettings = "tnum",
            ),
            color = accent,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MappingCard(
    row: BangumiVolumeMapping,
    all: List<BangumiVolumeMapping>,
    originalEditionKey: String?,
    baseline: Boolean,
    showBaseline: Boolean,
    enabled: Boolean,
    onMapping: (String, String?) -> Unit,
    onComplete: (String, Boolean) -> Unit,
    onBaseline: (String, Boolean) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    var menu by remember { mutableStateOf(false) }

    EditorialCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Choice(row.title, row.editionKey != null, enabled) { checked ->
                onMapping(row.volumeId, if (checked) originalEditionKey ?: "local:${row.volumeId}" else null)
            }
            if (row.editionKey != null) {
                EditorialHairlineDivider(Modifier.padding(vertical = 4.dp))
                Choice(stringResource(R.string.bangumi_complete_volume), row.complete, enabled) {
                    onComplete(row.volumeId, it)
                }
                if (showBaseline) {
                    Choice(stringResource(R.string.bangumi_already_read), baseline, enabled) {
                        onBaseline(row.editionKey, it)
                    }
                }
                Box(Modifier.padding(top = 4.dp)) {
                    val first = all.first { it.editionKey == row.editionKey }
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(tokens.activePillFill)
                            .border(0.75.dp, tokens.activePillBorder, CircleShape)
                            .clickable(enabled = enabled) { menu = true }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.bangumi_count_as, first.title),
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.W600),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            painter = painterResource(R.drawable.search_expand_24px),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    EditorialPopupMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        EditorialPopupMenuItem(
                            text = stringResource(R.string.bangumi_separate_volume),
                            selected = row.editionKey == "local:${row.volumeId}",
                            onClick = {
                                menu = false
                                onMapping(row.volumeId, "local:${row.volumeId}")
                            },
                        )
                        all.filter { it.editionKey != null && it.volumeId != row.volumeId }
                            .distinctBy { it.editionKey }
                            .forEach { other ->
                                EditorialPopupMenuItem(
                                    text = other.title,
                                    selected = other.editionKey == row.editionKey,
                                    onClick = {
                                        menu = false
                                        onMapping(row.volumeId, other.editionKey)
                                    },
                                )
                            }
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun statusText(status: BangumiSyncStatus): Int = when (status) {
    BangumiSyncStatus.READY -> R.string.bangumi_status_ready
    BangumiSyncStatus.PENDING -> R.string.bangumi_status_pending
    BangumiSyncStatus.SYNCED -> R.string.bangumi_status_synced
    BangumiSyncStatus.REMOTE_AHEAD -> R.string.bangumi_status_ahead
    BangumiSyncStatus.AUTH_REQUIRED -> R.string.bangumi_error_auth
    BangumiSyncStatus.REMOTE_CHANGED -> R.string.bangumi_status_conflict
    BangumiSyncStatus.MAPPING_CHANGED -> R.string.bangumi_status_mapping
    BangumiSyncStatus.REMOTE_STATE -> R.string.bangumi_status_state
    BangumiSyncStatus.REMOTE_MISSING -> R.string.bangumi_status_missing
    BangumiSyncStatus.REQUEST_REJECTED -> R.string.bangumi_status_rejected
    BangumiSyncStatus.OFFLINE -> R.string.bangumi_status_offline
    BangumiSyncStatus.MATCH_REQUIRED -> R.string.bangumi_status_match
}
