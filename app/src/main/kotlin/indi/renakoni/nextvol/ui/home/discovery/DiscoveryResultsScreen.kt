package indi.renakoni.nextvol.ui.home.discovery

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    onFilter: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onScroll: (DiscoveryScroll) -> Unit,
    onBook: (SourceBookId) -> Unit,
    onManageSources: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    coverFor: suspend (String) -> Uri? = { null },
) {
    val list = key(state.resetId) { rememberLazyListState(state.scroll.index, state.scroll.offset) }
    LaunchedEffect(list) {
        snapshotFlow { DiscoveryScroll(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }.collect(onScroll)
    }
    Scaffold(topBar = { DiscoveryTopBar(state.title, onBack, onRefresh, onSettings) }) { padding ->
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(state.sourceName, style = MaterialTheme.typography.labelLarge) }
            items(state.definitions, key = { "filter:${it.id}" }) { filter ->
                DiscoveryFilterControl(filter, state.filters[filter.id].orEmpty()) { onFilter(filter.id, it) }
            }
            items(state.books, key = { it.id.storageKey }) { book -> DiscoveryBookCard(book, coverFor) { onBook(book.id) } }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error -> item { DiscoveryFailure(error, onLoadMore, onManageSources, onBack, state.errorField, state.errorPermission, state.errorDiagnostic, state.errorHttpStatus) } }
            if (state.loaded && !state.hasMore && state.books.isEmpty() && state.error == null) item { Text(stringResource(R.string.discovery_no_books)) }
            if (state.hasMore && state.error == null) item {
                OutlinedButton(onClick = onLoadMore, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.discovery_load_more))
                }
            }
        }
    }
}

/** Discovery summaries share the source-aware cover and result renderer. Details are read only for a book listed without a cover. */
@Composable
internal fun DiscoveryBookCard(book: SourceDiscoveryBook, coverFor: suspend (String) -> Uri? = { null }, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Cover(book.id.storageKey, 72.dp, 108.dp, rememberListCover(book.id.storageKey, book.coverUrl, coverFor), book.title, author = book.author)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(book.author, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
