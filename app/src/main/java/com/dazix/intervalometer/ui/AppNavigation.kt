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
    SUN_AR(Screen.SUN_AR, R.string.nav_short_sun_ar, AppIcons.SunAndMoon),
    NIGHT_SKY_AR(Screen.NIGHT_SKY_AR, R.string.nav_short_night_sky, AppIcons.NightSky),
    SETTINGS(Screen.SETTINGS, R.string.nav_short_settings, AppIcons.Settings),
    ;

    companion object {
        /**
         * The destination the given screen belongs to, or `null` for a screen that is pushed on top of
         * one rather than being a top-level place of its own — sub-screens reachable from home.
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
        val bar: (@Composable () -> Unit)? = if (enabled && !rail) {
            remember(current, onNavigate) {
                @Composable {
                    ShortNavigationBar {
                        NavigationDestination.entries.forEach { destination ->
                            val label = stringResource(destination.labelRes)
                            val showLabel = destination != NavigationDestination.SETTINGS
                            ShortNavigationBarItem(
                                selected = destination.screen == current,
                                onClick = { onNavigate(destination.screen) },
                                icon = {
                                    Icon(imageVector = destination.icon, contentDescription = label)
                                },
                                label = if (showLabel) {
                                    {
                                        Text(
                                            text = label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                } else null,
                            )
                        }
                    }
                }
            }
        } else {
            null
        }

        if (rail && enabled) {
            val windowHeight = maxHeight
            Row(modifier = Modifier.fillMaxSize()) {
                WideNavigationRail(modifier = Modifier.fillMaxHeight()) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = windowHeight)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        NavigationDestination.entries.forEach { destination ->
                            val label = stringResource(destination.labelRes)
                            val showLabel = destination != NavigationDestination.SETTINGS
                            WideNavigationRailItem(
                                selected = destination.screen == current,
                                onClick = { onNavigate(destination.screen) },
                                icon = {
                                    Icon(imageVector = destination.icon, contentDescription = label)
                                },
                                label = if (showLabel) {
                                    {
                                        Text(
                                            text = label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                } else null,
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
            CompositionLocalProvider(LocalNavigationBar provides bar) { content() }
        }
    }
}
