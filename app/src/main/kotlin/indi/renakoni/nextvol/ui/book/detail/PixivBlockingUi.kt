package indi.renakoni.nextvol.ui.book.detail

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.rules.PixivBlockKind
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialPillTabRow
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem
import indi.renakoni.nextvol.utils.navigationBarSpacer
import indi.renakoni.nextvol.utils.textToast
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.launch

@Composable
internal fun PixivBlockingFeedback(model: PixivBlockingViewModel) {
    val context = LocalContext.current
    val message = model.state.message
    LaunchedEffect(message) {
        message?.let {
            model.consumeMessage()
            textToast(context, it, Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
internal fun PixivBlockMenu(model: PixivBlockingViewModel, dismiss: () -> Unit) {
    val state = model.state
    val book = state.book ?: return
    val blockIcon = painterResource(R.drawable.block_24px)
    EditorialPopupMenuItem(
        text = stringResource(R.string.pixiv_block_book),
        leadingIcon = blockIcon,
        destructive = true,
        enabled = !state.busy && !state.contains(PixivBlockKind.Book, book.key),
        onClick = {
            dismiss()
            model.request(PixivBlockKind.Book)
        },
    )
    if (book.authorId.isNotBlank()) {
        EditorialPopupMenuItem(
            text = stringResource(R.string.pixiv_block_author),
            leadingIcon = blockIcon,
            destructive = true,
            enabled = !state.busy && !state.contains(PixivBlockKind.Author, book.authorId),
            onClick = {
                dismiss()
                model.request(PixivBlockKind.Author)
            },
        )
    }
    if (book.tags.isNotEmpty()) {
        EditorialPopupMenuItem(
            text = stringResource(R.string.pixiv_block_tags),
            leadingIcon = painterResource(R.drawable.tag_24px),
            destructive = true,
            enabled = !state.busy,
            onClick = {
                dismiss()
                model.request(PixivBlockKind.Tag)
            },
        )
    }
}

@Composable
internal fun PixivBlockConfirmation(model: PixivBlockingViewModel) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val state = model.state
    val kind = state.confirmation ?: return
    val book = state.book ?: return
    var selected by remember(book.key, kind) { mutableStateOf(emptySet<String>()) }
    val isTags = kind == PixivBlockKind.Tag
    val title = when (kind) {
        PixivBlockKind.Book -> R.string.pixiv_block_book
        PixivBlockKind.Author -> R.string.pixiv_block_author
        else -> R.string.pixiv_block_tags
    }
    AlertDialog(
        onDismissRequest = model::cancel,
        shape = RoundedCornerShape(tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
        title = {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.W600),
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(if (isTags) R.string.pixiv_block_tag_hint else R.string.pixiv_block_scope),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (isTags) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(book.tags, key = { it }) { tag ->
                            val blocked = state.contains(PixivBlockKind.Tag, tag)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = blocked || tag in selected,
                                        enabled = !state.busy && !blocked,
                                        role = Role.Checkbox,
                                        onValueChange = { checked ->
                                            selected = if (checked) selected + tag else selected - tag
                                        },
                                    )
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = blocked || tag in selected,
                                    onCheckedChange = null,
                                    enabled = !state.busy && !blocked,
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 12.dp),
                                ) {
                                    Text(tag, style = MaterialTheme.typography.bodyLarge)
                                    if (blocked) {
                                        Text(
                                            text = stringResource(R.string.pixiv_block_already),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = if (kind == PixivBlockKind.Book) book.title else book.author.ifBlank { book.authorId },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W600),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !state.busy && (!isTags || selected.isNotEmpty()),
                onClick = { model.confirm(selected) },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.error,
                    contentColor = colors.onError,
                ),
            ) {
                Text(stringResource(R.string.pixiv_block_confirm), fontWeight = FontWeight.W600)
            }
        },
        dismissButton = {
            TextButton(enabled = !state.busy, onClick = model::cancel) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun PixivBlockManager(id: Identifier, model: PixivBlockingViewModel) {
    val colors = MaterialTheme.colorScheme
    val state = model.state
    val scope = rememberCoroutineScope()
    PixivBlockingFeedback(model)
    val kinds = listOf(PixivBlockKind.Author, PixivBlockKind.Tag, PixivBlockKind.Book) +
        listOfNotNull(PixivBlockKind.Caption.takeIf { state.rules.any { it.kind == PixivBlockKind.Caption } })
    var selected by remember(id) { mutableStateOf(PixivBlockKind.Author) }
    val kind = selected.takeIf { it in kinds } ?: PixivBlockKind.Author
    val rows = state.rules.filter { it.kind == kind }
    val counts = state.rules.groupingBy { it.kind }.eachCount()
    val tabLabels = kinds.map { tab ->
        val label = stringResource(
            when (tab) {
                PixivBlockKind.Author -> R.string.pixiv_block_authors
                PixivBlockKind.Tag -> R.string.pixiv_block_tag_rules
                PixivBlockKind.Book -> R.string.pixiv_block_books
                PixivBlockKind.Caption -> R.string.pixiv_block_legacy_captions
            },
        )
        if (!state.busy || state.rules.isNotEmpty()) "$label (${counts[tab] ?: 0})" else label
    }

    Column(Modifier.fillMaxSize()) {
        EditorialPillTabRow(
            tabs = tabLabels,
            selectedTabIndex = kinds.indexOf(kind).coerceAtLeast(0),
            onTabSelected = { idx -> selected = kinds[idx] },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        key(kind) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "scope_help") {
                    Text(
                        text = stringResource(R.string.pixiv_block_scope),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (state.busy && state.rules.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (state.failed) {
                    item {
                        EditorialCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 18.dp,
                            contentPadding = PaddingValues(18.dp),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.sources_action_failed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurface,
                                )
                                FilledTonalButton(
                                    onClick = { scope.launch { model.loadManager(id) } },
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text(stringResource(R.string.action_retry))
                                }
                            }
                        }
                    }
                } else if (rows.isEmpty()) {
                    item {
                        EmptyPage(
                            icon = painterResource(R.drawable.block_24px),
                            title = stringResource(R.string.pixiv_block_empty),
                            description = stringResource(R.string.pixiv_block_empty_hint),
                        )
                    }
                }
                items(rows, key = { rule -> rule.kind.name + ":" + rule.value }) { rule ->
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 16.dp,
                        contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = rule.label,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.W500),
                                    color = colors.onSurface,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (kind == PixivBlockKind.Author && rule.value != rule.label) {
                                    Text(
                                        text = rule.value,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            TextButton(
                                enabled = !state.busy,
                                onClick = { model.remove(rule) },
                            ) {
                                Text(stringResource(R.string.pixiv_block_remove))
                            }
                        }
                    }
                }
                navigationBarSpacer()
            }
        }
    }
}
