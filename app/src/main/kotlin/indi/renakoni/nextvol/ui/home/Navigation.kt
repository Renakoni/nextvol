package indi.renakoni.nextvol.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.navigation
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.design.GlassVariant
import indi.renakoni.nextvol.ui.components.design.LiquidGlassSurface
import indi.renakoni.nextvol.ui.home.bookshelf.bookshelfNavigation
import indi.renakoni.nextvol.ui.home.categories.categoriesDestination
import indi.renakoni.nextvol.ui.home.explore.exploreNavigation
import indi.renakoni.nextvol.ui.home.reading.readingNavigation
import indi.renakoni.nextvol.ui.home.settings.settingsNavigation
import indi.renakoni.nextvol.utils.currentMainRoute
import io.nightfish.lightnovelreader.api.Route
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.roundToInt

fun NavGraphBuilder.homeNavigation() {
    navigation<Route.Main>(
        startDestination = Route.Main.Reading
    ) {
        readingNavigation()
        exploreNavigation()
        categoriesDestination()
        bookshelfNavigation()
        settingsNavigation()
    }
}

private data class HomeTabSpec(
    val route: Any,
    val label: String,
    val painter: Painter,
)

// NavHost derives hasBottomBarByRoute from currentMainRoute() != null. Settings and its
// secondary destinations return null there, which hides this bar without changing NavHost.
@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
fun HomeNavigateBar(
    selectedRoute: Any?,
    controller: NavController,
) {
    val isReading = selectedRoute is Route.Main.Reading
    val isBookshelf = selectedRoute is Route.Main.Bookshelf
    val isExploration = selectedRoute is Route.Main.Explore
    val isCategories = selectedRoute is Route.Main.Categories

    val selectedIndex = when {
        isReading -> 0
        isBookshelf -> 1
        isExploration -> 2
        isCategories -> 3
        else -> 0
    }

    val avdReading = AnimatedImageVector.animatedVectorResource(R.drawable.animated_book)
    val avdShelf = AnimatedImageVector.animatedVectorResource(R.drawable.animated_bookshelf)
    val avdExplore = AnimatedImageVector.animatedVectorResource(R.drawable.animated_explore)

    val tabs = listOf(
        HomeTabSpec(
            route = Route.Main.Reading,
            label = stringResource(R.string.nav_reading),
            painter = rememberAnimatedVectorPainter(avdReading, isReading),
        ),
        HomeTabSpec(
            route = Route.Main.Bookshelf,
            label = stringResource(R.string.nav_bookshelf),
            painter = rememberAnimatedVectorPainter(avdShelf, isBookshelf),
        ),
        HomeTabSpec(
            route = Route.Main.Explore,
            label = stringResource(R.string.nav_explore),
            painter = rememberAnimatedVectorPainter(avdExplore, isExploration),
        ),
        HomeTabSpec(
            route = Route.Main.Categories(),
            label = stringResource(R.string.categories_title),
            painter = painterResource(R.drawable.view_list_24px),
        ),
    )

    val tokens = NextVolDesign.tokens
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current

    var dragging by remember { mutableStateOf(false) }
    var dragIndex by remember { mutableFloatStateOf(selectedIndex.toFloat()) }

    val targetLensIndex = if (dragging) dragIndex else selectedIndex.toFloat()
    val animatedLensIndex by animateFloatAsState(
        targetValue = targetLensIndex,
        animationSpec = if (dragging) {
            spring(dampingRatio = 0.85f, stiffness = 900f)
        } else {
            spring(dampingRatio = 0.72f, stiffness = 390f)
        },
        label = "liquidNavLensPos",
    )

    // Liquid stretch & jelly response while the droplet travels or is scrubbed by finger.
    val travelDelta = abs(animatedLensIndex - targetLensIndex)
    val lensScaleX = (1f + (travelDelta * 0.24f).coerceAtMost(0.18f) + if (dragging) 0.05f else 0f)
    val lensScaleY = (1f - (travelDelta * 0.08f).coerceAtMost(0.06f) + if (dragging) 0.04f else 0f)

    // Compact floating pill width leaves generous breathing room on both sides so book covers peek cleanly.
    val barWidth = 276.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.systemBars.only(
                    WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                )
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        LiquidGlassSurface(
            modifier = Modifier
                .widthIn(max = barWidth)
                .fillMaxWidth()
                .height(58.dp),
            shape = CircleShape,
            variant = GlassVariant.Liquid,
            blurRadius = 11.dp,
            elevation = 14.dp,
            borderWidth = 1.dp,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .pointerInput(tabs.size, isRtl) {
                        val slotWidthPx = size.width.toFloat() / tabs.size
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                dragging = true
                                val x = if (isRtl) size.width - offset.x else offset.x
                                dragIndex = (x / slotWidthPx - 0.5f).coerceIn(0f, (tabs.size - 1).toFloat())
                            },
                            onDragCancel = {
                                dragging = false
                            },
                            onDragEnd = {
                                val resolved = dragIndex.roundToInt().coerceIn(0, tabs.lastIndex)
                                dragging = false
                                controller.navigateToMainRoot(tabs[resolved].route)
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val signedDelta = if (isRtl) -dragAmount else dragAmount
                                dragIndex = (dragIndex + signedDelta / slotWidthPx)
                                    .coerceIn(0f, (tabs.size - 1).toFloat())
                            },
                        )
                    },
            ) {
                val slotWidth = maxWidth / tabs.size
                val slotWidthPx = with(density) { slotWidth.toPx() }
                val lensFillBrush = remember(tokens.isDark) {
                    if (tokens.isDark) {
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.22f),
                                Color.White.copy(alpha = 0.13f),
                            ),
                        )
                    } else {
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0x17000000),
                                Color(0x11000000),
                            ),
                        )
                    }
                }
                val lensRimBrush = remember(tokens.isDark) {
                    if (tokens.isDark) {
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.34f),
                                Color.White.copy(alpha = 0.08f),
                            ),
                        )
                    } else {
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.72f),
                                Color.White.copy(alpha = 0.18f),
                            ),
                        )
                    }
                }

                // Sliding optical liquid droplet lens behind the 4 tab items.
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset((animatedLensIndex * slotWidthPx).roundToInt(), 0)
                        }
                        .width(slotWidth)
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleX = lensScaleX
                            scaleY = lensScaleY
                        }
                        .clip(CircleShape)
                        .background(lensFillBrush)
                        .border(BorderStroke(0.65.dp, lensRimBrush), CircleShape),
                )

                val highlightedIndex = if (dragging) {
                    dragIndex.roundToInt().coerceIn(0, tabs.lastIndex)
                } else {
                    selectedIndex
                }

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .selectableGroup(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        LiquidNavigationTab(
                            selected = index == selectedIndex,
                            highlighted = index == highlightedIndex,
                            onClick = { controller.navigateToMainRoot(tab.route) },
                            painter = tab.painter,
                            label = tab.label,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.LiquidNavigationTab(
    selected: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    painter: Painter,
    label: String,
) {
    val colors = MaterialTheme.colorScheme
    val contentTint by animateColorAsState(
        targetValue = if (highlighted) colors.primary else colors.onSurface.copy(alpha = 0.78f),
        animationSpec = tween(160),
        label = "navTint",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (highlighted) 1.07f else 1.0f,
        animationSpec = spring(
            dampingRatio = 0.65f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "navIconScale",
    )

    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(CircleShape)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
            )
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
    ) {
        Icon(
            painter = painter,
            contentDescription = null,
            tint = contentTint,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.5.sp,
                lineHeight = 13.sp,
                fontWeight = if (highlighted) FontWeight.W600 else FontWeight.W500,
            ),
            color = contentTint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// CategorySourceSelection consumes this one-shot shortcut through openSource() once sources load.
// Restored entries receive new requests here without replaying their original route arguments.
internal const val CATEGORY_SOURCE_REQUEST = "category.sourceRequest"

/** Bottom roots restore their own stack. An explicit category shortcut changes only its source. */
internal fun <T : Any> NavController.navigateToMainRoot(route: T) {
    // Incoming entries remain STARTED during transitions. The visible bottom bar must accept
    // another root selection then; sameRoot/launchSingleTop already prevent duplicate entries.
    if (currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != true) return
    val current = currentDestination.currentMainRoute()
    // Equality handles singleton roots and the canonical empty Categories tab marker. A Categories
    // shortcut with source arguments differs by data-class equality but still targets that same root.
    val sameRoot = current == route || (current is Route.Main.Categories && route is Route.Main.Categories)
    if (!sameRoot) navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(graph.findStartDestination().id) { saveState = true }
    }
    if (route is Route.Main.Categories && route.namespace != null && route.sourceId != null) {
        // Compose Navigation adds/restores this leaf synchronously. Deliver only if Categories
        // is still current after navigate(), without a throwing lookup for a missing entry.
        currentBackStackEntry?.takeIf { it.destination.hasRoute<Route.Main.Categories>() }
            ?.savedStateHandle?.set(CATEGORY_SOURCE_REQUEST, Json.encodeToString<Route.Main.Categories>(route))
    }
}
