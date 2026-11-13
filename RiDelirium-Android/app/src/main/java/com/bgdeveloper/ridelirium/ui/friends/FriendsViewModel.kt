package com.bgdeveloper.ridelirium.ui.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bgdeveloper.ridelirium.data.SettingsRepository
import com.bgdeveloper.ridelirium.domain.Units
import com.bgdeveloper.ridelirium.friends.Friend
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
    fun invite(rider: Friend) = repository.invite(rider)
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
    fun leave() = repository.leave()
    fun clearError() = repository.clearError()

    fun join(text: String) {
        viewModelScope.launch { repository.join(text) }
    }

    fun askToWait(friendId: String, done: (Boolean) -> Unit) {
        viewModelScope.launch { done(repository.askToWait(friendId)) }
    }

    fun loadInvite() {
        viewModelScope.launch {
            repository.loadInvite()
            repository.refresh()
            repository.loadRecentRiders()
        }
    }
}
