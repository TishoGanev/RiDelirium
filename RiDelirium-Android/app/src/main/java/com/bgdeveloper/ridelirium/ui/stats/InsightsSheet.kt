package com.bgdeveloper.ridelirium.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bgdeveloper.ridelirium.ai.AiInsights
import com.bgdeveloper.ridelirium.ai.AiResult
import com.bgdeveloper.ridelirium.ai.AiSummary
import com.bgdeveloper.ridelirium.domain.InsightFacts
import com.bgdeveloper.ridelirium.domain.RideInsights
import com.bgdeveloper.ridelirium.ui.theme.Sb
import com.bgdeveloper.ridelirium.ui.theme.sb

/** What stood out in one ride: fastest run, time split, longest stop. */
@Composable
fun InsightsSheet(ui: DailyUiState, onDismiss: () -> Unit) {
    val session = ui.session ?: return
    val facts = remember(ui.analysis, session, ui.units, ui.placeName) {
        InsightFacts.ride(ui.analysis, session.points, ui.placeName, ui.units, session.sport)
    }
    BulletSheet(title = "INSIGHTS", facts = facts, onDismiss = onDismiss)
}

/**
 * A bottom sheet with a title, a written summary where this phone can write one (Gemini Nano, on the device),
 * and the numbers behind it, one line each. The numbers always show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulletSheet(title: String, facts: InsightFacts, onDismiss: () -> Unit) {
    val bullets = facts.bullets
    // null while writing; Unavailable and Failed leave the numbers alone.
    var result by remember(facts) { mutableStateOf<AiResult?>(null) }
    LaunchedEffect(facts) { result = AiInsights.generate(facts) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(title, style = sb(24, FontWeight.ExtraBold), color = Sb.Charcoal)
            when (val r = result) {
                null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Sb.Red, strokeWidth = 2.dp)
                    Text("WRITING YOUR SUMMARY", style = sb(13, tracking = 0.04f), color = Sb.Secondary)
                }
                is AiResult.Ready -> Summary(r.summary)
                AiResult.Unavailable, AiResult.Failed -> Unit
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("THE NUMBERS", style = sb(13, tracking = 0.04f), color = Sb.Secondary)
                bullets.forEach { bullet ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.padding(top = 8.dp).size(7.dp).clip(CircleShape).background(Sb.Red))
                        Text(bullet, style = sb(18, FontWeight.SemiBold), color = Sb.Charcoal)
                    }
                }
            }
        }
    }
}

/** The model's words: a headline, three observations and a tip. */
@Composable
private fun Summary(summary: AiSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("SUMMARY", style = sb(13, tracking = 0.04f), color = Sb.Secondary)
        Text(summary.headline, style = sb(20, FontWeight.ExtraBold), color = Sb.Charcoal)
        summary.observations.forEach { observation ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.padding(top = 8.dp).size(7.dp).clip(CircleShape).background(Sb.Red))
                Text(observation, style = sb(17, FontWeight.SemiBold), color = Sb.Charcoal)
            }
        }
        Text("TIP · ${summary.tip}", style = sb(16, FontWeight.SemiBold), color = Sb.Red)
        Text("Written on your phone", style = sb(12), color = Sb.Secondary)
    }
}
