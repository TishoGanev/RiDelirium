package com.bgdeveloper.ridelirium.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bgdeveloper.ridelirium.friends.Sport
import com.bgdeveloper.ridelirium.ui.settings.SettingsViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bgdeveloper.ridelirium.R
import com.bgdeveloper.ridelirium.ui.settings.AboutScreen
import com.bgdeveloper.ridelirium.ui.settings.SettingsScreen
import com.bgdeveloper.ridelirium.ui.stats.DailyStatsScreen
import com.bgdeveloper.ridelirium.ui.stats.StatsScreen
import com.bgdeveloper.ridelirium.ui.theme.Sb
import com.bgdeveloper.ridelirium.ui.theme.sb
import com.bgdeveloper.ridelirium.ui.track.TrackScreen

private enum class AppTab { Track, Stats, Settings }

private const val STATS_ROUTE = "stats"
private const val DAY_ROUTE = "day"
private const val SETTINGS_ROUTE = "settings"
private const val ABOUT_ROUTE = "about"

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableStateOf(AppTab.Track) }
    val nav = rememberNavController()
    val settingsNav = rememberNavController()

    BackHandler(enabled = tab != AppTab.Track) {
        if (tab == AppTab.Settings && settingsNav.previousBackStackEntry != null) settingsNav.popBackStack() else tab = AppTab.Track
    }

    val landscape = isLandscape()
    val landscapeNav = LandscapeNav(
        trackActive = tab == AppTab.Track,
        statsActive = tab == AppTab.Stats,
        onTrack = { tab = AppTab.Track },
        onStats = {
            if (tab == AppTab.Stats) nav.popBackStack(STATS_ROUTE, inclusive = false)
            tab = AppTab.Stats
        },
    )

    CompositionLocalProvider(LocalLandscapeNav provides landscapeNav) {
    Box(Modifier.fillMaxSize().background(Color.White)) {
        // Track stays composed so the map camera and the live ride keep their state.
        TrackScreen()

        if (tab == AppTab.Stats) {
            // An empty pointerInput stops touches reaching the map underneath.
            Box(Modifier.fillMaxSize().background(Color.White).pointerInput(Unit) {}) {
                StatsNavHost(nav)
            }
        }

        if (tab == AppTab.Settings) {
            Box(Modifier.fillMaxSize().background(Color.White).pointerInput(Unit) {}) {
                SettingsNavHost(settingsNav)
            }
        }

        if (landscape) {
            // Sideways the tabs live in each screen's bottom bar (Landscape.kt); settings sits top-right.
            val sport by hiltViewModel<SettingsViewModel>().sport.collectAsState()
            val pad = landscapePadding()
            Box(Modifier.align(Alignment.TopEnd).padding(top = pad.calculateTopPadding(), end = pad.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr))) {
                SettingsTabButton(sport = sport, active = tab == AppTab.Settings, onClick = { tab = AppTab.Settings }, size = LandscapeMetrics.GearSize)
            }
        } else {
        Row(
            Modifier.align(Alignment.BottomCenter)
                .padding(horizontal = 14.dp)
                .padding(bottom = 30.dp + navigationBarBottom()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FloatingTabBar(
                selected = tab,
                onTrack = { tab = AppTab.Track },
                onStats = {
                    if (tab == AppTab.Stats) nav.popBackStack(STATS_ROUTE, inclusive = false)
                    tab = AppTab.Stats
                },
                modifier = Modifier.weight(1f),
            )
            val sport by hiltViewModel<SettingsViewModel>().sport.collectAsState()
            SettingsTabButton(sport = sport, active = tab == AppTab.Settings, onClick = { tab = AppTab.Settings })
        }
        }
    }
    }
}

@Composable
private fun StatsNavHost(nav: NavHostController) {
    NavHost(nav, startDestination = STATS_ROUTE) {
        composable(STATS_ROUTE) {
            StatsScreen(onOpenDay = { nav.navigate("$DAY_ROUTE/$it") })
        }
        composable("$DAY_ROUTE/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) {
            DailyStatsScreen(onBack = { nav.popBackStack() })
        }
    }
}

@Composable
private fun SettingsNavHost(nav: NavHostController) {
    NavHost(nav, startDestination = SETTINGS_ROUTE) {
        composable(SETTINGS_ROUTE) { SettingsScreen(onOpenAbout = { nav.navigate(ABOUT_ROUTE) }) }
        composable(ABOUT_ROUTE) { AboutScreen(onBack = { nav.popBackStack() }) }
    }
}

@Composable
private fun FloatingTabBar(selected: AppTab, onTrack: () -> Unit, onStats: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(62.dp)
            .shadow(15.dp, shape, ambientColor = Color.Black.copy(0.3f), spotColor = Color.Black.copy(0.3f))
            .clip(shape)
            .background(Sb.Red)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabItem("TRACK", R.drawable.ic_record, selected == AppTab.Track, onTrack, Modifier.weight(1f))
        TabItem("STATS", R.drawable.ic_chart, selected == AppTab.Stats, onStats, Modifier.weight(1f))
    }
}

/** The settings button: a gear with the rider's own sport inside. */
private val Sport.settingsIcon: Int
    get() = when (this) {
        Sport.SKI -> R.drawable.ic_settings_ski
        Sport.BOARD -> R.drawable.ic_settings_board
        Sport.BIKE -> R.drawable.ic_settings_bike
        Sport.WALK -> R.drawable.ic_settings_walk
    }

/** Settings sits beside the tab bar as its own round button. */
@Composable
private fun SettingsTabButton(sport: Sport, active: Boolean, onClick: () -> Unit, size: Dp = 62.dp) {
    Box(
        Modifier
            .size(size)
            .shadow(15.dp, CircleShape, ambientColor = Color.Black.copy(0.3f), spotColor = Color.Black.copy(0.3f))
            .clip(CircleShape)
            .background(Sb.Red)
            .background(if (active) Color.Black.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Settings" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(sport.settingsIcon), contentDescription = null, tint = Color.White.copy(alpha = if (active) 1f else 0.85f), modifier = Modifier.size(size * 40 / 62))
    }
}

@Composable
private fun TabItem(title: String, icon: Int, active: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(20.dp)
    val tint = Color.White.copy(alpha = if (active) 1f else 0.78f)
    Row(
        modifier
            .height(46.dp)
            .clip(shape)
            .background(if (active) Color.Black.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(title, style = sb(16), color = tint)
    }
}
