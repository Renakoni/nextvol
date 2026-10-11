package indi.renakoni.nextvol.ui.home.explore.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.michaelbull.result.getOrElse
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.SourceCategory
import indi.renakoni.nextvol.data.web.sourceFailureMessage
import indi.renakoni.nextvol.ui.components.Cover
import io.nightfish.lightnovelreader.api.identifier.Identifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchHubScreen(
    state: SearchHubState, onQuery: (String) -> Unit, onSearch: (String) -> Unit,
    onScope: (SourceCategory?) -> Unit, onDeleteHistory: (String) -> Unit, onClearHistory: () -> Unit,
    onLoadMore: () -> Unit, onStop: () -> Unit, onResume: () -> Unit, onRetry: () -> Unit,
    onManageSources: () -> Unit, onSource: (Identifier) -> Unit, onBook: (String) -> Unit, onBack: () -> Unit,
    onSelectSource: (Identifier) -> Unit,
) {
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
    } else stringResource(state.scope?.title ?: R.string.source_range_all)
    val scopeDescription = stringResource(R.string.search_change_scope, scopeLabel)

    Scaffold(topBar = {
        Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.sources_back))
                }
                TextField(value = state.query, onValueChange = onQuery,
                    modifier = Modifier.weight(1f).testTag("search_query"),
                    placeholder = { Text(stringResource(R.string.search_input_hint), maxLines = 1) },
                    singleLine = true, shape = MaterialTheme.shapes.extraLarge,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit(state.query) }),
                    trailingIcon = if (state.query.isNotEmpty()) {{
                        IconButton(onClick = { onQuery("") }) {
                            Icon(painterResource(R.drawable.close_24px), stringResource(R.string.search_clear_query))
                        }
                    }} else null)
                IconButton(onClick = { submit(state.query) }, enabled = state.query.isNotBlank()) {
                    Icon(painterResource(R.drawable.search_24px), stringResource(R.string.search_hub_title))
                }
            }
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AssistChip(onClick = { keyboard?.hide(); focus.clearFocus(); showScope = true },
                    shape = MaterialTheme.shapes.extraLarge, border = null,
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.testTag("search_scope").semantics { contentDescription = scopeDescription }, label = {
                    Text(scopeLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }, trailingIcon = { Icon(painterResource(R.drawable.search_expand_24px), null, Modifier.size(18.dp)) })
                if (state.selectedSource == null) Text(stringResource(R.string.search_source_count, state.scopedSources.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            if (state.submittedKeyword.isBlank()) {
                val history = remember(state.history, state.query) {
                    state.history.filter { it.contains(state.query.trim(), ignoreCase = true) }
                }
                LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("search_history"), state = historyList,
                    contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (state.scopedSources.isEmpty()) item {
                        SearchEmptyScope(onManageSources, onChooseScope = { showScope = true })
                    }
                    if (history.isNotEmpty()) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.search_history), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                TextButton(onClick = onClearHistory) { Text(stringResource(R.string.clear_all)) }
                            }
                        }
                        items(history, key = { it }) { value ->
                            ListItem(headlineContent = { Text(value, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { Icon(painterResource(R.drawable.outline_schedule_24px), null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                trailingContent = { IconButton(onClick = { onDeleteHistory(value) }) {
                                    Icon(painterResource(R.drawable.close_24px), stringResource(R.string.search_delete_history_entry, value), Modifier.size(20.dp))
                                } }, modifier = Modifier.clickable { submit(value) }.padding(start = 8.dp, end = 4.dp))
                        }
                    } else if (state.scopedSources.isNotEmpty()) item {
                        SearchMessage(stringResource(R.string.search_start_title), stringResource(R.string.search_start_description))
                    }
                }
            } else {
                Column(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(stringResource(R.string.search_book_count, state.books.size), style = MaterialTheme.typography.titleSmall)
                            if (state.searching) Text(stringResource(R.string.search_progress, state.completed, state.total),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else if (state.stopped) Text(stringResource(R.string.search_stopped),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.searching) IconButton(onClick = onStop) {
                            Icon(painterResource(R.drawable.stop_24px), stringResource(R.string.search_stop))
                        } else if (state.stopped) TextButton(onClick = onResume) { Text(stringResource(R.string.search_resume)) }
                        if (state.failures.isNotEmpty()) TextButton(onClick = { showFailures = true }) {
                            Text(stringResource(R.string.search_failure_count, state.failures.size))
                        }
                    }
                    if (state.searching) LinearProgressIndicator(
                        progress = { if (state.total == 0) 0f else state.completed.toFloat() / state.total },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(2.dp))
                    LazyColumn(Modifier.weight(1f).testTag("search_results"), state = resultsList,
                        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                        if (state.scopedSources.isEmpty()) item { SearchEmptyScope(onManageSources, onChooseScope = { showScope = true }) }
                        else if (state.books.isEmpty() && !state.hasMore && !state.searching && !state.stopped) item {
                            if (state.failures.isEmpty()) {
                                SearchMessage(if (state.selectedSource != null) stringResource(R.string.search_source_empty, scopeLabel)
                                    else stringResource(R.string.search_no_results), stringResource(R.string.search_empty_description))
                                if (state.selectedSource != null || state.scope != null) TextButton(onClick = { onScope(null) },
                                    modifier = Modifier.padding(horizontal = 24.dp)) { Text(stringResource(R.string.search_expand_all)) }
                            }
                            else SearchMessage(stringResource(R.string.search_incomplete_title), stringResource(R.string.search_incomplete_description))
                        }
                        items(state.books, key = { it.id }, contentType = { "book" }) { book ->
                            SearchBookRow(book, onClick = { onBook(book.id) })
                        }
                        if (state.limited) item {
                            Text(stringResource(R.string.search_result_limit), Modifier.padding(24.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.hasMore && !state.searching && !state.stopped) item {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                FilledTonalButton(onClick = onLoadMore) { Text(stringResource(R.string.search_load_more)) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showScope) SearchScopeSheet(state, onDismiss = { showScope = false },
        onScope = { showScope = false; onScope(it) },
        onSource = { showScope = false; onSelectSource(it) })
    if (showFailures) ModalBottomSheet(onDismissRequest = { showFailures = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(stringResource(R.string.search_incomplete_title), Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.displayMedium)
                TextButton(onClick = { showFailures = false; onRetry() }, enabled = !state.searching,
                    modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.search_retry_failed)) }
            }
            items(state.failures, key = { it.id.toString() }) { source ->
                ListItem(headlineContent = { Text(source.name) },
                    supportingContent = { Text(stringResource(sourceFailureMessage(source.failure!!.error))) },
                    trailingContent = { Icon(painterResource(R.drawable.arrow_forward_ios_24px), stringResource(R.string.sources_title), Modifier.size(16.dp)) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clickable { showFailures = false; onSource(source.id) })
            }
        }
    }
}

@Composable
private fun SearchBookRow(book: SearchHubBook, onClick: () -> Unit) {
    val result by key(book.information) { book.information.collectAsStateWithLifecycle(initialValue = null) }
    val info = book.preview ?: result?.getOrElse { null }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Cover(book.id, 80.dp, 112.dp, info?.coverUri ?: android.net.Uri.EMPTY,
            info?.title.orEmpty(), author = info?.author.orEmpty())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(info?.title ?: stringResource(if (result?.isErr == true) R.string.search_book_unavailable else R.string.search_book_loading),
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!info?.author.isNullOrBlank()) Text(info!!.author, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!info?.description.isNullOrBlank()) Text(info!!.description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(book.sourceName, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SearchMessage(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SearchEmptyScope(onManageSources: () -> Unit, onChooseScope: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.search_no_sources), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.search_no_sources_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onChooseScope) { Text(stringResource(R.string.search_scope_title)) }
        FilledTonalButton(onClick = onManageSources) { Text(stringResource(R.string.sources_title)) }
    }
}
