package com.bgdeveloper.ridelirium.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import com.bgdeveloper.ridelirium.domain.DayMetrics
import com.bgdeveloper.ridelirium.domain.PeriodInsights
import com.bgdeveloper.ridelirium.domain.StatsRange
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bgdeveloper.ridelirium.domain.SessionSummary
import com.bgdeveloper.ridelirium.ui.Chevron
import com.bgdeveloper.ridelirium.ui.HeroNumber
import com.bgdeveloper.ridelirium.ui.statusBarTop
import com.bgdeveloper.ridelirium.ui.LandscapeBar
import com.bgdeveloper.ridelirium.ui.LandscapeMetrics
import com.bgdeveloper.ridelirium.ui.LandscapeStat
import com.bgdeveloper.ridelirium.ui.isLandscape
import com.bgdeveloper.ridelirium.ui.landscapePadding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import com.bgdeveloper.ridelirium.ui.theme.Sb
import com.bgdeveloper.ridelirium.ui.theme.sb
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun StatsScreen(onOpenDay: (Int) -> Unit, viewModel: StatsViewModel = hiltViewModel()) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<SessionSummary?>(null) }
    var showProgress by remember { mutableStateOf(false) }

    if (isLandscape()) {
        LandscapeStats(ui, viewModel, onOpenDay, onDelete = { pendingDelete = it }, onProgress = { showProgress = true })
    } else {
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Header(ui)
        RangeBar(ui, onRange = viewModel::setRange, onProgress = { showProgress = true })
        if (ui.sessions.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 110.dp), contentAlignment = Alignment.Center) {
                Text(if (ui.hasAnyRides) "NO RIDES IN THIS PERIOD" else "NO RIDES YET", style = sb(18, tracking = 0.04f), color = Sb.Secondary)
            }
        } else {
            RideList(ui, viewModel, onOpenDay, onDelete = { pendingDelete = it })
        }
    }
    }

    if (showProgress) {
        val bullets = remember(ui.sessions, ui.units) { PeriodInsights.bullets(ui.sessions.map(DayMetrics::of), ui.units) }
        BulletSheet(title = "PROGRESS · ${ui.range.title}", bullets = bullets, onDismiss = { showProgress = false })
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this ride?") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(session); pendingDelete = null }) { Text("Delete Ride", color = Sb.Red) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

/** Sideways: ride cards you swipe through, the period's totals in the bottom bar (Landscape.kt). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LandscapeStats(
    ui: StatsUiState,
    viewModel: StatsViewModel,
    onOpen: (Int) -> Unit,
    onDelete: (SessionSummary) -> Unit,
    onProgress: () -> Unit,
) {
    val pad = landscapePadding()
    val dir = LocalLayoutDirection.current
    val start = pad.calculateStartPadding(dir)
    val end = pad.calculateEndPadding(dir)
    Column(
        Modifier.fillMaxSize().background(Color(0xFFF4F2F0))
            .padding(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = start, end = end + LandscapeMetrics.GearSize + 10.dp).height(LandscapeMetrics.GearSize),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("YOUR RIDES", style = sb(22, FontWeight.ExtraBold), color = Sb.Charcoal, modifier = Modifier.padding(end = 6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatsRange.entries.forEach { option ->
                    val selected = option == ui.range
                    Box(
                        Modifier.height(30.dp).clip(CircleShape)
                            .background(if (selected) Sb.Red else Color.White)
                            .clickable { viewModel.setRange(option) }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(option.title, style = sb(12, tracking = 0.04f), color = if (selected) Color.White else Sb.GreyButtonText) }
                }
            }
        }
        if (ui.sessions.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (ui.hasAnyRides) "NO RIDES IN THIS PERIOD" else "NO RIDES YET", style = sb(18, tracking = 0.04f), color = Sb.Secondary)
            }
        } else {
            LazyRow(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = start, end = end),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(ui.sessions, key = { it.session }) { session ->
                    LaunchedEffect(session.placeKey) { viewModel.resolvePlace(session) }
                    RideCard(
                        session, ui,
                        Modifier.combinedClickable(onClick = { onOpen(session.session) }, onLongClick = { onDelete(session) }),
                    )
                }
            }
        }
        val header = ui.header
        LandscapeBar(
            Modifier.padding(start = start, end = end),
            trailing = {
                if (ui.sessions.size >= 2) {
                    Box(
                        Modifier.height(60.dp).clip(RoundedCornerShape(18.dp)).background(Sb.LightGrey)
                            .clickable(onClick = onProgress).padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("INSIGHTS", style = sb(17), color = Sb.Red) }
                }
            },
        ) {
            LandscapeStat(header.title, "${header.days} ${if (header.days == 1) "DAY" else "DAYS"}", labelColor = Sb.Red)
            LandscapeStat("RIDDEN", ui.units.distance(header.distanceMeters))
            LandscapeStat("TOP SPEED", ui.units.speed(header.topSpeed))
        }
    }
}

@Composable
private fun RideCard(session: SessionSummary, ui: StatsUiState, modifier: Modifier) {
    val date = session.startDate
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.width(176.dp).height(210.dp).clip(shape).background(Color.White)
            .border(1.dp, Color(0xFFE3E3E3), shape).then(modifier),
    ) {
        RouteSketch(session, Modifier.fillMaxWidth().height(92.dp).background(Sb.MapBackground))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("%02d".format(date.dayOfMonth), style = sb(26, FontWeight.ExtraBold, tabular = true), color = Sb.Charcoal)
                Text(
                    date.month.getDisplayName(TextStyle.SHORT, androidx.compose.ui.text.intl.Locale.current.platformLocale).uppercase(),
                    style = sb(12), color = Sb.Red, modifier = Modifier.padding(bottom = 3.dp),
                )
                Text("${date.year}", style = sb(12, FontWeight.SemiBold), color = Sb.Secondary, modifier = Modifier.padding(bottom = 3.dp))
            }
            Text(ui.placeName(session), style = sb(16), color = Sb.Charcoal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${ui.units.distance(session.distance)} · ${ui.units.speed(session.maxSpeed)}",
                style = sb(13, FontWeight.SemiBold, tabular = true), color = Sb.Secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The ride's route drawn flat, scaled to fit, for the landscape ride cards. */
@Composable
private fun RouteSketch(session: SessionSummary, modifier: Modifier) {
    val points = remember(session.placeKey, session.points.size) {
        val all = session.points
        val step = maxOf(1, all.size / 400)
        all.filterIndexed { i, _ -> i % step == 0 || i == all.size - 1 }.map { it.lat to it.lon }
    }
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val minLat = points.minOf { it.first }; val maxLat = points.maxOf { it.first }
        val minLon = points.minOf { it.second }; val maxLon = points.maxOf { it.second }
        val inset = 14.dp.toPx()
        val w = size.width - inset * 2; val h = size.height - inset * 2
        // Longitude degrees shrink towards the poles; keep the shape's proportions.
        val lonScale = kotlin.math.cos((minLat + maxLat) / 2 * Math.PI / 180)
        val spanX = maxOf((maxLon - minLon) * lonScale, 1e-9); val spanY = maxOf(maxLat - minLat, 1e-9)
        val scale = minOf(w / spanX, h / spanY)
        val ox = inset + (w - spanX * scale) / 2; val oy = inset + (h - spanY * scale) / 2
        val path = Path()
        points.forEachIndexed { i, (lat, lon) ->
            val x = (ox + (lon - minLon) * lonScale * scale).toFloat()
            val y = (oy + (maxLat - lat) * scale).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Sb.Red, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun Header(ui: StatsUiState) {
    val header = ui.header
    Column(
        Modifier.fillMaxWidth().background(Sb.Red)
            .padding(top = statusBarTop() + 16.dp, start = 20.dp, end = 20.dp, bottom = 22.dp),
    ) {
        Text(
            "${header.title} · ${header.days} ${if (header.days == 1) "DAY" else "DAYS"}",
            style = sb(18, tracking = 0.04f), color = Color.White,
        )
        HeroNumber(
            value = "%.1f".format(java.util.Locale.US, ui.units.distanceValue(header.distanceMeters)),
            captionLines = listOf("${ui.units.distanceUnit.uppercase()} RIDDEN", "${ui.units.speed(header.topSpeed)} TOP SPEED"),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Time filter chips, and the progress summary for the rides they leave in view. */
@Composable
private fun RangeBar(ui: StatsUiState, onRange: (StatsRange) -> Unit, onProgress: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatsRange.entries.forEach { option ->
                val selected = option == ui.range
                Box(
                    Modifier.height(36.dp).clip(CircleShape)
                        .background(if (selected) Sb.Red else Sb.LightGrey)
                        .clickable { onRange(option) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(option.title, style = sb(14, tracking = 0.04f), color = if (selected) Color.White else Sb.GreyButtonText)
                }
            }
        }
        // One ride has nothing to compare against.
        if (ui.sessions.size >= 2) {
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Sb.LightGrey)
                    .clickable(onClick = onProgress)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("PROGRESS INSIGHTS", style = sb(16, tracking = 0.04f), color = Sb.Red, modifier = Modifier.weight(1f))
                Text("${ui.sessions.size} RIDES", style = sb(13), color = Sb.Red.copy(alpha = 0.7f))
                Chevron(color = Sb.Red, size = 10.dp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RideList(ui: StatsUiState, viewModel: StatsViewModel, onOpen: (Int) -> Unit, onDelete: (SessionSummary) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
        items(ui.sessions, key = { it.session }) { session ->
            LaunchedEffect(session.placeKey) { viewModel.resolvePlace(session) }
            val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = {
                if (it == SwipeToDismissBoxValue.EndToStart) onDelete(session)
                false // the row stays until the dialog confirms
            })
            SwipeToDismissBox(
                state = dismiss,
                enableDismissFromStartToEnd = false,
                backgroundContent = {
                    Box(Modifier.fillMaxSize().background(Sb.Red).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                        Text("DELETE", style = sb(16), color = Color.White)
                    }
                },
            ) {
                RideRow(session, ui, onClick = { onOpen(session.session) })
            }
        }
    }
}

@Composable
private fun RideRow(session: SessionSummary, ui: StatsUiState, onClick: () -> Unit) {
    val date = session.startDate
    Row(
        Modifier.fillMaxWidth().height(78.dp).background(Color.White).clickable(onClick = onClick)
            .padding(start = 18.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "%02d".format(date.dayOfMonth), style = sb(40, tabular = true), color = Sb.Charcoal,
            textAlign = TextAlign.End, modifier = Modifier.width(44.dp),
        )
        Column(Modifier.width(40.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(date.month.getDisplayName(TextStyle.SHORT, androidx.compose.ui.text.intl.Locale.current.platformLocale).uppercase(), style = sb(14), color = Sb.Red)
            Text("${date.year}", style = sb(13, FontWeight.SemiBold), color = Sb.Secondary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(ui.placeName(session), style = sb(18, lineHeight = 1.1f), color = Sb.Charcoal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${ui.units.distance(session.distance)} · ${ui.units.speed(session.maxSpeed)}",
                style = sb(14, FontWeight.SemiBold, lineHeight = 1.2f, tabular = true), color = Sb.Secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Chevron(Modifier.padding(end = 4.dp))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Sb.Divider))
}
