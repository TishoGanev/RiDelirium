package com.bgdeveloper.ridelirium.ui.settings

import com.bgdeveloper.ridelirium.ui.LandscapeBar
import com.bgdeveloper.ridelirium.ui.LandscapeMetrics
import com.bgdeveloper.ridelirium.ui.isLandscape
import com.bgdeveloper.ridelirium.ui.landscapePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.bgdeveloper.ridelirium.friends.Friend
import com.bgdeveloper.ridelirium.friends.Sport
import com.bgdeveloper.ridelirium.domain.Units
import com.bgdeveloper.ridelirium.ui.track.RiderTag
import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bgdeveloper.ridelirium.push.CrewNotifications
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bgdeveloper.ridelirium.BuildConfig
import com.bgdeveloper.ridelirium.R
import com.bgdeveloper.ridelirium.ui.Chevron
import com.bgdeveloper.ridelirium.ui.SportGlyph
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.bgdeveloper.ridelirium.ui.statusBarTop
import com.bgdeveloper.ridelirium.ui.theme.Sb
import com.bgdeveloper.ridelirium.ui.theme.sb

@Composable
fun SettingsScreen(onOpenAbout: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val metric by viewModel.metric.collectAsStateWithLifecycle()
    val name by viewModel.name.collectAsStateWithLifecycle()
    val initials by viewModel.initials.collectAsStateWithLifecycle()
    val autoStart by viewModel.autoStart.collectAsStateWithLifecycle()
    val autoStop by viewModel.autoStop.collectAsStateWithLifecycle()
    val stopMinutes by viewModel.stopMinutes.collectAsStateWithLifecycle()
    val sport by viewModel.sport.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    val deleting by viewModel.deleting.collectAsStateWithLifecycle()
    val deleteFailed by viewModel.deleteFailed.collectAsStateWithLifecycle()
    var confirmingDelete by remember { mutableStateOf(false) }

    // Re-read on every return from system settings, so the status is never stale.
    val context = LocalContext.current
    var notificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var testSent by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val openNotificationSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        // Android stops asking after a refusal or two; then only the system settings can turn it on.
        if (!granted) openNotificationSettings()
    }

    // Sideways: a small title, the same rows in one rounded card, and the tabs in the bottom bar (Landscape.kt).
    val landscape = isLandscape()
    val landscapePad = landscapePadding()
    Column(
        Modifier.fillMaxSize().background(if (landscape) Color(0xFFF4F2F0) else Color.White)
            .then(if (landscape) Modifier.padding(landscapePad) else Modifier),
    ) {
        if (landscape) {
            Box(Modifier.height(LandscapeMetrics.GearSize).padding(bottom = 4.dp), contentAlignment = Alignment.CenterStart) {
                Text("SETTINGS", style = sb(22, FontWeight.ExtraBold), color = Sb.Charcoal)
            }
        } else {
        Column(
            Modifier.fillMaxWidth().background(Sb.Red)
                .padding(top = statusBarTop() + 16.dp, start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            Text("SETTINGS", style = sb(40, FontWeight.ExtraBold, lineHeight = 1f), color = Color.White)
        }
        }

        // The rows scroll under the fixed header; the bottom padding clears the floating tab bar.
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .then(if (landscape) Modifier.clip(RoundedCornerShape(22.dp)).background(Color.White) else Modifier)
                .verticalScroll(rememberScrollState()).padding(bottom = if (landscape) 0.dp else 110.dp),
        ) {
        SettingsRow {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Sb.Charcoal), contentAlignment = Alignment.Center) {
                Text(initials, style = sb(14), color = Color.White)
            }
            Text(
                (name ?: "YOUR NAME").uppercase(), style = sb(18), color = Sb.Charcoal,
                maxLines = 1, modifier = Modifier.weight(1f),
            )
            Text(
                if (name == null) "ADD" else "EDIT", style = sb(15), color = Sb.Red,
                modifier = Modifier.clickable { editing = true }.padding(8.dp),
            )
        }
        SettingsRow {
            Text("UNITS", style = sb(18), color = Sb.Charcoal, modifier = Modifier.weight(1f))
            SegmentedControl(listOf("METRIC" to true, "IMPERIAL" to false), metric, viewModel::setMetric)
        }
        SportRow(sport, me, metric, viewModel::setSport)
        // Start and stop from motion activity. Recorded points are never deleted by it.
        SettingsRow {
            Text("START WHEN DRIVING", style = sb(18), color = Sb.Charcoal, modifier = Modifier.weight(1f))
            Switch(checked = autoStart, onCheckedChange = viewModel::setAutoStart, colors = SwitchDefaults.colors(checkedTrackColor = Sb.Red))
        }
        SettingsRow {
            Text("STOP WHEN IDLE", style = sb(18), color = Sb.Charcoal, modifier = Modifier.weight(1f))
            Switch(checked = autoStop, onCheckedChange = viewModel::setAutoStop, colors = SwitchDefaults.colors(checkedTrackColor = Sb.Red))
        }
        if (autoStop) {
            SettingsRow {
                Text("AFTER $stopMinutes MIN STANDING OR WALKING", style = sb(15), color = Sb.Charcoal, modifier = Modifier.weight(1f))
                StepButton("−") { viewModel.setStopMinutes(stopMinutes - 1) }
                StepButton("+") { viewModel.setStopMinutes(stopMinutes + 1) }
            }
        }
        // Push alerts (crew joined, wait for me) need all three: permission, the app not stopped, and no battery restriction.
        SettingsRow(onClick = {
            if (!notificationsOn && Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else openNotificationSettings()
        }) {
            Text("NOTIFICATIONS", style = sb(18), color = Sb.Charcoal, modifier = Modifier.weight(1f))
            Text(if (notificationsOn) "ON" else "OFF · FIX", style = sb(15), color = if (notificationsOn) Sb.Secondary else Sb.Red)
            Chevron(Modifier.padding(end = 4.dp), size = 10.dp)
        }
        SettingsRow(onClick = {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) {
            Text("BACKGROUND & BATTERY", style = sb(18), color = Sb.Charcoal, modifier = Modifier.weight(1f))
            Chevron(Modifier.padding(end = 4.dp), size = 10.dp)
        }
        SettingsRow(onClick = {
            CrewNotifications.show(context, mapOf("title" to "RiDelirium", "body" to "Test notification", "event" to "test"))
            testSent = true
        }) {
            Text(if (testSent) "TEST SENT · NOTHING SHOWN? TAP NOTIFICATIONS" else "SEND TEST NOTIFICATION", style = sb(15), color = Sb.Charcoal, modifier = Modifier.weight(1f))
        }
        SettingsRow(onClick = onOpenAbout) {
            Text("ABOUT RIDELIRIUM", style = sb(18), color = Sb.Charcoal, modifier = Modifier.weight(1f))
            Chevron(Modifier.padding(end = 4.dp), size = 10.dp)
        }
        // Play account deletion / App Store 5.1.1(v): what Ride Together keeps on the server can be deleted here.
        SettingsRow(onClick = if (deleting) null else ({ confirmingDelete = true })) {
            Text("DELETE MY RIDE TOGETHER DATA", style = sb(16), color = Sb.Red, maxLines = 2, modifier = Modifier.weight(1f))
            if (deleting) CircularProgressIndicator(Modifier.size(20.dp), color = Sb.Red, strokeWidth = 2.dp)
        }
        }
        if (landscape) {
            Box(Modifier.height(10.dp))
            LandscapeBar { Text("SETTINGS", style = sb(14), color = Sb.Secondary) }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete your Ride Together data?") },
            text = {
                Text(
                    "You leave every crew, and your name and crews are removed from the Ride Together server and from " +
                        "this phone. Your recorded rides stay. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; viewModel.deleteRideTogetherData() }) { Text("Delete", color = Sb.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
    if (deleteFailed) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteError,
            title = { Text("Couldn't delete your data") },
            text = { Text("We are offline. Check your signal and try again.") },
            confirmButton = { TextButton(onClick = viewModel::dismissDeleteError) { Text("OK") } },
        )
    }

    if (editing) {
        NameDialog(initial = name.orEmpty(), onDismiss = { editing = false }, onSave = { viewModel.setName(it); editing = false })
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Sb.LightGrey).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = sb(22), color = Sb.GreyButtonText) }
}

