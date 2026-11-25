package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.tts.SpeechScheme
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuHeader
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip

@Composable
internal fun SpeechScheme.label() = name.ifBlank { stringResource(R.string.tts_scheme_initial) }

@Composable
internal fun SpeechSchemesState.usage(id: String): String {
    val books = titles.filterKeys { schemes.effectiveId(it) == id }.values
        .map { it.ifBlank { stringResource(R.string.tts_scheme_unknown_book) } }
    return listOfNotNull(
        stringResource(R.string.tts_scheme_default_usage).takeIf { schemes.defaultId == id },
        stringResource(R.string.tts_scheme_used_by, books.joinToString("、")).takeIf { books.isNotEmpty() },
    ).joinToString(" · ").ifEmpty { stringResource(R.string.tts_scheme_unused) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechSchemesScreen(
    state: SpeechSchemesState,
    bookId: String?,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onBind: (String?) -> Unit,
    onDefault: (String) -> Unit,
    onCreate: (String, String?, () -> Unit) -> Unit,
    onRename: (String, String, () -> Unit) -> Unit,
    onDelete: (String, String, () -> Unit) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var action by rememberSaveable { mutableStateOf<String?>(null) }
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var bindingMenu by remember { mutableStateOf(false) }
    var replacement by rememberSaveable { mutableStateOf<String?>(null) }
    val initial = stringResource(R.string.tts_scheme_new_name)
    val copySuffix = stringResource(R.string.tts_voice_copy_suffix)
    val bookTitle = state.titles[bookId].orEmpty().ifBlank { stringResource(R.string.tts_scheme_unknown_book) }
    val current = state.schemes.items.first { it.id == state.schemes.effectiveId(bookId) }
    val dismiss = {
        action = null
        target = null
    }
    val enabled = !state.loading && !state.saving

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            EditorialTopBar(
                title = stringResource(R.string.tts_schemes),
                scrollBehavior = scrollBehavior,
                hazeState = null,
                frosted = false,
                navigationIcon = {
                    EditorialIconButton(
                        onClick = onBack,
                        painter = painterResource(R.drawable.arrow_back_24px),
                        contentDescription = stringResource(R.string.sources_back),
                    )
                },
                actions = {
                    EditorialIconButton(
                        onClick = {
                            name = initial
                            target = null
                            action = "new"
                        },
                        enabled = enabled,
                        painter = painterResource(R.drawable.add_circle_24px),
                        contentDescription = stringResource(R.string.tts_scheme_new),
                        modifier = Modifier.testTag("speech-scheme-add"),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (bookId != null) {
                item(key = "book_scheme_binding") {
                    SettingsCategory(title = bookTitle) {
                        Box {
                            SettingsClickableEntry(
                                painter = painterResource(R.drawable.headphones_24px),
                                title = stringResource(R.string.tts_scheme_book),
                                option = if (state.schemes.bindings[bookId] == null) {
                                    stringResource(R.string.tts_scheme_follow_name, current.label())
                                } else {
                                    current.label()
                                },
                                trailingContent = {
                                    Icon(
                                        painter = painterResource(R.drawable.unfold_more_double_24px),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                onClick = { if (enabled) bindingMenu = true },
                            )
                            EditorialPopupMenu(
                                expanded = bindingMenu,
                                onDismissRequest = { bindingMenu = false },
                            ) {
                                EditorialPopupMenuHeader(text = stringResource(R.string.tts_scheme_book))
                                EditorialPopupMenuItem(
                                    text = stringResource(R.string.tts_scheme_follow_default),
                                    selected = state.schemes.bindings[bookId] == null,
                                    onClick = {
                                        onBind(null)
                                        bindingMenu = false
                                    },
                                )
                                EditorialHairlineDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                                state.schemes.items.forEach { scheme ->
                                    EditorialPopupMenuItem(
                                        text = scheme.label(),
                                        selected = state.schemes.bindings[bookId] == scheme.id,
                                        onClick = {
                                            onBind(scheme.id)
                                            bindingMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (state.error && action == null) {
                item(key = "scheme_error") {
                    EditorialCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                        contentPadding = PaddingValues(14.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.tts_error_storage),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            item(key = "schemes_header") {
                SectionHeader(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 2.dp),
                    text = stringResource(R.string.tts_schemes),
                )
            }
            items(state.schemes.items, key = { it.id }) { scheme ->
                var menu by remember { mutableStateOf(false) }
                val label = scheme.label()
                val isDefault = scheme.id == state.schemes.defaultId
                val isCurrentBookScheme = bookId != null && scheme.id == current.id
                EditorialCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    borderColor = if (isDefault || isCurrentBookScheme) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else tokens.cardBorder,
                    onClick = { onOpen(scheme.id) },
                    enabled = enabled,
                    contentPadding = PaddingValues(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        EditorialIconBadge(
                            painter = painterResource(R.drawable.view_list_24px),
                            size = 38.dp,
                            iconSize = 19.dp,
                            cornerRadius = 11.dp,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = label,
                                    modifier = Modifier.weight(1f, fill = false),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.W600,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (isDefault) {
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(tokens.activePillFill)
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.tts_scheme_default),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.W600,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                            Text(
                                text = state.usage(scheme.id),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Box {
                            EditorialIconButton(
                                onClick = { menu = true },
                                enabled = enabled,
                                painter = painterResource(R.drawable.more_vert_24px),
                                contentDescription = stringResource(R.string.tts_scheme_actions, label),
                            )
                            EditorialPopupMenu(
                                expanded = menu,
                                onDismissRequest = { menu = false },
                            ) {
                                EditorialPopupMenuHeader(text = label)
                                if (!isDefault) {
                                    EditorialPopupMenuItem(
                                        text = stringResource(R.string.tts_scheme_set_default),
                                        leadingIcon = painterResource(R.drawable.check_24px),
                                        onClick = {
                                            menu = false
                                            onDefault(scheme.id)
                                        },
                                    )
                                }
                                if (bookId != null && scheme.id != current.id) {
                                    EditorialPopupMenuItem(
                                        text = stringResource(R.string.tts_scheme_use),
                                        leadingIcon = painterResource(R.drawable.headphones_24px),
                                        onClick = {
                                            menu = false
                                            onBind(scheme.id)
                                        },
                                    )
                                }
                                EditorialPopupMenuItem(
                                    text = stringResource(R.string.tts_scheme_copy),
                                    leadingIcon = painterResource(R.drawable.add_circle_24px),
                                    onClick = {
                                        menu = false
                                        target = scheme.id
                                        name = String.format(copySuffix, label)
                                        action = "copy"
                                    },
                                )
                                EditorialPopupMenuItem(
                                    text = stringResource(R.string.tts_scheme_rename),
                                    leadingIcon = painterResource(R.drawable.edit_square_24px),
                                    onClick = {
                                        menu = false
                                        target = scheme.id
                                        name = label
                                        action = "rename"
                                    },
                                )
                                EditorialHairlineDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                                EditorialPopupMenuItem(
                                    text = stringResource(R.string.tts_scheme_delete),
                                    leadingIcon = painterResource(R.drawable.delete_forever_24px),
                                    destructive = true,
                                    enabled = state.schemes.items.size > 1,
                                    onClick = {
                                        menu = false
                                        target = scheme.id
                                        action = "delete"
                                        replacement = state.schemes.items.first { it.id != scheme.id }.id
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (action != null && action != "delete") {
        AlertDialog(
            onDismissRequest = { if (!state.saving) dismiss() },
            shape = RoundedCornerShape(tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
            icon = {
                EditorialIconBadge(
                    painter = painterResource(
                        when (action) {
                            "rename" -> R.drawable.edit_square_24px
                            else -> R.drawable.view_list_24px
                        },
                    ),
                    size = 42.dp,
                    iconSize = 21.dp,
                )
            },
            title = {
                Text(
                    text = stringResource(
                        when (action) {
                            "rename" -> R.string.tts_scheme_rename
                            "copy" -> R.string.tts_scheme_copy
                            else -> R.string.tts_scheme_new
                        },
                    ),
                    fontWeight = FontWeight.W600,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.tts_scheme_name)) },
                        singleLine = true,
                        enabled = !state.saving,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("speech-scheme-name"),
                    )
                    if (state.error) {
                        Text(
                            text = stringResource(R.string.tts_error_storage),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !state.saving && name.isNotBlank(),
                    shape = CircleShape,
                    onClick = {
                        if (action == "rename") onRename(requireNotNull(target), name, dismiss)
                        else onCreate(name, target, dismiss)
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss, enabled = !state.saving) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (action == "delete") {
        AlertDialog(
            onDismissRequest = { if (!state.saving) dismiss() },
            shape = RoundedCornerShape(tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
            icon = {
                EditorialIconBadge(
                    painter = painterResource(R.drawable.delete_forever_24px),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.tts_scheme_delete), fontWeight = FontWeight.W600) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.tts_scheme_delete_replacement),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.schemes.items.filter { it.id != target }.forEach { scheme ->
                        val isChosen = scheme.id == replacement
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .squircleBackground(
                                    color = if (isChosen) tokens.activePillFill else MaterialTheme.colorScheme.surfaceContainerLow,
                                    cornerRadius = 12.dp,
                                )
                                .squircleBorder(
                                    width = if (isChosen) 1.25.dp else 0.75.dp,
                                    color = if (isChosen) MaterialTheme.colorScheme.primary else tokens.cardBorder,
                                    cornerRadius = 12.dp,
                                )
                                .squircleClip(12.dp)
                                .clickable(enabled = !state.saving) { replacement = scheme.id }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = isChosen, onClick = null)
                            Text(
                                text = scheme.label(),
                                modifier = Modifier.padding(start = 10.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isChosen) FontWeight.W600 else FontWeight.W400,
                            )
                        }
                    }
                    if (state.error) {
                        Text(
                            text = stringResource(R.string.tts_error_storage),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !state.saving,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    onClick = {
                        onDelete(requireNotNull(target), requireNotNull(replacement), dismiss)
                    },
                ) {
                    Text(stringResource(R.string.tts_scheme_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss, enabled = !state.saving) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
