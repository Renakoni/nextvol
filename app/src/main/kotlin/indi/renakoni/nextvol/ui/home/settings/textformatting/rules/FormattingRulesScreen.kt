package indi.renakoni.nextvol.ui.home.settings.textformatting.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.format.FormattingRule
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.RegexText
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.utils.navigationBarSpacer
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormattingRulesScreen(
    rules: List<FormattingRule>,
    onToggle: (rule: Int) -> Unit,
    onClickBack: () -> Unit,
    onClickAddRule: () -> Unit,
    onClickEditRule: (ruleId: Int) -> Unit,
    isGlobal: Boolean = false,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            EditorialTopBar(
                title = stringResource(if (isGlobal) R.string.global_formatting_rules else R.string.book_rules),
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
                actions = {
                    EditorialIconButton(
                        onClick = onClickAddRule,
                        painter = painterResource(id = R.drawable.add_circle_24px),
                        contentDescription = stringResource(R.string.action_add),
                    )
                    Spacer(Modifier.width(4.dp))
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { paddingValues ->
        if (rules.isEmpty()) {
            EmptyPage(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                icon = painterResource(R.drawable.find_replace_24px),
                title = stringResource(R.string.nothing_here),
                description = "",
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(rules, key = { it.id ?: it.hashCode() }) { rule ->
                    val ruleId = rule.id ?: return@items
                    RuleListItem(
                        rule = rule,
                        ruleId = ruleId,
                        onEdit = onClickEditRule,
                        onToggle = onToggle,
                    )
                }
                navigationBarSpacer()
            }
        }
    }
}

@Composable
private fun RuleListItem(
    rule: FormattingRule,
    ruleId: Int,
    onEdit: (ruleId: Int) -> Unit,
    onToggle: (ruleId: Int) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    EditorialCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onEdit(ruleId) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (rule.isRegex) {
                        Icon(
                            modifier = Modifier.size(18.dp),
                            painter = painterResource(R.drawable.regular_expression_24px),
                            tint = colorScheme.primary,
                            contentDescription = null,
                        )
                    }
                    Text(
                        text = rule.name,
                        style = typography.titleSmall.copy(fontWeight = FontWeight.W600),
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (rule.isRegex) {
                        RegexText(
                            regex = rule.match,
                            style = typography.labelMedium,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    } else {
                        Text(
                            text = rule.match,
                            style = typography.labelMedium,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Icon(
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .size(18.dp),
                        painter = painterResource(R.drawable.keyboard_double_arrow_right_24px),
                        tint = colorScheme.onSurfaceVariant,
                        contentDescription = null,
                    )
                    Text(
                        text = rule.replacement,
                        style = typography.labelMedium,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }

            VerticalDivider(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .height(26.dp),
                color = tokens.divider,
            )

            MiuixSwitch(
                checked = rule.isEnabled,
                onCheckedChange = { onToggle(ruleId) },
            )
        }
    }
}
