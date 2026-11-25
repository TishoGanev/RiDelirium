package com.bgdeveloper.ridelirium.ui.track

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import com.bgdeveloper.ridelirium.tracking.MotionMonitor
import com.bgdeveloper.ridelirium.ui.SportGlyph
import com.bgdeveloper.ridelirium.friends.Sport
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.widthIn
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.bgdeveloper.ridelirium.friends.Friend
import com.bgdeveloper.ridelirium.ui.Chevron
import com.bgdeveloper.ridelirium.ui.friends.FriendsViewModel
import com.bgdeveloper.ridelirium.ui.friends.RideTogetherSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import kotlinx.coroutines.launch
import com.google.android.gms.maps.model.LatLngBounds
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.rotate as rotateScope
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bgdeveloper.ridelirium.R
import com.bgdeveloper.ridelirium.domain.Activity
import com.bgdeveloper.ridelirium.domain.Units
import com.bgdeveloper.ridelirium.ui.navigationBarBottom
import com.bgdeveloper.ridelirium.ui.statusBarTop
import com.bgdeveloper.ridelirium.ui.LandscapeBar
import com.bgdeveloper.ridelirium.ui.LandscapeMetrics
import com.bgdeveloper.ridelirium.ui.LandscapeStat
import com.bgdeveloper.ridelirium.ui.isLandscape
import com.bgdeveloper.ridelirium.ui.landscapePadding
import androidx.compose.foundation.layout.Spacer
import com.bgdeveloper.ridelirium.ui.theme.Sb
import com.bgdeveloper.ridelirium.ui.theme.sb
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import androidx.compose.ui.platform.LocalContext

