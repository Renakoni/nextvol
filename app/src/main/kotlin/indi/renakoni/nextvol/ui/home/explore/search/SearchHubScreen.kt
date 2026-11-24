package indi.renakoni.nextvol.ui.home.explore.search

import android.net.Uri
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.michaelbull.result.getOrElse
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.bookDescriptionForDisplay
import indi.renakoni.nextvol.data.web.sourceFailureMessage
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryEmpty
import indi.renakoni.nextvol.utils.navigationBarSpacer
import io.nightfish.lightnovelreader.api.identifier.Identifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchHubScreen(
    state: SearchHubState,
    onQuery: (String) -> Unit,
    onSearch: (String) -> Unit,
    onScope: (String?) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onLoadMore: () -> Unit,
    onStop: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onManageSources: () -> Unit,
    onSource: (Identifier) -> Unit,
    onBook: (String) -> Unit,
    onBack: () -> Unit,
    onSelectSource: (Identifier) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val submit: (String) -> Unit = { value ->
        if (value.isNotBlank()) {
            keyboard?.hide()
            focus.clearFocus()
            onSearch(value)
        }
    }
    var showScope by rememberSaveable { mutableStateOf(false) }
    var showFailures by rememberSaveable { mutableStateOf(false) }
    val historyList = rememberLazyListState()
    val resultsList = rememberSaveable(state.revision, saver = LazyListState.Saver) { LazyListState() }
    val scopeLabel = if (state.selectedSource != null) {
        state.sources.firstOrNull { it.id == state.selectedSource }?.name
            ?: state.selectedSourceName.ifBlank { stringResource(R.string.search_selected_source) }
    } else {
        when (state.scope) {
            null -> stringResource(R.string.source_range_all)
            "" -> stringResource(R.string.source_group_ungrouped)
            else -> state.groups.firstOrNull { it.id == state.scope }?.name.orEmpty()
        }
    }
    val scopeDescription = stringResource(R.string.search_change_scope, scopeLabel)
    val hasCustomScope = state.selectedSource != null || state.scope != null

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    EditorialIconButton(
                        onClick = onBack,
                        painter = painterResource(R.drawable.arrow_back_24px),
                        contentDescription = stringResource(R.string.sources_back),
                    )
                    TextField(
                        value = state.query,
                        onValueChange = onQuery,
                        modifier = Modifier
                            .weight(1f)
                            .border(0.75.dp, tokens.cardBorder, CircleShape)
                            .testTag("search_query"),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.search_input_hint),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        singleLine = true,
                        shape = CircleShape,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = tokens.cardSurface,
                            unfocusedContainerColor = tokens.cardSurface,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit(state.query) }),
                        trailingIcon = if (state.query.isNotEmpty()) {
                            {
                                IconButton(onClick = { onQuery("") }) {
                                    Icon(
                                        painterResource(R.drawable.close_24px),
                                        stringResource(R.string.search_clear_query),
                                    )
                                }
                            }
                        } else {
                            null
                        },
                    )
                    EditorialIconButton(
                        onClick = { submit(state.query) },
                        enabled = state.query.isNotBlank(),
                        painter = painterResource(R.drawable.search_24px),
                        contentDescription = stringResource(R.string.search_hub_title),
                        containerColor = if (state.query.isNotBlank()) colors.primaryContainer.copy(alpha = 0.72f) else tokens.cardSurface,
                        contentColor = if (state.query.isNotBlank()) colors.primary else colors.onSurfaceVariant,
                    )
                }

                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (hasCustomScope) tokens.activePillFill else tokens.cardSurface)
                            .border(
                                width = if (hasCustomScope) 1.dp else 0.75.dp,
                                color = if (hasCustomScope) tokens.activePillBorder else tokens.cardBorder,
                                shape = CircleShape,
                            )
                            .clickable {
                                keyboard?.hide()
                                focus.clearFocus()
                                showScope = true
                            }
                            .testTag("search_scope")
                            .semantics {
                                role = Role.Button
                                contentDescription = scopeDescription
                            }
                            .padding(start = 14.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = scopeLabel,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.W600),
                            color = if (hasCustomScope) colors.primary else colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp),
                        )
                        Icon(
                            painter = painterResource(R.drawable.search_expand_24px),
                            contentDescription = null,
                            tint = if (hasCustomScope) colors.primary else colors.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (state.selectedSource == null) {
                        Text(
                            text = stringResource(R.string.search_source_count, state.scopedSources.size),
                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                EditorialHairlineDivider()
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (state.loadingSources) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (state.restorationFailed && state.scopedSources.isEmpty()) {
                DiscoveryEmpty(stringResource(R.string.sources_restoration_failed), onManageSources)
            } else if (state.submittedKeyword.isBlank()) {
                val history = remember(state.history, state.query) {
                    state.history.filter { it.contains(state.query.trim(), ignoreCase = true) }
                }
                if (state.scopedSources.isEmpty()) {
                    SearchEmptyScope(onManageSources, onChooseScope = { showScope = true })
                } else if (history.isEmpty()) {
                    EmptyPage(
                        icon = painterResource(R.drawable.search_24px),
                        title = stringResource(R.string.search_start_title),
                        description = stringResource(R.string.search_start_description),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .widthIn(max = 840.dp)
                            .fillMaxSize()
                            .testTag("search_history"),
                        state = historyList,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item(key = "history_header") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SectionHeader(
                                    modifier = Modifier.weight(1f),
                                    text = stringResource(R.string.search_history),
                                )
                                TextButton(onClick = onClearHistory) {
                                    Text(stringResource(R.string.clear_all))
                                }
                            }
                        }
                        item(key = "history_card") {
                            EditorialCard(
                                modifier = Modifier.fillMaxWidth(),
                                cornerRadius = 20.dp,
                            ) {
                                history.forEachIndexed { index, value ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { submit(value) }
                                            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.outline_schedule_24px),
                                            contentDescription = null,
                                            tint = colors.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            text = value,
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = colors.onSurface,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        IconButton(onClick = { onDeleteHistory(value) }) {
                                            Icon(
                                                painterResource(R.drawable.close_24px),
                                                stringResource(R.string.search_delete_history_entry, value),
                                                modifier = Modifier.size(18.dp),
                                                tint = colors.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (index != history.lastIndex) {
                                        EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                                    }
                                }
                            }
                        }
                        navigationBarSpacer()
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .widthIn(max = 840.dp)
                        .fillMaxSize(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.search_book_count, state.books.size),
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.W600,
                                    fontFeatureSettings = "tnum",
                                ),
                                color = colors.onSurface,
                            )
                            if (state.searching) {
                                Text(
                                    text = stringResource(R.string.search_progress, state.completed, state.total),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                    color = colors.onSurfaceVariant,
                                )
                            } else if (state.stopped) {
                                Text(
                                    text = stringResource(R.string.search_stopped),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                        if (state.searching) {
                            EditorialIconButton(
                                onClick = onStop,
                                painter = painterResource(R.drawable.stop_24px),
                                contentDescription = stringResource(R.string.search_stop),
                            )
                        } else if (state.stopped) {
                            FilledTonalButton(onClick = onResume, shape = CircleShape) {
                                Text(stringResource(R.string.search_resume))
                            }
                        }
                        if (state.failures.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(colors.errorContainer.copy(alpha = 0.65f))
                                    .border(0.75.dp, colors.error.copy(alpha = 0.35f), CircleShape)
                                    .clickable { showFailures = true }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.search_failure_count, state.failures.size),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.W600,
                                        fontFeatureSettings = "tnum",
                                    ),
                                    color = colors.error,
                                )
                            }
                        }
                    }

                    if (state.searching) {
                        LinearProgressIndicator(
                            progress = { if (state.total == 0) 0f else state.completed.toFloat() / state.total },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                                .height(3.dp)
                                .clip(CircleShape),
                        )
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("search_results"),
                        state = resultsList,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.scopedSources.isEmpty()) {
                            item { SearchEmptyScope(onManageSources, onChooseScope = { showScope = true }) }
                        } else if (state.books.isEmpty() && !state.hasMore && !state.searching && !state.stopped) {
                            item {
                                if (state.failures.isEmpty()) {
                                    EmptyPage(
                                        icon = painterResource(R.drawable.not_found_90dp),
                                        title = if (state.selectedSource != null) {
                                            stringResource(R.string.search_source_empty, scopeLabel)
                                        } else {
                                            stringResource(R.string.search_no_results)
                                        },
                                        description = stringResource(R.string.search_empty_description),
                                        content = if (hasCustomScope) {
                                            {
                                                FilledTonalButton(
                                                    onClick = { onScope(null) },
                                                    shape = RoundedCornerShape(14.dp),
                                                ) {
                                                    Text(stringResource(R.string.search_expand_all))
                                                }
                                            }
                                        } else {
                                            null
                                        },
                                    )
                                } else {
                                    EmptyPage(
                                        icon = painterResource(R.drawable.error_24px),
                                        title = stringResource(R.string.search_incomplete_title),
                                        description = stringResource(R.string.search_incomplete_description),
                                    )
                                }
                            }
                        }

                        items(state.books, key = { it.id }, contentType = { "book" }) { book ->
                            SearchBookRow(book, onClick = { onBook(book.id) })
                        }

                        if (state.limited) {
                            item {
                                Text(
                                    text = stringResource(R.string.search_result_limit),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                        if (state.hasMore && !state.searching && !state.stopped) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    FilledTonalButton(
                                        onClick = onLoadMore,
                                        shape = RoundedCornerShape(14.dp),
                                    ) {
                                        Text(stringResource(R.string.search_load_more))
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

    if (showScope) {
        SearchScopeSheet(
            state = state,
            onDismiss = { showScope = false },
            onScope = {
                showScope = false
                onScope(it)
            },
            onSource = {
                showScope = false
                onSelectSource(it)
            },
        )
    }

    if (showFailures) {
        ModalBottomSheet(
            onDismissRequest = { showFailures = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = RoundedCornerShape(topStart = tokens.sheetRadius, topEnd = tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
        ) {
            LazyColumn(
                modifier = Modifier.navigationBarsPadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.search_incomplete_title),
                            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.W600),
                            color = colors.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(
                            onClick = {
                                showFailures = false
                                onRetry()
                            },
                            enabled = !state.searching,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(stringResource(R.string.search_retry_failed))
                        }
                    }
                }
                items(state.failures, key = { it.id.toString() }) { source ->
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 16.dp,
                        onClick = {
                            showFailures = false
                            onSource(source.id)
                        },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = source.name,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W600),
                                    color = colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(sourceFailureMessage(source.failure!!.error)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.error,
                                )
                            }
                            Icon(
                                painter = painterResource(R.drawable.arrow_forward_ios_24px),
                                contentDescription = stringResource(R.string.sources_title),
                                modifier = Modifier.size(14.dp),
                                tint = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBookRow(book: SearchHubBook, onClick: () -> Unit) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val result by key(book.information) { book.information.collectAsStateWithLifecycle(initialValue = null) }
    val info = book.preview ?: result?.getOrElse { null }
    val description = remember(info?.id, info?.description) {
        info?.let { bookDescriptionForDisplay(it.id, it.description).trim() }.orEmpty()
    }

    EditorialCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(12.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(
                bookId = book.id,
                width = 84.dp,
                height = 122.dp,
                uri = info?.coverUri ?: Uri.EMPTY,
                title = info?.title.orEmpty(),
                rounded = 10.dp,
                author = info?.author.orEmpty(),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = info?.title ?: stringResource(
                        if (result?.isErr == true) R.string.search_book_unavailable else R.string.search_book_loading,
                    ),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W600),
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!info?.author.isNullOrBlank()) {
                    Text(
                        text = info.author,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.W600),
                        color = colors.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.cardElevatedSurface)
                        .border(0.6.dp, tokens.cardBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = book.sourceName,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchEmptyScope(onManageSources: () -> Unit, onChooseScope: () -> Unit) {
    EmptyPage(
        icon = painterResource(R.drawable.public_24px),
        title = stringResource(R.string.search_no_sources),
        description = stringResource(R.string.search_no_sources_description),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(
                onClick = onChooseScope,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(stringResource(R.string.search_scope_title))
            }
            FilledTonalButton(
                onClick = onManageSources,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(stringResource(R.string.sources_title))
            }
        }
    }
}
