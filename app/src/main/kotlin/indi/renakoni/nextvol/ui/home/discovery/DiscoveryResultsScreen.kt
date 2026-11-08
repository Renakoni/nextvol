package indi.renakoni.nextvol.ui.home.discovery

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.web.SourceDiscoveryBook
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.components.rememberListCover

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryResultsScreen(
    state: DiscoveryResultsState,
    onFilter: (Map<String, String>) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onScroll: (DiscoveryScroll) -> Unit,
    onBook: (SourceBookId) -> Unit,
    onManageSources: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    coverFor: suspend (String) -> Uri? = { null },
) {
    var showFilters by rememberSaveable(state.sourceName, state.definitions) { mutableStateOf(false) }
    val values = filterValues(state.definitions, state.filters)
    val defaults = filterValues(state.definitions, emptyMap())
    val activeFilters = state.definitions.filter { values[it.id] != defaults[it.id] }
    val list = key(state.resetId) { rememberLazyListState(state.scroll.index, state.scroll.offset) }
    LaunchedEffect(list) {
        snapshotFlow { DiscoveryScroll(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }.collect(onScroll)
    }
    Scaffold(topBar = {
        DiscoveryTopBar(state.title, state.sourceName, onBack, onRefresh, onSettings,
            onFilters = if (state.definitions.isEmpty()) null else { { showFilters = true } },
            filterCount = activeFilters.size)
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (activeFilters.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .clickable(role = Role.Button) { showFilters = true }
                .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                activeFilters.forEach { filter ->
                    Text(discoveryFilterSummary(filter, values.getValue(filter.id)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(state.books, key = { it.id.storageKey }, contentType = { "book" }) { book ->
                    DiscoveryBookRow(book, coverFor) { onBook(book.id) }
                }
                if (state.loading) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.discovery_preview_loading), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.error?.let { error -> item { DiscoveryFailure(error, onLoadMore, onManageSources, onBack, state.errorField, state.errorPermission, state.errorDiagnostic, state.errorHttpStatus) } }
                if (state.loaded && !state.loading && state.books.isEmpty() && state.error == null) item {
                    Text(stringResource(if (state.hasMore) R.string.discovery_no_page_matches else R.string.discovery_no_books),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.hasMore && state.error == null) item {
                    TextButton(onClick = onLoadMore, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.discovery_load_more))
                    }
                }
            }
        }
    }
    if (showFilters && state.definitions.isNotEmpty()) DiscoveryResultsFilters(
        state.definitions, values, onDismiss = { showFilters = false },
        onApply = { showFilters = false; onFilter(it) })
}

/** Discovery summaries share the source-aware cover and result renderer. Details are read only for a book listed without a cover. */
@Composable
internal fun DiscoveryBookRow(book: SourceDiscoveryBook, coverFor: suspend (String) -> Uri? = { null }, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Cover(book.id.storageKey, 92.dp, 138.dp, rememberListCover(book.id.storageKey, book.coverUrl, coverFor),
            book.title, author = book.author, rounded = 6.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
