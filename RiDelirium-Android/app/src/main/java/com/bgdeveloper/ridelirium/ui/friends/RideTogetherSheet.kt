package com.bgdeveloper.ridelirium.ui.friends

import androidx.compose.material3.rememberModalBottomSheetState
import com.bgdeveloper.ridelirium.friends.timeLeftLabel
import com.bgdeveloper.ridelirium.friends.Stay
import com.bgdeveloper.ridelirium.friends.sharingLabel
import java.time.Instant
import java.time.ZonedDateTime
import java.time.ZoneId
import com.bgdeveloper.ridelirium.friends.CrewPreview
import com.bgdeveloper.ridelirium.friends.Crew
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import android.content.Intent
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.horizontalScroll
import com.bgdeveloper.ridelirium.domain.Units
import com.bgdeveloper.ridelirium.ui.Chevron
import kotlinx.coroutines.delay
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bgdeveloper.ridelirium.friends.Friend
import com.bgdeveloper.ridelirium.ui.BigButton
import com.bgdeveloper.ridelirium.ui.Field
import com.bgdeveloper.ridelirium.ui.theme.Sb
import com.bgdeveloper.ridelirium.ui.theme.sb
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

private sealed interface Screen {
    data object Home : Screen
    data class Detail(val crewId: String) : Screen
    data class InviteCode(val crewId: String) : Screen
    data object NewCrew : Screen
    data object Join : Screen
}

private enum class Look { RIDING, STOPPED, OFFLINE }

private const val LIVE_WINDOW_MS = 120_000L
private val StatusGreen = Color(0xFF30D158)
private val StatusAmber = Color(0xFFFFB020)
private val StatusGrey = Color(0xFFC9CDD3)
private val OfflineAvatar = Color(0xFF8C939D)

private fun lookOf(friend: Friend, now: Long): Look = when {
    !friend.hasPosition -> Look.OFFLINE
    now - (friend.updatedAtMs ?: 0L) > LIVE_WINDOW_MS -> Look.OFFLINE
    (friend.speedKmh ?: 0.0) > 2.0 -> Look.RIDING
    else -> Look.STOPPED
}

