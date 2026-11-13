package com.bgdeveloper.ridelirium.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import com.bgdeveloper.ridelirium.domain.DayMetrics
import com.bgdeveloper.ridelirium.domain.PeriodInsights
import com.bgdeveloper.ridelirium.domain.StatsRange
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bgdeveloper.ridelirium.domain.SessionSummary
import com.bgdeveloper.ridelirium.ui.Chevron
import com.bgdeveloper.ridelirium.ui.HeroNumber
import com.bgdeveloper.ridelirium.ui.statusBarTop
import com.bgdeveloper.ridelirium.ui.theme.Sb
import com.bgdeveloper.ridelirium.ui.theme.sb
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun StatsScreen(onOpenDay: (Int) -> Unit, viewModel: StatsViewModel = hiltViewModel()) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<SessionSummary?>(null) }
    var showProgress by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Header(ui)
        RangeBar(ui, onRange = viewModel::setRange, onProgress = { showProgress = true })
        if (ui.sessions.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 110.dp), contentAlignment = Alignment.Center) {
                Text(if (ui.hasAnyRides) "NO RIDES IN THIS PERIOD" else "NO RIDES YET", style = sb(18, tracking = 0.04f), color = Sb.Secondary)
            }
        } else {
            RideList(ui, viewModel, onOpenDay, onDelete = { pendingDelete = it })
        }
    }

    if (showProgress) {
        val bullets = remember(ui.sessions, ui.units) { PeriodInsights.bullets(ui.sessions.map(DayMetrics::of), ui.units) }
        BulletSheet(title = "PROGRESS · ${ui.range.title}", bullets = bullets, onDismiss = { showProgress = false })
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this ride?") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(session); pendingDelete = null }) { Text("Delete Ride", color = Sb.Red) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(ui: StatsUiState) {
    val header = ui.header
    Column(
        Modifier.fillMaxWidth().background(Sb.Red)
            .padding(top = statusBarTop() + 16.dp, start = 20.dp, end = 20.dp, bottom = 22.dp),
    ) {
        Text(
            "${header.title} · ${header.days} ${if (header.days == 1) "DAY" else "DAYS"}",
            style = sb(18, tracking = 0.04f), color = Color.White,
        )
        HeroNumber(
            value = "%.0f".format(ui.units.distanceValue(header.distanceMeters)),
            captionLines = listOf("${ui.units.distanceUnit.uppercase()} RIDDEN", "${ui.units.speed(header.topSpeed)} TOP SPEED"),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Time filter chips, and the progress summary for the rides they leave in view. */
@Composable
private fun RangeBar(ui: StatsUiState, onRange: (StatsRange) -> Unit, onProgress: () -> Unit) {
    val enabled = ui.sessions.isNotEmpty()
    Column(Modifier.fillMaxWidth().background(Color.White).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatsRange.entries.forEach { option ->
                val selected = option == ui.range
                Box(
                    Modifier.height(36.dp).clip(CircleShape)
                        .background(if (selected) Sb.Red else Sb.LightGrey)
                        .clickable { onRange(option) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(option.title, style = sb(14, tracking = 0.04f), color = if (selected) Color.White else Sb.GreyButtonText)
                }
            }
        }
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp)
                .clip(RoundedCornerShape(16.dp)).background(Sb.LightGrey)
                .clickable(enabled = enabled, onClick = onProgress)
                .alpha(if (enabled) 1f else 0.4f)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("PROGRESS INSIGHTS", style = sb(16, tracking = 0.04f), color = Sb.Red, modifier = Modifier.weight(1f))
            Text("${ui.sessions.size} ${if (ui.sessions.size == 1) "RIDE" else "RIDES"}", style = sb(13), color = Sb.Red.copy(alpha = 0.7f))
            Chevron(color = Sb.Red, size = 10.dp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RideList(ui: StatsUiState, viewModel: StatsViewModel, onOpen: (Int) -> Unit, onDelete: (SessionSummary) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
        items(ui.sessions, key = { it.session }) { session ->
            LaunchedEffect(session.placeKey) { viewModel.resolvePlace(session) }
            val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = {
                if (it == SwipeToDismissBoxValue.EndToStart) onDelete(session)
                false // the row stays until the dialog confirms
            })
            SwipeToDismissBox(
                state = dismiss,
                enableDismissFromStartToEnd = false,
                backgroundContent = {
                    Box(Modifier.fillMaxSize().background(Sb.Red).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                        Text("DELETE", style = sb(16), color = Color.White)
                    }
                },
            ) {
                RideRow(session, ui, onClick = { onOpen(session.session) })
            }
        }
    }
}

@Composable
private fun RideRow(session: SessionSummary, ui: StatsUiState, onClick: () -> Unit) {
    val date = session.startDate
    Row(
        Modifier.fillMaxWidth().height(78.dp).background(Color.White).clickable(onClick = onClick)
            .padding(start = 18.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "%02d".format(date.dayOfMonth), style = sb(40, tabular = true), color = Sb.Charcoal,
            textAlign = TextAlign.End, modifier = Modifier.width(44.dp),
        )
        Column(Modifier.width(40.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(date.month.getDisplayName(TextStyle.SHORT, androidx.compose.ui.text.intl.Locale.current.platformLocale).uppercase(), style = sb(14), color = Sb.Red)
            Text("${date.year}", style = sb(13, FontWeight.SemiBold), color = Sb.Secondary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(ui.placeName(session), style = sb(18, lineHeight = 1.1f), color = Sb.Charcoal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${ui.units.distance(session.distance)} · ${ui.units.speed(session.maxSpeed)}",
                style = sb(14, FontWeight.SemiBold, lineHeight = 1.2f, tabular = true), color = Sb.Secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Chevron(Modifier.padding(end = 4.dp))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Sb.Divider))
}