/** What the rider rides, and a preview of the tag friends see for it. */
@Composable
private fun SportRow(sport: Sport, me: Friend?, metric: Boolean, onSport: (Sport) -> Unit) {
    val now = System.currentTimeMillis()
    val preview = (me ?: Friend("preview", "You", "ME")).copy(isRiding = true, speedKmh = 24.0, updatedAtMs = now, sport = sport)
    Column {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("I RIDE", style = sb(18), color = Sb.Charcoal, modifier = Modifier.weight(1f))
                SportPicker(sport, onSport)
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Sb.MapBackground).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("How friends see you", style = sb(13, FontWeight.SemiBold), color = Color.White.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RiderTag(preview, Units(metric), compact = false, now = now, sport = sport)
                    Text(sport.activity, style = sb(11, tracking = 0.04f), color = Color.White.copy(alpha = 0.7f))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Sb.Divider))
    }
}

/** Four sports as pictograms only, so the row fits beside "I RIDE" without wrapping. */
@Composable
private fun SportPicker(selection: Sport, onSelect: (Sport) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.clip(shape).border(1.5.dp, Sb.Charcoal, shape)) {
        Sport.entries.forEach { sport ->
            val selected = sport == selection
            Box(
                Modifier
                    .width(52.dp).height(44.dp)
                    .background(if (selected) Sb.Charcoal else Color.White)
                    .clickable(role = Role.RadioButton) { onSelect(sport) }
                    .semantics { contentDescription = sport.title; this.selected = selected },
                contentAlignment = Alignment.Center,
            ) {
                SportGlyph(sport, if (selected) Color.White else Sb.Charcoal, Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun SettingsRow(onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().height(72.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(Sb.Divider))
    }
}

@Composable
private fun <T> SegmentedControl(options: List<Pair<String, T>>, selection: T, onSelect: (T) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.clip(shape).border(1.5.dp, Sb.Charcoal, shape)) {
        options.forEach { (title, value) ->
            val selected = value == selection
            Text(
                title, style = sb(15, lineHeight = 1f), color = if (selected) Color.White else Sb.Charcoal,
                modifier = Modifier
                    .background(if (selected) Sb.Charcoal else Color.White)
                    .clickable { onSelect(value) }
                    .padding(vertical = 12.dp, horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun NameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Riders who join your code see this name. It isn't stored anywhere but on your phone.")
                OutlinedTextField(
                    draft, { draft = it.take(30) }, singleLine = true, label = { Text("Name") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (draft.isNotBlank()) onSave(draft) }, enabled = draft.isNotBlank()) { Text("Save", color = Sb.Red) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Column(
            Modifier.fillMaxWidth().background(Sb.Red)
                .padding(top = statusBarTop() + 8.dp, start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            Row(
                Modifier.height(44.dp).clickable(onClick = onBack).padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chevron(color = Color.White, pointsLeft = true)
                Text("SETTINGS", style = sb(16), color = Color.White)
            }
            Text("ABOUT", style = sb(40, FontWeight.ExtraBold, lineHeight = 1f), color = Color.White, modifier = Modifier.padding(top = 8.dp))
        }
        Column(
            Modifier.fillMaxWidth().padding(top = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Image(
                painterResource(R.drawable.ic_launcher_art), contentDescription = null,
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)),
            )
            Text("RIDELIRIUM", style = sb(28, FontWeight.ExtraBold), color = Sb.Charcoal)
            Text(
                "VERSION ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = sb(13, tracking = 0.08f), color = Sb.Secondary,
            )
            Text(
                "Track your runs, speed and altitude on the mountain.",
                style = sb(16, FontWeight.SemiBold), color = Sb.Secondary,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 40.dp),
            )
        }
    }
}