/** Ride Together: your crews first; each crew opens its riders, its invite code and how long you stay. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideTogetherSheet(onDismiss: () -> Unit, viewModel: FriendsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val units by viewModel.units.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var confirmLeave by remember { mutableStateOf<Crew?>(null) }
    var changeTime by remember { mutableStateOf<Crew?>(null) }
    var rename by remember { mutableStateOf<Crew?>(null) }
    val context = LocalContext.current
    val view = LocalView.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); now = System.currentTimeMillis() } }
    val waitSent = remember { androidx.compose.runtime.mutableStateMapOf<String, Long>() }

    LaunchedEffect(ui.me) { if (ui.me != null) viewModel.reload() }
    // The screen stays awake while a code is up, so it does not lock before the friend has scanned it.
    val showingCode = screen is Screen.InviteCode ||
        (screen as? Screen.Detail)?.let { d -> ui.crews.firstOrNull { it.id == d.crewId }?.others(ui.me?.id)?.isEmpty() } == true
    DisposableEffect(showingCode) {
        view.keepScreenOn = showingCode
        onDispose { view.keepScreenOn = false }
    }
    // A friend just joined: a small confirming tap, like the success haptic on iOS.
    LaunchedEffect(ui.joinBanner) {
        if (ui.joinBanner != null) {
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
            )
        }
    }
    // A crew that went away (left, or its time ran out) can't stay open.
    LaunchedEffect(ui.crews, screen) {
        val id = (screen as? Screen.Detail)?.crewId ?: (screen as? Screen.InviteCode)?.crewId
        if (id != null && ui.crews.none { it.id == id }) screen = Screen.Home
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Color.White) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val crewOf = { id: String -> ui.crews.firstOrNull { it.id == id } }
            val current = screen
            when {
                ui.justJoined != null -> JoinedScreen(ui.justJoined!!, onDismiss)
                ui.me == null && viewModel.isConfigured -> NameStep(onSave = viewModel::setName)
                ui.offline && ui.crews.isEmpty() -> OfflineStep(onRetry = viewModel::reload)
                ui.me == null -> NameStep(onSave = viewModel::setName)
                ui.joinPreview != null -> PreviewStep(ui.joinPreview!!, ui.isJoining, ui.joinError, onJoin = viewModel::confirmJoin, onCancel = viewModel::cancelJoin)
                current is Screen.Detail && crewOf(current.crewId) != null -> {
                    val crew = crewOf(current.crewId)!!
                    CrewDetail(
                        crew, ui.me, selected = crew.id == ui.selectedCrew?.id, ui.recentRiders, ui.invitedIds, units, now, waitSent,
                        metersTo = viewModel::metersTo, bearingTo = viewModel::bearingTo,
                        onInvite = { viewModel.invite(it, crew.id) },
                        onWait = { friend ->
                            waitSent[friend.id] = System.currentTimeMillis()
                            now = System.currentTimeMillis()
                            viewModel.askToWait(friend.id) { ok ->
                                if (!ok) {
                                    waitSent.remove(friend.id)
                                    Toast.makeText(context, "Couldn't reach the server", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onShowOnMap = { friend -> viewModel.selectCrew(crew.id); viewModel.showOnMap(friend) },
                        onSelect = { viewModel.selectCrew(crew.id); onDismiss() },
                        onBack = { screen = Screen.Home },
                        onCode = { screen = Screen.InviteCode(crew.id) },
                        onShare = { shareInvite(context, crew, onDismiss) },
                        onChangeTime = { changeTime = crew },
                        onLeave = { confirmLeave = crew },
                        onRename = { rename = crew },
                    )
                }
                current is Screen.InviteCode && crewOf(current.crewId) != null -> {
                    val crew = crewOf(current.crewId)!!
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        BackTitle(crew.name.uppercase(), onBack = { screen = Screen.Detail(crew.id) })
                        ShareTab(crew.invite.url, crew.code, ui.joinBanner, failed = false, onRetry = {}, onShare = { shareInvite(context, crew, onDismiss) })
                    }
                }
                current is Screen.NewCrew -> NewCrewStep(
                    defaultName = ui.me?.let { "${it.displayName.uppercase()}'S CREW" } ?: "MY CREW",
                    error = ui.joinError,
                    onBack = { screen = Screen.Home; viewModel.clearError() },
                    onCreate = { name -> viewModel.createCrew(name) { crew -> if (crew != null) screen = Screen.InviteCode(crew.id) } },
                )
                current is Screen.Join -> Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    BackTitle("JOIN A CREW", onBack = { screen = Screen.Home; viewModel.clearError() })
                    JoinTab(ui.isJoining, onCode = viewModel::preview)
                    ui.joinError?.let { Text(it, style = sb(14, FontWeight.SemiBold), color = Sb.Red) }
                }
                else -> CrewsHome(
                    ui.crews, ui.me?.id, ui.selectedCrew?.id, now,
                    onOpen = { screen = Screen.Detail(it.id) },
                    onNew = { viewModel.clearError(); screen = Screen.NewCrew },
                    onJoin = { viewModel.clearError(); screen = Screen.Join },
                )
            }
        }
    }

    confirmLeave?.let { crew ->
        AlertDialog(
            onDismissRequest = { confirmLeave = null },
            title = { Text("Leave ${crew.name}?", style = sb(22)) },
            text = {
                val others = crew.others(ui.me?.id)
                Text(
                    if (others.isEmpty()) "Nobody else is in it, so the crew goes away."
                    else "You'll stop riding with ${others.joinToString { it.displayName }}. You can join again with its code.",
                    style = sb(16, FontWeight.SemiBold),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.leave(crew.id); confirmLeave = null; screen = Screen.Home }) { Text("LEAVE", style = sb(17), color = Sb.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = null }) { Text("STAY", style = sb(17), color = Sb.Charcoal) } },
        )
    }

    changeTime?.let { crew ->
        // The same picker as joining, opening on the current end time.
        var end by remember(crew.id) {
            val current = crew.myExpiresAtMs?.takeIf { it > System.currentTimeMillis() }
                ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
            mutableStateOf(Stay.clamp(current ?: Stay.defaultEnd()))
        }
        ModalBottomSheet(
            onDismissRequest = { changeTime = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color.White,
        ) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(crew.name.uppercase(), style = sb(26, FontWeight.ExtraBold), color = Sb.Charcoal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StayPicker(end) { end = it }
                BigButton("SAVE · UNTIL ${Stay.clock(end)}", Sb.Red, Color.White) { viewModel.setStay(crew.id, end); changeTime = null }
            }
        }
    }

    rename?.let { crew ->
        var draft by remember(crew.id) { mutableStateOf(crew.name) }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("Crew name", style = sb(22)) },
            text = { Field(draft, { draft = it.take(30) }, "Crew name", Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = { viewModel.renameCrew(crew.id, draft); rename = null }, enabled = draft.isNotBlank()) {
                    Text("SAVE", style = sb(17), color = Sb.Red)
                }
            },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("CANCEL", style = sb(17), color = Sb.Charcoal) } },
        )
    }
}

private fun shareInvite(context: android.content.Context, crew: Crew, onSent: () -> Unit) {
    val text = "Ride with me on RiDelirium. Tap the link to join ${crew.name}, or enter the code ${formatCode(crew.code)}.\n${crew.invite.url}"
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    // The invite is on its way to Mail, Viber, Messages...; this screen has done its job.
    onSent()
}

@Composable
private fun BackTitle(title: String, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
        Text(title, style = sb(20), color = Sb.Charcoal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 56.dp))
        Box(
            Modifier.align(Alignment.CenterStart).size(40.dp).clip(CircleShape).background(Sb.LightGrey)
                .clickable(onClick = onBack).semantics { contentDescription = "Back" },
            contentAlignment = Alignment.Center,
        ) { Chevron(color = Sb.Charcoal, pointsLeft = true) }
        if (trailing != null) Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

/** The rarely used crew actions, kept out of the way behind "⋯". */
@Composable
private fun CrewMenu(isOwner: Boolean, onChangeTime: () -> Unit, onRename: () -> Unit, onLeave: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.size(40.dp).clip(CircleShape).background(Sb.LightGrey).clickable { open = true }
                .semantics { contentDescription = "Crew options" },
            horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
        ) { repeat(3) { Box(Modifier.size(5.dp).clip(CircleShape).background(Sb.Charcoal)) } }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Color.White) {
            androidx.compose.material3.DropdownMenuItem(text = { Text("CHANGE TIME", style = sb(17), color = Sb.Charcoal) }, onClick = { open = false; onChangeTime() })
            if (isOwner) androidx.compose.material3.DropdownMenuItem(text = { Text("RENAME CREW", style = sb(17), color = Sb.Charcoal) }, onClick = { open = false; onRename() })
            androidx.compose.material3.DropdownMenuItem(text = { Text("LEAVE CREW", style = sb(17), color = Sb.Red) }, onClick = { open = false; onLeave() })
        }
    }
}

