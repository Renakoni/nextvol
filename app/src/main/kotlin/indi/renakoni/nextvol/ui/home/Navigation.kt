package indi.renakoni.nextvol.ui.home

import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.navigation
import indi.renakoni.nextvol.R
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

@Suppress("unused")
fun NavController.navigateToHomeNavigation() {
    navigate(Route.Main)
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


    NavigationBar(
        windowInsets = WindowInsets.systemBars.only(
            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
        )
    ) {
        NavigationBarItem(
            selected = isReading,
            onClick = { controller.navigateToMainRoot(Route.Main.Reading) },
            icon = { Icon(painter = rememberAnimatedVectorPainter(avdReading, isReading), null) },
            label = { Text(stringResource(R.string.nav_reading), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        )
        NavigationBarItem(
            selected = isBookshelf,
            onClick = { controller.navigateToMainRoot(Route.Main.Bookshelf) },
            icon = { Icon(painter = rememberAnimatedVectorPainter(avdShelf, isBookshelf), null) },
            label = { Text(stringResource(R.string.nav_bookshelf), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        )
        NavigationBarItem(
            selected = isExploration,
            onClick = { controller.navigateToMainRoot(Route.Main.Explore) },
            icon = { Icon(painter = rememberAnimatedVectorPainter(avdExplore, isExploration), null) },
            label = { Text(stringResource(R.string.nav_explore), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        )
        NavigationBarItem(
            selected = isCategories,
            onClick = { controller.navigateToMainRoot(Route.Main.Categories()) },
            icon = { Icon(painterResource(R.drawable.view_list_24px), null) },
            label = { Text(stringResource(R.string.categories_title), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        )
    }
}

// CategorySourceSelection consumes this one-shot shortcut through select() once sources load.
// It is separate from namespace/sourceId route arguments (initial fallback) and the persisted
// category.namespace/category.source selection owned by DiscoveryPageViewModel.
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
