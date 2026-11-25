package indi.renakoni.nextvol.ui.localbook

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
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
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuHeader
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
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
) {
    var encodingMenu by remember { mutableStateOf(false) }
    var editingRule by rememberSaveable { mutableStateOf(false) }
    var expandedChapter by remember(state.preview) { mutableIntStateOf(0) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = !state.importing,
            dismissOnClickOutside = false,
        ),
    ) {
        val view = LocalView.current
        val isDark = LocalAppTheme.current.isDark
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !isDark
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        isAppearanceLightNavigationBars = !isDark
                    }
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                modifier = Modifier.imePadding(),
                containerColor = MaterialTheme.colorScheme.surface,
                topBar = {
                    Column {
                        EditorialTopBar(
                            title = stringResource(
                                if (relinkState == null) R.string.local_book_import
                                else R.string.local_file_relink_action,
                            ),
                            hazeState = null,
                            frosted = false,
                            showBottomDivider = !(state.busy || state.importing),
                            navigationIcon = {
                                EditorialIconButton(
                                    onClick = onDismiss,
                                    enabled = !state.importing,
                                    painter = painterResource(R.drawable.arrow_back_24px),
                                    contentDescription = stringResource(R.string.cancel),
                                )
                            },
                            actions = {
                                Button(
                                    onClick = onImport,
                                    enabled = relinkState?.canRelink ?: state.canImport,
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                    modifier = Modifier.padding(end = 8.dp),
                                ) {
                                    Text(
                                        stringResource(
                                            if (state.importing) R.string.processing
                                            else if (relinkState != null) R.string.local_file_relink_confirm
                                            else R.string.local_book_confirm_import,
                                        ),
                                    )
                                }
                            },
                        )
                        if (state.busy || state.importing) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                },
            ) { insets ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(insets),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .widthIn(max = 720.dp)
                            .fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(key = "file_summary") {
                            EditorialCard(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(16.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    state.bookKey?.let {
                                        Cover(
                                            bookId = it,
                                            width = 62.dp,
                                            height = 90.dp,
                                            uri = state.cover,
                                            title = state.title,
                                            author = state.preview?.author.orEmpty(),
                                        )
                                    }
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = state.fileName.ifBlank { stringResource(R.string.local_book_preparing) },
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.W600,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        state.format?.let {
                                            Text(
                                                text = "${it.name} · ${formatSize(state.fileBytes)}",
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontFeatureSettings = "tnum",
                                                ),
                                                fontWeight = FontWeight.W600,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        Text(
                                            text = if (relinkState == null) {
                                                stringResource(R.string.local_book_target, state.shelfName)
                                            } else {
                                                state.title
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }

                        if (state.bookKey != null && relinkState == null) {
                            item(key = "book_title_field") {
                                EditorialCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(16.dp),
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = state.title,
                                            onValueChange = onTitleChange,
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(14.dp),
                                            enabled = !state.importing && !state.busy,
                                            singleLine = true,
                                            label = { Text(stringResource(R.string.local_book_title)) },
                                            supportingText = {
                                                if (!state.busy && state.title.isBlank()) {
                                                    Text(stringResource(R.string.local_import_error_title))
                                                }
                                            },
                                            isError = !state.busy && state.title.isBlank(),
                                        )
                                        SectionDescription(text = stringResource(R.string.local_book_copy_note))
                                    }
                                }
                            }
                        }

                        if (relinkState != null) {
                            item(key = "relink_notice") {
                                LocalBookRelinkNotice(relinkState, Modifier.fillMaxWidth())
                            }
                        }

                        if (state.format == LocalBookFormat.TXT) {
                            item(key = "txt_chapter_detection") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SectionHeader(
                                        modifier = Modifier.padding(start = 6.dp, top = 4.dp),
                                        text = stringResource(R.string.local_book_chapter_detection),
                                    )
                                    EditorialCard(modifier = Modifier.fillMaxWidth()) {
                                        Box {
                                            SettingsClickableEntry(
                                                painter = painterResource(R.drawable.text_snippet_24px),
                                                title = stringResource(R.string.local_book_encoding),
                                                description = state.encoding ?: (
                                                    stringResource(R.string.local_book_auto) +
                                                        state.preview?.encoding?.let { " · $it" }.orEmpty()
                                                    ),
                                                onClick = { if (!state.importing) encodingMenu = true },
                                            )
                                            EditorialPopupMenu(
                                                expanded = encodingMenu,
                                                onDismissRequest = { encodingMenu = false },
                                            ) {
                                                EditorialPopupMenuHeader(
                                                    text = stringResource(R.string.local_book_encoding),
                                                )
                                                (listOf<String?>(null) + TxtBookParser.encodings).forEach { encoding ->
                                                    EditorialPopupMenuItem(
                                                        text = encoding ?: stringResource(R.string.local_book_auto),
                                                        selected = state.encoding == encoding,
                                                        onClick = {
                                                            encodingMenu = false
                                                            onEncodingChange(encoding)
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                        EditorialHairlineDivider(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                        )
                                        SettingsClickableEntry(
                                            painter = painterResource(R.drawable.find_replace_24px),
                                            title = stringResource(R.string.local_book_chapter_rule),
                                            description = stringResource(
                                                when {
                                                    state.rule == TxtBookParser.DEFAULT_RULE -> R.string.local_book_default_rule
                                                    state.rule.isBlank() -> R.string.local_book_whole_text
                                                    else -> R.string.local_book_custom_rule
                                                },
                                            ),
                                            onClick = { if (!state.importing) editingRule = !editingRule },
                                        )
                                        AnimatedVisibility(editingRule) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                OutlinedTextField(
                                                    value = state.rule,
                                                    onValueChange = onRuleChange,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    shape = RoundedCornerShape(14.dp),
                                                    enabled = !state.importing,
                                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                                        fontFamily = FontFamily.Monospace,
                                                    ),
                                                    minLines = 2,
                                                    maxLines = 5,
                                                    label = { Text(stringResource(R.string.local_book_regex)) },
                                                    supportingText = {
                                                        Text(stringResource(R.string.local_book_rule_help))
                                                    },
                                                )
                                                TextButton(
                                                    onClick = { onRuleChange(TxtBookParser.DEFAULT_RULE) },
                                                    enabled = !state.importing,
                                                    modifier = Modifier.align(Alignment.End),
                                                ) {
                                                    Text(stringResource(R.string.local_book_reset_rule))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        state.error?.let { error ->
                            item(key = "import_error") {
                                LocalBookImportError(error, Modifier.fillMaxWidth())
                            }
                        }

                        state.preview?.let { preview ->
                            item(key = "preview_header") {
                                Column(
                                    modifier = Modifier.padding(start = 6.dp, top = 4.dp, end = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    SectionHeader(
                                        text = pluralStringResource(
                                            R.plurals.local_book_contents_count,
                                            preview.chapters.size,
                                            preview.chapters.size,
                                        ),
                                    )
                                    SectionDescription(
                                        text = stringResource(R.string.local_book_preview_hint),
                                    )
                                }
                            }
                            itemsIndexed(
                                items = preview.chapters,
                                key = { index, _ -> "chapter_$index" },
                            ) { index, chapter ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (chapter.volume.isNotBlank() &&
                                        preview.chapters.getOrNull(index - 1)?.volume != chapter.volume
                                    ) {
                                        Text(
                                            text = chapter.volume,
                                            modifier = Modifier.padding(start = 6.dp, top = 6.dp),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.W600,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    val expanded = expandedChapter == index
                                    val arrowRotation by animateFloatAsState(
                                        targetValue = if (expanded) 0f else 180f,
                                        label = "chapterExpand",
                                    )
                                    EditorialCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        cornerRadius = 16.dp,
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { expandedChapter = if (expanded) -1 else index }
                                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = (index + 1).toString(),
                                                modifier = Modifier.width(36.dp),
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontFeatureSettings = "tnum",
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                text = chapter.title,
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = if (expanded) FontWeight.W600 else FontWeight.W400,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Icon(
                                                painter = painterResource(R.drawable.keyboard_arrow_up_24px),
                                                contentDescription = null,
                                                modifier = Modifier.rotate(arrowRotation),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (expanded) {
                                            EditorialHairlineDivider(
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                            )
                                            LocalChapterPreview(preview, index)
                                        }
                                    }
                                }
                            }
                        }

                        item(key = "backup_note") {
                            SectionDescription(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                                text = stringResource(R.string.local_book_backup_note),
                            )
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
        value = try {
            Result.success(withContext(Dispatchers.IO) { preview.chapter(index) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }
    loaded?.getOrNull()?.let { chapter ->
        val excerpt = remember(chapter) {
            chapter.blocks
                .filterIsInstance<LocalBookBlock.Text>()
                .take(4)
                .joinToString("\n") { it.value }
                .take(500)
        }
        Text(
            text = excerpt.ifBlank {
                stringResource(
                    if (chapter.blocks.isEmpty()) R.string.local_book_empty_chapter
                    else R.string.local_book_image_chapter,
                )
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 7,
            overflow = TextOverflow.Ellipsis,
        )
    }
    (loaded?.exceptionOrNull() as? Exception)?.let { failure ->
        LocalBookImportError(
            failure = LocalBookImportFailure.from(failure),
            modifier = Modifier.padding(14.dp),
        )
    }
}
