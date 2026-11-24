package indi.renakoni.nextvol.ui.home.settings.textformatting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.format.FormattingGroup
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.utils.navigationBarSpacer
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextFormattingScreen(
    onClickGroup: (String) -> Unit,
    onClickBack: () -> Unit,
    groups: List<FormattingGroup>,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            EditorialTopBar(
                title = stringResource(id = R.string.settings_text_formatting),
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
    ) { paddingValues ->
        val rules = groups.filter { it.id.isNotBlank() }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "global_rules") {
                EditorialCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    contentPadding = PaddingValues(14.dp),
                    onClick = { onClickGroup("") },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EditorialIconBadge(
                            painter = painterResource(R.drawable.language_24px),
                            size = 48.dp,
                            iconSize = 24.dp,
                            cornerRadius = 14.dp,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.global_formatting_rules),
                                style = typography.titleMedium.copy(fontWeight = FontWeight.W600),
                                color = colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(R.string.n_rules, groups.firstOrNull { it.id.isEmpty() }?.size ?: 0),
                                style = typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                color = colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            modifier = Modifier.size(14.dp),
                            painter = painterResource(R.drawable.arrow_forward_ios_24px),
                            tint = colorScheme.onSurfaceVariant,
                            contentDescription = stringResource(R.string.action_open),
                        )
                    }
                }
            }

            if (rules.isNotEmpty()) {
                item(key = "book_rules_header") {
                    SectionHeader(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        text = stringResource(R.string.book_rules),
                    )
                }
            }

            items(rules, key = { it.id }) { group ->
                Group(
                    onClickGroup = { onClickGroup(group.id) },
                    formattingGroup = group,
                    bookInformationFlow = group.bookInformationFlow,
                )
            }
            navigationBarSpacer()
        }
    }
}

@Composable
private fun Group(
    onClickGroup: (String) -> Unit,
    formattingGroup: FormattingGroup,
    bookInformationFlow: Flow<Result<BookInformation, WebRequestError>>,
) {
    val bookInformationResult by bookInformationFlow.collectAsStateWithLifecycle(null)
    val ruleCountText = pluralStringResource(R.plurals.formatting_rules_count, formattingGroup.size, formattingGroup.size)

    EditorialCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(12.dp),
        onClick = { onClickGroup(formattingGroup.id) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bookInformationResult?.onOk { info ->
                Cover(
                    bookId = info.id,
                    width = 56.dp,
                    height = 80.dp,
                    uri = info.coverUri,
                    title = info.title,
                    author = info.author,
                    rounded = 10.dp,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = info.title,
                        style = typography.titleSmall.copy(fontWeight = FontWeight.W600),
                        color = colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (info.author.isNotBlank()) {
                        Text(
                            text = info.author,
                            style = typography.labelMedium,
                            color = colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = ruleCountText,
                        style = typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }?.onErr {
                EditorialIconBadge(
                    painter = painterResource(R.drawable.error_24px),
                    size = 44.dp,
                    iconSize = 22.dp,
                    cornerRadius = 12.dp,
                    containerColor = colorScheme.errorContainer.copy(alpha = 0.65f),
                    contentColor = colorScheme.error,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = stringResource(R.string.search_book_unavailable),
                        style = typography.titleSmall.copy(fontWeight = FontWeight.W600),
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = ruleCountText,
                        style = typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            } ?: run {
                Box(
                    modifier = Modifier
                        .size(width = 56.dp, height = 80.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colorScheme.surfaceContainerHighest),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colorScheme.surfaceContainerHighest),
                    )
                    Text(
                        text = ruleCountText,
                        style = typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                modifier = Modifier.size(14.dp),
                painter = painterResource(R.drawable.arrow_forward_ios_24px),
                tint = colorScheme.onSurfaceVariant,
                contentDescription = stringResource(R.string.action_open),
            )
        }
    }
}
