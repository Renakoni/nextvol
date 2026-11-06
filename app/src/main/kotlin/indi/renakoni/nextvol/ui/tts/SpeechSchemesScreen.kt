package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.SpeechScheme
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory

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
internal fun SpeechSchemesScreen(state: SpeechSchemesState, bookId: String?, onBack: () -> Unit,
    onOpen: (String) -> Unit, onBind: (String?) -> Unit, onDefault: (String) -> Unit,
    onCreate: (String, String?, Boolean, () -> Unit) -> Unit, onRename: (String, String, () -> Unit) -> Unit,
    onDelete: (String, String, () -> Unit) -> Unit) {
    var action by rememberSaveable { mutableStateOf<String?>(null) }
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var bindingMenu by remember { mutableStateOf(false) }
    var replacement by rememberSaveable { mutableStateOf<String?>(null) }
    val initial = stringResource(R.string.tts_scheme_new_name)
    val copySuffix = stringResource(R.string.tts_voice_copy_suffix)
    val bookTitle = state.titles[bookId].orEmpty().ifBlank { stringResource(R.string.tts_scheme_unknown_book) }
    val current = state.schemes.items.first { it.id == state.schemes.effectiveId(bookId) }
    val dismiss = { action = null; target = null; Unit }
    val enabled = !state.loading && !state.saving
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.tts_schemes), style = MaterialTheme.typography.displayLarge) },
            navigationIcon = { IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.sources_back))
            } }, actions = { IconButton(onClick = { name = initial; target = null; action = "new" }, enabled = enabled,
                modifier = Modifier.testTag("speech-scheme-add")) {
                Icon(painterResource(R.drawable.add_circle_24px), stringResource(R.string.tts_scheme_new))
            } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 16.dp)) {
            if (bookId != null) item {
                SettingsCategory(title = bookTitle) {
                    Box {
                        SettingsClickableEntry(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
                            painter = painterResource(R.drawable.headphones_24px), title = stringResource(R.string.tts_scheme_book),
                            option = if (state.schemes.bindings[bookId] == null)
                                stringResource(R.string.tts_scheme_follow_name, current.label()) else current.label(),
                            trailingContent = { Icon(painterResource(R.drawable.unfold_more_double_24px), null) },
                            onClick = { if (enabled) bindingMenu = true })
                        DropdownMenu(bindingMenu, { bindingMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.tts_scheme_follow_default)) }, onClick = {
                                onBind(null); bindingMenu = false
                            })
                            state.schemes.items.forEach { scheme -> DropdownMenuItem(text = { Text(scheme.label()) }, onClick = {
                                onBind(scheme.id); bindingMenu = false
                            }) }
                        }
                    }
                    TextButton(onClick = { target = current.id; name = bookTitle; action = "book" }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
                        Text(stringResource(R.string.tts_scheme_copy_for_book))
                    }
                }
            }
            if (state.error && action == null) item {
                Text(stringResource(R.string.tts_error_storage), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
            items(state.schemes.items, key = { it.id }) { scheme ->
                var menu by remember { mutableStateOf(false) }
                val label = scheme.label()
                SettingsCategory {
                    ListItem(modifier = Modifier.clickable(enabled = enabled) { onOpen(scheme.id) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        headlineContent = { Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(label, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (scheme.id == state.schemes.defaultId) Text(stringResource(R.string.tts_scheme_default),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        } },
                        supportingContent = { Text(state.usage(scheme.id)) },
                        trailingContent = { Box {
                            IconButton(onClick = { menu = true }, enabled = enabled) {
                                Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.tts_scheme_actions, label))
                            }
                            DropdownMenu(menu, { menu = false }) {
                                if (scheme.id != state.schemes.defaultId) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.tts_scheme_set_default)) },
                                    onClick = { menu = false; onDefault(scheme.id) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.tts_scheme_copy)) }, onClick = {
                                    menu = false; target = scheme.id; name = String.format(copySuffix, label); action = "copy"
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.tts_scheme_rename)) }, onClick = {
                                    menu = false; target = scheme.id; name = label; action = "rename"
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.tts_scheme_delete)) },
                                    enabled = state.schemes.items.size > 1, onClick = {
                                        menu = false; target = scheme.id; action = "delete"
                                        replacement = state.schemes.items.first { it.id != scheme.id }.id
                                    })
                            }
                        } })
                }
            }
        }
    }
    if (action != null && action != "delete") AlertDialog(onDismissRequest = { if (!state.saving) dismiss() },
        title = { Text(stringResource(when (action) {
            "rename" -> R.string.tts_scheme_rename
            "book" -> R.string.tts_scheme_copy_for_book
            "copy" -> R.string.tts_scheme_copy
            else -> R.string.tts_scheme_new
        })) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.tts_scheme_name)) },
                singleLine = true, enabled = !state.saving, modifier = Modifier.testTag("speech-scheme-name"))
            if (state.error) Text(stringResource(R.string.tts_error_storage), color = MaterialTheme.colorScheme.error)
        } }, confirmButton = { TextButton(enabled = !state.saving && name.isNotBlank(), onClick = {
            if (action == "rename") onRename(requireNotNull(target), name, dismiss)
            else onCreate(name, target, action == "book", dismiss)
        }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !state.saving) { Text(stringResource(R.string.cancel)) } })
    if (action == "delete") AlertDialog(onDismissRequest = { if (!state.saving) dismiss() },
        title = { Text(stringResource(R.string.tts_scheme_delete)) },
        text = { Column {
            Text(stringResource(R.string.tts_scheme_delete_replacement))
            state.schemes.items.filter { it.id != target }.forEach { scheme ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !state.saving) { replacement = scheme.id }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(scheme.id == replacement, onClick = null)
                    Text(scheme.label(), Modifier.padding(start = 12.dp))
                }
            }
            if (state.error) Text(stringResource(R.string.tts_error_storage), color = MaterialTheme.colorScheme.error)
        } }, confirmButton = { TextButton(enabled = !state.saving, onClick = {
            onDelete(requireNotNull(target), requireNotNull(replacement), dismiss)
        }) { Text(stringResource(R.string.tts_scheme_delete)) } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !state.saving) { Text(stringResource(R.string.cancel)) } })
}
