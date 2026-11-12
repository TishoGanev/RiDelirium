package com.bgdeveloper.snowman.ui.friends

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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bgdeveloper.snowman.friends.Friend
import com.bgdeveloper.snowman.ui.BigButton
import com.bgdeveloper.snowman.ui.Field
import com.bgdeveloper.snowman.ui.theme.Sb
import com.bgdeveloper.snowman.ui.theme.sb
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

private enum class Mode { MY_CODE, SCAN }

/** Show your code, scan a friend's, and see who you ride with. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideTogetherSheet(onDismiss: () -> Unit, viewModel: FriendsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(Mode.MY_CODE) }
    var confirmLeave by remember { mutableStateOf(false) }

    LaunchedEffect(ui.me) { if (ui.me != null) viewModel.loadInvite() }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("RIDE TOGETHER", style = sb(32, FontWeight.ExtraBold), color = Sb.Charcoal)

            when {
                !viewModel.isConfigured -> Text(
                    "Ride Together isn't set up yet. Add API_BASE_URL to local.properties.",
                    style = sb(17, FontWeight.SemiBold), color = Sb.Secondary,
                )
                ui.me == null -> NamePrompt(onSave = viewModel::setName)
                else -> {
                    ui.justJoined?.let { Joined(it) }
                    if (ui.friends.isNotEmpty()) Crew(ui.friends, onWait = viewModel::askToWait, onLeave = { confirmLeave = true })

                    Row(Modifier.fillMaxWidth().border(1.5.dp, Sb.Charcoal, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))) {
                        ModeOption("MY CODE", mode == Mode.MY_CODE, Modifier.weight(1f)) { mode = Mode.MY_CODE }
                        ModeOption("SCAN A CODE", mode == Mode.SCAN, Modifier.weight(1f)) { mode = Mode.SCAN }
                    }
                    when (mode) {
                        Mode.MY_CODE -> MyCode(ui.invite?.url, ui.invite?.code, failed = ui.joinError != null, onRetry = viewModel::loadInvite, onShared = onDismiss)
                        Mode.SCAN -> Scan(ui.isJoining, onCode = viewModel::join)
                    }
                }
            }

            ui.joinError?.let { Text(it, style = sb(14, FontWeight.SemiBold), color = Sb.Red) }
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
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("CANCEL", style = sb(17), color = Sb.Charcoal) } },
        )
    }
}

@Composable
private fun NamePrompt(onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Text(
        "Choose the name your friends will see. It's saved on this phone and shared only with riders who join your code.",
        style = sb(17, FontWeight.SemiBold), color = Sb.Secondary,
    )
    Field(name, { name = it.take(30) }, "Your name", Modifier.fillMaxWidth())
    BigButton("CONTINUE", if (name.isBlank()) Sb.Secondary else Sb.Red, Color.White) { if (name.isNotBlank()) onSave(name) }
}

@Composable
private fun Joined(friend: Friend) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Sb.LightGrey).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(friend)
        Text("YOU'RE RIDING WITH ${friend.displayName.uppercase()}", style = sb(20), color = Sb.Charcoal, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Crew(friends: List<Friend>, onWait: (String, (Boolean) -> Unit) -> Unit, onLeave: () -> Unit) {
    val context = LocalContext.current
    Column {
        friends.forEach { friend ->
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(friend)
                Text(friend.displayName, style = sb(18), color = Sb.Charcoal, modifier = Modifier.weight(1f))
                Text(if (friend.isRiding) "RIDING" else "OFFLINE", style = sb(13, tracking = 0.04f), color = if (friend.isRiding) Sb.Red else Sb.Secondary)
                if (friend.isRiding) {
                    TextButton(onClick = {
                        onWait(friend.id) { ok ->
                            Toast.makeText(context, if (ok) "${friend.displayName} was asked to wait" else "Couldn't reach the server", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("WAIT FOR ME", style = sb(13), color = Sb.Charcoal) }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Sb.Divider))
        }
        TextButton(onClick = onLeave, modifier = Modifier.padding(top = 4.dp)) { Text("LEAVE CREW", style = sb(15), color = Sb.Red) }
    }
}

@Composable
private fun MyCode(url: String?, code: String?, failed: Boolean, onRetry: () -> Unit, onShared: () -> Unit) {
    val context = LocalContext.current
    if (url == null || code == null) {
        if (failed) {
            TextButton(onClick = onRetry) { Text("TRY AGAIN", style = sb(18, FontWeight.ExtraBold), color = Sb.Red) }
            return
        }
        Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp), color = Sb.Red, strokeWidth = 2.dp)
            Text("Getting your code…", style = sb(18), color = Sb.Secondary)
        }
        return
    }
    val qr = remember(url) { qrBitmap(url) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(qr.asImageBitmap(), contentDescription = "Invite QR code", Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)))
        Text(formatCode(code), style = sb(34, FontWeight.ExtraBold, tabular = true), color = Sb.Charcoal)
        Text(
            "Let a friend scan this with their camera or in the app, or send them the code.",
            style = sb(15, FontWeight.SemiBold), color = Sb.Secondary,
        )
    }
    BigButton("SHARE INVITE", Sb.Red, Color.White) {
        val text = "Ride with me on RiDelirium. Tap the link to join my crew, or enter the code ${formatCode(code)}.\n$url"
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
        // The invite is on its way to Mail, Viber, Messages...; this screen has done its job.
        onShared()
    }
}

@Composable
private fun Scan(isJoining: Boolean, onCode: (String) -> Unit) {
    val context = LocalContext.current
    var typed by remember { mutableStateOf("") }
    Text("Scan the QR code on your friend's screen, or type their 8-character code.", style = sb(17, FontWeight.SemiBold), color = Sb.Secondary)
    BigButton(if (isJoining) "JOINING…" else "SCAN QR CODE", Sb.Red, Color.White) {
        if (isJoining) return@BigButton
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let(onCode) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Field(typed, { typed = it.uppercase() }, "Type a code", Modifier.weight(1f))
        TextButton(onClick = { onCode(typed); typed = "" }, enabled = typed.isNotBlank() && !isJoining) {
            Text("JOIN", style = sb(18), color = Sb.Red)
        }
    }
}

@Composable
fun Avatar(friend: Friend, size: androidx.compose.ui.unit.Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(friend.avatarColor)),
        contentAlignment = Alignment.Center,
    ) { Text(friend.initials, style = sb(15, FontWeight.ExtraBold), color = Color.White) }
}

@Composable
private fun ModeOption(title: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(44.dp).background(if (selected) Sb.Charcoal else Color.White).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(title, style = sb(15), color = if (selected) Color.White else Sb.Charcoal) }
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
