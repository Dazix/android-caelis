package com.dazix.intervalometer.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dazix.intervalometer.R

/**
 * A top-level place in the app, as the navigation shows it.
 *
 * The labels are deliberately not the screen titles: a navigation label has one line to work with,
 * while a title has a whole app bar. Both exist as strings, and both are translated.
 */
enum class NavigationDestination(
    val screen: Screen,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    SESSION(Screen.HOME, R.string.nav_short_home, AppIcons.Session),
    SETUP(Screen.SETUP, R.string.nav_short_setup, AppIcons.Setup),
    RAMP(Screen.RAMP, R.string.nav_short_ramp, AppIcons.Ramp),
    ADVANCED(Screen.ADVANCED, R.string.nav_short_advanced, AppIcons.Advanced),
    PERMISSIONS(Screen.ONBOARDING, R.string.nav_short_permissions, AppIcons.Permissions),
    ;

    companion object {
        /**
         * The destination the given screen belongs to, or `null` for a screen that is pushed on top of
         * one rather than being a place of its own — the app picker, which is reached from the session
         * screen and returns to it.
         */
        fun forScreen(screen: Screen): NavigationDestination? = entries.firstOrNull { it.screen == screen }
    }
}

/**
 * How the navigation reaches a screen's [ScreenScaffold].
 *
 * The shell decides *whether* there is a bottom bar (there is not, once the window is wide enough for
 * a rail) and the scaffold decides *where* it goes, so a screen never has to know which of the two it
 * is being shown inside.
 */
val LocalNavigationBar = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

/**
 * Below this the window gets a bottom navigation bar, at or above it a navigation rail down the side.
 *
 * 600dp is the Material breakpoint between compact and medium: a Pixel 7 Pro is ~412dp wide upright
 * and ~892dp on its side, so this is exactly the rotation the calibration flow depends on — and the
 * reason the app is no longer one long column on a landscape screen.
 */
internal val RailBreakpoint: Dp = 600.dp

internal fun wantsNavigationRail(width: Dp): Boolean = width >= RailBreakpoint

/**
 * Wraps the current screen in the app's navigation, in whichever of its two forms the window can take.
 *
 * [enabled] is false for a screen that is not a destination of its own: the app picker fills the window
 * and leaves by its own Back, so a navigation bar under it would offer to leave it sideways.
 */
@Composable
fun AppNavigationShell(
    current: Screen,
    onNavigate: (Screen) -> Unit,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val rail = wantsNavigationRail(maxWidth)
        if (!enabled) {
            CompositionLocalProvider(LocalNavigationBar provides null) { content() }
            return@BoxWithConstraints
        }
        if (rail) {
            // Read out here: inside the rail the composable lambdas have receivers of their own and
            // this one is no longer reachable implicitly.
            val windowHeight = maxHeight
            Row(modifier = Modifier.fillMaxSize()) {
                WideNavigationRail(modifier = Modifier.fillMaxHeight()) {
                    // The rail brings no scroll container of its own, and five items with Czech
                    // labels at a large font scale are taller than a landscape phone — without this
                    // the last destination is clipped off with no way to reach it.
                    //
                    // The explicit cap is the window's own height: the rail measures its content
                    // itself, and a scroll container only scrolls once something bounds it.
                    Column(
                        modifier = Modifier
                            .heightIn(max = windowHeight)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        NavigationDestination.entries.forEach { destination ->
                            val label = stringResource(destination.labelRes)
                            WideNavigationRailItem(
                                selected = destination.screen == current,
                                onClick = { onNavigate(destination.screen) },
                                icon = {
                                    Icon(imageVector = destination.icon, contentDescription = null)
                                },
                                // One line, ellipsised: the item has a fixed height, so a label that
                                // wrapped would be cut through the middle of its second line.
                                label = {
                                    Text(
                                        text = label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                railExpanded = false,
                            )
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    CompositionLocalProvider(LocalNavigationBar provides null) { content() }
                }
            }
        } else {
            // Remembered, not rebuilt: LocalNavigationBar is a static local, so a new lambda identity
            // would invalidate the whole screen under it on every recomposition of the shell.
            val bar: @Composable () -> Unit = remember(current, onNavigate) {
                @Composable {
                    ShortNavigationBar {
                        NavigationDestination.entries.forEach { destination ->
                            val label = stringResource(destination.labelRes)
                            ShortNavigationBarItem(
                                selected = destination.screen == current,
                                onClick = { onNavigate(destination.screen) },
                                icon = {
                                    Icon(imageVector = destination.icon, contentDescription = null)
                                },
                                // Five slots on a 412dp phone: the Czech labels are up to twice the
                                // English length, so they have to ellipsise rather than wrap into a
                                // second line the bar has no room for.
                                label = {
                                    Text(
                                        text = label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
            }
            CompositionLocalProvider(LocalNavigationBar provides bar) { content() }
        }
    }
}