@Composable
fun TrackScreen(viewModel: TrackViewModel = hiltViewModel(), friendsViewModel: FriendsViewModel = hiltViewModel()) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val friendsUi by friendsViewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val tracking = ui.tracking
    val context = LocalContext.current
    // A long press on the map while in a crew drops its meet point there.
    var meetDraft by remember { mutableStateOf<LatLng?>(null) }
    var confirmClearMeet by remember { mutableStateOf(false) }
    var hiddenMeetAt by remember { mutableStateOf<Long?>(null) }
    val meet = friendsUi.selectedCrew?.meet

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onPermissionResult()
    }
    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                // Motion activity (auto start and stop) is hidden below Android 10, where this permission does not exist.
                if (MotionMonitor.isSupported) add(Manifest.permission.ACTIVITY_RECOGNITION)
            }.toTypedArray()
        )
    }

    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(42.6977, 23.3219), 14f)
    }
    val here = tracking.current?.let { LatLng(it.latitude, it.longitude) }

    // Centre once on the first fix, then only while following.
    LaunchedEffect(tracking.hasFix) {
        here?.let { camera.animate(CameraUpdateFactory.newLatLngZoom(it, 16f), 450) }
    }
    LaunchedEffect(ui.follow, here) {
        if (ui.follow && here != null) {
            camera.animate(CameraUpdateFactory.newLatLngZoom(here, maxOf(camera.position.zoom, 16f)), 450)
        }
    }
    // A gesture means the user took over, so follow never resumes by itself.
    LaunchedEffect(camera.cameraMoveStartedReason) {
        if (camera.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE) viewModel.stopFollowing()
    }

    val recenter: () -> Unit = {
        here?.let { camera.move(CameraUpdateFactory.newLatLng(it)) }
    }
    // Fits you and every friend who is sharing a position; the map's padding keeps them clear of the card below.
    val showEveryone: () -> Unit = {
        val points = friendsUi.onMap.map { LatLng(it.latitude!!, it.longitude!!) } + listOfNotNull(here)
        if (points.isNotEmpty()) {
            viewModel.stopFollowing()
            val minLat = points.minOf { it.latitude }
            val maxLat = points.maxOf { it.latitude }
            val minLon = points.minOf { it.longitude }
            val maxLon = points.maxOf { it.longitude }
            val padLat = maxOf((maxLat - minLat) * 0.25, 0.002)
            val padLon = maxOf((maxLon - minLon) * 0.25, 0.002)
            val bounds = LatLngBounds.Builder()
                .include(LatLng(maxLat + padLat, minLon - padLon))
                .include(LatLng(minLat - padLat, maxLon + padLon))
                .build()
            scope.launch { camera.animate(CameraUpdateFactory.newLatLngBounds(bounds, 80), 450) }
        }
    }
    val density = LocalDensity.current
    // Status ages on its own ("last seen"), so re-read the clock now and then.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = System.currentTimeMillis() } }
    // Zoomed far out, tags would pile up, so each rider becomes a small coloured dot.
    val compactMarkers by remember { derivedStateOf { camera.position.zoom < 13f } }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val waitSent = remember { mutableStateMapOf<String, Long>() }
    // The crew list's map-pin button: select that rider and centre on them.
    val focusId by friendsViewModel.focusFriendId.collectAsStateWithLifecycle()
    LaunchedEffect(focusId, friendsUi.friends) {
        val id = focusId ?: return@LaunchedEffect
        val target = friendsUi.friends.firstOrNull { it.id == id }
        if (target?.hasPosition == true) {
            selectedId = id
            viewModel.stopFollowing()
            camera.animate(CameraUpdateFactory.newLatLng(LatLng(target.latitude!!, target.longitude!!)), 450)
        }
        friendsViewModel.clearFocus()
    }
    val selected = selectedId?.let { id -> friendsUi.friends.firstOrNull { it.id == id } }
    val darkStyle = remember(context) { MapStyleOptions.loadRawResourceStyle(context, R.raw.map_dark) }
    var askBattery by remember { mutableStateOf(false) }
    // Many phones stop background apps to save battery, ride or not. Ask once, the first time a ride starts.
    val start = {
        viewModel.start()
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val prefs = context.getSharedPreferences("tracking", android.content.Context.MODE_PRIVATE)
        if (!power.isIgnoringBatteryOptimizations(context.packageName) && !prefs.getBoolean("batteryAsked", false)) {
            prefs.edit().putBoolean("batteryAsked", true).apply()
            askBattery = true
        }
    }
    if (askBattery) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askBattery = false },
            title = { Text("Keep recording with the screen off", style = sb(20)) },
            text = {
                Text(
                    "This phone may stop RiDelirium in the background to save battery, which ends the ride early. " +
                        "Set RiDelirium to \"Unrestricted\" or \"Don't optimise\" in the next screen.",
                    style = sb(16, FontWeight.SemiBold),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    askBattery = false
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("OPEN SETTINGS", style = sb(16), color = Sb.Red) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { askBattery = false }) { Text("NOT NOW", style = sb(16), color = Sb.Charcoal) }
            },
        )
    }


    // The map's visible area ends at the top of the card, so the camera centres there
    // and the Google logo sits just above the card instead of under the tab bar.
    var screenHeightPx by remember { mutableIntStateOf(0) }
    var cardTopPx by remember { mutableFloatStateOf(0f) }
    val mapBottomPadding = if (screenHeightPx > 0 && cardTopPx > 0f) {
        with(density) { (screenHeightPx - cardTopPx).toDp() } + 6.dp
    } else 104.dp + navigationBarBottom()

    Box(Modifier.fillMaxSize().background(Sb.MapBackground).onSizeChanged { screenHeightPx = it.height }) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            contentPadding = if (isLandscape()) PaddingValues(top = 70.dp, bottom = LandscapeMetrics.BarHeight + 24.dp)
                else PaddingValues(top = statusBarTop() + 76.dp, bottom = mapBottomPadding),
            properties = MapProperties(mapStyleOptions = darkStyle),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false, compassEnabled = false,
                mapToolbarEnabled = false, myLocationButtonEnabled = false,
            ),
            onMapLongClick = { spot -> if (friendsUi.selectedCrew != null) meetDraft = spot },
        ) {
            // The crew's meet point, under the riders.
            if (meet != null) {
                key("meet", meet.atMs, meet.latitude, meet.longitude) {
                    val meetState = rememberMarkerState(key = "meet-${meet.atMs}-${meet.latitude}-${meet.longitude}", position = LatLng(meet.latitude, meet.longitude))
                    MarkerComposable(
                        meet, now / 20_000,
                        state = meetState, anchor = Offset(0.5f, 1f), zIndex = 0.5f,
                        onClick = { confirmClearMeet = true; true },
                    ) { MeetPin(meet, now) }
                }
            }
            if (tracking.coordinates.size > 1) {
                Polyline(
                    points = tracking.coordinates, color = Sb.Red,
                    width = with(density) { 4.dp.toPx() }, startCap = RoundCap(), endCap = RoundCap(),
                )
            }
            // Friends sharing a position; the bottom tip of each tag is their position.
            friendsUi.onMap.forEach { friend ->
                key(friend.id) {
                    val position = LatLng(friend.latitude!!, friend.longitude!!)
                    val state = rememberMarkerState(key = friend.id, position = position)
                    SideEffect { state.position = position }
                    MarkerComposable(
                        friend, compactMarkers, now / 20_000, ui.units,
                        state = state, anchor = if (compactMarkers) Offset(0.5f, 0.5f) else Offset(0.5f, 1f), zIndex = 1f,
                        onClick = {
                            selectedId = friend.id
                            viewModel.stopFollowing()
                            scope.launch { camera.animate(CameraUpdateFactory.newLatLng(position), 450) }
                            true
                        },
                    ) { RiderTag(friend, ui.units, compactMarkers, now) }
                }
            }
            if (here != null) {
                val markerState = rememberMarkerState()
                SideEffect { markerState.position = here }
                // You, drawn like a friend sees you: your sport, your speed, "ME", in red. The tag's tip is your position.
                val moving = tracking.isTracking && !tracking.isPaused
                val speedKmh = if (moving) (tracking.currentSpeed * 3.6).toInt() else 0
                val me = Friend(friendsUi.me?.id ?: "me", "ME", "ME", isRiding = moving, speedKmh = speedKmh.toDouble(), updatedAtMs = now)
                MarkerComposable(
                    compactMarkers, speedKmh, friendsUi.mySport, ui.units, now / 20_000,
                    state = markerState, anchor = if (compactMarkers) Offset(0.5f, 0.5f) else Offset(0.5f, 1f), zIndex = 2f,
                ) { RiderTag(me, ui.units, compactMarkers, now, sport = friendsUi.mySport, tint = Sb.Red) }
            }
        }

        if (isLandscape()) {
            // Sideways: crew pill and map buttons along the top, one low bar along the bottom (Landscape.kt).
            Column(Modifier.fillMaxSize().padding(landscapePadding()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(end = LandscapeMetrics.GearSize + 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.widthIn(max = 340.dp)) { RideTogetherPill(now) }
                    if (meet != null && hiddenMeetAt != meet.atMs) {
                        val spot = LatLng(meet.latitude, meet.longitude)
                        val meters = here?.let {
                            val out = FloatArray(1)
                            android.location.Location.distanceBetween(it.latitude, it.longitude, spot.latitude, spot.longitude, out)
                            out[0].toInt()
                        }
                        MeetBanner(
                            meet, meters?.let { shortDistance(ui.units, it) }, setByMe = meet.byId == friendsUi.me?.id, now,
                            onTap = {
                                viewModel.stopFollowing()
                                scope.launch { camera.animate(CameraUpdateFactory.newLatLng(spot), 450) }
                            },
                            onHide = { hiddenMeetAt = meet.atMs },
                            modifier = Modifier.widthIn(max = 380.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (friendsUi.onMap.isNotEmpty()) ShowEveryoneButton(showEveryone)
                    MapButton(ui, onRecenter = recenter, onFollow = viewModel::startFollowing, onUnfollow = viewModel::stopFollowing)
                }
                Spacer(Modifier.weight(1f))
                val shown = selected
                if (shown != null) {
                    val meters = if (here != null && shown.hasPosition) {
                        val out = FloatArray(1)
                        android.location.Location.distanceBetween(here.latitude, here.longitude, shown.latitude!!, shown.longitude!!, out)
                        out[0].toInt()
                    } else null
                    val sent = now - (waitSent[shown.id] ?: 0L) < 30_000
                    Box(Modifier.align(Alignment.End).widthIn(max = 460.dp)) {
                        SelectedFriendCard(
                            shown, meters?.let { shortDistance(ui.units, it) }, sent,
                            onWait = {
                                waitSent[shown.id] = System.currentTimeMillis()
                                now = System.currentTimeMillis()
                                friendsViewModel.askToWait(shown.id) { ok -> if (!ok) waitSent.remove(shown.id) }
                            },
                            onClose = { selectedId = null },
                        )
                    }
                }
                LandscapeBar(trailing = { LandscapeTrackButtons(ui, viewModel, start) }) {
                    LandscapeTrackStats(ui, friendsUi.friends, now, friendsViewModel::openSheet)
                }
            }
        } else {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.padding(top = statusBarTop() + 14.dp, start = 12.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { RideTogetherPill(now) }
            }
            if (meet != null && hiddenMeetAt != meet.atMs) {
                val spot = LatLng(meet.latitude, meet.longitude)
                val meters = here?.let {
                    val out = FloatArray(1)
                    android.location.Location.distanceBetween(it.latitude, it.longitude, spot.latitude, spot.longitude, out)
                    out[0].toInt()
                }
                MeetBanner(
                    meet, meters?.let { shortDistance(ui.units, it) }, setByMe = meet.byId == friendsUi.me?.id, now,
                    onTap = {
                        viewModel.stopFollowing()
                        scope.launch { camera.animate(CameraUpdateFactory.newLatLng(spot), 450) }
                    },
                    onHide = { hiddenMeetAt = meet.atMs },
                    modifier = Modifier.padding(top = 10.dp, start = 12.dp, end = 12.dp),
                )
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 104.dp + navigationBarBottom()),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.End,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End) {
                    if (friendsUi.onMap.isNotEmpty()) ShowEveryoneButton(showEveryone)
                    MapButton(ui, onRecenter = recenter, onFollow = viewModel::startFollowing, onUnfollow = viewModel::stopFollowing)
                }
                // Keeps the last rider while the card slides out, like iOS (move from the bottom, fade).
                var lastSelected by remember { mutableStateOf<Friend?>(null) }
                if (selected != null) lastSelected = selected
                AnimatedVisibility(
                    visible = selected != null,
                    enter = slideInVertically(tween(250)) { it / 2 } + fadeIn(tween(250)),
                    exit = slideOutVertically(tween(200)) { it / 2 } + fadeOut(tween(200)),
                ) {
                    val shown = selected ?: lastSelected
                    if (shown != null) {
                        val meters = if (here != null && shown.hasPosition) {
                            val out = FloatArray(1)
                            android.location.Location.distanceBetween(here.latitude, here.longitude, shown.latitude!!, shown.longitude!!, out)
                            out[0].toInt()
                        } else null
                        val sent = now - (waitSent[shown.id] ?: 0L) < 30_000
                        Box(Modifier.padding(top = 12.dp)) {
                            SelectedFriendCard(
                                shown, meters?.let { shortDistance(ui.units, it) }, sent,
                                onWait = {
                                    waitSent[shown.id] = System.currentTimeMillis()
                                    now = System.currentTimeMillis()
                                    friendsViewModel.askToWait(shown.id) { ok -> if (!ok) waitSent.remove(shown.id) }
                                },
                                onClose = { selectedId = null },
                            )
                        }
                    }
                }
                Box(Modifier.padding(top = 12.dp).onGloballyPositioned { cardTopPx = it.positionInRoot().y }) {
                    StatsCard(ui, viewModel, friendsUi.friends, now, friendsViewModel::openSheet, onStart = start)
                }
            }
        }
        }

        meetDraft?.let { spot ->
            friendsUi.selectedCrew?.let { crew ->
                MeetPointSheet(crew.name, onDismiss = { meetDraft = null }) { kind, minutes, done ->
                    friendsViewModel.setMeet(crew.id, kind, minutes, spot.latitude, spot.longitude) { ok ->
                        done(ok)
                        if (ok) meetDraft = null
                    }
                }
            }
        }
        if (confirmClearMeet) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmClearMeet = false },
                title = { Text("Remove the meet point?", style = sb(22)) },
                text = { Text("It disappears from everyone's map in the crew.", style = sb(16, FontWeight.SemiBold)) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        friendsUi.selectedCrew?.id?.let(friendsViewModel::clearMeet)
                        confirmClearMeet = false
                    }) { Text("REMOVE", style = sb(17), color = Sb.Red) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmClearMeet = false }) { Text("CANCEL", style = sb(17), color = Sb.Charcoal) }
                },
            )
        }
    }
}

