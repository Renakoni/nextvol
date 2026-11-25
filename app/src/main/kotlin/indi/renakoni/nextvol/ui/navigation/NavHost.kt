package indi.renakoni.nextvol.ui.navigation

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import indi.renakoni.nextvol.theme.LocalNextVolHazeState
import indi.renakoni.nextvol.ui.LocalBottomBarController
import indi.renakoni.nextvol.ui.bangumi.bangumiDestination
import indi.renakoni.nextvol.ui.book.bookNavigation
import indi.renakoni.nextvol.ui.book.download.bookDownload
import indi.renakoni.nextvol.ui.bookmanager.bookManager
import indi.renakoni.nextvol.ui.components.LnrNavigationBar
import indi.renakoni.nextvol.ui.components.LnrSnackbar
import indi.renakoni.nextvol.ui.dialog.addBookToBookshelfDialog
import indi.renakoni.nextvol.ui.dialog.updatesAvailableDialog
import indi.renakoni.nextvol.ui.home.homeNavigation
import indi.renakoni.nextvol.ui.storagemanager.storageManager
import indi.renakoni.nextvol.utils.LocalClaimSnackbarHost
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.currentMainRoute
import indi.renakoni.nextvol.utils.expandEnter
import indi.renakoni.nextvol.utils.expandExit
import indi.renakoni.nextvol.utils.expandPopEnter
import indi.renakoni.nextvol.utils.expandPopExit
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle
import io.nightfish.lightnovelreader.api.ui.ReaderStyle

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun NextVolNavHost(
    navController: NavHostController,
    onReaderActiveChanged: (Boolean) -> Unit,
    readerStyle: ReaderStyle,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val navHazeState = rememberHazeState()

    var claimCount by remember { mutableIntStateOf(0) }
    val claim: (Boolean) -> Unit = remember { { take -> claimCount += if (take) 1 else -1 } }

    var bottomBarVisible by remember { mutableStateOf(true) }
    CompositionLocalProvider(
        LocalReaderStyle provides readerStyle,
        LocalNavController provides navController,
        LocalSnackbarHost provides snackbarHostState,
        LocalClaimSnackbarHost provides claim,
        LocalBottomBarController provides { visible -> bottomBarVisible = visible },
        LocalNextVolHazeState provides navHazeState,
    ) {
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDest = backStackEntry?.destination
        val selectedRoute = currentDest.currentMainRoute()
        val hasBottomBarByRoute = selectedRoute != null
        LaunchedEffect(selectedRoute) {
            bottomBarVisible = true
        }

        val bottomPadding by animateDpAsState(
            targetValue = if (bottomBarVisible && hasBottomBarByRoute) 88.dp else 0.dp,
            animationSpec = tween(
                durationMillis = 240,
                easing = FastOutSlowInEasing,
            ),
            label = "snackbarBottomPadding",
        )

        Scaffold(
            snackbarHost = {
                if (claimCount == 0) {
                    Box(Modifier.navigationBarsPadding().padding(bottom = bottomPadding.coerceAtLeast(0.dp))) {
                        SnackbarHost(snackbarHostState) { data -> LnrSnackbar(data) }
                    }
                }
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { _ ->
            Box(Modifier.fillMaxSize()) {
                NavHost(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(navHazeState),
                    navController = navController,
                    startDestination = Route.Main,
                    enterTransition = { destinationEnter() },
                    exitTransition = { destinationExit() },
                    popEnterTransition = { destinationEnter(pop = true) },
                    popExitTransition = { destinationExit(pop = true) }
                ) {
                    homeNavigation()
                    bookNavigation(onReaderActiveChanged)
                    bangumiDestination()
                    updatesAvailableDialog()
                    addBookToBookshelfDialog()
                    bookManager()
                    bookDownload()
                    storageManager()
                }

                LnrNavigationBar(
                    showBottomBar = hasBottomBarByRoute && bottomBarVisible,
                    selectedRoute = selectedRoute,
                    navController = navController
                )
            }
        }
    }
}

private const val MAIN_TAB_SLIDE_DURATION_MS = 180

private fun mainTabOrdinal(route: Any?): Int? = when (route) {
    is Route.Main.Reading -> 0
    is Route.Main.Bookshelf -> 1
    is Route.Main.Explore -> 2
    is Route.Main.Categories -> 3
    else -> null
}

private val AnimatedContentTransitionScope<NavBackStackEntry>.mainTabSlideDirection: AnimatedContentTransitionScope.SlideDirection?
    get() {
        val from = mainTabOrdinal(initialState.destination.currentMainRoute()) ?: return null
        val to = mainTabOrdinal(targetState.destination.currentMainRoute()) ?: return null
        if (from == to) return null
        return if (to > from) {
            AnimatedContentTransitionScope.SlideDirection.Start
        } else {
            AnimatedContentTransitionScope.SlideDirection.End
        }
    }

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.destinationEnter(pop: Boolean = false): EnterTransition {
    val direction = mainTabSlideDirection
    if (direction != null) {
        return slideIntoContainer(
            towards = direction,
            animationSpec = tween(MAIN_TAB_SLIDE_DURATION_MS, easing = FastOutSlowInEasing),
        )
    }
    return if (pop) expandPopEnter(fadeThrough = true) else expandEnter(fadeThrough = true)
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.destinationExit(pop: Boolean = false): ExitTransition {
    val direction = mainTabSlideDirection
    if (direction != null) {
        return slideOutOfContainer(
            towards = direction,
            animationSpec = tween(MAIN_TAB_SLIDE_DURATION_MS, easing = FastOutSlowInEasing),
        )
    }
    return if (pop) expandPopExit() else expandExit()
}
