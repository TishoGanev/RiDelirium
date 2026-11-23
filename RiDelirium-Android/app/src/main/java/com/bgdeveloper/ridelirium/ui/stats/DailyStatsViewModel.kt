package com.bgdeveloper.ridelirium.ui.stats

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bgdeveloper.ridelirium.data.PlaceNameResolver
import com.bgdeveloper.ridelirium.data.RideRepository
import com.bgdeveloper.ridelirium.data.SessionMetadataStore
import com.bgdeveloper.ridelirium.domain.TrackCsv
import com.bgdeveloper.ridelirium.domain.RideAnalysis
import com.bgdeveloper.ridelirium.domain.RideReplay
import com.bgdeveloper.ridelirium.domain.SessionSummary
import com.bgdeveloper.ridelirium.data.SettingsRepository
import com.bgdeveloper.ridelirium.domain.Units
import com.bgdeveloper.ridelirium.share.ShareCardData
import com.bgdeveloper.ridelirium.share.ShareCardFormat
import com.bgdeveloper.ridelirium.share.ShareCardRenderer
import com.bgdeveloper.ridelirium.share.ShareFiles
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/** One-shot UI events: the screen reacts and then calls [DailyStatsViewModel.consumeShare]. */
sealed interface ShareRequest {
    /** [feed] is the 4:5 ride card for the share sheet. */
    data class Card(val feed: Uri, val text: String) : ShareRequest
    data class Csv(val file: Uri) : ShareRequest
}

/** Playback position and settings; the route itself lives in [DailyUiState.replayModel]. */
data class ReplayState(
    val active: Boolean = false,
    val playing: Boolean = false,
    val time: Double = 0.0,
    val speed: Int = 1,
)

data class DailyUiState(
    val analysis: RideAnalysis = RideAnalysis.EMPTY,
    val replay: ReplayState = ReplayState(),
    val replayModel: RideReplay? = null,
    val session: SessionSummary? = null,
    val placeName: String = "",
    val units: Units = Units(),
    val isPreparingShare: Boolean = false,
    val shareRequest: ShareRequest? = null,
) {
    val kicker: String
        get() {
            val s = session ?: return ""
            val zone = ZoneId.systemDefault()
            val day = DateTimeFormatter.ofPattern("dd MMM", Locale.getDefault()).withZone(zone).format(Instant.ofEpochMilli(s.startMillis))
            val time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
            return "${placeName.uppercase()} · ${day.uppercase()} · ${time.format(Instant.ofEpochMilli(s.startMillis))}–${time.format(Instant.ofEpochMilli(s.endMillis))}"
        }
}

@HiltViewModel
class DailyStatsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
    repository: RideRepository,
    places: PlaceNameResolver,
    settings: SettingsRepository,
    private val sessionMetadata: SessionMetadataStore,
) : ViewModel() {
    private val sessionId: Int = checkNotNull(savedState["id"])
    private val transient = MutableStateFlow(DailyUiState())
    private val replayState = MutableStateFlow(ReplayState())
    private var replayJob: Job? = null
    private var cachedModel: RideReplay? = null
    private var cachedPoints = -1
    private var cachedAnalysis = RideAnalysis.EMPTY

    val uiState: StateFlow<DailyUiState> = combine(
        repository.observeSession(sessionId), places.names, transient, replayState, settings.units,
    ) { session, names, state, replay, units ->
        if (session != null && session.points.size != cachedPoints) {
            cachedModel = RideReplay(session.points)
            cachedAnalysis = session.analysis
            cachedPoints = session.points.size
        }
        state.copy(
            session = session,
            placeName = session?.let { names[it.placeKey] } ?: "Session $sessionId",
            replay = replay,
            units = units,
            replayModel = cachedModel,
            analysis = cachedAnalysis,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DailyUiState())

    private val summaryText: (DailyUiState) -> String = { ui ->
        val s = ui.session
        if (s == null) "" else {
            val date = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()).format(s.startDate)
            "${ui.placeName} · $date — ${ui.units.distance(s.distance)}, top speed ${ui.units.speed(s.maxSpeed)}. Tracked with RiDelirium."
        }
    }

    /** [mapSnapshots] come from the live map, one framed for each card; a missing one gives a plain card. */
    fun prepareShare(mapSnapshots: Map<ShareCardFormat, Bitmap?>) {
        val ui = uiState.value
        val session = ui.session ?: return
        transient.update { it.copy(isPreparingShare = true) }
        viewModelScope.launch {
            val data = ShareCardData(
                placeName = ui.placeName,
                date = DateTimeFormatter.ofPattern("dd MMM", Locale.getDefault()).format(session.startDate),
                distance = "%.1f".format(Locale.US, ui.units.distanceValue(session.distance)),
                distanceUnit = ui.units.distanceUnit,
                maxSpeed = ui.units.speed(session.maxSpeed),
            )
            val feed = withContext(Dispatchers.Default) {
                val card = ShareCardRenderer.render(context, mapSnapshots[ShareCardFormat.Feed], data, ShareCardFormat.Feed)
                ShareFiles.savePng(context, "ride-$sessionId-feed.png", card)
            }
            transient.update { it.copy(isPreparingShare = false, shareRequest = ShareRequest.Card(feed, summaryText(ui))) }
        }
    }

    fun export() {
        val ui = uiState.value
        val session = ui.session ?: return
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                ShareFiles.save(context, "RiDelirium-${session.startDate}-session-$sessionId.csv") { out ->
                    val writer = out.bufferedWriter()
                    writer.write(TrackCsv.csv(session.points, sessionMetadata.load(sessionId)))
                    writer.flush()
                }
            }
            transient.update { it.copy(shareRequest = ShareRequest.Csv(uri)) }
        }
    }

    // region Replay

    fun startReplay() {
        replayState.update { it.copy(active = true) }
        restartReplay()
    }

    fun closeReplay() {
        pauseReplay()
        replayState.update { it.copy(active = false, time = 0.0) }
    }

    fun restartReplay() {
        replayState.update { it.copy(time = 0.0) }
        playReplay()
    }

    fun togglePlay() = if (replayState.value.playing) pauseReplay() else playReplay()

    fun setReplaySpeed(speed: Int) = replayState.update { it.copy(speed = speed) }

    fun seekReplay(time: Double) {
        val duration = uiState.value.replayModel?.duration ?: return
        replayState.update { it.copy(time = time.coerceIn(0.0, duration)) }
    }

    fun playReplay() {
        val duration = uiState.value.replayModel?.takeUnless { it.isEmpty }?.duration ?: return
        if (replayState.value.time >= duration) replayState.update { it.copy(time = 0.0) }
        replayJob?.cancel()
        replayState.update { it.copy(playing = true) }
        replayJob = viewModelScope.launch {
            var last = System.nanoTime()
            while (true) {
                delay(50)
                val now = System.nanoTime()
                val dt = (now - last) / 1e9
                last = now
                val next = minOf(replayState.value.time + dt * replayState.value.speed, duration)
                replayState.update { it.copy(time = next) }
                if (next >= duration) break
            }
            replayState.update { it.copy(playing = false) }
        }
    }

    fun pauseReplay() {
        replayJob?.cancel()
        replayState.update { it.copy(playing = false) }
    }

    // endregion

    fun consumeShare() = transient.update { it.copy(shareRequest = null) }
}