// region Crews home

@Composable
private fun CrewsHome(crews: List<Crew>, meId: String?, selectedId: String?, now: Long, onOpen: (Crew) -> Unit, onNew: () -> Unit, onJoin: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("RIDE TOGETHER", style = sb(32, FontWeight.ExtraBold), color = Sb.Charcoal)
        if (crews.isEmpty()) {
            Text(
                "Make a crew for today's ride and invite your friends, or join a friend's crew with their code.",
                style = sb(17, FontWeight.SemiBold), color = Sb.Secondary,
            )
        } else {
            Text("YOUR CREWS", style = sb(13, tracking = 0.08f), color = Sb.Secondary, modifier = Modifier.padding(top = 4.dp))
            crews.forEach { CrewCard(it, meId, it.id == selectedId, now) { onOpen(it) } }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).height(58.dp).clip(CircleShape).background(Sb.Red).clickable(onClick = onNew),
                contentAlignment = Alignment.Center,
            ) { Text("NEW CREW", style = sb(19), color = Color.White) }
            Box(
                Modifier.weight(1f).height(58.dp).clip(CircleShape).background(Sb.Charcoal).clickable(onClick = onJoin),
                contentAlignment = Alignment.Center,
            ) { Text("JOIN CREW", style = sb(19), color = Color.White) }
        }
    }
}

