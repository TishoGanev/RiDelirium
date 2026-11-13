package com.bgdeveloper.ridelirium.ui.friends

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

private enum class Page { CREW, ADD }
private enum class Tab { SHARE, JOIN }
private enum class Look { RIDING, STOPPED, OFFLINE }

private const val LIVE_WINDOW_MS = 120_000L
private val StatusGreen = Color(0xFF30D158)
private val StatusAmber = Color(0xFFFFB020)
private val StatusGrey = Color(0xFFC9CDD3)
private val OfflineAvatar = Color(0xFF8C939D)
private val LightBorder = Color(0xFFE4E4E4)

private fun lookOf(friend: Friend, now: Long): Look = when {
    !friend.hasPosition -> Look.OFFLINE
    now - (friend.updatedAtMs ?: 0L) > LIVE_WINDOW_MS -> Look.OFFLINE
    (friend.speedKmh ?: 0.0) > 2.0 -> Look.RIDING
    else -> Look.STOPPED
}

/** Ride Together: opens on the crew list; sharing and joining are a second page inside the same sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideTogetherSheet(onDismiss: () -> Unit, viewModel: FriendsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val units by viewModel.units.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf(Page.CREW) }
    var tab by remember { mutableStateOf(Tab.SHARE) }
    var confirmLeave by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val view = LocalView.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); now = System.currentTimeMillis() } }
    val waitSent = remember { androidx.compose.runtime.mutableStateMapOf<String, Long>() }

    LaunchedEffect(ui.me) { if (ui.me != null) viewModel.loadInvite() }
    // The screen stays awake while the code is up, so it does not lock before the friend has scanned it.
    val showingCode = page == Page.ADD && tab == Tab.SHARE
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
    val crewName = ui.me?.let { "${it.displayName.uppercase()} CREW" } ?: "YOUR CREW"

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                ui.justJoined != null -> JoinedScreen(ui.justJoined!!, onDismiss)
                ui.me == null && viewModel.isConfigured -> NameStep(onSave = viewModel::setName)
                ui.offline && ui.invite == null && ui.friends.isEmpty() -> OfflineStep(onRetry = viewModel::loadInvite)
                ui.me == null -> NameStep(onSave = viewModel::setName)
                page == Page.CREW -> CrewPage(
                    crewName, ui.friends, ui.recentRiders, ui.invitedIds, ui.invite?.code, ui.invite?.url, units, now, waitSent,
                    metersTo = viewModel::metersTo, bearingTo = viewModel::bearingTo, onInvite = viewModel::invite,
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
                    onShowOnMap = viewModel::showOnMap,
                    onLeave = { confirmLeave = true },
                    onShare = { shareInvite(context, ui.invite?.code, ui.invite?.url, onDismiss) },
                    onEnlarge = { tab = Tab.SHARE; page = Page.ADD },
                    onJoin = { tab = Tab.JOIN; page = Page.ADD },
                )
                else -> AddPage(
                    title = if (ui.friends.isEmpty()) "ADD TO YOUR CREW" else "ADD TO $crewName",
                    tab = tab, onTab = { tab = it; viewModel.clearError() }, onBack = { page = Page.CREW },
                    code = ui.invite?.code, url = ui.invite?.url, isJoining = ui.isJoining, joinBanner = ui.joinBanner,
                    error = ui.joinError, onRetry = viewModel::loadInvite, onJoin = viewModel::join,
                    onShare = { shareInvite(context, ui.invite?.code, ui.invite?.url, onDismiss) },
                )
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave your crew?", style = sb(22)) },
            text = {
                Text(
                    "You'll stop riding with ${ui.friends.joinToString { it.displayName }}. You can join again with a code.",
                    style = sb(16, FontWeight.SemiBold),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.leave(); confirmLeave = false }) { Text("LEAVE", style = sb(17), color = Sb.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("STAY", style = sb(17), color = Sb.Charcoal) } },
        )
    }
}

private fun shareInvite(context: android.content.Context, code: String?, url: String?, onSent: () -> Unit) {
    if (code == null || url == null) return
    val text = "Ride with me on RiDelirium. Tap the link to join my crew, or enter the code ${formatCode(code)}.\n$url"
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    // The invite is on its way to Mail, Viber, Messages...; this screen has done its job.
    onSent()
}

private fun Modifier.pill(color: Color, height: Int = 40) = this.height(height.dp).clip(RoundedCornerShape(20.dp)).background(color)

// region Crew page

@Composable
private fun CrewPage(
    crewName: String, friends: List<Friend>, recent: List<Friend>, invited: Set<String>, code: String?, url: String?, units: Units, now: Long,
    waitSent: Map<String, Long>, metersTo: (Friend) -> Int?, bearingTo: (Friend) -> Float?, onInvite: (Friend) -> Unit, onWait: (Friend) -> Unit, onShowOnMap: (Friend) -> Unit,
    onLeave: () -> Unit, onShare: () -> Unit, onEnlarge: () -> Unit, onJoin: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (friends.isEmpty()) {
                Text("RIDE TOGETHER", style = sb(32, FontWeight.ExtraBold), color = Sb.Charcoal)
            } else {
                Text("RIDING WITH", style = sb(13, tracking = 0.08f), color = Sb.Secondary)
                Text(crewName, style = sb(32, FontWeight.ExtraBold), color = Sb.Charcoal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (friends.isNotEmpty()) {
            Box(
                Modifier.padding(start = 8.dp).height(40.dp).border(1.5.dp, LightBorder, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp)).clickable(onClick = onLeave).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text("LEAVE", style = sb(15), color = Sb.Red) }
        }
    }

    if (friends.isNotEmpty()) {
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
    }

    if (recent.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("RECENT RIDERS", style = sb(13, tracking = 0.08f), color = Sb.Secondary, modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                recent.forEach { RiderChip(it, it.id in invited) { onInvite(it) } }
            }
        }
    }

    CodeCard(code, url, onEnlarge)

    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.weight(1f).height(58.dp).clip(CircleShape).background(if (code == null) Sb.Secondary else Sb.Red)
                .clickable(enabled = code != null, onClick = onShare),
            contentAlignment = Alignment.Center,
        ) { Text("SHARE CREW", style = sb(19), color = Color.White) }
        Box(
            Modifier.width(140.dp).height(58.dp).clip(CircleShape).background(Sb.Charcoal).clickable(onClick = onJoin),
            contentAlignment = Alignment.Center,
        ) { Text("JOIN CREW", style = sb(19), color = Color.White) }
    }
}

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

@Composable
private fun CodeCard(code: String?, url: String?, onClick: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Sb.LightGrey)
            .clickable(onClick = onClick).padding(14.dp)
            .semantics { contentDescription = "Invite code, tap to enlarge" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(8.dp), contentAlignment = Alignment.Center) {
            if (url != null) {
                val qr = remember(url) { qrBitmap(url) }
                Image(qr.asImageBitmap(), null, Modifier.fillMaxWidth())
            } else CircularProgressIndicator(Modifier.size(24.dp), color = Sb.Red, strokeWidth = 2.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("INVITE CODE · TAP TO ENLARGE", style = sb(12), color = Sb.Secondary)
            Text(code?.let(::formatCode) ?: "—", style = sb(26, tracking = 0.08f, tabular = true), color = Sb.Charcoal, maxLines = 1)
        }
    }
}

// endregion

// region Add page

@Composable
private fun AddPage(
    title: String, tab: Tab, onTab: (Tab) -> Unit, onBack: () -> Unit, code: String?, url: String?,
    isJoining: Boolean, joinBanner: String?, error: String?, onRetry: () -> Unit, onJoin: (String) -> Unit, onShare: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
            Text(title, style = sb(20), color = Sb.Charcoal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 56.dp))
            Box(
                Modifier.align(Alignment.CenterStart).size(40.dp).clip(CircleShape).background(Sb.LightGrey)
                    .clickable(onClick = onBack).semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) { Chevron(color = Sb.Charcoal, pointsLeft = true) }
        }
        Row(Modifier.fillMaxWidth().border(1.5.dp, Sb.Charcoal, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))) {
            SegmentOption("SHARE CREW", tab == Tab.SHARE, Modifier.weight(1f)) { onTab(Tab.SHARE) }
            SegmentOption("JOIN CREW", tab == Tab.JOIN, Modifier.weight(1f)) { onTab(Tab.JOIN) }
        }
        when (tab) {
            Tab.SHARE -> ShareTab(url, code, joinBanner, failed = error != null, onRetry = onRetry, onShare = onShare)
            Tab.JOIN -> JoinTab(isJoining, onJoin)
        }
        error?.let { Text(it, style = sb(14, FontWeight.SemiBold), color = Sb.Red) }
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
            "Friends scan with their camera or in RiDelirium. They appear in your crew as soon as they join.",
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
    Text("Scan the QR code on your friend's screen, or type their 8-character code.", style = sb(17, FontWeight.SemiBold), color = Sb.Secondary)
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

/** "w858b7ba" -> "W858-B7BA": letters and digits only, a dash after the fourth, eight at most. */
private fun autoDash(text: String): String {
    val chars = text.uppercase().filter { it.isLetterOrDigit() }.take(8)
    return if (chars.length > 4) "${chars.take(4)}-${chars.drop(4)}" else chars
}

@Composable
private fun SegmentOption(title: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(44.dp).background(if (selected) Sb.Charcoal else Color.White).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(title, style = sb(15), color = if (selected) Color.White else Sb.Charcoal) }
}

// endregion

/** No server configured, or it can't be reached: nothing here works without it. */
@Composable
private fun OfflineStep(onRetry: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("RIDE TOGETHER", style = sb(32, FontWeight.ExtraBold), color = Sb.Charcoal)
        Text("WE ARE OFFLINE", style = sb(20), color = Sb.Red, modifier = Modifier.padding(top = 12.dp))
        Text(
            "Check your signal. Your crew and invite code come back as soon as you are connected.",
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
private fun JoinedScreen(friend: Friend, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Avatar(friend, 88.dp)
        Text("YOU'RE RIDING WITH", style = sb(16, tracking = 0.08f), color = Sb.Secondary)
        Text(friend.displayName.uppercase(), style = sb(32, FontWeight.ExtraBold), color = Sb.Charcoal, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Box(Modifier.padding(top = 12.dp)) { BigButton("LET'S RIDE", Sb.Red, Color.White, onDone) }
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
