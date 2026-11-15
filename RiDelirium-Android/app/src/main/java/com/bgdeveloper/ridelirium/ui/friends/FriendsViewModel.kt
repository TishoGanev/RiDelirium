package com.bgdeveloper.ridelirium.ui.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bgdeveloper.ridelirium.data.SettingsRepository
import com.bgdeveloper.ridelirium.domain.Units
import com.bgdeveloper.ridelirium.friends.Crew
import com.bgdeveloper.ridelirium.friends.Friend
import com.bgdeveloper.ridelirium.friends.Stay
import com.bgdeveloper.ridelirium.friends.FriendsRepository
import com.bgdeveloper.ridelirium.friends.FriendsState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FriendsViewModel @Inject constructor(
    private val repository: FriendsRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val units: StateFlow<Units> = settings.units
    fun metersTo(friend: Friend): Int? = repository.metersTo(friend)
    fun bearingTo(friend: Friend): Float? = repository.bearingTo(friend)
    fun invite(rider: Friend, crewId: String) = repository.invite(rider, crewId)
    fun showOnMap(friend: Friend) {
        repository.focusOnMap(friend.id)
        repository.showSheet(false)
    }

    val state: StateFlow<FriendsState> = repository.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), repository.state.value)
    val focusFriendId: StateFlow<String?> = repository.focusFriendId
    fun clearFocus() = repository.focusOnMap(null)
    val showSheet: StateFlow<Boolean> = repository.showSheet
    val isConfigured: Boolean get() = repository.isConfigured

    fun openSheet() = repository.showSheet(true)
    fun closeSheet() = repository.showSheet(false)
    fun setName(name: String) = repository.setName(name)
    fun clearError() = repository.clearError()
    fun selectCrew(id: String) = repository.selectCrew(id)
    fun leave(crewId: String) = repository.leave(crewId)
    fun cancelJoin() = repository.cancelJoin()

    fun preview(text: String) {
        viewModelScope.launch { repository.preview(text) }
    }

    fun confirmJoin(stay: Stay) {
        viewModelScope.launch { repository.confirmJoin(stay) }
    }

    fun setStay(crewId: String, stay: Stay) {
        viewModelScope.launch { repository.setStay(crewId, stay) }
    }

    fun createCrew(name: String, done: (Crew?) -> Unit) {
        viewModelScope.launch { done(repository.createCrew(name)) }
    }

    fun renameCrew(id: String, name: String) {
        viewModelScope.launch { repository.renameCrew(id, name) }
    }

    fun askToWait(friendId: String, done: (Boolean) -> Unit) {
        viewModelScope.launch { done(repository.askToWait(friendId)) }
    }

    /** Fresh crews and recent riders whenever the sheet opens. */
    fun reload() {
        viewModelScope.launch {
            repository.refresh()
            repository.loadRecentRiders()
        }
    }
}
