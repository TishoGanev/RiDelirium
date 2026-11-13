package com.bgdeveloper.ridelirium.ui.stats

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.SideEffect
import com.bgdeveloper.ridelirium.domain.Activity
import com.bgdeveloper.ridelirium.domain.RideReplay
import com.bgdeveloper.ridelirium.domain.Units
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bgdeveloper.ridelirium.R
import com.bgdeveloper.ridelirium.share.ShareCardFormat
import com.bgdeveloper.ridelirium.share.ShareFiles
import com.bgdeveloper.ridelirium.share.StoryTarget
import com.bgdeveloper.ridelirium.ui.Chevron
import com.bgdeveloper.ridelirium.ui.HeroNumber
import com.bgdeveloper.ridelirium.ui.statusBarTop
import com.bgdeveloper.ridelirium.ui.theme.Sb
import com.bgdeveloper.ridelirium.ui.theme.sb
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap as GmsMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import androidx.compose.ui.graphics.toArgb
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerComposable
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun DailyStatsScreen(onBack: () -> Unit, viewModel: DailyStatsViewModel = hiltViewModel()) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var map by remember { mutableStateOf<GmsMap?>(null) }
    val padding = with(LocalDensity.current) { 48.dp.roundToPx() }
    var cardRequest by remember { mutableStateOf<ShareRequest.Card?>(null) }
    var showSegments by remember { mutableStateOf(false) }
    var showInsights by remember { mutableStateOf(false) }

    LaunchedEffect(ui.shareRequest) {
        when (val request = ui.shareRequest) {
            is ShareRequest.Card -> cardRequest = request
            is ShareRequest.Csv -> ShareFiles.chooser(context, request.file, "text/csv")
            null -> return@LaunchedEffect
        }
        viewModel.consumeShare()
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Header(ui, onBack, onShowSegments = { showSegments = true }, onShowInsights = { showInsights = true })
        RouteMap(ui, onMap = { map = it }, modifier = Modifier.weight(1f))
        if (ui.replay.active) {
            ReplayControls(ui, viewModel)
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 106.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ActionButton("REPLAY", Sb.LightGrey, Sb.GreyButtonText, Modifier.weight(1f), onClick = viewModel::startReplay)
                ActionButton("SHARE", Sb.LightGrey, Sb.GreyButtonText, Modifier.weight(1f), loading = ui.isPreparingShare) {
                    scope.launch {
                        val snapshots = map?.let { shareSnapshots(it, ui.session?.coordinates.orEmpty(), padding) }.orEmpty()
                        viewModel.prepareShare(snapshots)
                    }
                }
                ActionButton("EXPORT", Sb.LightGrey, Sb.GreyButtonText, Modifier.weight(1f), onClick = viewModel::export)
            }
        }
    }

    if (showSegments) {
        SegmentsSheet(ui, onDismiss = { showSegments = false })
    }

    if (showInsights) {
        InsightsSheet(ui, onDismiss = { showInsights = false })
    }

    cardRequest?.let { request ->
        ShareOptionsDialog(
            onDismiss = { cardRequest = null },
            onStory = { target -> target.share(context, request.story); cardRequest = null },
            onMore = { ShareFiles.chooser(context, request.feed, "image/png", request.text); cardRequest = null },
        )
    }
}

private suspend fun snapshot(map: GmsMap): Bitmap? = suspendCancellableCoroutine { cont ->
    map.snapshot { cont.resume(it) }
}

/**
 * One map snapshot per share card, each with the route framed for that card's map area, so the
 * card's centre crop never cuts it off. The camera goes back where the rider left it afterwards.
 */