private fun Modifier.chipShadow(shape: androidx.compose.ui.graphics.Shape) =
    shadow(9.dp, shape, ambientColor = Color.Black.copy(0.35f), spotColor = Color.Black.copy(0.35f))

private const val LIVE_WINDOW_S = 120
private const val MOVING_KMH = 2.0
private val OfflineGrey = Color(0xFF8C939D)

private enum class Look { RIDING, STOPPED, LAST_SEEN }

private fun ageSeconds(friend: Friend, now: Long): Long = maxOf(0L, (now - (friend.updatedAtMs ?: now)) / 1000)

private fun lookOf(friend: Friend, now: Long): Look = when {
    ageSeconds(friend, now) > LIVE_WINDOW_S -> Look.LAST_SEEN
    (friend.speedKmh ?: 0.0) > MOVING_KMH -> Look.RIDING
    else -> Look.STOPPED
}

private fun shortDistance(units: Units, meters: Int): String =
    if (units.metric && meters < 1000) "$meters M" else units.distance(meters.toDouble()).uppercase()

/** Opens Ride Together. Says who you are riding with and who is on the map, or invites you to ride with someone. */
@Composable
private fun RideTogetherPill(now: Long, viewModel: FriendsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val open by viewModel.showSheet.collectAsStateWithLifecycle()
    val friends = ui.friends
    val live = friends.filter { it.hasPosition && lookOf(it, now) != Look.LAST_SEEN }
    val ordered = live + friends.filter { it !in live }
    val offline = friends.size - live.size
    Row(
        Modifier.fillMaxWidth().height(48.dp).chipShadow(CircleShape).clip(CircleShape).background(Color.White)
            .semantics { contentDescription = ui.selectedCrew?.let { "Crew ${it.name}" } ?: "Ride with someone" }
            .clickable(onClick = viewModel::openSheet).padding(start = 8.dp, end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val crew = ui.selectedCrew
        if (crew == null) {
            Icon(painterResource(R.drawable.ic_people), contentDescription = null, tint = Sb.Red, modifier = Modifier.padding(start = 8.dp).size(20.dp))
            Text("RIDE WITH SOMEONE", style = sb(16, FontWeight.ExtraBold), color = Sb.Charcoal, maxLines = 1, modifier = Modifier.weight(1f))
        } else {
            if (ordered.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
                    ordered.take(3).forEach { friend ->
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).border(2.dp, Color.White, CircleShape)
                                .background(if (friend in live) Color(friend.avatarColor) else OfflineGrey),
                            contentAlignment = Alignment.Center,
                        ) { SportGlyph(friend.rideSport, Color.White, Modifier.size(18.dp)) }
                    }
                }
            } else {
                Icon(painterResource(R.drawable.ic_people), contentDescription = null, tint = Sb.Red, modifier = Modifier.padding(start = 8.dp).size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    crew.name.uppercase() + if (ui.crews.size > 1) " · ${ui.crews.size} CREWS" else "",
                    style = sb(16, FontWeight.ExtraBold), color = Sb.Charcoal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        ordered.isEmpty() -> "JUST YOU · INVITE SOMEONE"
                        offline > 0 -> "${live.size} ON THE MAP · $offline OFFLINE"
                        else -> "${live.size} ON THE MAP"
                    },
                    style = sb(11, tracking = 0.04f), color = Sb.Secondary, maxLines = 1,
                )
            }
        }
        Chevron(size = 10.dp)
    }
    if (open) RideTogetherSheet(onDismiss = viewModel::closeSheet)
}

