package com.bgdeveloper.ridelirium.ui.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bgdeveloper.ridelirium.friends.Stay
import com.bgdeveloper.ridelirium.ui.theme.Sb
import com.bgdeveloper.ridelirium.ui.theme.sb
import kotlinx.coroutines.delay
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

private val RowHeight = 38.dp

/** Now, ticking over each minute so "3 H 33 MIN" stays true. */
@Composable
fun rememberMinuteClock(): ZonedDateTime {
    val now by produceState(ZonedDateTime.now()) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            value = ZonedDateTime.now()
        }
    }
    return now
}

/** Picks the exact minute sharing stops: day, hour and minute wheels, with 2 H, 4 H and TONIGHT as quick picks. */
@Composable
fun StayPicker(end: ZonedDateTime, onEnd: (ZonedDateTime) -> Unit) {
    val now = rememberMinuteClock()
    val today = now.toLocalDate()
    val dayIndex = ChronoUnit.DAYS.between(today, end.toLocalDate()).toInt().coerceIn(0, Stay.MAX_DAYS.toInt())
    val days = remember(today) { (0..Stay.MAX_DAYS.toInt()).map { Stay.day(now.plusDays(it.toLong()), now) } }
    val hours = remember { (0..23).map { "%02d".format(it) } }
    val minutes = remember { (0..59).map { "%02d".format(it) } }
    fun pick(day: Int = dayIndex, hour: Int = end.hour, minute: Int = end.minute) =
        onEnd(Stay.clamp(today.plusDays(day.toLong()).atTime(hour, minute).atZone(now.zone), ZonedDateTime.now()))

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SHARE MY LOCATION UNTIL", style = sb(13, tracking = 0.08f), color = Sb.Secondary)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(Stay.clock(end), style = sb(54, FontWeight.ExtraBold, tabular = true), color = Sb.Charcoal)
            Text(
                "${Stay.day(end, now)} · ${Stay.duration(System.currentTimeMillis(), end.toInstant().toEpochMilli())}",
                style = sb(15, tracking = 0.04f), color = Sb.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Sb.LightGrey).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Box(Modifier.align(Alignment.Center).fillMaxWidth().height(RowHeight).clip(RoundedCornerShape(10.dp)).background(Color.White))
            Row(Modifier.fillMaxWidth()) {
                Wheel(days, dayIndex, "Day", Modifier.weight(1.6f)) { pick(day = it) }
                Wheel(hours, end.hour, "Hour", Modifier.weight(1f)) { pick(hour = it) }
                Wheel(minutes, end.minute, "Minute", Modifier.weight(1f)) { pick(minute = it) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stay.Preset.entries.forEach { preset ->
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp))
                        .border(1.5.dp, Color(0xFFE4E4E4), RoundedCornerShape(22.dp))
                        .clickable { onEnd(preset.until(ZonedDateTime.now())) }
                        .semantics { contentDescription = if (preset == Stay.Preset.TONIGHT) "Until tonight" else "For ${preset.title.lowercase()}" },
                    contentAlignment = Alignment.Center,
                ) { Text(preset.title, style = sb(15, FontWeight.ExtraBold), color = Sb.Charcoal) }
            }
        }
    }
}

/** One spinning column of five rows; the middle row is the pick. */
@Composable
private fun Wheel(items: List<String>, selected: Int, label: String, modifier: Modifier, onSelected: (Int) -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selected)
    val latestSelected by rememberUpdatedState(selected)
    val latestOnSelected by rememberUpdatedState(onSelected)
    val centered by remember { derivedStateOf { state.centeredIndex() } }

    // A pick from outside (a quick pick, or a clamp) spins the wheel to it.
    LaunchedEffect(selected) {
        if (!state.isScrollInProgress && state.centeredIndex() != selected) state.animateScrollToItem(selected)
    }
    // When the wheel settles, report it; if the picker clamps it, spin to where it landed.
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (scrolling) return@collect
            val index = state.centeredIndex()
            if (index != latestSelected) latestOnSelected(index)
            withFrameNanos { }
            if (state.centeredIndex() != latestSelected) state.animateScrollToItem(latestSelected)
        }
    }

    LazyColumn(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(vertical = RowHeight * 2),
        modifier = modifier.height(RowHeight * 5).semantics { contentDescription = "$label ${items.getOrNull(selected) ?: ""}" },
    ) {
        items(items.size) { index ->
            val distance = abs(index - centered)
            Box(Modifier.fillMaxWidth().height(RowHeight), contentAlignment = Alignment.Center) {
                Text(
                    items[index],
                    style = sb(if (distance == 0) 24 else if (distance == 1) 20 else 17, FontWeight.ExtraBold, tabular = true),
                    color = Sb.Charcoal, maxLines = 1,
                    modifier = Modifier.alpha(if (distance == 0) 1f else if (distance == 1) 0.45f else 0.2f),
                )
            }
        }
    }
}

/** The row sitting in the middle band. */
private fun LazyListState.centeredIndex(): Int {
    val info = layoutInfo
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index ?: firstVisibleItemIndex
}