private suspend fun shareSnapshots(map: GmsMap, coordinates: List<LatLng>, padding: Int): Map<ShareCardFormat, Bitmap?> {
    if (coordinates.isEmpty()) return ShareCardFormat.entries.associateWith { snapshot(map) }
    val projection = map.projection
    val region = projection.visibleRegion
    val farRight = projection.toScreenLocation(region.farRight)
    val nearLeft = projection.toScreenLocation(region.nearLeft)
    val mapWidth = farRight.x - nearLeft.x
    val mapHeight = nearLeft.y - farRight.y
    if (mapWidth <= 0 || mapHeight <= 0) return ShareCardFormat.entries.associateWith { snapshot(map) }

    val bounds = LatLngBounds.builder().apply { coordinates.forEach(::include) }.build()
    val saved = map.cameraPosition
    val result = ShareCardFormat.entries.associateWith { format ->
        val aspect = format.width.toFloat() / format.mapHeight
        var width = mapWidth
        var height = (mapWidth / aspect).toInt()
        if (height > mapHeight) {
            height = mapHeight
            width = (mapHeight * aspect).toInt()
        }
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, width, height, padding.coerceAtMost(minOf(width, height) / 4)))
        mapLoaded(map)
        snapshot(map)
    }
    map.moveCamera(CameraUpdateFactory.newCameraPosition(saved))
    return result
}

/** Waits for the tiles after a camera move; gives up after a few seconds so Share never hangs offline. */
private suspend fun mapLoaded(map: GmsMap) = withTimeoutOrNull(3_000) {
    suspendCancellableCoroutine { cont ->
        map.setOnMapLoadedCallback { if (cont.isActive) cont.resume(Unit) }
    }
}

@Composable
private fun Header(ui: DailyUiState, onBack: () -> Unit, onShowSegments: () -> Unit, onShowInsights: () -> Unit) {
    val session = ui.session
    Column(
        Modifier.fillMaxWidth().background(Sb.Red)
            .padding(top = statusBarTop() + 8.dp, start = 20.dp, end = 20.dp, bottom = 22.dp),
    ) {
        Row(
            Modifier.height(44.dp).clickable(onClick = onBack).padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chevron(color = Color.White, pointsLeft = true)
            Text("STATS", style = sb(16), color = Color.White)
        }
        Text(
            ui.kicker, style = sb(18, tracking = 0.04f), color = Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
        )
        HeroNumber(
            value = "%.1f".format(ui.units.distanceValue(session?.distance ?: 0.0)),
            captionLines = listOf(ui.units.distanceUnit.uppercase(), "${ui.units.speed(session?.maxSpeed ?: 0.0)} MAX"),
            modifier = Modifier.padding(top = 6.dp),
        )
        if (ui.analysis.segments.isNotEmpty()) {
            val analysis = ui.analysis
            val lifts = analysis.lifts.size
            Row(
                Modifier.padding(top = 4.dp).clickable(onClick = onShowSegments),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "RIDING ${ui.units.compactDuration(analysis.duration(Activity.RUN))} · " +
                        "$lifts ${if (lifts == 1) "LIFT" else "LIFTS"} ${ui.units.compactDuration(analysis.duration(Activity.LIFT))} · " +
                        "STOPPED ${ui.units.compactDuration(analysis.duration(Activity.STILL))}",
                    style = sb(14, tracking = 0.04f), color = Color.White.copy(alpha = 0.88f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Chevron(color = Color.White, size = 9.dp)
            }
        }
        InsightsButton(onShowInsights)
        if (ui.analysis.runs.isNotEmpty()) {
            val runs = ui.analysis.runs.size
            Text(
                "$runs ${if (runs == 1) "RUN" else "RUNS"} · ${ui.units.distance(ui.analysis.downhillDistance)} DOWNHILL · " +
                    "${ui.units.altitude(ui.analysis.totalVertical)} DESCENT",
                style = sb(14, tracking = 0.04f), color = Color.White.copy(alpha = 0.88f),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun InsightsButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.padding(top = 16.dp).fillMaxWidth().height(64.dp)
            .shadow(8.dp, shape, ambientColor = Color.Black.copy(0.3f), spotColor = Color.Black.copy(0.3f))
            .clip(shape).background(Color.White).clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("INSIGHTS", style = sb(22, tracking = 0.04f, lineHeight = 1f), color = Sb.Red)
            Text("A SUMMARY OF THIS RIDE", style = sb(13, tracking = 0.06f, lineHeight = 1f), color = Sb.Red.copy(alpha = 0.7f))
        }
        Chevron(color = Sb.Red, size = 12.dp)
    }
}

@Composable
private fun RouteMap(ui: DailyUiState, onMap: (GmsMap) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val camera = rememberCameraPositionState()
    val darkStyle = remember(context) { MapStyleOptions.loadRawResourceStyle(context, R.raw.map_dark) }
    // Remembered: the replay recomposes this ~20 times a second.
    val coordinates = remember(ui.session) { ui.session?.coordinates.orEmpty() }
    val parts = remember(ui.session, ui.analysis) {
        // Run one point into the next segment so the coloured lines join up.
        ui.analysis.segments.filter { it.activity != Activity.STILL }.map {
            it.activity to coordinates.subList(it.startIndex, minOf(it.endIndex + 2, coordinates.size))
        }
    }
    val stillSpots = remember(ui.session, ui.analysis) {
        ui.analysis.stills.map { coordinates[(it.startIndex + it.endIndex) / 2] }
    }

    Box(modifier.fillMaxWidth().background(Sb.MapBackground)) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = MapProperties(mapStyleOptions = darkStyle),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false, compassEnabled = false,
                mapToolbarEnabled = false, myLocationButtonEnabled = false,
            ),
            onMapLoaded = {
                if (coordinates.isNotEmpty()) {
                    val bounds = LatLngBounds.builder().apply { coordinates.forEach(::include) }.build()
                    camera.move(CameraUpdateFactory.newLatLngBounds(bounds, with(density) { 48.dp.roundToPx() }))
                }
            },
        ) {
            MapEffect(Unit) { onMap(it) }
            val replay = ui.replayModel
            if (ui.replay.active && replay != null) {
                val width = with(density) { 4.dp.toPx() }
                RouteLines(parts, coordinates, alpha = 0.3f, width = width)
                val count = replay.committedCount(ui.replay.time)
                val committed = remember(replay, count) { replay.committed(count) }
                Polyline(points = committed, color = Sb.Red, width = width, startCap = RoundCap(), endCap = RoundCap())
                Polyline(points = replay.head(ui.replay.time), color = Sb.Red, width = width, startCap = RoundCap(), endCap = RoundCap())
                replay.position(ui.replay.time)?.let { here ->
                    val riderState = rememberMarkerState()
                    SideEffect { riderState.position = here }
                    Marker(
                        state = riderState, icon = rememberRiderIcon(),
                        anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f), zIndex = 1f,
                    )
                }
            } else {
                RouteLines(parts, coordinates, alpha = 1f, width = with(density) { 4.dp.toPx() })
                val stillIcon = rememberDotIcon(Sb.Still)
                stillSpots.forEach { spot ->
                    Marker(
                        state = rememberMarkerState(position = spot), icon = stillIcon,
                        anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                    )
                }
            }
            coordinates.firstOrNull()?.let {
                MarkerComposable(state = rememberMarkerState(position = it)) { RouteMarker(Color.White, Sb.Red) }
            }
            if (coordinates.size > 1) {
                MarkerComposable(state = rememberMarkerState(position = coordinates.last())) { RouteMarker(Sb.Red, Color.White) }
            }
        }
        if (parts.isNotEmpty()) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(12.dp).clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LegendItem("RUN", Sb.Red)
                LegendItem("LIFT", Sb.Lift)
                if (stillSpots.isNotEmpty()) LegendItem("STOP", Sb.Still)
            }
        }
    }
}

