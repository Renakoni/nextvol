package indi.renakoni.nextvol.ui.home.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.ui.home.settings.sources.SourcePermissionLabel
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryFilter
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryPermission
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscoveryTopBar(
    title: String,
    sourceName: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onSettings: () -> Unit = {},
    onFilters: (() -> Unit)?,
    filterCount: Int,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    val colors = MaterialTheme.colorScheme
    EditorialTopBar(
        title = title,
        subtitle = sourceName.ifBlank { null },
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
            if (onFilters != null) {
                BadgedBox(
                    badge = {
                        if (filterCount > 0) {
                            Badge(containerColor = colors.primary, contentColor = colors.onPrimary) {
                                Text(filterCount.toString())
                            }
                        }
                    },
                ) {
                    EditorialIconButton(
                        onClick = onFilters,
                        painter = painterResource(R.drawable.filter_list_24px),
                        contentDescription = stringResource(R.string.discovery_filters),
                        containerColor = if (filterCount > 0) NextVolDesign.tokens.activePillFill else colors.surfaceContainerLow.copy(alpha = 0.85f),
                        contentColor = if (filterCount > 0) colors.primary else colors.onSurface,
                    )
                }
            }
            EditorialIconButton(
                onClick = onRefresh,
                painter = painterResource(R.drawable.refresh_24px),
                contentDescription = stringResource(R.string.discovery_refresh),
            )
            Spacer(Modifier.width(4.dp))
        },
    )
}

@Composable
internal fun DiscoveryEmpty(message: String, onManageSources: () -> Unit) {
    EmptyPage(
        icon = painterResource(R.drawable.empty_90dp),
        title = stringResource(R.string.nothing_here),
        description = message,
    ) {
        Button(
            onClick = onManageSources,
            shape = RoundedCornerShape(14.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
        ) {
            Text(stringResource(R.string.sources_title))
        }
    }
}

@Composable
internal fun DiscoveryFailure(
    error: DiscoveryError,
    retry: (() -> Unit)?,
    manage: () -> Unit,
    back: (() -> Unit)?,
    field: String? = null,
    permission: DiscoveryPermission? = null,
    diagnostic: hnovel.execution.ExecutionResult.Failure? = null,
    httpStatus: Int? = null,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val message = if (error == DiscoveryError.Network && httpStatus != null) {
        stringResource(
            if (httpStatus == 401 || httpStatus == 403) R.string.discovery_http_denied else R.string.discovery_http_error,
            httpStatus,
        )
    } else {
        stringResource(indi.renakoni.nextvol.data.web.sourceFailureMessage(error))
    }
    var details by remember(error, field, permission, diagnostic, httpStatus) { mutableStateOf(false) }
    val canRetry = retry != null &&
        error != DiscoveryError.Unavailable &&
        error != DiscoveryError.Unsupported &&
        error != DiscoveryError.InvalidRequest

    EditorialCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        cornerRadius = 18.dp,
        containerColor = colors.errorContainer.copy(alpha = if (tokens.isDark) 0.28f else 0.36f),
        borderColor = colors.error.copy(alpha = 0.36f),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EditorialIconBadge(
                    painter = painterResource(R.drawable.error_24px),
                    size = 34.dp,
                    iconSize = 18.dp,
                    cornerRadius = 10.dp,
                    containerColor = colors.error.copy(alpha = 0.16f),
                    contentColor = colors.error,
                )
                Text(
                    text = stringResource(R.string.discovery_load_failed),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W600),
                    color = colors.onSurface,
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            if (error == DiscoveryError.PermissionDenied) {
                permission?.let { SourcePermissionLabel(it.origin, it.resourceKind) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canRetry) {
                    TextButton(onClick = { retry.invoke() }) {
                        Text(stringResource(R.string.discovery_retry))
                    }
                }
                TextButton(onClick = manage) {
                    Text(stringResource(R.string.sources_title))
                }
                if (back != null && (error == DiscoveryError.InvalidRequest || error == DiscoveryError.Unavailable)) {
                    TextButton(onClick = back) {
                        Text(stringResource(R.string.sources_back))
                    }
                }
                TextButton(onClick = { details = true }) {
                    Text(stringResource(R.string.discovery_error_details))
                }
            }
        }
    }

    if (details) {
        AlertDialog(
            onDismissRequest = { details = false },
            shape = RoundedCornerShape(tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
            title = {
                Text(
                    text = stringResource(R.string.discovery_error_details),
                    style = MaterialTheme.typography.displayMedium,
                )
            },
            text = {
                SelectionContainer {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(R.string.discovery_error_type, error.name),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        field?.let {
                            Text(
                                text = stringResource(R.string.discovery_rule_field, it),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        diagnostic?.let {
                            Text(
                                text = kotlinx.serialization.json.Json.encodeToString(
                                    hnovel.execution.ExecutionResult.Failure.serializer(),
                                    it,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        if (error == DiscoveryError.PermissionDenied) {
                            permission?.let { SourcePermissionLabel(it.origin, it.resourceKind) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { details = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
internal fun DiscoveryFilterControl(filter: DiscoveryFilter, value: String, onChange: (String) -> Unit) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    when (filter) {
        is DiscoveryFilter.Text -> {
            var draft by remember(value) { mutableStateOf(value) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 4096) draft = it },
                    label = { Text(filter.title) },
                    singleLine = true,
                    shape = RoundedCornerShape(tokens.controlRadius),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onChange(draft) }, enabled = draft != value) {
                    Text(stringResource(R.string.discovery_apply))
                }
            }
        }
        is DiscoveryFilter.Toggle -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = filter.title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            MiuixSwitch(
                checked = value == "true",
                onCheckedChange = { onChange(it.toString()) },
            )
        }
        is DiscoveryFilter.Choice -> {
            var open by remember { mutableStateOf(false) }
            Box {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(tokens.cardSurface)
                        .border(0.75.dp, tokens.cardBorder, CircleShape)
                        .clickable { open = true }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "${filter.title}: ${filter.options[value].orEmpty()}",
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        painter = painterResource(R.drawable.search_expand_24px),
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                EditorialPopupMenu(expanded = open, onDismissRequest = { open = false }) {
                    filter.options.forEach { (key, label) ->
                        EditorialPopupMenuItem(
                            text = label,
                            selected = key == value,
                            onClick = {
                                open = false
                                onChange(key)
                            },
                        )
                    }
                }
            }
        }
        is DiscoveryFilter.Number -> {
            var draft by remember(value) { mutableStateOf(value) }
            val valid = draft.toIntOrNull()?.let { it in filter.min..filter.max } == true
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(filter.title) },
                    isError = !valid,
                    singleLine = true,
                    shape = RoundedCornerShape(tokens.controlRadius),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onChange(draft) }, enabled = valid && draft != value) {
                    Text(stringResource(R.string.discovery_apply))
                }
            }
        }
    }
}