/** Tap a rider on the map, then WAIT: the same "wait for me" as the crew list. */
@Composable
private fun SelectedFriendCard(friend: Friend, distance: String?, sent: Boolean, onWait: () -> Unit, onClose: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().shadow(14.dp, shape, ambientColor = Color.Black.copy(0.35f), spotColor = Color.Black.copy(0.35f))
            .clip(shape).background(Color.White).padding(top = 10.dp, bottom = 10.dp, start = 12.dp, end = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Color(friend.avatarColor)), contentAlignment = Alignment.Center) {
            Text(friend.initials, style = sb(16), color = Color.White)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(friend.displayName.uppercase(), style = sb(19), color = Sb.Charcoal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            distance?.let { Text(it, style = sb(14), color = Sb.Secondary) }
        }
        Box(
            Modifier.height(44.dp).clip(CircleShape)
                .background(if (sent) Color.White else Sb.Red)
                .then(if (sent) Modifier.border(1.5.dp, Sb.Red, CircleShape) else Modifier)
                .clickable(enabled = !sent, onClick = onWait)
                .semantics { contentDescription = if (sent) "Wait request sent" else "Ask ${friend.displayName} to wait" }
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) { Text(if (sent) "SENT ✓" else "WAIT", style = sb(16), color = if (sent) Sb.Red else Color.White) }
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Sb.LightGrey)
                .semantics { contentDescription = "Close" }.clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Text("✕", style = sb(14, FontWeight.ExtraBold), color = Sb.Charcoal) }
    }
}

