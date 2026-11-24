package indi.renakoni.nextvol.ui.home.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.SourceCategory
import indi.renakoni.nextvol.data.web.SourceDiscoveryCategory
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.home.HomeSettingsAction
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryEmpty
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryFailure
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryFilterControl
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryPageContent
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryPageState
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryScroll
import indi.renakoni.nextvol.ui.home.discovery.SourceScopeEmpty
import indi.renakoni.nextvol.ui.home.discovery.SourceScopeTitle
import indi.renakoni.nextvol.ui.home.discovery.SourceTabs
import indi.renakoni.nextvol.ui.home.discovery.sourceTopBarHeight
import io.nightfish.lightnovelreader.api.identifier.Identifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    state: DiscoveryPageState,
    onSelect: (Identifier) -> Unit,
    onCategory: (SourceDiscoveryCategory) -> Unit,
    onScroll: (Identifier, DiscoveryScroll) -> Unit,
    onRefresh: () -> Unit,
    onManageSources: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    onInput: (String, String) -> Unit = { _, _ -> },
    onAction: (String, Boolean) -> Unit = { _, _ -> },
    onScope: (SourceCategory?) -> Unit = {},
    onPage: (Int) -> Unit = {},
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val content = state.content[state.selected] ?: DiscoveryPageContent()
    val isRefreshing = !state.loadingSources && (content.refreshing || content.acting || (content.loading && content.categories.isEmpty()))

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CategoriesTopBar(
                onRefresh = onRefresh,
                onSettings = onSettings,
                loading = !state.loadingSources && (content.loading || content.acting),
                title = { SourceScopeTitle(state, onScope) },
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.loadingSources) {
                // No inventory snapshot yet is not an authoritative empty-source result.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.sources.isEmpty()) {
                SourceScopeEmpty(state, explore = false, onScope, onManageSources)
            } else {
                SourceTabs(state, onSelect, onPage)
                val id = state.selected
                if (id != null) {
                    key(id, content.resetId) {
                        val list = rememberLazyListState(content.scroll.index, content.scroll.offset)
                        LaunchedEffect(list) {
                            snapshotFlow { DiscoveryScroll(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }
                                .collect { onScroll(id, it) }
                        }

                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = onRefresh,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            // Keep bottom clearance out of the item keys so delayed content starts at the top.
                            LazyColumn(
                                state = list,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = 8.dp, bottom = 92.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                content.error?.let { error ->
                                    item(key = "error") {
                                        DiscoveryFailure(
                                            error = error,
                                            retry = onRefresh,
                                            manage = onManageSources,
                                            back = onBack,
                                            field = content.errorField,
                                            permission = content.errorPermission,
                                            diagnostic = content.errorDiagnostic,
                                            httpStatus = content.errorHttpStatus,
                                        )
                                    }
                                }

                                if (content.buttons.isNotEmpty()) {
                                    item(key = "action-buttons") {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            items(content.buttons, key = { it.id }) { button ->
                                                val enabled = !content.acting && !content.loading
                                                Box(
                                                    modifier = Modifier
                                                        .heightIn(min = 42.dp)
                                                        .clip(CircleShape)
                                                        .background(tokens.activePillFill)
                                                        .border(0.75.dp, tokens.activePillBorder, CircleShape)
                                                        .combinedClickable(
                                                            enabled = enabled,
                                                            role = Role.Button,
                                                            onClick = { onAction(button.id, false) },
                                                            onLongClick = { onAction(button.id, true) },
                                                        )
                                                        .alpha(if (enabled) 1f else 0.4f)
                                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Text(
                                                        text = button.title,
                                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.W600),
                                                        color = colors.primary,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                items(content.filters, key = { "input:${it.id}" }) { filter ->
                                    Column(Modifier.padding(horizontal = 16.dp)) {
                                        DiscoveryFilterControl(filter, content.values[filter.id].orEmpty()) {
                                            if (!content.acting) onInput(filter.id, it)
                                        }
                                    }
                                }

                                if (content.loaded && content.categories.isEmpty() && content.buttons.isEmpty() && content.filters.isEmpty()) {
                                    item {
                                        DiscoveryEmpty(stringResource(R.string.categories_empty), onManageSources)
                                    }
                                }

                                if (content.categories.isNotEmpty()) {
                                    item(key = "category-tags") {
                                        FlowRow(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            content.categories.forEach { category ->
                                                key(category.id) {
                                                    if (category.target.target.isBlank()) {
                                                        SectionHeader(
                                                            text = category.title,
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(top = 10.dp, bottom = 2.dp)
                                                                .semantics { heading() },
                                                        )
                                                    } else {
                                                        Box(
                                                            modifier = Modifier
                                                                .heightIn(min = 40.dp)
                                                                .clip(CircleShape)
                                                                .background(tokens.cardSurface)
                                                                .border(0.75.dp, tokens.cardBorder, CircleShape)
                                                                .clickable { onCategory(category) }
                                                                .semantics { role = Role.Button }
                                                                .padding(horizontal = 16.dp, vertical = 9.dp),
                                                            contentAlignment = Alignment.Center,
                                                        ) {
                                                            Text(
                                                                text = category.title.ifBlank { stringResource(R.string.discovery_unnamed_entry) },
                                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.W500),
                                                                color = colors.onSurface,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoriesTopBar(
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    loading: Boolean = false,
    title: @Composable () -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    val tokens = NextVolDesign.tokens
    val refreshLabel = stringResource(R.string.discovery_refresh)

    TopAppBar(
        title = title,
        expandedHeight = sourceTopBarHeight(),
        actions = {
            if (loading) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onRefresh)
                        .semantics {
                            role = Role.Button
                            contentDescription = refreshLabel
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(tokens.cardSurface)
                            .border(0.75.dp, tokens.cardBorder, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            } else {
                EditorialIconButton(
                    onClick = onRefresh,
                    painter = painterResource(R.drawable.refresh_24px),
                    contentDescription = refreshLabel,
                )
            }
            HomeSettingsAction(onSettings)
            Spacer(Modifier.width(4.dp))
        },
        scrollBehavior = scrollBehavior,
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}
