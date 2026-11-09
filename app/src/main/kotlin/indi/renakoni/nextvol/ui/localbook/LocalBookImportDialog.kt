package indi.renakoni.nextvol.ui.localbook

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.localbook.LocalBookBlock
import indi.renakoni.nextvol.data.localbook.LocalBookChapter
import indi.renakoni.nextvol.data.localbook.LocalBookFormat
import indi.renakoni.nextvol.data.localbook.LocalBookImportFailure
import indi.renakoni.nextvol.data.localbook.PreparedLocalBook
import indi.renakoni.nextvol.data.localbook.TxtBookParser
import indi.renakoni.nextvol.ui.LocalAppTheme
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.components.SectionDescription
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import indi.renakoni.nextvol.utils.formatSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalBookImportDialog(
    state: LocalBookImportState,
    onDismiss: () -> Unit,
    onTitleChange: (String) -> Unit,
    onEncodingChange: (String?) -> Unit,
    onRuleChange: (String) -> Unit,
    onImport: () -> Unit,
    relinkState: LocalBookRelinkState? = null,
    onConfirmLegacy: (Boolean) -> Unit = {},
) {
    var encodingMenu by remember { mutableStateOf(false) }
    var editingRule by rememberSaveable { mutableStateOf(false) }
    var expandedChapter by remember(state.preview) { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            dismissOnBackPress = !state.importing, dismissOnClickOutside = false)) {
        val view = LocalView.current
        val isDark = LocalAppTheme.current.isDark
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !isDark
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) isAppearanceLightNavigationBars = !isDark
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                modifier = Modifier.imePadding(),
                topBar = {
                    Column {
                        TopAppBar(
                            title = { Text(stringResource(if (relinkState == null) R.string.local_book_import else R.string.local_file_relink_action),
                                style = MaterialTheme.typography.displayLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            navigationIcon = {
                                IconButton(onClick = onDismiss, enabled = !state.importing) {
                                    Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.cancel))
                                }
                            },
                            actions = {
                                TextButton(onClick = onImport, enabled = relinkState?.canRelink ?: state.canImport) {
                                    Text(stringResource(if (state.importing) R.string.processing else if (relinkState != null) R.string.local_file_relink_confirm else R.string.local_book_confirm_import))
                                }
                            },
                        )
                        if (state.busy || state.importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                },
            ) { insets ->
                Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                state.bookKey?.let { Cover(it, 64.dp, 92.dp, state.cover, state.title, author = state.preview?.author.orEmpty()) }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(state.fileName.ifBlank { stringResource(R.string.local_book_preparing) },
                                        style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    state.format?.let {
                                        Text("${it.name} · ${formatSize(state.fileBytes)}", style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary)
                                    }
                                    Text(if (relinkState == null) stringResource(R.string.local_book_target, state.shelfName) else state.title, style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if (state.bookKey != null && relinkState == null) {
                            item {
                                OutlinedTextField(value = state.title, onValueChange = onTitleChange,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                                    enabled = !state.importing && !state.busy, singleLine = true,
                                    label = { Text(stringResource(R.string.local_book_title)) },
                                    supportingText = {
                                        if (!state.busy && (state.title.isBlank() || state.title.length > 200)) Text(stringResource(R.string.local_book_title_error))
                                    },
                                    isError = !state.busy && (state.title.isBlank() || state.title.length > 200))
                                SectionDescription(Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                    stringResource(R.string.local_book_copy_note))
                            }
                        }
                        if (relinkState != null) item {
                            LocalBookRelinkNotice(relinkState, onConfirmLegacy,
                                Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                        }
                        if (state.format == LocalBookFormat.TXT) {
                            item {
                                SectionHeader(Modifier.padding(start = 24.dp, top = 16.dp, bottom = 6.dp), stringResource(R.string.local_book_chapter_detection))
                                Box {
                                    SettingsClickableEntry(painter = painterResource(R.drawable.text_snippet_24px),
                                        title = stringResource(R.string.local_book_encoding),
                                        description = state.encoding ?: (stringResource(R.string.local_book_auto) +
                                            state.preview?.encoding?.let { " · $it" }.orEmpty()),
                                        onClick = { if (!state.importing) encodingMenu = true })
                                    DropdownMenu(expanded = encodingMenu, onDismissRequest = { encodingMenu = false }) {
                                        (listOf<String?>(null) + TxtBookParser.encodings).forEach { encoding ->
                                            DropdownMenuItem(text = { Text(encoding ?: stringResource(R.string.local_book_auto)) },
                                                onClick = { encodingMenu = false; onEncodingChange(encoding) })
                                        }
                                    }
                                }
                                SettingsClickableEntry(painter = painterResource(R.drawable.find_replace_24px),
                                    title = stringResource(R.string.local_book_chapter_rule),
                                    description = stringResource(when {
                                        state.rule == TxtBookParser.DEFAULT_RULE -> R.string.local_book_default_rule
                                        state.rule.isBlank() -> R.string.local_book_whole_text
                                        else -> R.string.local_book_custom_rule
                                    }), onClick = { if (!state.importing) editingRule = !editingRule })
                                AnimatedVisibility(editingRule) {
                                    Column(Modifier.padding(horizontal = 24.dp)) {
                                        OutlinedTextField(value = state.rule, onValueChange = onRuleChange,
                                            modifier = Modifier.fillMaxWidth(), enabled = !state.importing,
                                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                            minLines = 2, maxLines = 5,
                                            label = { Text(stringResource(R.string.local_book_regex)) },
                                            supportingText = { Text(stringResource(R.string.local_book_rule_help)) })
                                        TextButton(onClick = { onRuleChange(TxtBookParser.DEFAULT_RULE) }, enabled = !state.importing) {
                                            Text(stringResource(R.string.local_book_reset_rule))
                                        }
                                    }
                                }
                            }
                        }
                        state.error?.let { error ->
                            item {
                                LocalBookImportError(error, Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
                            }
                        }
                        state.preview?.let { preview ->
                            item {
                                SectionHeader(Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp),
                                    pluralStringResource(R.plurals.local_book_contents_count, preview.chapters.size, preview.chapters.size))
                                SectionDescription(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                                    stringResource(R.string.local_book_preview_hint))
                            }
                            itemsIndexed(preview.chapters) { index, chapter ->
                                if (chapter.volume.isNotBlank() && preview.chapters.getOrNull(index - 1)?.volume != chapter.volume) {
                                    Text(chapter.volume, Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
                                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                }
                                Column {
                                    Row(Modifier.fillMaxWidth().clickable { expandedChapter = if (expandedChapter == index) -1 else index }
                                        .padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text((index + 1).toString(), Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(chapter.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Icon(painterResource(R.drawable.keyboard_arrow_up_24px), null,
                                            modifier = Modifier.rotate(if (expandedChapter == index) 0f else 180f),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (expandedChapter == index) {
                                        LocalChapterPreview(preview, index)
                                    }
                                    HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                }
                            }
                        }
                        item {
                            SectionDescription(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), stringResource(R.string.local_book_backup_note))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalChapterPreview(preview: PreparedLocalBook, index: Int) {
    val loaded by produceState<Result<LocalBookChapter>?>(null, preview, index) {
        value = try { Result.success(withContext(Dispatchers.IO) { preview.chapter(index) }) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
    }
    loaded?.getOrNull()?.let { chapter ->
        val excerpt = remember(chapter) { chapter.blocks.filterIsInstance<LocalBookBlock.Text>().take(4).joinToString("\n") { it.value }.take(500) }
        Text(excerpt.ifBlank { stringResource(if (chapter.blocks.isEmpty()) R.string.local_book_empty_chapter else R.string.local_book_image_chapter) },
            Modifier.padding(start = 64.dp, end = 24.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 7, overflow = TextOverflow.Ellipsis)
    }
    (loaded?.exceptionOrNull() as? Exception)?.let { failure ->
        LocalBookImportError(LocalBookImportFailure.from(failure), Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
    }
}