@Composable
private fun ShowEveryoneButton(onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).chipShadow(CircleShape).clip(CircleShape).background(Color.White)
            .semantics { contentDescription = "Show everyone on the map" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_people), contentDescription = null, tint = Sb.Charcoal, modifier = Modifier.size(22.dp))
    }
}

/**
 * A friend on the map: a name tag with their glyph in their colour and live status. Riding shows speed, stopped says so,
 * and a rider silent for two minutes goes grey with "last seen". Far out it collapses to a coloured dot with initials.
 */
@Composable
fun RiderTag(friend: Friend, units: Units, compact: Boolean, now: Long, sport: Sport? = null, tint: Color? = null) {
    // Your own tag is drawn in the brand red, which no friend gets.
    val color = tint ?: Color(friend.avatarColor)
    val look = lookOf(friend, now)
    if (compact) {
        Box(Modifier.padding(2.dp)) {
            Box(
                Modifier.size(28.dp).shadow(3.dp, CircleShape).clip(CircleShape)
                    .background(if (look == Look.LAST_SEEN) OfflineGrey else color)
                    .border(2.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(friend.initials, style = sb(11, FontWeight.Bold), color = Color.White, maxLines = 1) }
        }
        return
    }
    val fill = if (look == Look.LAST_SEEN) Color(0xFFC9CDD3) else Color.White
    val age = ageSeconds(friend, now)
    val status = when (look) {
        Look.RIDING -> "${units.speedValue((friend.speedKmh ?: 0.0) / 3.6).toInt()} ${units.speedUnit}"
        Look.STOPPED -> "STOPPED"
        Look.LAST_SEEN -> (age / 60).let { if (it < 60) "LAST SEEN ${it}M AGO" else "LAST SEEN ${it / 60}H AGO" }
    }
    val statusColor = when (look) { Look.RIDING -> color; Look.STOPPED -> Sb.Secondary; Look.LAST_SEEN -> Color(0xFF6A717B) }
    Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.shadow(6.dp, CircleShape, ambientColor = Color.Black.copy(0.45f), spotColor = Color.Black.copy(0.45f))
                .clip(CircleShape).background(fill).padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                if (look == Look.RIDING) Box(Modifier.size(38.dp).clip(CircleShape).background(color.copy(alpha = 0.3f)))
                Box(
                    Modifier.size(30.dp).clip(CircleShape)
                        .background(when (look) { Look.RIDING -> color; Look.STOPPED -> Color.White; Look.LAST_SEEN -> OfflineGrey })
                        .then(if (look == Look.STOPPED) Modifier.border(2.5.dp, color, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    SportGlyph(sport ?: friend.rideSport, when (look) { Look.RIDING -> Color.White; Look.STOPPED -> color; Look.LAST_SEEN -> Color(0xFFE4E7EB) }, Modifier.size(20.dp))
                }
            }
            Column(Modifier.widthIn(max = 150.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    friend.displayName, style = sb(13, FontWeight.Bold),
                    color = if (look == Look.LAST_SEEN) Color(0xFF4A515B) else Sb.Charcoal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(status, style = sb(10, FontWeight.Bold, tracking = 0.03f), color = statusColor, maxLines = 1)
            }
        }
        Canvas(Modifier.size(12.dp, 7.dp)) {
            drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close() }, fill)
        }
    }
}

@Composable
private fun MapButton(ui: TrackUiState, onRecenter: () -> Unit, onFollow: () -> Unit, onUnfollow: () -> Unit) {
    val tracking = ui.tracking.isTracking
    val following = tracking && ui.follow
    Box(
        Modifier.size(48.dp).chipShadow(CircleShape).clip(CircleShape)
            .background(if (following) Sb.Red else Color.White)
            .semantics { contentDescription = if (!tracking) "Re-centre map" else if (following) "Stop following" else "Follow me" }
            .clickable(onClick = if (!tracking) onRecenter else if (following) onUnfollow else onFollow),
        contentAlignment = Alignment.Center,
    ) {
        if (tracking) NavigationArrow(following, Modifier.size(20.dp)) else LocationPin()
    }
}

