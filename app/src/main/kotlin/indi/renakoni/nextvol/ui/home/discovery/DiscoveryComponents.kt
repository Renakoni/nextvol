package indi.renakoni.nextvol.ui.home.discovery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryFilter
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryPermission
import indi.renakoni.nextvol.ui.home.settings.sources.SourcePermissionLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscoveryTopBar(title: String, sourceName: String, onBack: () -> Unit, onRefresh: () -> Unit,
    onSettings: () -> Unit, onFilters: (() -> Unit)?, filterCount: Int) {
    var menu by remember { mutableStateOf(false) }
    val typography = MaterialTheme.typography
    val height = with(LocalDensity.current) {
        typography.displayLarge.lineHeight.toDp() + typography.bodySmall.lineHeight.toDp() + 16.dp
    }.coerceAtLeast(64.dp)
    TopAppBar(title = {
        Column {
            Text(title, style = typography.displayLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sourceName.isNotBlank()) Text(sourceName, style = typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }, expandedHeight = height, navigationIcon = {
        IconButton(onClick = onBack) { Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.sources_back)) }
    }, actions = {
        if (onFilters != null) IconButton(onClick = onFilters) {
            BadgedBox(badge = { if (filterCount > 0) Badge { Text(filterCount.toString()) } }) {
                Icon(painterResource(R.drawable.filter_list_24px), stringResource(R.string.discovery_filters),
                    tint = if (filterCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.action_more_options))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.discovery_refresh)) }, onClick = { menu = false; onRefresh() })
                DropdownMenuItem(text = { Text(stringResource(R.string.nav_settings)) }, onClick = { menu = false; onSettings() })
            }
        }
    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
}

@Composable
internal fun DiscoveryEmpty(message: String, onManageSources: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message)
        Button(onClick = onManageSources) { Text(stringResource(R.string.sources_title)) }
    }
}

@Composable
internal fun DiscoveryFailure(error: DiscoveryError, retry: (() -> Unit)?, manage: () -> Unit, back: (() -> Unit)?, field: String? = null,
    permission: DiscoveryPermission? = null, diagnostic: hnovel.execution.ExecutionResult.Failure? = null, httpStatus: Int? = null) {
    val message = if (error == DiscoveryError.Network && httpStatus != null)
        stringResource(if (httpStatus == 401 || httpStatus == 403) R.string.discovery_http_denied else R.string.discovery_http_error, httpStatus)
        else stringResource(indi.renakoni.nextvol.data.web.sourceFailureMessage(error))
    var details by remember(error, field, permission, diagnostic, httpStatus) { mutableStateOf(false) }
    val canRetry = retry != null && error != DiscoveryError.Unavailable && error != DiscoveryError.Unsupported &&
        error != DiscoveryError.InvalidRequest
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.error_24px), null, tint = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.discovery_load_failed), Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleSmall)
        }
        Text(message, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (error == DiscoveryError.PermissionDenied) permission?.let { SourcePermissionLabel(it.origin, it.resourceKind) }
        FlowRow {
            if (canRetry) TextButton(onClick = { retry?.invoke() }) { Text(stringResource(R.string.discovery_retry)) }
            TextButton(onClick = manage) { Text(stringResource(R.string.sources_title)) }
            if (back != null && (error == DiscoveryError.InvalidRequest || error == DiscoveryError.Unavailable))
                TextButton(onClick = back) { Text(stringResource(R.string.sources_back)) }
            TextButton(onClick = { details = true }) { Text(stringResource(R.string.discovery_error_details)) }
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false },
        title = { Text(stringResource(R.string.discovery_error_details)) },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(message)
                    Text(stringResource(R.string.discovery_error_type, error.name), style = MaterialTheme.typography.bodySmall)
                    field?.let { Text(stringResource(R.string.discovery_rule_field, it), style = MaterialTheme.typography.bodySmall) }
                    diagnostic?.let { Text(kotlinx.serialization.json.Json.encodeToString(
                        hnovel.execution.ExecutionResult.Failure.serializer(), it), style = MaterialTheme.typography.bodySmall) }
                    if (error == DiscoveryError.PermissionDenied) permission?.let { SourcePermissionLabel(it.origin, it.resourceKind) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { details = false }) { Text(stringResource(R.string.close)) } })
}

@Composable
internal fun DiscoveryFilterControl(filter: DiscoveryFilter, value: String, onChange: (String) -> Unit) {
    when (filter) {
        is DiscoveryFilter.Text -> {
            var draft by remember(value) { mutableStateOf(value) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = draft, onValueChange = { if (it.length <= 4096) draft = it },
                    label = { Text(filter.title) }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(draft) }, enabled = draft != value) {
                    Text(stringResource(R.string.discovery_apply))
                }
            }
        }
        is DiscoveryFilter.Toggle -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(filter.title, Modifier.padding(vertical = 12.dp))
            Switch(checked = value == "true", onCheckedChange = { onChange(it.toString()) })
        }
        is DiscoveryFilter.Choice -> {
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }) { Text("${filter.title}: ${filter.options[value].orEmpty()}") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    filter.options.forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, onClick = {
                        open = false
                        onChange(key)
                    }) }
                }
            }
        }
        is DiscoveryFilter.Number -> {
            var draft by remember(value) { mutableStateOf(value) }
            val valid = draft.toIntOrNull()?.let { it in filter.min..filter.max } == true
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text(filter.title) },
                    isError = !valid, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(draft) }, enabled = valid && draft != value) {
                    Text(stringResource(R.string.discovery_apply))
                }
            }
        }
    }
}