@Composable
private fun LegendItem(title: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(title, style = sb(12, tracking = 0.04f), color = Color.White)
    }
}

/** Runs red, lifts blue. Without detected segments the whole route is one red line. */
@Composable
private fun RouteLines(parts: List<Pair<Activity, List<LatLng>>>, all: List<LatLng>, alpha: Float, width: Float) {
    if (parts.isEmpty()) {
        if (all.size > 1) Polyline(points = all, color = Sb.Red.copy(alpha = alpha), width = width, startCap = RoundCap(), endCap = RoundCap())
    } else {
        parts.forEach { (activity, points) ->
            if (points.size > 1) {
                Polyline(
                    points = points, color = (if (activity == Activity.LIFT) Sb.Lift else Sb.Red).copy(alpha = alpha),
                    width = width, startCap = RoundCap(), endCap = RoundCap(),
                )
            }
        }
    }
}

@Composable
private fun rememberDotIcon(color: Color): BitmapDescriptor {
    val density = LocalDensity.current.density
    return remember(density, color) {
        val size = (9 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() }
        android.graphics.Canvas(bitmap).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}

@Composable
private fun RouteMarker(fill: Color, stroke: Color) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(stroke), contentAlignment = Alignment.Center) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(fill))
    }
}

@Composable
private fun ActionButton(
    title: String, background: Color, foreground: Color, modifier: Modifier,
    loading: Boolean = false, onClick: () -> Unit,
) {
    Box(
        modifier.height(64.dp).clip(RoundedCornerShape(18.dp)).background(background)
            .clickable(enabled = !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(24.dp), color = foreground, strokeWidth = 2.dp)
        else Text(title, style = sb(22), color = foreground)
    }
}

