package indi.renakoni.nextvol.ui.components

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import indi.renakoni.nextvol.ui.home.HomeNavigateBar

@Composable
fun LnrNavigationBar(
    showBottomBar: Boolean,
    selectedRoute: Any?,
    navController: NavController
) {
    val navInsets = WindowInsets.navigationBars
    val navHeight = navInsets.asPaddingValues().calculateBottomPadding()

    val showPlaceholder =
        !showBottomBar &&
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            navHeight > 0.dp

    val slideSpec = spring<IntOffset>(
        dampingRatio = 0.86f,
        stiffness = Spring.StiffnessMediumLow,
    )

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        AnimatedVisibility(
            modifier = Modifier.align(Alignment.BottomCenter),
            visible = showBottomBar,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = slideSpec,
            ) + fadeIn(tween(220)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = slideSpec,
            ) + fadeOut(tween(180)),
        ) {
            HomeNavigateBar(
                selectedRoute = selectedRoute,
                controller = navController
            )
        }

        AnimatedVisibility(
            modifier = Modifier.align(Alignment.BottomCenter),
            visible = showPlaceholder,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(navInsets)
                    .background(
                        colorScheme.surface.copy(alpha = 0.78f)
                    )
            )
        }
    }
}
