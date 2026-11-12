package com.bgdeveloper.snowman.ui.track

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bgdeveloper.snowman.data.SettingsRepository
import com.bgdeveloper.snowman.domain.Units
import com.bgdeveloper.snowman.tracking.TrackingEngine
import com.bgdeveloper.snowman.tracking.TrackingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class TrackUiState(
    val tracking: TrackingState = TrackingState(),
    val follow: Boolean = false,
    val units: Units = Units(),
)

@HiltViewModel
class TrackViewModel @Inject constructor(
    private val engine: TrackingEngine,
    settings: SettingsRepository,
) : ViewModel() {
    private val follow = MutableStateFlow(false)

    val uiState: StateFlow<TrackUiState> = combine(engine.state, follow, settings.units) { tracking, follow, units ->
        TrackUiState(tracking, follow, units)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrackUiState())

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
}
