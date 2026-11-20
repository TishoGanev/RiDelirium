package com.bgdeveloper.ridelirium.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bgdeveloper.ridelirium.ui.theme.Sb
import com.bgdeveloper.ridelirium.ui.theme.sb

/*
 * Landscape layout (design "Landscape C" on the canvas): the map or content fills the screen and one low white
 * bar runs along the bottom with TRACK / STATS on the left, the screen's numbers in the middle and its buttons on
 * the right. Portrait layouts are untouched; screens switch on the configuration's orientation.
 */

@Composable
fun isLandscape(): Boolean = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/** What the landscape bar needs to switch tabs. AppRoot provides it. */
data class LandscapeNav(
    val trackActive: Boolean = true,
    val statsActive: Boolean = false,
    val onTrack: () -> Unit = {},
    val onStats: () -> Unit = {},
)

val LocalLandscapeNav = staticCompositionLocalOf { LandscapeNav() }

object LandscapeMetrics {
    val BarHeight = 80.dp
    /** Room the round settings button takes in the top-right corner. */
    val GearSize = 46.dp
}

/** Safe-area padding for landscape screens (status bar, camera cutout, gesture bar), plus a small margin. */
@Composable
fun landscapePadding(extra: Dp = 8.dp): PaddingValues {
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val dir = LocalLayoutDirection.current
    return PaddingValues(
        start = insets.calculateStartPadding(dir) + extra,
        end = insets.calculateEndPadding(dir) + extra,
        top = insets.calculateTopPadding() + 12.dp,
        bottom = maxOf(insets.calculateBottomPadding(), 8.dp) + 4.dp,
    )
}

/** TRACK / STATS on the left, then [middle], then [trailing]. */
@Composable
fun LandscapeBar(
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    middle: @Composable RowScope.() -> Unit,
) {
    val nav = LocalLandscapeNav.current
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier.fillMaxWidth().height(LandscapeMetrics.BarHeight)
            .shadow(20.dp, shape, ambientColor = Color.Black.copy(0.35f), spotColor = Color.Black.copy(0.35f))
            .clip(shape).background(Color.White)
            .padding(start = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LandscapeTab("TRACK", nav.trackActive, nav.onTrack)
            LandscapeTab("STATS", nav.statsActive, nav.onStats)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 14.dp).background(Sb.Divider))
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            content = middle,
        )
        trailing()
    }
}

@Composable
private fun LandscapeTab(title: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(30.dp).widthIn(min = 64.dp).clip(CircleShape)
            .background(if (active) Sb.Red else Sb.LightGrey)
            .semantics { selected = active }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(title, style = sb(12), color = if (active) Color.White else Sb.Charcoal) }
}

/** Small label over a number, as used in the bar. */
@Composable
fun LandscapeStat(label: String, value: String, size: Int = 28, labelColor: Color = Sb.Secondary, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = sb(11, tracking = 0.04f), color = labelColor, maxLines = 1)
        Text(
            value, style = sb(size, FontWeight.ExtraBold, lineHeight = 1.05f, tabular = true), color = Sb.Charcoal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
