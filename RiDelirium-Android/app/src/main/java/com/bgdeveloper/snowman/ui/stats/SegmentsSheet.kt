package com.bgdeveloper.snowman.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bgdeveloper.snowman.domain.Activity
import com.bgdeveloper.snowman.domain.Segment
import com.bgdeveloper.snowman.ui.theme.Sb
import com.bgdeveloper.snowman.ui.theme.sb
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private class SegmentRow(val segment: Segment, val title: String)

/** Every run, lift and stop of one ride, with a time breakdown on top. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentsSheet(ui: DailyUiState, onDismiss: () -> Unit) {
    val analysis = ui.analysis
    val session = ui.session ?: return
    val units = ui.units
    val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    // Numbers runs and lifts separately: RUN 1, LIFT 1, RUN 2…
    var runs = 0
    var lifts = 0
    val rows = analysis.segments.map {
        when (it.activity) {
            Activity.RUN -> SegmentRow(it, "RUN ${++runs}")
            Activity.LIFT -> SegmentRow(it, "LIFT ${++lifts}")
            Activity.STILL -> SegmentRow(it, "STOPPED")
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                "SEGMENTS", style = sb(24, FontWeight.ExtraBold), color = Sb.Charcoal,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Tile("RIDING", units.compactDuration(analysis.duration(Activity.RUN)), Sb.Red, Modifier.weight(1f))
                Tile("ON LIFT", units.compactDuration(analysis.duration(Activity.LIFT)), Sb.Lift, Modifier.weight(1f))
                Tile("STOPPED", units.compactDuration(analysis.duration(Activity.STILL)), Sb.Still, Modifier.weight(1f))
            }
            LazyColumn {
                items(rows) { row ->
                    val segment = row.segment
                    val start = Instant.ofEpochMilli(session.points[segment.startIndex].timestamp)
                    Row(
                        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(colorFor(segment.activity)))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(row.title, style = sb(18), color = Sb.Charcoal)
                            Text(
                                "${time.format(start)} · ${units.duration(segment.durationSeconds.toLong())}",
                                style = sb(14, FontWeight.SemiBold, tabular = true), color = Sb.Secondary,
                            )
                        }
                        if (segment.activity != Activity.STILL) {
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(units.distance(segment.distance), style = sb(16, tabular = true), color = Sb.Charcoal)
                                Text(
                                    if (segment.activity == Activity.RUN) {
                                        "↓ ${units.altitude(segment.vertical)} · ${units.speed(segment.maxSpeed)}"
                                    } else {
                                        "↑ ${units.altitude(segment.vertical)}"
                                    },
                                    style = sb(14, FontWeight.SemiBold, tabular = true), color = Sb.Secondary,
                                )
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Sb.Divider))
                }
            }
        }
    }
}

@Composable
private fun Tile(title: String, value: String, color: Color, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Sb.LightGrey).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(title, style = sb(13, tracking = 0.04f), color = Sb.Secondary)
        }
        Text(value, style = sb(24, FontWeight.ExtraBold, tabular = true), color = Sb.Charcoal)
    }
}

private fun colorFor(activity: Activity) = when (activity) {
    Activity.RUN -> Sb.Red
    Activity.LIFT -> Sb.Lift
    Activity.STILL -> Sb.Still
}
