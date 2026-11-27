package com.bgdeveloper.ridelirium.ui.track

import androidx.compose.ui.platform.LocalView
import com.bgdeveloper.ridelirium.ui.successHaptic
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bgdeveloper.ridelirium.friends.MeetKind
import com.bgdeveloper.ridelirium.friends.MeetPoint
import com.bgdeveloper.ridelirium.ui.theme.Sb
import com.bgdeveloper.ridelirium.ui.theme.sb

/** The crew's meet point on the map: what it is and when, above a red dot on the spot. */
@Composable
fun MeetPin(meet: MeetPoint, now: Long) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics { contentDescription = "Meet point, ${meet.kind.place.lowercase()}" }) {
        Column(
            Modifier.clip(RoundedCornerShape(14.dp)).background(Sb.Red).padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("MEET HERE", style = sb(10, tracking = 0.08f), color = Color.White.copy(alpha = 0.85f))
            Text("${meet.kind.title} · ${meet.whenLabel(now)}", style = sb(15, FontWeight.ExtraBold), color = Color.White)
        }
        Canvas(Modifier.width(16.dp).height(9.dp)) {
            drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close() }, Sb.Red)
        }
        Box(Modifier.padding(top = 2.dp).size(14.dp).clip(CircleShape).background(Color.White).padding(3.dp).clip(CircleShape).background(Sb.Red))
    }
}

/** Where and when the crew meets; tap to see it on the map, ✕ to hide the banner on this phone. */
@Composable
fun MeetBanner(meet: MeetPoint, distance: String?, setByMe: Boolean, now: Long, onTap: () -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    val by = if (setByMe) "YOU" else meet.byName?.uppercase() ?: "YOUR CREW"
    Row(
        modifier.fillMaxWidth()
            .shadow(9.dp, shape, ambientColor = Color.Black.copy(0.35f), spotColor = Color.Black.copy(0.35f))
            .clip(shape).background(Color.White.copy(alpha = 0.96f)).clickable(onClick = onTap)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Sb.Red), contentAlignment = Alignment.Center) {
            Box(Modifier.size(14.dp).border(3.dp, Color.White, CircleShape))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("MEET ${meet.kind.place} · ${meet.whenLabel(now)}", style = sb(17, FontWeight.ExtraBold), color = Sb.Charcoal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(distance, "SET BY $by").joinToString(" · "), style = sb(13), color = Sb.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onHide).semantics { contentDescription = "Hide" },
            contentAlignment = Alignment.Center,
        ) { Text("✕", style = sb(16), color = Sb.Secondary) }
    }
}

/** After a long press on the map: what is at the spot and when to meet, then send it to the crew. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetPointSheet(crewName: String, onDismiss: () -> Unit, onSend: (MeetKind, Int, (Boolean) -> Unit) -> Unit) {
    var kind by remember { mutableStateOf(MeetKind.LIFT) }
    var minutes by remember { mutableIntStateOf(10) }
    var sending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val view = LocalView.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color.White) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("MEET POINT", style = sb(28, FontWeight.ExtraBold), color = Sb.Charcoal)
            Text("Everyone in $crewName sees it on their map and gets a notification.", style = sb(15, FontWeight.SemiBold), color = Sb.Secondary)
            Text("WHAT'S HERE", style = sb(13, tracking = 0.08f), color = Sb.Secondary, modifier = Modifier.padding(top = 4.dp))
            Choices(MeetKind.entries.map { it.title to it }, kind) { kind = it }
            Text("WHEN", style = sb(13, tracking = 0.08f), color = Sb.Secondary, modifier = Modifier.padding(top = 4.dp))
            Choices(listOf("NOW" to 0, "10 MIN" to 10, "30 MIN" to 30), minutes) { minutes = it }
            if (failed) Text("Couldn't reach the server. Try again.", style = sb(14, FontWeight.SemiBold), color = Sb.Red)
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().height(58.dp).clip(CircleShape).background(Sb.Red)
                    .clickable(enabled = !sending) {
                        sending = true
                        failed = false
                        onSend(kind, minutes) { ok ->
                            sending = false
                            failed = !ok
                            if (ok) view.successHaptic()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Text(if (sending) "SENDING…" else "SEND TO ${crewName.uppercase()}", style = sb(19), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp)) }
        }
    }
}

@Composable
private fun <T> Choices(options: List<Pair<String, T>>, selection: T, onSelect: (T) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().clip(shape).border(1.5.dp, Sb.Charcoal, shape)) {
        options.forEach { (title, value) ->
            val selected = value == selection
            Box(
                Modifier.weight(1f).height(46.dp).background(if (selected) Sb.Charcoal else Color.White).clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) { Text(title, style = sb(15), color = if (selected) Color.White else Sb.Charcoal, maxLines = 1) }
        }
    }
}