@Composable
private fun CrewCard(crew: Crew, meId: String?, selected: Boolean, now: Long, onClick: () -> Unit) {
    val others = crew.others(meId)
    val live = others.count { lookOf(it, now) != Look.OFFLINE }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Sb.LightGrey)
            .then(if (selected) Modifier.border(2.dp, Sb.Red, RoundedCornerShape(20.dp)) else Modifier)
            .clickable(onClick = onClick).padding(14.dp)
            .semantics { contentDescription = "${crew.name}, ${others.size} riders" + if (selected) ", on the map" else "" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(crew.name.uppercase(), style = sb(20), color = Sb.Charcoal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (others.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                        others.take(4).forEach { friend ->
                            Box(
                                Modifier.size(26.dp).clip(CircleShape).border(2.dp, Sb.LightGrey, CircleShape)
                                    .background(if (lookOf(friend, now) == Look.OFFLINE) OfflineAvatar else Color(friend.avatarColor)),
                                contentAlignment = Alignment.Center,
                            ) { Text(friend.initials, style = sb(10, FontWeight.ExtraBold), color = Color.White) }
                        }
                    }
                }
                Text(
                    if (others.isEmpty()) "JUST YOU · INVITE SOMEONE" else "$live ON THE MAP · ${others.size} ${if (others.size == 1) "RIDER" else "RIDERS"}",
                    style = sb(12, tracking = 0.04f), color = Sb.Secondary, maxLines = 1,
                )
            }
            timeLeftLabel(crew.myExpiresAtMs, now)?.let { Text(it, style = sb(12, tracking = 0.04f), color = Sb.Red) }
        }
        if (selected) Text("ON MAP", style = sb(12, tracking = 0.04f), color = Sb.Red)
        Chevron(size = 10.dp)
    }
}

// endregion

// region Crew detail

