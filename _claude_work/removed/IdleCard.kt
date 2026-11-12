package com.bgdeveloper.snowman.ui.track

import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bgdeveloper.snowman.data.Weather
import com.bgdeveloper.snowman.domain.Units
import com.bgdeveloper.snowman.ui.theme.Sb
import com.bgdeveloper.snowman.ui.theme.sb
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val ReadyGreen = Color(0xFF1F7A3A)
private val ReadyDot = Color(0xFF30D158)
private val SnowBlue = Color(0xFF3D7FD9)
private val TileGrey = Color(0xFFF4F4F4)
private val RowBorder = Color(0xFFEDEDED)
private val CloudGrey = Color(0xFF9AA3AD)

/** Before a ride: where you are and the weather, the conditions, and your last ride with the season so far. */
@Composable
internal fun IdleHeader(ui: TrackUiState, friendsOut: Int?, onOpenRide: (Int) -> Unit) {
    val info = ui.idle
    val units = ui.units
    val location = ui.tracking.current
    val altitude = location?.takeIf { it.hasAltitude() }?.let { altitudeLabel(units, it.altitude) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val ready = location != null
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (ready) ReadyDot else Sb.Still))
                    Text(
                        if (ready) "READY TO RIDE" else "FINDING YOU…",
                        style = sb(12, tracking = 0.08f), color = if (ready) ReadyGreen else Sb.Secondary, maxLines = 1,
                    )
                }
                // Without a place name, the altitude is the headline.
                val headline = info.placeName?.uppercase(LocalConfiguration.current.locales[0]) ?: altitude ?: "--"
                Text(
                    headline, style = sb(30, FontWeight.ExtraBold, lineHeight = 1f), color = Sb.Charcoal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val details = listOfNotNull(
                    altitude?.takeIf { info.placeName != null },
                    friendsOut?.let { if (it == 0) "NO FRIENDS OUT" else if (it == 1) "1 FRIEND OUT" else "$it FRIENDS OUT" },
                )
                if (details.isNotEmpty()) {
                    Text(details.joinToString(" · "), style = sb(13, tracking = 0.03f), color = Sb.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            info.weather?.let { WeatherNow(it, units) }
        }
        info.weather?.let { ConditionTiles(it, units) }
        info.lastRide?.let { ride ->
            LastRideRow(ride, info.lastRidePlace, info.season, units, onClick = { onOpenRide(ride.session) })
        }
    }
}

@Composable
private fun WeatherNow(weather: Weather, units: Units) {
    Row(
        Modifier.semantics { contentDescription = "${temperature(units, weather.temperatureC)}, ${weather.label.lowercase()}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SkyIcon(weather.sky, Modifier.size(30.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(temperature(units, weather.temperatureC), style = sb(30, FontWeight.ExtraBold, lineHeight = 1f, tabular = true), color = Sb.Charcoal)
            if (weather.label.isNotEmpty()) Text(weather.label, style = sb(11, tracking = 0.04f), color = Sb.Secondary, maxLines = 1)
        }
    }
}

@Composable
private fun ConditionTiles(weather: Weather, units: Units) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val gust = units.speedValue(weather.gustKmh / 3.6).roundToInt()
        Tile("WIND", windSpeed(units, weather.windKmh), "${compass(weather.windFromDeg)} · GUSTS $gust", Sb.Secondary, Modifier.weight(1f))
        val fresh = weather.snowfall24hCm >= 0.5
        Tile(
            "SNOW", weather.snowDepthM?.let { snowDepth(units, it * 100) } ?: "--",
            if (fresh) "+${snowDepth(units, weather.snowfall24hCm)} 24H" else "NO NEW SNOW",
            if (fresh) SnowBlue else Sb.Secondary, Modifier.weight(1f),
        )
        Tile(
            "VISIBILITY", weather.visibilityM?.let { visibility(units, it) } ?: "--",
            "FEELS ${temperature(units, weather.apparentC)}", Sb.Secondary, Modifier.weight(1f),
        )
    }
}