@Composable
private fun ShareOptionsDialog(onDismiss: () -> Unit, onStory: (StoryTarget) -> Unit, onMore: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share ride") },
        text = {
            Column {
                StoryTarget.entries.filter { it.isInstalled(context) }.forEach { target ->
                    TextButton(onClick = { onStory(target) }, modifier = Modifier.fillMaxWidth()) { Text(target.title) }
                }
                TextButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text("More…") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The rider as a plain bitmap marker, drawn once. A MarkerComposable re-renders its content into a
 * new bitmap whenever it recomposes, which flickers when the position changes 20 times a second.
 */
@Composable
private fun rememberRiderIcon(): BitmapDescriptor {
    val density = LocalDensity.current.density
    return remember(density) {
        val size = (50 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val c = size / 2f
        paint.color = 0x382E6EC8; canvas.drawCircle(c, c, 25 * density, paint)
        paint.color = android.graphics.Color.WHITE; canvas.drawCircle(c, c, 11 * density, paint)
        paint.color = 0xFF1A7CFF.toInt(); canvas.drawCircle(c, c, 8 * density, paint)
        BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}

/** Playback panel shown in place of Replay / Share / Export while a ride is being replayed. */
@Composable
private fun ReplayControls(ui: DailyUiState, viewModel: DailyStatsViewModel) {
    val replay = ui.replay
    val model = ui.replayModel ?: return
    val units: Units = ui.units
    val small = RoundedCornerShape(16.dp)

    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 106.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${units.duration(replay.time.toLong())} / ${units.duration(model.duration.toLong())}",
                style = sb(16, tabular = true), color = Sb.Charcoal, modifier = Modifier.weight(1f),
            )
            Text("CLOSE", style = sb(16), color = Sb.Red, modifier = Modifier.clickable(onClick = viewModel::closeReplay).padding(8.dp))
        }
        Text(
            "${units.speed(model.speedAt(replay.time))} · ${units.altitude(model.altitudeAt(replay.time))}" +
                (model.gForceAt(replay.time)?.let { " · %.2f G".format(java.util.Locale.US, it) } ?: ""),
            style = sb(14, FontWeight.SemiBold, tabular = true), color = Sb.Secondary,
        )
        Slider(
            value = replay.time.toFloat(),
            onValueChange = { viewModel.pauseReplay(); viewModel.seekReplay(it.toDouble()) },
            valueRange = 0f..maxOf(model.duration.toFloat(), 1f),
            colors = SliderDefaults.colors(thumbColor = Sb.Red, activeTrackColor = Sb.Red),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier.width(56.dp).height(52.dp).clip(small).background(Sb.Red).clickable(onClick = viewModel::togglePlay),
                contentAlignment = Alignment.Center,
            ) { Text(if (replay.playing) "❚❚" else "▶", style = sb(22), color = Color.White) }
            Box(
                Modifier.width(48.dp).height(52.dp).clip(small).background(Sb.LightGrey).clickable(onClick = viewModel::restartReplay),
                contentAlignment = Alignment.Center,
            ) { Text("↺", style = sb(24), color = Sb.GreyButtonText) }
            RideReplay.SPEEDS.forEach { speed ->
                val selected = replay.speed == speed
                Box(
                    Modifier.weight(1f).height(52.dp).clip(small)
                        .background(if (selected) Sb.Charcoal else Sb.LightGrey)
                        .clickable { viewModel.setReplaySpeed(speed) },
                    contentAlignment = Alignment.Center,
                ) { Text("$speed×", style = sb(18), color = if (selected) Color.White else Sb.GreyButtonText) }
            }
        }
    }
}