@Composable
private fun CrewDetail(
    crew: Crew, me: Friend?, selected: Boolean, recent: List<Friend>, invited: Set<String>, units: Units, now: Long,
    waitSent: Map<String, Long>, metersTo: (Friend) -> Int?, bearingTo: (Friend) -> Float?, onInvite: (Friend) -> Unit,
    onWait: (Friend) -> Unit, onShowOnMap: (Friend) -> Unit, onSelect: () -> Unit, onBack: () -> Unit, onCode: () -> Unit,
    onShare: () -> Unit, onChangeTime: () -> Unit, onLeave: () -> Unit, onRename: () -> Unit,
) {
    val isOwner = crew.ownerId == me?.id
    val friends = crew.others(me?.id)
    Column(Modifier.padding(horizontal = 20.dp)) {
        BackTitle(crew.name.uppercase(), onBack) { CrewMenu(isOwner, onChangeTime, onRename, onLeave) }
        val left = sharingLabel(crew.myExpiresAtMs, now) ?: "YOU STAY UNTIL YOU LEAVE"
        Text(
            if (friends.isEmpty()) "JUST YOU SO FAR · $left" else left,
            style = sb(12, tracking = 0.04f), color = Sb.Secondary,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }

    val recentRow = @Composable {
        if (recent.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("RECENT RIDERS", style = sb(13, tracking = 0.08f), color = Sb.Secondary, modifier = Modifier.padding(horizontal = 20.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    recent.forEach { RiderChip(it, it.id in invited) { onInvite(it) } }
                }
            }
        }
    }

    if (friends.isEmpty()) {
        // Nobody else is here yet, so inviting is the only thing to do: the code leads.
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val qr = remember(crew.invite.url) { qrBitmap(crew.invite.url) }
            Box(Modifier.clip(RoundedCornerShape(28.dp)).border(1.5.dp, Color(0xFFEAEAEA), RoundedCornerShape(28.dp)).background(Color.White).padding(20.dp)) {
                Image(qr.asImageBitmap(), contentDescription = "Invite QR code", Modifier.size(210.dp))
            }
            Text(formatCode(crew.code), style = sb(36, FontWeight.ExtraBold, tracking = 0.1f, tabular = true), color = Sb.Charcoal)
            Text(
                "Let friends scan this or open your invite link, or add someone you rode with.",
                style = sb(15, FontWeight.SemiBold), color = Sb.Secondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.widthIn(max = 300.dp),
            )
        }
        recentRow()
        Box(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(58.dp).clip(CircleShape).background(Sb.Red).clickable(onClick = onShare),
            contentAlignment = Alignment.Center,
        ) { Text("SHARE CREW LINK", style = sb(19), color = Color.White) }
        return
    }

    val rows = friends.map { Triple(it, lookOf(it, now), metersTo(it)) }.sortedWith(
        compareBy({ it.second.ordinal }, { it.third ?: Int.MAX_VALUE }),
    )
    Text(
        "ON THE MAP · ${rows.count { it.second != Look.OFFLINE }} OF ${rows.size}",
        style = sb(13, tracking = 0.08f), color = Sb.Secondary, modifier = Modifier.padding(horizontal = 20.dp),
    )
    Column(Modifier.padding(horizontal = 20.dp)) {
        rows.forEach { (friend, look, meters) ->
            CrewRow(friend, look, meters, if (look == Look.OFFLINE) null else bearingTo(friend), units, now, sent = now - (waitSent[friend.id] ?: 0L) < 30_000, onWait = { onWait(friend) }, onShowOnMap = { onShowOnMap(friend) })
        }
    }

    recentRow()

    CodeCard(crew.code, crew.invite.url, onCode, onShare)

    Box(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(58.dp).clip(CircleShape).background(if (selected) Sb.Charcoal else Sb.Red).clickable(onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) { Text(if (selected) "ON THE MAP" else "SHOW ON MAP", style = sb(19), color = Color.White) }
}

private fun Modifier.pill(color: Color, height: Int = 40) = this.height(height.dp).clip(RoundedCornerShape(20.dp)).background(color)


@Composable
private fun CrewRow(friend: Friend, look: Look, meters: Int?, bearing: Float?, units: Units, now: Long, sent: Boolean, onWait: () -> Unit, onShowOnMap: () -> Unit) {
    val offline = look == Look.OFFLINE
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp).alpha(if (offline) 0.6f else 1f),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(if (offline) OfflineAvatar else Color(friend.avatarColor)),
                    contentAlignment = Alignment.Center,
                ) { Text(friend.initials, style = sb(17, FontWeight.ExtraBold), color = Color.White) }
                Box(
                    Modifier.align(Alignment.BottomEnd).size(16.dp).clip(CircleShape).background(Color.White).padding(3.dp).clip(CircleShape)
                        .background(when (look) { Look.RIDING -> StatusGreen; Look.STOPPED -> StatusAmber; Look.OFFLINE -> StatusGrey }),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(friend.displayName.uppercase(), style = sb(19), color = Sb.Charcoal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (look == Look.OFFLINE) {
                    val detail = friend.updatedAtMs?.let {
                        val minutes = maxOf((now - it) / 60_000, 0)
                        if (minutes < 60) "LAST SEEN $minutes MIN AGO" else "LAST SEEN ${minutes / 60} H AGO"
                    } ?: "OFFLINE"
                    Text(detail, style = sb(14), color = Sb.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    val color = if (look == Look.RIDING) Color(friend.avatarColor) else Sb.Secondary
                    val state = if (look == Look.RIDING) "RIDING ${units.speedValue((friend.speedKmh ?: 0.0) / 3.6).toInt()} ${units.speedUnit}" else "STOPPED"
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (meters != null) {
                            Text(if (units.metric && meters < 1000) "$meters M" else units.distance(meters.toDouble()).uppercase(), style = sb(14), color = color, maxLines = 1)
                            if (bearing != null) Text("↑", style = sb(14, FontWeight.ExtraBold), color = color, modifier = Modifier.rotate(bearing))
                            Text("·", style = sb(14), color = color)
                        }
                        Text(state, style = sb(14), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // Offline riders still get the push, so WAIT stays; only the distance is unknown.
            Box(
                Modifier.pill(if (sent) Color.White else Sb.Red)
                    .then(if (sent) Modifier.border(1.5.dp, Sb.Red, RoundedCornerShape(20.dp)) else Modifier)
                    .clickable(enabled = !sent, onClick = onWait).padding(horizontal = 14.dp)
                    .semantics { contentDescription = if (sent) "Wait request sent" else "Ask ${friend.displayName} to wait" },
                contentAlignment = Alignment.Center,
            ) { Text(if (sent) "SENT ✓" else "WAIT", style = sb(15), color = if (sent) Sb.Red else Color.White) }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Sb.LightGrey)
                    .clickable(enabled = friend.hasPosition, onClick = onShowOnMap)
                    .alpha(if (friend.hasPosition) 1f else 0.4f)
                    .semantics { contentDescription = "Show ${friend.displayName} on the map" },
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(com.bgdeveloper.ridelirium.R.drawable.ic_pin), null, tint = Color.Unspecified, modifier = Modifier.size(20.dp)) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Sb.Divider))
    }
}

