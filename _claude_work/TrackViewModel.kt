package com.bgdeveloper.snowman.ui.track

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bgdeveloper.snowman.data.PlaceNameResolver
import com.bgdeveloper.snowman.data.RideRepository
import com.bgdeveloper.snowman.data.SettingsRepository
import com.bgdeveloper.snowman.data.Weather
import com.bgdeveloper.snowman.data.WeatherRepository
import com.bgdeveloper.snowman.domain.Season
import com.bgdeveloper.snowman.domain.SessionSummary
import com.bgdeveloper.snowman.domain.Units
import com.bgdeveloper.snowman.tracking.TrackingEngine
import com.bgdeveloper.snowman.tracking.TrackingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** The most recent ride, shown on the idle card. [track] is a thinned copy of the route for the thumbnail. */
data class LastRide(
    val session: Int,
    val startMillis: Long,
    val endMillis: Long,
    val distanceMeters: Double,
    val maxSpeed: Double,
    val placeKey: String,
    val track: List<Pair<Double, Double>>,
)

data class SeasonSoFar(val label: String, val days: Int, val distanceMeters: Double)

/** What the Track screen shows before a ride: where you are, the weather, your last ride and the season. */
data class IdleInfo(
    val placeName: String? = null,
    val weather: Weather? = null,
    val lastRide: LastRide? = null,
    val lastRidePlace: String? = null,
    val season: SeasonSoFar? = null,
)

data class TrackUiState(
    val tracking: TrackingState = TrackingState(),
    val follow: Boolean = false,
    val units: Units = Units(),
    val idle: IdleInfo = IdleInfo(),
)

private data class RideHistory(val last: LastRide?, val season: SeasonSoFar?)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TrackViewModel @Inject constructor(
    private val engine: TrackingEngine,
    settings: SettingsRepository,
    rides: RideRepository,
    private val places: PlaceNameResolver,
    private val weather: WeatherRepository,
) : ViewModel() {
    private val follow = MutableStateFlow(false)
    private val placeHere = MutableStateFlow<String?>(null)
    private var placeLookupAt: Location? = null

    // Sessions are only read before a ride: during one, every new point would recompute every ride.
    private val history = engine.state.map { it.isTracking }.distinctUntilChanged().flatMapLatest { tracking ->
        if (tracking) flowOf(null) else rides.observeSessions().map(::summarise).flowOn(Dispatchers.Default)
    }

    private val idle = combine(history, places.names, placeHere, weather.weather) { history, names, here, weather ->
        IdleInfo(
            placeName = here,
            weather = weather,
            lastRide = history?.last,
            lastRidePlace = history?.last?.let { names[it.placeKey] },
            season = history?.season,
        )
    }

    val uiState: StateFlow<TrackUiState> = combine(engine.state, follow, settings.units, idle) { tracking, follow, units, idle ->
        TrackUiState(tracking, follow, units, idle)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrackUiState())

    init {
        // Place name and weather follow the rider, but only look up again after a real move.
        viewModelScope.launch {
            engine.state.map { it.current }.collect { location ->
                if (location == null) return@collect
                weather.refresh(location.latitude, location.longitude, location.altitude.takeIf { location.hasAltitude() })
                val last = placeLookupAt
                if (last == null || last.distanceTo(location) > PLACE_MOVE_METERS) {
                    placeLookupAt = location
                    launch { places.nameAt(location.latitude, location.longitude)?.let { placeHere.value = it } }
                }
            }
        }
    }

    private fun summarise(sessions: List<SessionSummary>): RideHistory {
        val last = sessions.firstOrNull()
        last?.let(places::resolve)
        val season = Season.containing(LocalDate.now())
        val inSeason = sessions.filter { Season.containing(it.startDate) == season }
        return RideHistory(
            last = last?.let {
                val step = maxOf(1, it.points.size / THUMB_POINTS)
                LastRide(
                    session = it.session,
                    startMillis = it.startMillis,
                    endMillis = it.endMillis,
                    distanceMeters = it.distance,
                    maxSpeed = it.maxSpeed,
                    placeKey = it.placeKey,
                    track = it.points.filterIndexed { i, _ -> i % step == 0 }.map { p -> p.lat to p.lon },
                )
            },
            season = if (inSeason.isEmpty()) null else SeasonSoFar(
                label = season.label,
                days = inSeason.map { it.startDate }.toSet().size,
                distanceMeters = inSeason.sumOf { it.distance },
            ),
        )
    }

    fun onPermissionResult() = engine.startLocationUpdates()

    fun start() {
        engine.start()
        follow.value = true
    }

    fun stop() {
        engine.stop()
        follow.value = false
    }

    fun togglePause() {
        if (engine.state.value.isPaused) engine.resume() else engine.pause()
    }

    fun startFollowing() = follow.update { true }
    fun stopFollowing() = follow.update { false }

    private companion object {
        const val THUMB_POINTS = 80
        const val PLACE_MOVE_METERS = 2_000f
    }
}