@Composable
private fun Tile(label: String, value: String, sub: String, subColor: Color, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(TileGrey).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(label, style = sb(11, tracking = 0.06f), color = Sb.Secondary, maxLines = 1)
        Text(value, style = sb(20, FontWeight.ExtraBold, lineHeight = 1f, tabular = true), color = Sb.Charcoal, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, style = sb(11), color = subColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LastRideRow(ride: LastRide, place: String?, season: SeasonSoFar?, units: Units, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).border(1.5.dp, RowBorder, shape)
            .semantics { contentDescription = "Open last ride" }
            .clickable(onClick = onClick).padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        RouteThumbnail(ride.track, Modifier.size(52.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("LAST RIDE · ${dayLabel(ride.startMillis)}", style = sb(11, tracking = 0.06f), color = Sb.Secondary, maxLines = 1)
            Text(
                "${(place ?: "Session ${ride.session}").uppercase(LocalConfiguration.current.locales[0])} · ${units.distance(ride.distanceMeters).uppercase(Locale.US)}",
                style = sb(17, FontWeight.ExtraBold, lineHeight = 1f), color = Sb.Charcoal, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${units.speed(ride.maxSpeed)} MAX · ${clock(ride.startMillis)}–${clock(ride.endMillis)}",
                style = sb(12), color = Sb.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        season?.let {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (it.days == 1) "1 DAY" else "${it.days} DAYS", style = sb(13, FontWeight.ExtraBold), color = Sb.Red, maxLines = 1)
                Text(
                    "%.0f %s".format(Locale.US, units.distanceValue(it.distanceMeters), units.distanceUnit.uppercase(Locale.US)),
                    style = sb(10, tabular = true), color = Sb.Secondary, maxLines = 1,
                )
                Text(it.label, style = sb(10), color = Sb.Secondary, maxLines = 1)
            }
        }
    }
}

/** The ride's route, scaled to fit, on the dark map colour: white dot at the start, red at the end. */
@Composable
private fun RouteThumbnail(track: List<Pair<Double, Double>>, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(Sb.MapBackground)) {
        if (track.size < 2) return@Canvas
        val minLat = track.minOf { it.first }
        val maxLat = track.maxOf { it.first }
        val minLon = track.minOf { it.second }
        val maxLon = track.maxOf { it.second }
        // Longitude degrees shrink with latitude, so scale x to keep the route's shape.
        val lonScale = kotlin.math.cos(Math.toRadians((minLat + maxLat) / 2))
        val spanX = maxOf((maxLon - minLon) * lonScale, 1e-6)
        val spanY = maxOf(maxLat - minLat, 1e-6)
        val inset = size.minDimension * 0.16f
        val scale = minOf((size.width - 2 * inset) / spanX, (size.height - 2 * inset) / spanY).toFloat()
        val offsetX = (size.width - spanX.toFloat() * scale) / 2
        val offsetY = (size.height - spanY.toFloat() * scale) / 2
        fun point(p: Pair<Double, Double>) = Offset(
            offsetX + ((p.second - minLon) * lonScale).toFloat() * scale,
            offsetY + (maxLat - p.first).toFloat() * scale,
        )
        val path = Path().apply {
            val first = point(track.first())
            moveTo(first.x, first.y)
            track.drop(1).forEach { val o = point(it); lineTo(o.x, o.y) }
        }
        drawPath(path, Sb.Red, style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(Color.White, 2.6.dp.toPx(), point(track.first()))
        drawCircle(Color.White, 3.3.dp.toPx(), point(track.last()))
        drawCircle(Sb.Red, 2.6.dp.toPx(), point(track.last()))
    }
}

/** GPS state under the START button, so the button keeps one short label. */
@Composable
internal fun GpsLine(location: Location?, units: Units, hasCrew: Boolean) {
    val (dot, text) = when {
        location == null || !location.hasAccuracy() -> Sb.Still to "SEARCHING GPS…"
        location.accuracy <= 20f -> ReadyDot to "GPS ±${units.altitudeValue(location.accuracy.toDouble()).roundToInt()} ${units.altitudeUnit.uppercase(Locale.US)}"
        else -> Sb.Sun to "WEAK GPS ±${units.altitudeValue(location.accuracy.toDouble()).roundToInt()} ${units.altitudeUnit.uppercase(Locale.US)}"
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Text(
            if (hasCrew) "$text · CREW SEES YOU WHEN YOU START" else text,
            style = sb(12, tracking = 0.08f), color = Sb.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SkyIcon(sky: Weather.Sky, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val stroke = 2.4.dp.toPx()
        fun cloud(color: Color, dy: Float = 0f) {
            drawCircle(color, w * 0.18f, Offset(w * 0.32f, w * 0.55f + dy))
            drawCircle(color, w * 0.24f, Offset(w * 0.52f, w * 0.44f + dy))
            drawCircle(color, w * 0.17f, Offset(w * 0.72f, w * 0.56f + dy))
            drawRect(color, Offset(w * 0.32f, w * 0.52f + dy), androidx.compose.ui.geometry.Size(w * 0.40f, w * 0.21f))
        }
        when (sky) {
            Weather.Sky.SNOW -> {
                // The design's snowflake: three crossing strokes with a chevron at each vertical end.
                val c = SnowBlue
                val s = w / 30f
                drawLine(c, Offset(15 * s, 3 * s), Offset(15 * s, 27 * s), stroke, StrokeCap.Round)
                drawLine(c, Offset(4.6f * s, 9 * s), Offset(25.4f * s, 21 * s), stroke, StrokeCap.Round)
                drawLine(c, Offset(4.6f * s, 21 * s), Offset(25.4f * s, 9 * s), stroke, StrokeCap.Round)
                val chevrons = Path().apply {
                    moveTo(11 * s, 5 * s); lineTo(15 * s, 8.5f * s); lineTo(19 * s, 5 * s)
                    moveTo(11 * s, 25 * s); lineTo(15 * s, 21.5f * s); lineTo(19 * s, 25 * s)
                }
                drawPath(chevrons, c, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Weather.Sky.CLEAR -> {
                val center = Offset(w / 2, w / 2)
                drawCircle(Sb.Sun, w * 0.2f, center)
                repeat(8) { i ->
                    val a = Math.toRadians(i * 45.0)
                    val dx = kotlin.math.cos(a).toFloat()
                    val dy = kotlin.math.sin(a).toFloat()
                    drawLine(Sb.Sun, center + Offset(dx, dy) * (w * 0.32f), center + Offset(dx, dy) * (w * 0.45f), stroke, StrokeCap.Round)
                }
            }
            Weather.Sky.RAIN, Weather.Sky.STORM -> {
                cloud(CloudGrey, -w * 0.12f)
                listOf(0.36f, 0.52f, 0.68f).forEach { x ->
                    drawLine(SnowBlue, Offset(w * x, w * 0.72f), Offset(w * (x - 0.05f), w * 0.9f), stroke, StrokeCap.Round)
                }
            }
            Weather.Sky.FOG -> listOf(0.3f, 0.5f, 0.7f).forEach { y ->
                drawLine(CloudGrey, Offset(w * 0.12f, w * y), Offset(w * 0.88f, w * y), stroke, StrokeCap.Round)
            }
            Weather.Sky.CLOUD -> cloud(CloudGrey)
        }
    }
}

// region Formatting

private fun temperature(units: Units, celsius: Double): String {
    val value = (if (units.metric) celsius else celsius * 9 / 5 + 32).roundToInt()
    // A real minus sign, like the design; "-0°" reads oddly.
    return if (value < 0) "−${-value}°" else "$value°"
}

private fun windSpeed(units: Units, kmh: Double): String = "${units.speedValue(kmh / 3.6).roundToInt()} ${units.speedUnit}"

private fun snowDepth(units: Units, cm: Double): String =
    if (units.metric) "${cm.roundToInt()} CM" else "${(cm / 2.54).roundToInt()} IN"

private fun visibility(units: Units, meters: Double): String {
    val value = if (units.metric) meters / 1000 else meters / 1609.34
    val unit = if (units.metric) "KM" else "MI"
    return when {
        value >= 10 -> "10+ $unit"
        value >= 1 -> "${value.roundToInt()} $unit"
        units.metric -> "${(meters / 10).roundToInt() * 10} M"
        else -> "%.1f MI".format(Locale.US, value)
    }
}

private fun altitudeLabel(units: Units, meters: Double): String =
    "%,.0f %s".format(Locale.US, units.altitudeValue(meters), units.altitudeUnit.uppercase(Locale.US))

private fun compass(degrees: Double): String {
    val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return names[(((degrees % 360) + 360) % 360 / 45 + 0.5).toInt() % 8]
}

private fun dayLabel(millis: Long): String {
    val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> "TODAY"
        today.minusDays(1) -> "YESTERDAY"
        else -> date.format(DateTimeFormatter.ofPattern("d MMM", Locale.US)).uppercase(Locale.US)
    }
}

private fun clock(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))

// endregion