@Composable
private fun RiderChip(rider: Friend, invited: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.widthIn(min = 120.dp).height(52.dp).clip(RoundedCornerShape(24.dp)).background(Sb.LightGrey)
            .clickable(enabled = !invited, onClick = onClick).padding(horizontal = 10.dp)
            .semantics { contentDescription = if (invited) "${rider.displayName} invited" else "Invite ${rider.displayName}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Avatar(rider, 32.dp)
        Column(Modifier.weight(1f, fill = false)) {
            Text(rider.displayName.uppercase(), style = sb(15), color = Sb.Charcoal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (invited) Text("INVITED", style = sb(10), color = Sb.Red)
        }
        if (!invited) Text("+", style = sb(20), color = Sb.Red)
    }
}

/** The invite: tap to show the big code, or the share icon to send the link. */
@Composable
private fun CodeCard(code: String?, url: String?, onClick: () -> Unit, onShare: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Sb.LightGrey).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier.weight(1f).clickable(onClick = onClick).semantics { contentDescription = "Invite code, tap to enlarge" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(8.dp), contentAlignment = Alignment.Center) {
                if (url != null) {
                    val qr = remember(url) { qrBitmap(url) }
                    Image(qr.asImageBitmap(), null, Modifier.fillMaxWidth())
                } else CircularProgressIndicator(Modifier.size(24.dp), color = Sb.Red, strokeWidth = 2.dp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("INVITE · TAP TO SHOW OR SHARE", style = sb(12), color = Sb.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(code?.let(::formatCode) ?: "—", style = sb(26, tracking = 0.08f, tabular = true), color = Sb.Charcoal, maxLines = 1)
            }
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Color.White).clickable(onClick = onShare)
                .semantics { contentDescription = "Share crew link" },
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(com.bgdeveloper.ridelirium.R.drawable.ic_share), null, tint = Sb.Charcoal, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun ShareTab(url: String?, code: String?, joinBanner: String?, failed: Boolean, onRetry: () -> Unit, onShare: () -> Unit) {
    if (url == null || code == null) {
        if (failed) {
            TextButton(onClick = onRetry) { Text("TRY AGAIN", style = sb(18, FontWeight.ExtraBold), color = Sb.Red) }
        } else {
            Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Sb.Red, strokeWidth = 2.dp)
                Text("Getting your code…", style = sb(18), color = Sb.Secondary)
            }
        }
        return
    }
    val qr = remember(url) { qrBitmap(url) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.clip(RoundedCornerShape(28.dp)).border(1.5.dp, Color(0xFFEAEAEA), RoundedCornerShape(28.dp)).background(Color.White).padding(22.dp)) {
            Image(qr.asImageBitmap(), contentDescription = "Invite QR code", Modifier.size(228.dp))
        }
        Text(formatCode(code), style = sb(38, FontWeight.ExtraBold, tracking = 0.1f, tabular = true), color = Sb.Charcoal)
        Text(
            "Friends scan with their camera or in RiDelirium, or open the invite link you send them. They appear in the crew as soon as they join.",
            style = sb(16, FontWeight.SemiBold), color = Sb.Secondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )
        if (joinBanner != null) {
            Row(
                Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFFEAF6EE)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(StatusGreen))
                Text("${joinBanner.uppercase()} JUST JOINED", style = sb(15), color = Color(0xFF1F7A3A))
            }
        }
    }
    Box(
        Modifier.fillMaxWidth().height(58.dp).clip(CircleShape).background(Sb.Red).clickable(onClick = onShare),
        contentAlignment = Alignment.Center,
    ) { Text("SHARE CREW LINK", style = sb(19), color = Color.White) }
}

