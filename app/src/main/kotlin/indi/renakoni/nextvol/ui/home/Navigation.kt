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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

    val avdReading = AnimatedImageVector.animatedVectorResource(R.drawable.animated_book)
    val avdShelf = AnimatedImageVector.animatedVectorResource(R.drawable.animated_bookshelf)
    val avdExplore = AnimatedImageVector.animatedVectorResource(R.drawable.animated_explore)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.systemBars.only(
                    WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                )
            )
            .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        LiquidGlassSurface(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
            shape = CircleShape,
            variant = GlassVariant.Liquid,
            elevation = 12.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LiquidNavigationTab(
                    selected = isReading,
                    onClick = { controller.navigateToMainRoot(Route.Main.Reading) },
                    painter = rememberAnimatedVectorPainter(avdReading, isReading),
                    label = stringResource(R.string.nav_reading),
                )
                LiquidNavigationTab(
                    selected = isBookshelf,
                    onClick = { controller.navigateToMainRoot(Route.Main.Bookshelf) },
                    painter = rememberAnimatedVectorPainter(avdShelf, isBookshelf),
                    label = stringResource(R.string.nav_bookshelf),
                )
                LiquidNavigationTab(
                    selected = isExploration,
                    onClick = { controller.navigateToMainRoot(Route.Main.Explore) },
                    painter = rememberAnimatedVectorPainter(avdExplore, isExploration),
                    label = stringResource(R.string.nav_explore),
                )
                LiquidNavigationTab(
                    selected = isCategories,
                    onClick = { controller.navigateToMainRoot(Route.Main.Categories()) },
                    painter = painterResource(R.drawable.view_list_24px),
                    label = stringResource(R.string.categories_title),
                )
            }
        }
    }
}

@Composable
private fun RowScope.LiquidNavigationTab(
    selected: Boolean,
    onClick: () -> Unit,
    painter: Painter,
    label: String,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val pillBackground by animateColorAsState(
        targetValue = if (selected) tokens.activePillFill else Color.Transparent,
        animationSpec = tween(220),
        label = "navPillFill",
    )
    val pillBorder by animateColorAsState(
        targetValue = if (selected) tokens.activePillBorder else Color.Transparent,
        animationSpec = tween(220),
        label = "navPillBorder",
    )
    val contentTint by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.onSurfaceVariant,
        animationSpec = tween(200),
        label = "navTint",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.06f else 1.0f,
        animationSpec = spring(
            dampingRatio = 0.65f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "navIconScale",
    )

    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 52.dp)
            .clip(CircleShape)
            .background(pillBackground)
            .border(0.75.dp, pillBorder, CircleShape)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
            )
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Icon(
            painter = painter,
            contentDescription = null,
            tint = contentTint,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.W600 else FontWeight.W500,
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
