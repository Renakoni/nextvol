package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import hnovel.content.LoginField
import hnovel.content.LoginForm
import indi.renakoni.nextvol.R
import kotlinx.coroutines.launch

private data class ConfigurationPage(
    val section: String?, val title: String?, val content: (@Composable () -> Unit)?,
) {
    val isRoot: Boolean get() = section == null && title == null
}

/** Navigation is local; actions keep the original validated form and field IDs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceConfigurationSheet(
    form: LoginForm?, busy: Boolean,
    onSubmit: (Map<String, String>, String?, String, Boolean) -> Unit, onCancel: () -> Unit,
    pageTitle: String? = null, onPageBack: () -> Unit = {}, pageContent: (@Composable () -> Unit)? = null,
) {
    val values = remember(form) { mutableStateMapOf<String, String>().apply { putAll(form?.values.orEmpty()) } }
    val fields = form?.fields.orEmpty().filterNot { it.type == "button" &&
        it.action?.trim()?.removeSuffix(";")?.trim() in setOf("login()", "logout()") }
    val sections = fields.mapNotNull { it.section }.distinct()
    val managerFields = fields.filter { it.section == null && it.type == "button" &&
        it.action?.trim()?.removeSuffix(";")?.trim() == "pixivBlockManager()" }
    var selectedSection by remember { mutableStateOf<String?>(null) }
    val section = selectedSection?.takeIf { it in sections }
    val scrollState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val hasPage = pageTitle != null || section != null
    fun parent() {
        if (pageTitle != null) onPageBack() else selectedSection = null
    }
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        containerColor = MaterialTheme.colorScheme.surface) {
        // The sheet owns a separate window, not the parent screen's navigation dispatcher.
        val view = LocalView.current
        val backView = (view.parent as? DialogWindowProvider)?.window?.decorView ?: view
        val backOwner = requireNotNull(backView.findViewTreeOnBackPressedDispatcherOwner())
        CompositionLocalProvider(*listOfNotNull(
            LocalOnBackPressedDispatcherOwner provides backOwner,
            (backOwner as? NavigationEventDispatcherOwner)?.let { LocalNavigationEventDispatcherOwner provides it },
        ).toTypedArray()) {
            BackHandler {
                if (hasPage) parent() else scope.launch {
                    sheetState.hide()
                    if (!sheetState.isVisible) onCancel()
                }
            }
        }
        AnimatedContent(
            targetState = ConfigurationPage(section, pageTitle, pageContent),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f),
            contentKey = { it.section to it.title },
            transitionSpec = {
                val direction = if (targetState.isRoot) -1 else 1
                ((fadeIn(tween(180)) + slideInHorizontally(tween(180, easing = FastOutSlowInEasing)) { it / 16 * direction })
                    togetherWith (fadeOut(tween(120)) +
                        slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { -it / 16 * direction })).using(null)
            }, label = "SourceConfigurationPage",
        ) { page ->
            val pageScrollState = if (page.isRoot) scrollState else rememberLazyListState()
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (!page.isRoot) IconButton(onClick = ::parent) {
                        Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.import_back))
                    }
                    Text(page.title ?: page.section ?: stringResource(R.string.sources_configuration),
                        Modifier.weight(1f).padding(start = if (page.isRoot) 12.dp else 4.dp).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onCancel) {
                        Icon(painterResource(R.drawable.close_24px), stringResource(R.string.close))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (page.content != null) Box(Modifier.weight(1f).fillMaxWidth()) { page.content() }
                else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = pageScrollState,
                    contentPadding = PaddingValues(vertical = 8.dp)) {
                    if (form == null) item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    items(fields.filter { it.section == page.section && it !in managerFields }, key = { it.id }) { field ->
                        SourceConfigurationField(field, values[field.name].orEmpty(), !busy && field.enabled,
                            onChange = { values[field.name] = it },
                            onAction = { longClick -> form?.let { onSubmit(values.toMap(), field.id, it.id, longClick) } })
                    }
                    if (page.section == null && sections.isNotEmpty()) {
                        item { HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant) }
                        items(sections) { name ->
                            ListItem(headlineContent = { Text(name) },
                                trailingContent = { Icon(painterResource(R.drawable.arrow_forward_ios_24px), null, Modifier.size(16.dp)) },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier.clickable { selectedSection = name }.padding(horizontal = 8.dp))
                        }
                    }
                    if (page.section == null) items(managerFields, key = { it.id }) { field ->
                        SourceConfigurationField(field, values[field.name].orEmpty(), !busy && field.enabled,
                            onChange = { values[field.name] = it },
                            onAction = { longClick -> form?.let { onSubmit(values.toMap(), field.id, it.id, longClick) } }, navigates = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceConfigurationField(field: LoginField, value: String, enabled: Boolean,
    onChange: (String) -> Unit, onAction: (Boolean) -> Unit, navigates: Boolean = false) {
    // Decorative emoji are removed from display only, never from input bindings.
    val label = field.label.dropWhile { !it.isLetterOrDigit() }.ifBlank { field.label }
    val supporting: (@Composable () -> Unit)? = field.description?.let { { Text(it) } }
    val row = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f)
    val colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
    val checked = field.checked
    val onLongClick: (() -> Unit)? = if (field.action == null) null else { { onAction(true) } }
    when (field.type) {
        "button" -> if (checked != null) {
            ListItem(headlineContent = { Text(label) }, supportingContent = supporting,
                trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
                colors = colors, modifier = row.semantics { toggleableState = ToggleableState(checked) }
                    .combinedClickable(enabled = enabled, role = Role.Switch,
                        onClick = { onAction(false) }, onLongClick = onLongClick).padding(horizontal = 8.dp))
        } else {
            ListItem(headlineContent = { Text(label) }, supportingContent = if (navigates) null else supporting, colors = colors,
                trailingContent = if (navigates) { {
                    Icon(painterResource(R.drawable.arrow_forward_ios_24px), null, Modifier.size(16.dp))
                } } else null,
                modifier = row.combinedClickable(enabled = enabled, role = Role.Button,
                    onClick = { onAction(false) }, onLongClick = onLongClick).padding(horizontal = 8.dp))
        }
        "toggle", "select" -> {
            var expanded by remember(field.id) { mutableStateOf(false) }
            Box {
                ListItem(headlineContent = { Text(label) }, supportingContent = supporting,
                    trailingContent = { Text(value, color = MaterialTheme.colorScheme.primary) }, colors = colors,
                    modifier = row.combinedClickable(enabled = enabled,
                        onClick = { expanded = true }, onLongClick = onLongClick).padding(horizontal = 8.dp))
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    field.choices.forEach { choice ->
                        DropdownMenuItem(text = { Text(choice) }, enabled = enabled, onClick = {
                            expanded = false
                            onChange(choice)
                            if (field.action != null) onAction(false)
                        })
                    }
                }
            }
        }
        else -> Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            OutlinedTextField(value, { onChange(it.take(4096)) }, label = { Text(label) },
                supportingText = supporting, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (field.type == "password") PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (field.type == "password") KeyboardType.Password else KeyboardType.Text))
            if (field.action != null) TextButton(onClick = { onAction(false) }, enabled = enabled) {
                Text(stringResource(R.string.sources_apply_field))
            }
        }
    }
}