@Composable
private fun JoinTab(isJoining: Boolean, onCode: (String) -> Unit) {
    val context = LocalContext.current
    var typed by remember { mutableStateOf("") }
    Text("Scan the QR code on your friend's screen, open their invite link, or type their 8-character code.", style = sb(17, FontWeight.SemiBold), color = Sb.Secondary)
    BigButton(if (isJoining) "JOINING…" else "SCAN QR CODE", Sb.Red, Color.White) {
        if (isJoining) return@BigButton
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let(onCode) }
    }
    Text("ENTER CODE", style = sb(13, tracking = 0.08f), color = Sb.Secondary)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Field(typed, { typed = autoDash(it) }, "XXXX-XXXX", Modifier.weight(1f))
        Box(
            Modifier.height(56.dp).clip(RoundedCornerShape(20.dp)).background(if (typed.isBlank() || isJoining) Sb.Secondary else Sb.Red)
                .clickable(enabled = typed.isNotBlank() && !isJoining) { onCode(typed); typed = "" }.padding(horizontal = 26.dp),
            contentAlignment = Alignment.Center,
        ) { Text("JOIN", style = sb(20), color = Color.White) }
    }
}


// region New crew and join

@Composable
private fun NewCrewStep(defaultName: String, error: String?, onBack: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BackTitle("NEW CREW", onBack)
        Text("Give it a name your friends will recognise. You can change it later.", style = sb(17, FontWeight.SemiBold), color = Sb.Secondary)
        Field(name, { name = it.take(30) }, "Crew name", Modifier.fillMaxWidth())
        BigButton("CREATE", if (name.isBlank()) Sb.Secondary else Sb.Red, Color.White) { if (name.isNotBlank()) onCreate(name) }
        error?.let { Text(it, style = sb(14, FontWeight.SemiBold), color = Sb.Red) }
    }
}