@Composable
private fun StatsCard(ui: TrackUiState, viewModel: TrackViewModel, friends: List<Friend>, now: Long, onCrew: () -> Unit, onStart: () -> Unit) {
    val s = ui.tracking
    val units = ui.units
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(s.isTracking) { if (!s.isTracking) expanded = false }
    val compact = s.isTracking && !expanded
    val shape = RoundedCornerShape(if (compact) 24.dp else 28.dp)
    Column(
        Modifier.fillMaxWidth()
            .shadow(20.dp, shape, ambientColor = Color.Black.copy(0.45f), spotColor = Color.Black.copy(0.45f))
            .clip(shape).background(Color.White)
            .padding(start = if (compact) 14.dp else 18.dp, end = if (compact) 14.dp else 18.dp, top = if (s.isTracking) 8.dp else 20.dp, bottom = if (compact) 14.dp else 16.dp),
    ) {
        if (s.isTracking) {
            Grabber(expanded) { expanded = !expanded }
            Box(Modifier.height(if (compact) 10.dp else 12.dp))
        }
        if (compact) {
            CompactCard(ui, viewModel)
            return@Column
        }
        if (s.isTracking) {
            LiveHeader(ui)
            Box(Modifier.height(14.dp))
        }
        // Before a ride, distance and max speed would only read zero.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s.isTracking) {
                StatColumn("DISTANCE", units.distance(s.distanceMeters), Modifier.weight(1f))
                StatColumn("MAX SPEED", units.speed(s.maxSpeed), Modifier.weight(1f))
            }
            val altitude = s.current?.let { units.altitude(it.altitude) } ?: "--"
            if (s.isTracking) {
                StatColumn("ALTITUDE", altitude, Modifier.weight(1f))
            } else {
                StatColumn("YOUR ALTITUDE", altitude, Modifier.weight(1f))
                CrewColumn(friends, now, onCrew, Modifier.weight(1f))
            }
        }
        // Only on phones with an accelerometer.
        if (s.isTracking && s.gForce != null) {
            Box(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatColumn("G-FORCE", "%.2f G".format(java.util.Locale.US, s.gForce), Modifier.weight(1f))
                StatColumn("MAX G", s.maxGForce?.let { "%.2f G".format(java.util.Locale.US, it) } ?: "--", Modifier.weight(1f))
                Box(Modifier.weight(1f))
            }
        }
        Box(Modifier.height(18.dp))
        Buttons(ui, viewModel, onStart)
    }
}

@Composable
private fun Grabber(expanded: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(20.dp)
            .semantics { contentDescription = if (expanded) "Show less" else "Show more" }
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(Color(0xFFD6D6D6))) }
}

/** While recording, the compact card keeps the map visible; the grabber opens the full one. */
@Composable
private fun CompactCard(ui: TrackUiState, viewModel: TrackViewModel) {
    val s = ui.tracking
    val units = ui.units
    val small = RoundedCornerShape(16.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PulsingDot()
            Text(
                if (s.isPaused) "PAUSED" else "RECORDING · ${units.duration(s.elapsedSeconds)}",
                style = sb(12, tracking = 0.04f, tabular = true), color = Sb.Red, maxLines = 1, softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier.size(52.dp).clip(small).background(Sb.LightGrey).clickable(onClick = viewModel::togglePause)
                .semantics { contentDescription = if (s.isPaused) "Resume" else "Pause" },
            contentAlignment = Alignment.Center,
        ) { Text(if (s.isPaused) "▶" else "❚❚", style = sb(18), color = Sb.GreyButtonText) }
        Row(
            Modifier.height(52.dp).shadow(8.dp, small, ambientColor = Sb.Red.copy(0.3f), spotColor = Sb.Red.copy(0.3f))
                .clip(small).background(Sb.Red).clickable(onClick = viewModel::stop).padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(13.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
            Text("STOP", style = sb(18), color = Color.White)
        }
    }
    Box(Modifier.height(10.dp))
    // Speed gets the whole width, so it can be as big as the phone allows (same layout as iPhone).
    HeroSpeed(if (s.isPaused) 0.0 else units.speedValue(s.currentSpeed), units.speedUnit, maxSize = 56, minSize = 28)
    Box(Modifier.height(10.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEFEFEF)))
    Box(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CompactStat("DISTANCE", units.distance(s.distanceMeters), Modifier.weight(1f))
        CompactStat("MAX SPEED", units.speed(s.maxSpeed), Modifier.weight(1f))
        CompactStat("ALTITUDE", s.current?.let { units.altitude(it.altitude) } ?: "--", Modifier.weight(1f))
    }
}

