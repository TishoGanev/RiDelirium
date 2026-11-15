package com.bgdeveloper.ridelirium.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.bgdeveloper.ridelirium.friends.Sport

/** What a rider rides, drawn small in [tint]: used on map tags and the crew pill. */
@Composable
fun SportGlyph(sport: Sport, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        when (sport) {
            // A snowboard seen from above: a slim rounded board, tilted, with two bindings.
            Sport.BOARD -> rotate(-45f) {
                drawRoundRect(tint, Offset(w * 0.36f, h * 0.04f), Size(w * 0.28f, h * 0.92f), CornerRadius(w * 0.14f))
                val hole = Color.Black.copy(alpha = 0.35f)
                drawRect(hole, Offset(w * 0.36f, h * 0.28f), Size(w * 0.28f, h * 0.1f), blendMode = BlendMode.DstOut)
                drawRect(hole, Offset(w * 0.36f, h * 0.62f), Size(w * 0.28f, h * 0.1f), blendMode = BlendMode.DstOut)
            }
            // A pair of skis side by side, tilted.
            Sport.SKI -> rotate(-45f) {
                drawRoundRect(tint, Offset(w * 0.26f, h * 0.04f), Size(w * 0.16f, h * 0.92f), CornerRadius(w * 0.08f))
                drawRoundRect(tint, Offset(w * 0.58f, h * 0.04f), Size(w * 0.16f, h * 0.92f), CornerRadius(w * 0.08f))
                val hole = Color.Black.copy(alpha = 0.35f)
                drawRect(hole, Offset(w * 0.26f, h * 0.46f), Size(w * 0.16f, h * 0.08f), blendMode = BlendMode.DstOut)
                drawRect(hole, Offset(w * 0.58f, h * 0.46f), Size(w * 0.16f, h * 0.08f), blendMode = BlendMode.DstOut)
            }
            // A bike from the side: two wheels, a frame, handlebars and a saddle.
            Sport.BIKE -> {
                val line = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
                val r = w * 0.2f
                val back = Offset(w * 0.2f, h * 0.68f)
                val front = Offset(w * 0.8f, h * 0.68f)
                val seat = Offset(w * 0.42f, h * 0.32f)
                val bar = Offset(w * 0.7f, h * 0.3f)
                val crank = Offset(w * 0.48f, h * 0.68f)
                drawCircle(tint, r, back, style = line)
                drawCircle(tint, r, front, style = line)
                drawLine(tint, back, seat, line.width, StrokeCap.Round)
                drawLine(tint, seat, crank, line.width, StrokeCap.Round)
                drawLine(tint, crank, back, line.width, StrokeCap.Round)
                drawLine(tint, seat, bar, line.width, StrokeCap.Round)
                drawLine(tint, bar, front, line.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.34f, h * 0.28f), Offset(w * 0.5f, h * 0.28f), line.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.64f, h * 0.24f), Offset(w * 0.78f, h * 0.24f), line.width, StrokeCap.Round)
            }
        }
    }
}
