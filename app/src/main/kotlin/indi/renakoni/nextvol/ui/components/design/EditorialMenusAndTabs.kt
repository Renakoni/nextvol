package indi.renakoni.nextvol.ui.components.design

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.LocalNextVolHazeState
import indi.renakoni.nextvol.theme.NextVolDesign
import kotlin.math.roundToInt

/**
 * Refined floating popup menu replacing raw boxy Material DropdownMenus.
 * Preserves standard Window positioning, dismissal, and accessibility semantics while
 * introducing rounded glass-rimmed elevation, section headers, active checkmarks, and destructive tint.
 */
@Composable
fun EditorialPopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 6.dp),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val shape = RoundedCornerShape(tokens.popupRadius)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 196.dp, max = 280.dp),
        offset = offset,
        properties = properties,
        shape = shape,
        containerColor = tokens.cardElevatedSurface,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(0.75.dp, tokens.cardBorder),
        content = content,
    )
}

@Composable
fun EditorialPopupMenuHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.W600,
            letterSpacing = 0.4.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
fun EditorialPopupMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    leadingIcon: Painter? = null,
    selected: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val itemShape = RoundedCornerShape(12.dp)
    val foreground = when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        destructive -> colors.error
        selected -> colors.primary
        else -> colors.onSurface
    }
    val iconTint = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.38f)
        destructive -> colors.error
        selected -> colors.primary
        else -> colors.onSurfaceVariant
    }

    DropdownMenuItem(
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (selected) FontWeight.W600 else FontWeight.W400,
                    ),
                    color = foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!supportingText.isNullOrBlank()) {
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (enabled) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.5f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        onClick = onClick,
        modifier = modifier
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(itemShape)
            .background(if (selected) tokens.activePillFill else Color.Transparent),
        leadingIcon = leadingIcon?.let { painter ->
            {
                Icon(
                    painter = painter,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        trailingIcon = if (selected) {
            {
                Icon(
                    painter = painterResource(R.drawable.check_24px),
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            null
        },
        enabled = enabled,
        colors = MenuDefaults.itemColors(
            textColor = foreground,
            leadingIconColor = iconTint,
            trailingIconColor = colors.primary,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * Spring-sliding segmented control with crisp Light & Dark depth (lifted pure white thumb in Light,
 * elevated graphite thumb with subtle top-lit glass specular rim in Dark).
 */
@Composable
fun EditorialPillTabRow(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val safeIndex = selectedTabIndex.coerceIn(0, tabs.lastIndex)
    val trackColor = if (tokens.isDark) {
        colors.surfaceContainerLow
    } else {
        colors.surfaceContainerHigh.copy(alpha = 0.78f)
    }
    val thumbColor = if (tokens.isDark) {
        colors.surfaceContainerHighest
    } else {
        colors.surfaceContainerLowest
    }
    val thumbRimBrush = remember(tokens.isDark) {
        if (tokens.isDark) {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.18f),
                    Color.White.copy(alpha = 0.04f),
                ),
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.90f),
                    Color.Black.copy(alpha = 0.04f),
                ),
            )
        }
    }
    val animatedIndex by animateFloatAsState(
        targetValue = safeIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.80f, stiffness = 440f),
        label = "editorialPillThumbPos",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(trackColor)
            .padding(3.dp),
    ) {
        val slotWidth = maxWidth / tabs.size
        val slotWidthPx = with(density) { slotWidth.toPx() }
        val thumbShape = RoundedCornerShape(10.dp)

        Box(
            modifier = Modifier
                .offset { IntOffset((animatedIndex * slotWidthPx).roundToInt(), 0) }
                .width(slotWidth)
                .fillMaxHeight()
                .then(
                    if (!tokens.isDark) {
                        Modifier.shadow(
                            elevation = 2.dp,
                            shape = thumbShape,
                            ambientColor = Color.Black.copy(alpha = 0.08f),
                            spotColor = Color.Black.copy(alpha = 0.08f),
                            clip = false,
                        )
                    } else {
                        Modifier
                    },
                )
                .clip(thumbShape)
                .background(thumbColor)
                .border(BorderStroke(0.6.dp, thumbRimBrush), thumbShape),
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, title ->
                val selected = index == safeIndex
                val fg by animateColorAsState(
                    targetValue = if (selected) colors.onSurface else colors.onSurfaceVariant,
                    animationSpec = tween(160),
                    label = "editorialPillTabFg",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(thumbShape)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onTabSelected(index) },
                        )
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = if (selected) FontWeight.W600 else FontWeight.W500,
                        ),
                        color = fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Scroll-reactive Editorial TopAppBar with optional Haze progressive frosted glass backdrop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorialTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    hazeState: HazeState? = LocalNextVolHazeState.current,
    frosted: Boolean = false,
    showBottomDivider: Boolean = false,
    windowInsets: WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
) {
    val tokens = NextVolDesign.tokens
    val surfaceBg = MaterialTheme.colorScheme.surface
    val useBlur = frosted && hazeState != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val containerColor = when {
        useBlur -> Color.Transparent
        frosted -> tokens.topBarFallbackSurface
        else -> MaterialTheme.colorScheme.surface
    }

    val blurModifier = if (useBlur) {
        val blurStyle = remember(surfaceBg, tokens.topBarGlassTint, tokens.topBarFallbackSurface) {
            HazeBlurStyle {
                blurRadius(14.dp)
                noiseFactor(0.02f)
                backgroundColor(surfaceBg)
                colorEffects(listOf(HazeColorEffect.tint(tokens.topBarGlassTint)))
                fallbackColorEffect(HazeColorEffect.tint(tokens.topBarFallbackSurface))
                progressive(HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0.12f))
            }
        }
        Modifier.hazeBlur(input = HazeInput.Sources(hazeState), style = blurStyle)
    } else {
        Modifier
    }

    Column(modifier = modifier.then(blurModifier)) {
        TopAppBar(
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            navigationIcon = navigationIcon,
            actions = actions,
            scrollBehavior = scrollBehavior,
            windowInsets = windowInsets,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = containerColor,
                scrolledContainerColor = if (useBlur) Color.Transparent else tokens.topBarFallbackSurface,
            ),
        )
        if (showBottomDivider) {
            EditorialHairlineDivider()
        }
    }
}
