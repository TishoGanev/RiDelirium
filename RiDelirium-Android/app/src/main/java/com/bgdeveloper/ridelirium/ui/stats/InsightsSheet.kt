package com.bgdeveloper.ridelirium.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.launch
import com.bgdeveloper.ridelirium.ai.InsightChat
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
    val chat = remember(facts) { mutableStateListOf<ChatTurn>() }
    var asking by remember(facts) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun ask(text: String) {
        val question = InsightChat.clean(text) ?: return
        if (asking) return
        val history = chat.filter { it.answer != null }.map { InsightChat.Exchange(it.question, it.answer!!) }
        chat.add(ChatTurn(question))
        val index = chat.lastIndex
        asking = true
        scope.launch {
            val answer = AiInsights.ask(facts, question, history)
            chat[index] = chat[index].copy(answer = answer, failed = answer == null)
            asking = false
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.imePadding()) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
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
            if (InsightChat.ENABLED && result is AiResult.Ready) Conversation(facts, chat, onAsk = ::ask)
        }
        // Only where this phone can write the summary, so it can answer too.
        if (InsightChat.ENABLED && result is AiResult.Ready) QuestionBar(enabled = !asking, onAsk = ::ask)
        }
    }
}

private data class ChatTurn(val question: String, val answer: String? = null, val failed: Boolean = false)

/** Questions and answers, or a few questions to start from. */
@Composable
private fun Conversation(facts: InsightFacts, chat: List<ChatTurn>, onAsk: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ASK ABOUT THIS ${if (facts.isPeriod) "PERIOD" else "RIDE"}", style = sb(13, tracking = 0.04f), color = Sb.Secondary)
        if (chat.isEmpty()) {
            InsightChat.suggestions(facts).forEach { suggestion ->
                Text(
                    suggestion, style = sb(16, FontWeight.SemiBold), color = Sb.Charcoal,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Sb.LightGrey).clickable { onAsk(suggestion) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        chat.forEach { turn ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    turn.question, style = sb(16, FontWeight.SemiBold), color = Color.White,
                    modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(16.dp)).background(Sb.Charcoal)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
                when {
                    turn.answer != null -> Text(
                        turn.answer, style = sb(17, FontWeight.SemiBold), color = Sb.Charcoal,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Sb.LightGrey).padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                    turn.failed -> Text("Couldn't answer that. Try asking it another way.", style = sb(15, FontWeight.SemiBold), color = Sb.Secondary)
                    else -> CircularProgressIndicator(Modifier.size(18.dp), color = Sb.Red, strokeWidth = 2.dp)
                }
            }
        }
        Text("Answers come from your ride data, written on your phone.", style = sb(12), color = Sb.Secondary)
    }
}

@Composable
private fun QuestionBar(enabled: Boolean, onAsk: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    fun send() {
        if (!enabled || InsightChat.clean(draft) == null) return
        onAsk(draft)
        draft = ""
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f),
            placeholder = { Text("Ask a question") }, maxLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        TextButton(onClick = ::send, enabled = enabled && InsightChat.clean(draft) != null) {
            Text("ASK", style = sb(16), color = Sb.Red)
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
