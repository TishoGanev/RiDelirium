package com.bgdeveloper.ridelirium.ui

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.bgdeveloper.ridelirium.R
import com.bgdeveloper.ridelirium.friends.Sport

/** The sport pictogram from iOS, tinted [tint]: used on map tags, the crew pill and the Settings picker. */
@Composable
fun SportGlyph(sport: Sport, tint: Color, modifier: Modifier = Modifier) {
    Icon(painterResource(sport.glyph), contentDescription = null, tint = tint, modifier = modifier)
}

@get:DrawableRes
val Sport.glyph: Int
    get() = when (this) {
        Sport.SKI -> R.drawable.ic_sport_ski
        Sport.BOARD -> R.drawable.ic_sport_board
        Sport.BIKE -> R.drawable.ic_sport_bike
        Sport.WALK -> R.drawable.ic_sport_walk
    }
