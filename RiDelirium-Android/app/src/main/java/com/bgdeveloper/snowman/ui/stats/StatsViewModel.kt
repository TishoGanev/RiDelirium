package com.bgdeveloper.snowman.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bgdeveloper.snowman.data.PlaceNameResolver
import com.bgdeveloper.snowman.data.RideRepository
import com.bgdeveloper.snowman.data.SettingsRepository
import com.bgdeveloper.snowman.domain.Season
import com.bgdeveloper.snowman.domain.SessionSummary
import com.bgdeveloper.snowman.domain.StatsRange
import com.bgdeveloper.snowman.domain.Units
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SeasonHeader(val title: String, val days: Int, val distanceMeters: Double, val topSpeed: Double)

data class StatsUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val placeNames: Map<String, String> = emptyMap(),
    val units: Units = Units(),
    val range: StatsRange = StatsRange.ALL,
    /** False only when nothing was ever recorded; separates "NO RIDES YET" from an empty period. */
    val hasAnyRides: Boolean = false,
) {
    /** Totals for the rides in view, so the header follows the time filter. */
    val header: SeasonHeader
        get() = SeasonHeader(
            title = if (range == StatsRange.SEASON) "${Season.containing(java.time.LocalDate.now()).label} SEASON" else range.headerTitle,
            days = sessions.map { it.startDate }.toSet().size,
            distanceMeters = sessions.sumOf { it.distance },
            topSpeed = sessions.maxOfOrNull { it.maxSpeed } ?: 0.0,
        )

    fun placeName(session: SessionSummary) = placeNames[session.placeKey] ?: "Session ${session.session}"
}

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val repository: RideRepository,
    private val places: PlaceNameResolver,
    settings: SettingsRepository,
) : ViewModel() {

    private val settingsRepository = settings

    val uiState: StateFlow<StatsUiState> = combine(
        repository.observeSessions(), places.names, settings.units, settings.statsRange,
    ) { sessions, names, units, range ->
        val today = java.time.LocalDate.now()
        StatsUiState(sessions.filter { range.contains(it.startDate, today) }, names, units, range, hasAnyRides = sessions.isNotEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    fun setRange(range: StatsRange) = settingsRepository.setStatsRange(range)

    fun resolvePlace(session: SessionSummary) = places.resolve(session)

    fun delete(session: SessionSummary) {
        viewModelScope.launch { repository.deleteSession(session.session) }
    }
}