@Composable
private fun CompactStat(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = sb(11), color = Sb.Secondary)
        Text(value, style = sb(20, FontWeight.ExtraBold, lineHeight = 1.05f, tabular = true), color = Sb.Charcoal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LiveHeader(ui: TrackUiState) {
    val s = ui.tracking
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PulsingDot()
            Text(
                "${if (s.isPaused) "PAUSED" else "RECORDING"} · ${ui.units.duration(s.elapsedSeconds)}" +
                    if (s.isPaused) "" else " · " + when (s.activity) {
                        Activity.RUN -> "RIDING"
                        Activity.LIFT -> "LIFT"
                        Activity.STILL -> "STOPPED"
                    },
                style = sb(14, tracking = 0.04f, tabular = true), color = Sb.Red,
            )
        }
        HeroSpeed(
            if (s.isPaused) 0.0 else ui.units.speedValue(s.currentSpeed), ui.units.speedUnit,
            maxSize = 80, minSize = 38, tracking = -0.02f, lineHeight = 0.9f,
        )
    }
}

/** Unit beside the hero speed (KM/H, MPH); iPhone uses the same size for BPM. */
private fun heroUnitSize(numberSize: Int) = maxOf(13, numberSize * 3 / 10)

/**
 * The live speed at the largest size from [maxSize] down to [minSize] that fits the row, with the unit
 * always on one line. Measured against at least two digits, so the size doesn't jump as the speed changes.
 */
@Composable
private fun HeroSpeed(speed: Double, unit: String, maxSize: Int, minSize: Int, tracking: Float = 0f, lineHeight: Float = 1f) {
    val text = "%.0f".format(speed)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxWidth = constraints.maxWidth
        val template = "8".repeat(maxOf(2, text.length))
        val size = remember(maxWidth, template, unit, maxSize, minSize, density) {
            (maxSize downTo minSize step 2).firstOrNull { s ->
                val number = measurer.measure(template, sb(s, FontWeight.ExtraBold, tracking = tracking, lineHeight = lineHeight, tabular = true), maxLines = 1, softWrap = false).size.width
                val unitWidth = measurer.measure(unit, sb(heroUnitSize(s)), maxLines = 1, softWrap = false).size.width
                number + unitWidth + with(density) { (s * 0.1f).dp.roundToPx() } <= maxWidth
            } ?: minSize
        }
        Row {
            Text(
                text,
                style = sb(size, FontWeight.ExtraBold, tracking = tracking, lineHeight = lineHeight, tabular = true),
                color = Sb.Charcoal, maxLines = 1, softWrap = false,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                unit, style = sb(heroUnitSize(size)), color = Sb.Secondary, maxLines = 1, softWrap = false,
                modifier = Modifier.alignByBaseline().padding(start = (size * 0.1f).dp),
            )
        }
    }
}

/** Who of your crew is on the map right now (same rule as the Ride Together pill); taps open Ride Together. */
@Composable
private fun CrewColumn(friends: List<Friend>, now: Long, onTap: () -> Unit, modifier: Modifier) {
    val online = friends.count { it.hasPosition && lookOf(it, now) != Look.LAST_SEEN }
    val offline = friends.size - online
    Column(
        modifier
            .semantics(mergeDescendants = true) {
                contentDescription = if (friends.isEmpty()) "Your crew, no crew, invite a friend"
                else "Your crew, $online online, $offline offline"
            }
            .clickable(onClick = onTap),
        horizontalAlignment = Alignment.End,
    ) {
        Text("YOUR CREW", style = sb(13, tracking = 0.04f), color = Sb.Secondary)
        Box(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (online > 0) Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF30D158)))
            Text(
                if (friends.isEmpty()) "NO CREW" else "$online ONLINE",
                style = sb(28, FontWeight.ExtraBold, lineHeight = 1.05f, tabular = true),
                color = if (friends.isEmpty()) Sb.Secondary else Sb.Charcoal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (friends.isEmpty()) "INVITE A FRIEND" else "$offline OFFLINE",
            style = sb(12, FontWeight.Bold, tracking = 0.04f),
            color = if (friends.isEmpty()) Color(0xFFED213D) else Sb.Secondary,
            maxLines = 1, modifier = Modifier.padding(top = 5.dp),
        )
    }
}

@Composable
private fun StatColumn(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = sb(13, tracking = 0.04f), color = Sb.Secondary)
        Text(
            value, style = sb(28, FontWeight.ExtraBold, lineHeight = 1.05f, tabular = true), color = Sb.Charcoal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Buttons(ui: TrackUiState, viewModel: TrackViewModel, onStart: () -> Unit) {
    val red = RoundedCornerShape(20.dp)
    val glow = Modifier.shadow(10.dp, red, ambientColor = Sb.Red.copy(0.3f), spotColor = Sb.Red.copy(0.3f))
    if (ui.tracking.isTracking) {
        Row(Modifier.fillMaxWidth().height(68.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).height(68.dp).clip(red).background(Sb.LightGrey).clickable(onClick = viewModel::togglePause),
                contentAlignment = Alignment.Center,
            ) { Text(if (ui.tracking.isPaused) "RESUME" else "PAUSE", style = sb(22), color = Sb.GreyButtonText) }
            Row(
                Modifier.weight(1.4f).height(68.dp).then(glow).clip(red).background(Sb.Red).clickable(onClick = viewModel::stop),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
                Text("STOP", style = sb(22), color = Color.White)
            }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().height(68.dp).then(glow).clip(red).background(Sb.Red).clickable(onClick = onStart)
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White))
            // Shrinks to fit like iOS (minimumScaleFactor 0.6); the GPS part scales with it.
            BasicText(
                buildAnnotatedString {
                    append("START TRACKING")
                    withStyle(SpanStyle(fontSize = (13f / 22f).em)) { append(" · ${gpsStatus(ui)}") }
                },
                style = sb(22).copy(color = Color.White), maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = 22.sp),
            )
        }
    }
}

