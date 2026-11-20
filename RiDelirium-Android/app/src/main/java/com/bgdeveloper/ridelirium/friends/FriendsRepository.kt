package com.bgdeveloper.ridelirium.friends

import java.time.ZonedDateTime
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.bgdeveloper.ridelirium.push.PushSetup
import com.bgdeveloper.ridelirium.tracking.TrackingEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class FriendsState(
    /** You, as crew-mates see you. Null until the rider has entered a name. */
    val me: Friend? = null,
    /** Every crew you are in, most recently joined first. */
    val crews: List<Crew> = emptyList(),
    /** The crew the map shows; you switch it in Ride Together. */
    val selectedCrewId: String? = null,
    val isJoining: Boolean = false,
    val joinError: String? = null,
    /** What a scanned or typed code opens, waiting for the rider to pick how long to stay. */
    val joinPreview: CrewPreview? = null,
    /** The crew just joined, shown as a confirmation in the sheet. */
    val justJoined: Crew? = null,
    /** A scanned link that arrived before the rider entered a name; previewed right after. */
    val pendingInvite: String? = null,
    /** People you rode with lately who are in none of your crews; one tap sends them the selected crew's code. */
    val recentRiders: List<Friend> = emptyList(),
    /** Recent riders already asked, until they join. */
    val invitedIds: Set<String> = emptySet(),
    /** "NAME JUST JOINED", shown for four seconds on the invite screen. */
    val joinBanner: String? = null,
    /** What this rider chose in Settings. */
    val mySport: Sport = Sport.BOARD,
    /** No server configured, or the last call to it failed; the sheet then says "WE ARE OFFLINE". */
    val offline: Boolean = false,
) {
    val selectedCrew: Crew? get() = crews.firstOrNull { it.id == selectedCrewId } ?: crews.firstOrNull()

    /** Members of the selected crew, without you: what the map, the crew pill and WAIT work with. */
    val friends: List<Friend> get() = selectedCrew?.others(me?.id).orEmpty()

    /** The selected crew's code, for the invite card. */
    val invite: Invite? get() = selectedCrew?.invite

    /** Friends who are riding right now; anyone who stopped sharing or left drops out. */
    val ridingNow: List<Friend> get() = friends.filter { it.isRiding }

    /** Friends whose last position can be drawn on the map (an old one shows as "last seen"). */
    val onMap: List<Friend> get() = friends.filter { it.hasPosition }
}

/**
 * Ride Together: the crews you ride with. There are no accounts. The name lives on this phone and on the server
 * (with a random id and your crew memberships). Your position is shared only with riders in a crew with you, and
 * the server keeps just the latest one. Lives in a singleton so sharing keeps going while a ride is recorded.
 */
