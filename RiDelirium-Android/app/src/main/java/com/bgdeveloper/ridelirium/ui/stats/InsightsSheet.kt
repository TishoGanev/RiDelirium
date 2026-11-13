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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bgdeveloper.ridelirium.domain.RideInsights
import com.bgdeveloper.ridelirium.ui.theme.Sb
import com.bgdeveloper.ridelirium.ui.theme.sb

/** What stood out in one ride: fastest run, time split, longest stop. */
@Composable
fun InsightsSheet(ui: DailyUiState, onDismiss: () -> Unit) {
    val session = ui.session ?: return
    val bullets = remember(ui.analysis, session, ui.units) { RideInsights.bullets(ui.analysis, session.points, ui.units) }
    BulletSheet(title = "INSIGHTS", bullets = bullets, onDismiss = onDismiss)
}

/** A bottom sheet with a title and the numbers behind it, one line each. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulletSheet(title: String, bullets: List<String>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(title, style = sb(24, FontWeight.ExtraBold), color = Sb.Charcoal)
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
