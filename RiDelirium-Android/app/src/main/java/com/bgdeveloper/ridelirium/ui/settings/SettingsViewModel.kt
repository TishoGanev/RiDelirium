package com.bgdeveloper.ridelirium.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bgdeveloper.ridelirium.data.SettingsRepository
import com.bgdeveloper.ridelirium.friends.Friend
import com.bgdeveloper.ridelirium.friends.FriendsRepository
import com.bgdeveloper.ridelirium.friends.Sport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val friends: FriendsRepository,
) : ViewModel() {
    val metric: StateFlow<Boolean> = settings.units.map { it.metric }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), settings.units.value.metric)
    val name: StateFlow<String?> = friends.state.map { it.me?.displayName }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), friends.state.value.me?.displayName)
    val initials: StateFlow<String> = friends.state.map { it.me?.initials ?: "SB" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), friends.state.value.me?.initials ?: "SB")

    val sport: StateFlow<Sport> = friends.state.map { it.mySport }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), friends.state.value.mySport)
    val me: StateFlow<Friend?> = friends.state.map { it.me }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), friends.state.value.me)
    fun setSport(sport: Sport) = friends.setSport(sport)

    val autoStart: StateFlow<Boolean> = settings.autoStart
    val autoStop: StateFlow<Boolean> = settings.autoStop
    val stopMinutes: StateFlow<Int> = settings.stopMinutes

    fun setAutoStart(value: Boolean) = settings.setAutoStart(value)
    fun setAutoStop(value: Boolean) = settings.setAutoStop(value)
    fun setStopMinutes(value: Int) = settings.setStopMinutes(value)

    fun setMetric(metric: Boolean) = settings.setMetric(metric)
    fun setName(name: String) = friends.setName(name)
}
