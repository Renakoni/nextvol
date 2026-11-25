@file:Suppress("AssignedValueIsNeverRead")

package indi.renakoni.nextvol.ui.home.settings.licenses

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.util.author
import indi.renakoni.nextvol.ProjectLinks
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.utils.navigationBarSpacer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(
    onClickBack: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val libraries by produceLibraries(R.raw.aboutlibraries)
    var query by rememberSaveable { mutableStateOf("") }
    val allItems = libraries?.libraries ?: emptyList()
    val filtered = remember(allItems, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            allItems
        } else {
            allItems.filter { lib ->
                lib.name.contains(needle, ignoreCase = true) ||
                    lib.author.contains(needle, ignoreCase = true) ||
                    lib.licenses.any { it.name.contains(needle, ignoreCase = true) }
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            EditorialTopBar(
                title = stringResource(R.string.settings_open_source_licenses),
                scrollBehavior = scrollBehavior,
                hazeState = null,
                frosted = false,
                navigationIcon = {
                    EditorialIconButton(
                        onClick = onClickBack,
                        painter = painterResource(id = R.drawable.arrow_back_24px),
                        contentDescription = stringResource(R.string.sources_back),
                    )
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .border(0.75.dp, tokens.cardBorder, CircleShape),
                placeholder = {
                    Text(
                        text = stringResource(R.string.search_input_hint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.search_24px),
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                painter = painterResource(R.drawable.close_24px),
                                contentDescription = stringResource(R.string.search_clear_query),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = tokens.cardSurface,
                    unfocusedContainerColor = tokens.cardSurface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            LicenseList(
                items = filtered,
                showAppCard = query.isBlank(),
            )
        }
    }
}

@Composable
fun LicenseList(
    items: List<Library>,
    showAppCard: Boolean = true,
) {
    val uriHandler = LocalUriHandler.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showAppCard) {
            item(key = "app_license_card") {
                AppLicenseCard(
                    onClick = {
                        uriHandler.openUri("${ProjectLinks.GITHUB}/blob/main/LICENSE")
                    },
                )
            }
        }

        if (items.isEmpty() && !showAppCard) {
            item {
                EmptyPage(
                    icon = painterResource(R.drawable.not_found_90dp),
                    title = stringResource(R.string.search_no_results),
                    description = "",
                )
            }
        }

        items(
            items = items,
            key = { it.artifactId },
        ) { lib ->
            LicenseItem(lib)
        }

        navigationBarSpacer()
    }
}

@Composable
private fun LicenseCard(
    title: String,
    subtitle: String? = null,
    licenseType: String? = null,
    onClick: (() -> Unit)? = null,
) {
    EditorialCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(16.dp),
        onClick = onClick,
    ) {
        Text(
            text = title,
            style = typography.titleMedium.copy(fontWeight = FontWeight.W600),
            color = colorScheme.onSurface,
        )

        if (!subtitle.isNullOrBlank() || !licenseType.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }

                if (!licenseType.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    LicenseTypeLabel(licenseType)
                }
            }
        }
    }
}

@Composable
private fun LicenseTypeLabel(text: String) {
    val tokens = NextVolDesign.tokens
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(tokens.activePillFill)
            .border(width = 0.75.dp, color = tokens.activePillBorder, shape = CircleShape)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = typography.labelSmall.copy(fontWeight = FontWeight.W600),
            color = colorScheme.primary,
            maxLines = 1,
        )
    }
}

@Composable
private fun AppLicenseCard(onClick: (() -> Unit)? = null) {
    LicenseCard(
        title = stringResource(R.string.app_name),
        subtitle = "NextVol & LightNovelReader contributors",
        licenseType = "Apache License 2.0",
        onClick = onClick,
    )
}

@Composable
fun LicenseItem(lib: Library) {
    var showDialog by remember { mutableStateOf(false) }

    LicenseCard(
        title = lib.name,
        subtitle = lib.author,
        licenseType = lib.licenses.firstOrNull()?.name,
        onClick = { showDialog = true },
    )

    if (showDialog) LicenseDialog(lib = lib, onDismiss = { showDialog = false })
}

@Composable
fun LicenseDialog(
    lib: Library,
    onDismiss: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.W600)
            }
        },
        title = {
            Column {
                Text(
                    text = lib.name,
                    style = typography.titleLarge.copy(fontWeight = FontWeight.W700),
                )
                if (lib.author.isNotBlank()) {
                    Text(
                        text = lib.author,
                        style = typography.labelLarge,
                        color = colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        text = {
            val licenseText = buildString {
                lib.licenses.forEach { lic ->
                    lic.licenseContent?.let { append(it + "\n\n") }
                }
            }.trim()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = licenseText.ifBlank { stringResource(R.string.nothing_here) },
                    style = typography.bodyMedium,
                )
            }
        },
    )
}
