package com.bgdeveloper.ridelirium.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bgdeveloper.ridelirium.data.SettingsRepository
import com.bgdeveloper.ridelirium.friends.Friend
import com.bgdeveloper.ridelirium.friends.FriendsRepository
import com.bgdeveloper.ridelirium.friends.Sport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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



    fun setMetric(metric: Boolean) = settings.setMetric(metric)
    fun setName(name: String) = friends.setName(name)

    private val _deleting = MutableStateFlow(false)
    /** True while "Delete my Ride Together data" is on its way to the server. */
    val deleting: StateFlow<Boolean> = _deleting
    private val _deleteFailed = MutableStateFlow(false)
    /** The last delete couldn't reach the server; nothing was cleared. */
    val deleteFailed: StateFlow<Boolean> = _deleteFailed

    fun deleteRideTogetherData() {
        if (_deleting.value) return
        _deleting.value = true
        viewModelScope.launch {
            _deleteFailed.value = !friends.deleteMyData()
            _deleting.value = false
        }
    }

    fun dismissDeleteError() { _deleteFailed.value = false }
}
