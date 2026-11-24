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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import hnovel.content.LoginField
import hnovel.content.LoginForm
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuHeader
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem
import kotlinx.coroutines.launch

private data class ConfigurationPage(
    val section: String?,
    val title: String?,
    val content: (@Composable () -> Unit)?,
) {
    val isRoot: Boolean get() = section == null && title == null
}

/** Navigation is local; actions keep the original validated form and field IDs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceConfigurationSheet(
    form: LoginForm?,
    busy: Boolean,
    onSubmit: (Map<String, String>, String?, String, Boolean) -> Unit,
    onCancel: () -> Unit,
    pageTitle: String? = null,
    onPageBack: () -> Unit = {},
    pageContent: (@Composable () -> Unit)? = null,
) {
    val tokens = NextVolDesign.tokens
    val values = remember(form) { mutableStateMapOf<String, String>().apply { putAll(form?.values.orEmpty()) } }
    val fields = form?.fields.orEmpty().filterNot {
        it.type == "button" &&
            it.action?.trim()?.removeSuffix(";")?.trim() in setOf("login()", "logout()")
    }
    val sections = fields.mapNotNull { it.section }.distinct()
    val managerFields = fields.filter {
        it.section == null &&
            it.type == "button" &&
            it.action?.trim()?.removeSuffix(";")?.trim() == "pixivBlockManager()"
    }
    var selectedSection by remember { mutableStateOf<String?>(null) }
    val section = selectedSection?.takeIf { it in sections }
    val scrollState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val hasPage = pageTitle != null || section != null
    fun parent() {
        if (pageTitle != null) onPageBack() else selectedSection = null
    }
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = tokens.sheetRadius, topEnd = tokens.sheetRadius),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        containerColor = tokens.cardElevatedSurface,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = tokens.cardBorder)
        },
    ) {
        // The sheet owns a separate window, not the parent screen's navigation dispatcher.
        val view = LocalView.current
        val backView = (view.parent as? DialogWindowProvider)?.window?.decorView ?: view
        val backOwner = requireNotNull(backView.findViewTreeOnBackPressedDispatcherOwner())
        CompositionLocalProvider(
            *listOfNotNull(
                LocalOnBackPressedDispatcherOwner provides backOwner,
                (backOwner as? NavigationEventDispatcherOwner)?.let { LocalNavigationEventDispatcherOwner provides it },
            ).toTypedArray(),
        ) {
            BackHandler {
                if (hasPage) {
                    parent()
                } else {
                    scope.launch {
                        sheetState.hide()
                        if (!sheetState.isVisible) onCancel()
                    }
                }
            }
        }
        AnimatedContent(
            targetState = ConfigurationPage(section, pageTitle, pageContent),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .navigationBarsPadding(),
            contentKey = { it.section to it.title },
            transitionSpec = {
                val direction = if (targetState.isRoot) -1 else 1
                (
                    (fadeIn(tween(180)) + slideInHorizontally(tween(180, easing = FastOutSlowInEasing)) { it / 16 * direction })
                        togetherWith (
                        fadeOut(tween(120)) +
                            slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { -it / 16 * direction }
                        )
                    ).using(null)
            },
            label = "SourceConfigurationPage",
        ) { page ->
            val pageScrollState = if (page.isRoot) scrollState else rememberLazyListState()
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (!page.isRoot) {
                        EditorialIconButton(
                            onClick = ::parent,
                            painter = painterResource(R.drawable.arrow_back_24px),
                            contentDescription = stringResource(R.string.import_back),
                        )
                    }
                    Text(
                        text = page.title ?: page.section ?: stringResource(R.string.sources_configuration),
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = if (page.isRoot) 10.dp else 4.dp)
                            .semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.W600,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    EditorialIconButton(
                        onClick = onCancel,
                        painter = painterResource(R.drawable.close_24px),
                        contentDescription = stringResource(R.string.close),
                    )
                }
                EditorialHairlineDivider()
                if (page.content != null) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { page.content() }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        state = pageScrollState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (form == null) {
                            item {
                                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                        items(fields.filter { it.section == page.section && it !in managerFields }, key = { it.id }) { field ->
                            SourceConfigurationField(
                                field = field,
                                value = values[field.name].orEmpty(),
                                enabled = !busy && field.enabled,
                                onChange = { values[field.name] = it },
                                onAction = { longClick ->
                                    form?.let { onSubmit(values.toMap(), field.id, it.id, longClick) }
                                },
                            )
                        }
                        if (page.section == null && sections.isNotEmpty()) {
                            item(key = "config_sections_card") {
                                EditorialCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    cornerRadius = 18.dp,
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ) {
                                    sections.forEachIndexed { index, name ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { selectedSection = name }
                                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Text(
                                                text = name,
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.W500,
                                            )
                                            Icon(
                                                painter = painterResource(R.drawable.arrow_forward_ios_24px),
                                                contentDescription = null,
                                                modifier = Modifier.size(15.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (index != sections.lastIndex) {
                                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                                        }
                                    }
                                }
                            }
                        }
                        if (page.section == null) {
                            items(managerFields, key = { it.id }) { field ->
                                SourceConfigurationField(
                                    field = field,
                                    value = values[field.name].orEmpty(),
                                    enabled = !busy && field.enabled,
                                    onChange = { values[field.name] = it },
                                    onAction = { longClick ->
                                        form?.let { onSubmit(values.toMap(), field.id, it.id, longClick) }
                                    },
                                    navigates = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceConfigurationField(
    field: LoginField,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    onAction: (Boolean) -> Unit,
    navigates: Boolean = false,
) {
    // Decorative emoji are removed from display only, never from input bindings.
    val label = field.label.dropWhile { !it.isLetterOrDigit() }.ifBlank { field.label }
    val description = field.description
    val checked = field.checked
    val onLongClick: (() -> Unit)? = if (field.action == null) null else {
        { onAction(true) }
    }
    when (field.type) {
        "button" -> {
            EditorialCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (enabled) 1f else 0.38f),
                cornerRadius = 16.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                if (checked != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { toggleableState = ToggleableState(checked) }
                            .combinedClickable(
                                enabled = enabled,
                                role = Role.Switch,
                                onClick = { onAction(false) },
                                onLongClick = onLongClick,
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.W500)
                            if (!description.isNullOrBlank()) {
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                enabled = enabled,
                                role = Role.Button,
                                onClick = { onAction(false) },
                                onLongClick = onLongClick,
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.W500)
                            if (!navigates && !description.isNullOrBlank()) {
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (navigates) {
                            Icon(
                                painter = painterResource(R.drawable.arrow_forward_ios_24px),
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        "toggle", "select" -> {
            var expanded by remember(field.id) { mutableStateOf(false) }
            Box {
                EditorialCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (enabled) 1f else 0.38f),
                    cornerRadius = 16.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                enabled = enabled,
                                onClick = { expanded = true },
                                onLongClick = onLongClick,
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.W500)
                            if (!description.isNullOrBlank()) {
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = value,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.W600,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                EditorialPopupMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    EditorialPopupMenuHeader(text = label)
                    field.choices.forEach { choice ->
                        EditorialPopupMenuItem(
                            text = choice,
                            selected = choice == value,
                            enabled = enabled,
                            onClick = {
                                expanded = false
                                onChange(choice)
                                if (field.action != null) onAction(false)
                            },
                        )
                    }
                }
            }
        }
        else -> {
            EditorialCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (enabled) 1f else 0.38f),
                cornerRadius = 16.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentPadding = PaddingValues(14.dp),
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { onChange(it.take(4096)) },
                    label = { Text(label) },
                    supportingText = description?.let { { Text(it) } },
                    enabled = enabled,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (field.type == "password") {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (field.type == "password") KeyboardType.Password else KeyboardType.Text,
                    ),
                )
                if (field.action != null) {
                    TextButton(
                        onClick = { onAction(false) },
                        enabled = enabled,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.sources_apply_field))
                    }
                }
            }
        }
    }
}