/** Landscape bar, middle: altitude and crew before a ride; speed and the ride's numbers while recording. */
@Composable
private fun LandscapeTrackStats(ui: TrackUiState, friends: List<Friend>, now: Long, onCrew: () -> Unit) {
    val s = ui.tracking
    val units = ui.units
    val altitude = s.current?.let { units.altitude(it.altitude) } ?: "--"
    if (s.isTracking) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PulsingDot()
                Text(
                    if (s.isPaused) "PAUSED" else units.duration(s.elapsedSeconds),
                    style = sb(11, tracking = 0.04f, tabular = true), color = Sb.Red, maxLines = 1,
                )
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                val speed = if (s.isPaused) 0.0 else units.speedValue(s.currentSpeed)
                Text("%.0f".format(speed), style = sb(40, FontWeight.ExtraBold, lineHeight = 0.95f, tabular = true), color = Sb.Charcoal)
                Text(units.speedUnit, style = sb(13), color = Sb.Secondary, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 5.dp))
            }
        }
        LandscapeStat("DISTANCE", units.distance(s.distanceMeters), size = 20)
        LandscapeStat("MAX SPEED", units.speed(s.maxSpeed), size = 20)
        LandscapeStat("ALTITUDE", altitude, size = 20)
    } else {
        LandscapeStat("YOUR ALTITUDE", altitude)
        val online = friends.count { it.hasPosition && lookOf(it, now) != Look.LAST_SEEN }
        LandscapeStat(
            "YOUR CREW", if (friends.isEmpty()) "INVITE" else "$online ONLINE",
            modifier = Modifier
                .semantics(mergeDescendants = true) {
                    contentDescription = if (friends.isEmpty()) "Your crew, no crew, invite a friend" else "Your crew, $online online"
                }
                .clickable(onClick = onCrew),
        )
    }
}

/** Landscape bar, right: START TRACKING, or pause and STOP. */
@Composable
private fun LandscapeTrackButtons(ui: TrackUiState, viewModel: TrackViewModel, onStart: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val glow = Modifier.shadow(10.dp, shape, ambientColor = Sb.Red.copy(0.3f), spotColor = Sb.Red.copy(0.3f))
    if (ui.tracking.isTracking) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(60.dp).clip(shape).background(Sb.LightGrey).clickable(onClick = viewModel::togglePause)
                    .semantics { contentDescription = if (ui.tracking.isPaused) "Resume" else "Pause" },
                contentAlignment = Alignment.Center,
            ) { Text(if (ui.tracking.isPaused) "▶" else "❚❚", style = sb(20), color = Sb.GreyButtonText) }
            Row(
                Modifier.height(60.dp).then(glow).clip(shape).background(Sb.Red).clickable(onClick = viewModel::stop)
                    .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
                Text("STOP", style = sb(20), color = Color.White)
            }
        }
    } else {
        Row(
            Modifier.height(60.dp).then(glow).clip(shape).background(Sb.Red).clickable(onClick = onStart)
                .padding(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(Color.White))
            Text(
                buildAnnotatedString {
                    append("START TRACKING")
                    withStyle(SpanStyle(fontSize = (12f / 20f).em)) { append(" · ${gpsStatus(ui)}") }
                },
                style = sb(20), color = Color.White, maxLines = 1,
            )
        }
    }
}

private fun gpsStatus(ui: TrackUiState): String {
    val location = ui.tracking.current
    if (location == null || !location.hasAccuracy()) return "SEARCHING GPS…"
    val accuracy = ui.units.altitudeValue(location.accuracy.toDouble()).toInt()
    val unit = ui.units.altitudeUnit.uppercase()
    return if (location.accuracy <= 20f) "GPS ±$accuracy $unit" else "WEAK GPS ±$accuracy $unit"
}

// region Components

@Composable
private fun PulsingDot() {
    val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha",
    )
    Box(Modifier.size(9.dp).alpha(alpha).clip(CircleShape).background(Sb.Red))
}

/** Teardrop pin from the design: an 18 dp square with three round corners, rotated −45°, with a white centre. */
@Composable
private fun LocationPin() {
    Box(
        Modifier.size(18.dp).rotate(-45f).clip(RoundedCornerShape(9.dp, 9.dp, 9.dp, 0.dp)).background(Sb.Charcoal),
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White)) }
}

@Composable
private fun NavigationArrow(filled: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val sx = size.width / 20
        val sy = size.height / 20
        val path = Path().apply {
            moveTo(10 * sx, 2 * sy); lineTo(17 * sx, 17 * sy); lineTo(10 * sx, 13.5f * sy); lineTo(3 * sx, 17 * sy); close()
        }
        if (filled) {
            drawPath(path, Color.White, style = Fill)
        } else {
            drawPath(path, Sb.Red, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round))
        }
    }
}

// endregion