/** What the code opens, and how long to stay. */
@Composable
private fun PreviewStep(preview: CrewPreview, isJoining: Boolean, error: String?, onJoin: (ZonedDateTime) -> Unit, onCancel: () -> Unit) {
    var end by remember(preview.code) { mutableStateOf(Stay.defaultEnd()) }
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BackTitle("JOIN A CREW", onCancel)
        Text(preview.name.uppercase(), style = sb(32, FontWeight.ExtraBold), color = Sb.Charcoal)
        Text(
            "${preview.ownerName} · ${preview.memberCount} ${if (preview.memberCount == 1) "rider" else "riders"}",
            style = sb(17, FontWeight.SemiBold), color = Sb.Secondary,
        )
        if (preview.isMember) {
            Text("You are already in this crew. Joining again changes how long you stay.", style = sb(15, FontWeight.SemiBold), color = Sb.Secondary)
        }
        Box(Modifier.padding(top = 4.dp)) { StayPicker(end) { end = it } }
        BigButton(if (isJoining) "JOINING…" else "JOIN · UNTIL ${Stay.clock(end)}", Sb.Red, Color.White) { if (!isJoining) onJoin(end) }
        error?.let { Text(it, style = sb(14, FontWeight.SemiBold), color = Sb.Red) }
    }
}

@Composable
private fun JoinedScreen(crew: Crew, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("YOU'RE RIDING WITH", style = sb(16, tracking = 0.08f), color = Sb.Secondary)
        Text(crew.name.uppercase(), style = sb(32, FontWeight.ExtraBold), color = Sb.Charcoal, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(
            listOfNotNull("${crew.members.size} riders", timeLeftLabel(crew.myExpiresAtMs)?.lowercase()).joinToString(" · "),
            style = sb(16, FontWeight.SemiBold), color = Sb.Secondary,
        )
        Box(Modifier.padding(top = 12.dp)) { BigButton("LET'S RIDE", Sb.Red, Color.White, onDone) }
    }
}

// endregion


/** "w858b7ba" -> "W858-B7BA": letters and digits only, a dash after the fourth, eight at most. */
private fun autoDash(text: String): String {
    val chars = text.uppercase().filter { it.isLetterOrDigit() }.take(8)
    return if (chars.length > 4) "${chars.take(4)}-${chars.drop(4)}" else chars
}

// endregion

/** No server configured, or it can't be reached: nothing here works without it. */
@Composable
private fun OfflineStep(onRetry: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("RIDE TOGETHER", style = sb(32, FontWeight.ExtraBold), color = Sb.Charcoal)
        Text("WE ARE OFFLINE", style = sb(20), color = Sb.Red, modifier = Modifier.padding(top = 12.dp))
        Text(
            "Check your signal. Your crews come back as soon as you are connected.",
            style = sb(17, FontWeight.SemiBold), color = Sb.Secondary,
        )
        BigButton("TRY AGAIN", Sb.Red, Color.White, onRetry)
    }
}

@Composable
private fun NameStep(onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("YOUR NAME", style = sb(32, FontWeight.ExtraBold), color = Sb.Charcoal)
        Text(
            "This is what riders see when they join your code. It isn't stored anywhere but on your phone.",
            style = sb(17, FontWeight.SemiBold), color = Sb.Secondary,
        )
        Field(name, { name = it.take(30) }, "Your name", Modifier.fillMaxWidth())
        BigButton("CONTINUE", if (name.isBlank()) Sb.Secondary else Sb.Red, Color.White) { if (name.isNotBlank()) onSave(name) }
    }
}

@Composable
fun Avatar(friend: Friend, size: androidx.compose.ui.unit.Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(friend.avatarColor)),
        contentAlignment = Alignment.Center,
    ) { Text(friend.initials, style = sb(15, FontWeight.ExtraBold), color = Color.White) }
}

private fun qrBitmap(text: String): Bitmap {
    val size = 512
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) for (y in 0 until size) {
        bitmap.setPixel(x, y, if (matrix[x, y]) AndroidColor.rgb(0x35, 0x35, 0x35) else AndroidColor.WHITE)
    }
    return bitmap
}

/** "ABCD2345" -> "ABCD-2345". */
private fun formatCode(code: String) = if (code.length == 8) "${code.take(4)}-${code.takeLast(4)}" else code