@Singleton
class FriendsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val engine: TrackingEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("ridetogether", Context.MODE_PRIVATE)
    private val api = RideTogetherApi()
    private val registration = Mutex()

    private val _state = MutableStateFlow(FriendsState(offline = !api.isConfigured))
    private var hasLoadedCrews = false
    val state: StateFlow<FriendsState> = _state.asStateFlow()

    /** Any screen can ask for the Ride Together sheet; the Track screen presents it. */
    private val _showSheet = MutableStateFlow(false)
    val showSheet: StateFlow<Boolean> = _showSheet.asStateFlow()

    /** A rider the crew list asked the map to show; the Track screen selects and centres on them, then clears it. */
    private val _focusFriendId = MutableStateFlow<String?>(null)
    val focusFriendId: StateFlow<String?> = _focusFriendId.asStateFlow()

    fun focusOnMap(id: String?) { _focusFriendId.value = id }

    /** Metres from you to [friend], when both positions are known. */
    fun metersTo(friend: Friend): Int? {
        val here = engine.state.value.current ?: return null
        if (!friend.hasPosition) return null
        val out = FloatArray(1)
        android.location.Location.distanceBetween(here.latitude, here.longitude, friend.latitude!!, friend.longitude!!, out)
        return out[0].toInt()
    }

    /**
     * Degrees to turn from your heading to face [friend] (0 = straight ahead). Your heading is your course while
     * moving; standing still the arrow is relative to north, like the iPhone app.
     */
    fun bearingTo(friend: Friend): Float? {
        val here = engine.state.value.current ?: return null
        if (!friend.hasPosition) return null
        val target = android.location.Location("friend").apply { latitude = friend.latitude!!; longitude = friend.longitude!! }
        val absolute = (here.bearingTo(target) + 360f) % 360f
        val heading = if (here.hasBearing() && here.speed > 1f) here.bearing else 0f
        return (absolute - heading + 360f) % 360f
    }

    suspend fun loadRecentRiders() {
        if (_state.value.me == null) return
        val riders = runCatching { authorized { api.recentRiders(it) } }.getOrNull() ?: return
        _state.update { s -> s.copy(recentRiders = riders.filter { r -> s.crews.none { c -> c.members.any { it.id == r.id } } }) }
    }

    /** Sends [crewId]'s code to a recent rider; the chip says INVITED until they join. */
    fun invite(rider: Friend, crewId: String) {
        val crew = _state.value.crews.firstOrNull { it.id == crewId } ?: return
        if (rider.id in _state.value.invitedIds) return
        _state.update { it.copy(invitedIds = it.invitedIds + rider.id) }
        scope.launch {
            runCatching { authorized { api.invite(it, rider.id, crew.id) } }
                .onFailure { _state.update { s -> s.copy(invitedIds = s.invitedIds - rider.id) } }
        }
    }

    val isConfigured: Boolean get() = api.isConfigured

    /** Stable per install, so a rider you met today isn't added twice. */
    private val identity: String
        get() = prefs.getString("identity", null) ?: UUID.randomUUID().toString().uppercase().also {
            prefs.edit().putString("identity", it).apply()
        }

    val name: String? get() = prefs.getString("name", null)

    init {
        _state.update { it.copy(mySport = Sport.fromKey(prefs.getString("sport", null)) ?: Sport.BOARD) }
        name?.let { _state.update { s -> s.copy(me = Friend(identity, it, initialsOf(it), sport = s.mySport)) } }
        _state.update { it.copy(selectedCrewId = prefs.getString("selectedCrew", null)) }
        scope.launch { refresh() }
        scope.launch { watchSharing() }
        scope.launch { registerPush() }
        // The server may not know what this rider rides yet (or has the default); tell it, quietly.
        scope.launch { if (name != null) runCatching { authorized { api.updateSport(it, _state.value.mySport) } } }
    }

    /** Saves what the rider rides and tells the server, which tells their crew at once. */
    fun setSport(sport: Sport) {
        prefs.edit().putString("sport", sport.key).apply()
        _state.update { it.copy(mySport = sport, me = it.me?.copy(sport = sport)) }
        scope.launch { if (name != null) runCatching { authorized { api.updateSport(it, sport) } } }
    }

    // region Push

    /** Remembers this phone's FCM token and tells the server, so it can send crew and wait alerts. */
    fun onPushToken(fcmToken: String) {
        prefs.edit().putString("fcmToken", fcmToken).apply()
        scope.launch { registerPush() }
    }

    /**
     * Sends this phone's FCM token to the server on every launch and name change, like iOS does. Sending it only once
     * lost push for good whenever the server dropped the device row (a rejected send, a reset database).
     */
    private suspend fun registerPush() {
        if (!api.isConfigured || name == null || !PushSetup.isConfigured) return
        val fcm = PushSetup.currentToken()?.also { prefs.edit().putString("fcmToken", it).apply() }
            ?: prefs.getString("fcmToken", null) ?: return
        runCatching { authorized { api.registerPush(it, fcm) } }
    }

    /** The notification's "OK, waiting" button. */
    suspend fun acceptWait(id: String) {
        runCatching { authorized { api.acceptWait(it, id) } }
    }

    /** Asks [friendId] to wait for you, with the distance to them when both positions are known. */
    suspend fun askToWait(friendId: String): Boolean {
        val friend = _state.value.friends.firstOrNull { it.id == friendId }
        val here = engine.state.value.current
        val meters = if (friend?.hasPosition == true && here != null) {
            val out = FloatArray(1)
            android.location.Location.distanceBetween(here.latitude, here.longitude, friend.latitude!!, friend.longitude!!, out)
            out[0].toInt()
        } else null
        return runCatching { authorized { api.askToWait(it, friendId.uppercase(), meters) } }
            .onFailure { android.util.Log.e("RideTogether", "wait to $friendId failed", it) }
            .isSuccess
    }

    // endregion

    fun showSheet(show: Boolean) {
        _showSheet.value = show
        if (!show) _state.update { it.copy(justJoined = null, joinError = null, joinPreview = null) }
    }

    fun clearError() = _state.update { it.copy(joinError = null) }

    /** Saves the rider's name and presents them to crew-mates under it. */
    fun setName(raw: String) {
        val name = raw.trim().take(30)
        if (name.isEmpty()) return
        prefs.edit().putString("name", name).apply()
        _state.update { it.copy(me = Friend(identity, name, initialsOf(name), sport = it.mySport)) }
        scope.launch {
            runCatching { authorized { api.updateName(it, name) } }
            runCatching { authorized { api.updateSport(it, _state.value.mySport) } }
            registerPush()
            refresh()
            val pending = _state.value.pendingInvite
            if (pending != null) {
                _state.update { it.copy(pendingInvite = null) }
                preview(pending)
            }
        }
    }

    /** Opened from a link or scanned QR code. */
    fun handleLink(text: String) {
        _showSheet.value = true
        if (_state.value.me == null) {
            _state.update { it.copy(pendingInvite = text) }
        } else {
            scope.launch { preview(text) }
        }
    }

    /** Makes [id] the crew the map shows. */
    fun selectCrew(id: String) {
        prefs.edit().putString("selectedCrew", id).apply()
        _state.update { it.copy(selectedCrewId = id) }
    }

    /**
     * The server is the truth: a crew you left or whose time ran out is gone from its list, and so is anyone who
     * left a crew. Offline, we keep what we have.
     */
    suspend fun refresh() {
        if (_state.value.me == null) return
        val crews = runCatching { authorized { api.crews(it) } }.getOrNull()
        if (crews == null) {
            _state.update { it.copy(offline = api.isConfigured) }
            return
        }
        val before = _state.value.selectedCrew?.let { crew -> crew.id to crew.members.map { it.id }.toSet() }
        _state.update { s ->
            val selected = s.selectedCrewId?.takeIf { id -> crews.any { it.id == id } } ?: crews.firstOrNull()?.id
            val inCrews = crews.flatMap { c -> c.members.map { it.id } }.toSet()
            s.copy(
                crews = crews,
                selectedCrewId = selected,
                recentRiders = s.recentRiders.filter { it.id !in inCrews },
                invitedIds = s.invitedIds - inCrews,
                offline = false,
            )
        }
        // Someone new in the selected crew: say who, once the first load is past (not for the whole crew at launch).
        val state = _state.value
        val crew = state.selectedCrew
        val joined = if (before != null && crew?.id == before.first) crew.members.firstOrNull { it.id !in before.second } else null
        if (hasLoadedCrews && joined != null && joined.id != state.me?.id && state.justJoined == null && !state.isJoining) {
            showJoinBanner(joined.displayName)
        }
        hasLoadedCrews = true
    }

    private fun showJoinBanner(name: String) {
        _state.update { it.copy(joinBanner = name) }
        scope.launch {
            delay(4_000)
            _state.update { if (it.joinBanner == name) it.copy(joinBanner = null) else it }
        }
    }

    private var quickRefresh: Job? = null

    /** Checks the crews every 2 seconds for about 12 seconds, instead of waiting for the slow poll. */
    fun refreshQuickly() {
        quickRefresh?.cancel()
        quickRefresh = scope.launch {
            repeat(6) {
                delay(2_000)
                refresh()
            }
        }
    }

    /** Creates a crew and selects it; the sheet then shows its invite card. */
    suspend fun createCrew(name: String): Crew? {
        val clean = name.trim().take(30).ifEmpty { return null }
        return try {
            authorized { api.createCrew(it, clean) }.also { crew ->
                _state.update { it.copy(crews = listOf(crew) + it.crews, joinError = null, offline = false) }
                selectCrew(crew.id)
            }
        } catch (e: ApiException) {
            fail(if (e.status == 409) TOO_MANY_CREWS else NO_SIGNAL); null
        } catch (e: Exception) {
            fail(NO_SIGNAL); null
        }
    }

    suspend fun renameCrew(id: String, name: String) {
        val clean = name.trim().take(30).ifEmpty { return }
        runCatching { authorized { api.renameCrew(it, id, clean) } }
            .onSuccess { crew -> _state.update { s -> s.copy(crews = s.crews.map { if (it.id == crew.id) crew else it }) } }
            .onFailure { fail(NO_SIGNAL) }
    }

    /** Step one of joining: what a scanned QR payload, an opened invite link or a typed code opens. */
    suspend fun preview(text: String) {
        if (_state.value.isJoining) return
        val (code, _) = InviteLink.parse(text) ?: return fail(INVALID_CODE)
        if (_state.value.me == null) return fail(NO_NAME)
        if (_state.value.crews.any { it.code == code && it.ownerId == _state.value.me?.id }) return fail(OWN_CODE)
        _state.update { it.copy(isJoining = true, joinError = null) }
        try {
            val preview = authorized { api.previewCrew(it, code) }
            _state.update { it.copy(joinPreview = preview) }
        } catch (e: ApiException) {
            fail(if (e.status == 404) INVALID_CODE else NO_SIGNAL)
        } catch (e: Exception) {
            fail(NO_SIGNAL)
        } finally {
            _state.update { it.copy(isJoining = false) }
        }
    }

    fun cancelJoin() = _state.update { it.copy(joinPreview = null, joinError = null) }

    /** Step two: join the previewed crew, sharing my location until [until]. */
    suspend fun confirmJoin(until: ZonedDateTime) {
        val preview = _state.value.joinPreview ?: return
        if (_state.value.isJoining) return
        _state.update { it.copy(isJoining = true, joinError = null) }
        try {
            val crew = authorized { api.joinCrew(it, preview.code, Stay.clamp(until).toEpochSecond()) }
            _state.update { s ->
                s.copy(crews = listOf(crew) + s.crews.filter { it.id != crew.id }, joinPreview = null, justJoined = crew, offline = false)
            }
            selectCrew(crew.id)
            refreshQuickly()
        } catch (e: ApiException) {
            fail(
                when (e.status) {
                    404 -> INVALID_CODE
                    409 -> CREW_FULL
                    else -> NO_SIGNAL
                },
            )
        } catch (e: Exception) {
            fail(NO_SIGNAL)
        } finally {
            _state.update { it.copy(isJoining = false) }
        }
    }

    /** Changes when you stop sharing your location with [crewId]. */
    suspend fun setStay(crewId: String, until: ZonedDateTime) {
        runCatching { authorized { api.setStay(it, crewId, Stay.clamp(until).toEpochSecond()) } }
            .onSuccess { crew -> _state.update { s -> s.copy(crews = s.crews.map { if (it.id == crew.id) crew else it }) } }
            .onFailure { fail(NO_SIGNAL) }
    }

    /** Drops [crewId]'s meet point; everyone else in the crew is notified. False when the server can't be reached. */
    suspend fun setMeet(crewId: String, kind: MeetKind, inMinutes: Int, latitude: Double, longitude: Double): Boolean =
        runCatching { authorized { api.setMeet(it, crewId, latitude, longitude, kind, inMinutes) } }
            .onSuccess { crew -> _state.update { s -> s.copy(crews = s.crews.map { if (it.id == crew.id) crew else it }) } }
            .isSuccess

    /** Takes the meet point away for the whole crew. */
    suspend fun clearMeet(crewId: String) {
        runCatching { authorized { api.clearMeet(it, crewId) } }
            .onSuccess { crew -> _state.update { s -> s.copy(crews = s.crews.map { if (it.id == crew.id) crew else it }) } }
            .onFailure { fail(NO_SIGNAL) }
    }

    /** Leaves one crew; the others in it are told. */
    fun leave(crewId: String) {
        _state.update { s ->
            val crews = s.crews.filter { it.id != crewId }
            s.copy(crews = crews, selectedCrewId = if (s.selectedCrewId == crewId) crews.firstOrNull()?.id else s.selectedCrewId)
        }
        scope.launch {
            runCatching { authorized { api.leaveCrew(it, crewId) } }
            refresh()
            // Everyone in that crew is now a recent rider.
            loadRecentRiders()
        }
    }

    // region Sharing

    /**
     * Shares your position with your crew while a ride is recorded, or while the app is open and you are in a
     * crew, and keeps friends (and where they are) fresh. Only friends see it; it expires on the server about
     * a minute after we stop sending.
     */
    private suspend fun watchSharing() {
        val process = ProcessLifecycleOwner.get().lifecycle
        val foreground = isStarted(process).distinctUntilChanged()
        val tracking = engine.state.map { it.isTracking }.distinctUntilChanged()
        // Being in any crew counts, even alone in it: the rider is waiting for people to join, so their position
        // is already on the server when the first one does.
        val inCrew = _state.map { it.crews.isNotEmpty() }.distinctUntilChanged()

        var wasSharing = false
        combine(tracking, inCrew, foreground) { t, crew, fg -> (t || (crew && fg)) to fg }
            .distinctUntilChanged()
            .collectLatest { (sharing, fg) ->
                if (sharing) {
                    wasSharing = true
                    while (true) {
                        val here = engine.state.value.current
                        runCatching { authorized { api.setRiding(it, true, here?.latitude, here?.longitude, engine.state.value.currentSpeed * 3.6) } }
                        refresh()
                        delay(SHARING_MS)
                    }
                } else {
                    if (wasSharing) {
                        wasSharing = false
                        runCatching { authorized { api.setRiding(it, false) } }
                    }
                    // Not sharing yet, but someone may join a crew of ours, so look now and then while the app is open.
                    while (fg) {
                        refresh()
                        delay(IDLE_REFRESH_MS)
                    }
                }
            }
    }

    /** True while the app is on screen. */
    private fun isStarted(lifecycle: Lifecycle): Flow<Boolean> = callbackFlow {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { trySend(true) }
            override fun onStop(owner: LifecycleOwner) { trySend(false) }
        }
        trySend(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        lifecycle.addObserver(observer)
        awaitClose { lifecycle.removeObserver(observer) }
    }

    // endregion

    // region Registration

    /** Runs [block] with this install's token, registering first if it has none, and once more if the server forgot it. */
    private suspend fun <T> authorized(block: suspend (String) -> T): T {
        if (!api.isConfigured) throw IllegalStateException("No server configured")
        repeat(2) { attempt ->
            val token = currentToken()
            try {
                return block(token)
            } catch (e: ApiException) {
                if (e.status != 401 || attempt > 0) throw e
                prefs.edit().remove("token").apply()
            }
        }
        throw ApiException(401)
    }

    private suspend fun currentToken(): String = registration.withLock {
        prefs.getString("token", null) ?: run {
            val name = name ?: throw IllegalStateException("No name yet")
            api.register(identity, name).also { prefs.edit().putString("token", it).apply() }
        }
    }

    // endregion

    private fun fail(message: String) = _state.update { it.copy(joinError = message) }

    private companion object {
        const val SHARING_MS = 6_000L
        const val IDLE_REFRESH_MS = 60_000L
        const val INVALID_CODE = "That code doesn't look right. Codes are 8 letters and numbers."
        const val OWN_CODE = "That's your own code. Let a friend scan it instead."
        const val NO_NAME = "Add your name first to ride together."
        const val NO_SIGNAL = "We are offline. Check your signal and try again."
        const val TOO_MANY_CREWS = "You are in 10 crews already. Leave one first."
        const val CREW_FULL = "That crew is full (3 riders), or you are in 10 crews already."
    }
}
