package com.bgdeveloper.ridelirium.friends

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
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class FriendsState(
    /** You, as friends see you. Null until the rider has entered a name. */
    val me: Friend? = null,
    val friends: List<Friend> = emptyList(),
    val invite: Invite? = null,
    val isJoining: Boolean = false,
    val joinError: String? = null,
    /** The rider who just joined, shown as a confirmation in the sheet. */
    val justJoined: Friend? = null,
    /** A scanned link that arrived before the rider entered a name; joined right after. */
    val pendingInvite: String? = null,
    /** People you rode with lately who aren't in your crew; one tap sends them your code. */
    val recentRiders: List<Friend> = emptyList(),
    /** Recent riders already asked, until they join. */
    val invitedIds: Set<String> = emptySet(),
    /** "NAME JUST JOINED", shown for four seconds on the share screen. */
    val joinBanner: String? = null,
    /** No server configured, or the last call to it failed; the sheet then says "WE ARE OFFLINE". */
    val offline: Boolean = false,
) {
    /** Friends who are riding right now; anyone who stopped sharing or left drops out. */
    val ridingNow: List<Friend> get() = friends.filter { it.isRiding }

    /** Friends whose last position can be drawn on the map (an old one shows as "last seen"). */
    val onMap: List<Friend> get() = friends.filter { it.hasPosition }
}

/**
 * Ride Together: who you ride with. There are no accounts. The name lives on this phone and on the server
 * (with a random id and your friend links). Your position is shared with your friends only while you ride with
 * them; the server keeps just the latest one. Lives in a singleton so sharing keeps going while a ride is recorded.
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
    private var hasLoadedFriends = false
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
        _state.update { s -> s.copy(recentRiders = riders.filter { r -> s.friends.none { it.id == r.id } }) }
    }

    /** Sends your code to a recent rider; the chip says INVITED until they join. */
    fun invite(rider: Friend) {
        if (rider.id in _state.value.invitedIds) return
        _state.update { it.copy(invitedIds = it.invitedIds + rider.id) }
        scope.launch {
            runCatching { authorized { api.invite(it, rider.id) } }
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
        name?.let { _state.update { s -> s.copy(me = Friend(identity, it, initialsOf(it))) } }
        // Show today's crew straight away; the server confirms who is riding a moment later (or never, offline).
        _state.update { it.copy(friends = merged(readServerCrew(), localCrew())) }
        scope.launch { refresh() }
        scope.launch { watchSharing() }
        scope.launch { registerPush() }
    }

    // region Push

    /** Remembers this phone's FCM token and tells the server, so it can send crew and wait alerts. */
    fun onPushToken(fcmToken: String) {
        prefs.edit().putString("fcmToken", fcmToken).putBoolean("fcmSent", false).apply()
        scope.launch { registerPush() }
    }

    /** Sends the stored FCM token to the server once; retried on the next launch or name change when it fails. */
    private suspend fun registerPush() {
        if (!api.isConfigured || name == null || !PushSetup.isConfigured) return
        val fcm = prefs.getString("fcmToken", null) ?: PushSetup.currentToken()?.also { prefs.edit().putString("fcmToken", it).apply() } ?: return
        if (prefs.getBoolean("fcmSent", false)) return
        runCatching { authorized { api.registerPush(it, fcm) } }.onSuccess { prefs.edit().putBoolean("fcmSent", true).apply() }
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
        if (!show) _state.update { it.copy(justJoined = null, joinError = null) }
    }

    fun clearError() = _state.update { it.copy(joinError = null) }

    /** Saves the rider's name and presents them to friends under it. */
    fun setName(raw: String) {
        val name = raw.trim().take(30)
        if (name.isEmpty()) return
        prefs.edit().putString("name", name).apply()
        _state.update { it.copy(me = Friend(identity, name, initialsOf(name)), invite = null) }
        scope.launch {
            runCatching { authorized { api.updateName(it, name) } }
            registerPush()
            val pending = _state.value.pendingInvite
            if (pending != null) {
                _state.update { it.copy(pendingInvite = null) }
                join(pending)
            }
        }
    }

    /** Opened from a link or scanned QR code. */
    fun handleLink(text: String) {
        _showSheet.value = true
        if (_state.value.me == null) {
            _state.update { it.copy(pendingInvite = text) }
        } else {
            scope.launch { join(text) }
        }
    }

    suspend fun loadInvite() {
        val me = _state.value.me ?: return
        if (_state.value.invite != null) return
        val code = runCatching { authorized { api.inviteCode(it) } }.getOrNull()
        if (code == null) {
            _state.update { it.copy(offline = true) }
            return fail(NO_SIGNAL)
        }
        _state.update { it.copy(invite = Invite(code, me), joinError = null, offline = false) }
    }

    /**
     * The server is the truth while it is reachable: a friend who left the crew is gone from its list, so they
     * are dropped here too, unless their join is still on its way to the server. Offline, we keep who we have.
     */
    suspend fun refresh() {
        syncPendingJoins()
        val known = runCatching { authorized { api.friends(it) } }.getOrNull()
        if (known == null) {
            _state.update { it.copy(friends = merged(it.friends, localCrew()), offline = api.isConfigured) }
            return
        }
        writeServerCrew(known)
        pruneLocalCrew(serverIds = known.map { it.id }.toSet(), keeping = pendingJoins().values.toSet())
        // Anyone the server doesn't list yet can't be confirmed as riding.
        val unconfirmed = localCrew().map { it.copy(isRiding = false, latitude = null, longitude = null) }
        val before = _state.value.friends.map { it.id }.toSet()
        _state.update { s ->
            val crew = merged(known, unconfirmed)
            s.copy(
                friends = crew,
                recentRiders = s.recentRiders.filter { r -> crew.none { it.id == r.id } },
                invitedIds = s.invitedIds - crew.map { it.id }.toSet(),
                offline = false,
            )
        }
        // Someone new in the crew: say who, once the first load is past (not for the whole crew at launch).
        val state = _state.value
        val joined = state.friends.firstOrNull { it.id !in before }
        if (hasLoadedFriends && joined != null && state.justJoined == null && !state.isJoining) showJoinBanner(joined.displayName)
        hasLoadedFriends = true
    }

    private fun showJoinBanner(name: String) {
        _state.update { it.copy(joinBanner = name) }
        scope.launch {
            delay(4_000)
            _state.update { if (it.joinBanner == name) it.copy(joinBanner = null) else it }
        }
    }

    private var quickRefresh: Job? = null

    /** Checks the crew every 2 seconds for about 12 seconds, instead of waiting for the slow poll. */
    fun refreshQuickly() {
        quickRefresh?.cancel()
        quickRefresh = scope.launch {
            repeat(6) {
                delay(2_000)
                refresh()
            }
        }
    }

    /** Joins from a scanned QR payload, an opened invite link or a typed code. */
    suspend fun join(text: String) {
        if (_state.value.isJoining) return
        val (code, inviter) = InviteLink.parse(text) ?: return fail(INVALID_CODE)
        val me = _state.value.me ?: return fail(NO_NAME)
        if (code == _state.value.invite?.code || inviter?.id == me.id) return fail(OWN_CODE)

        _state.update { it.copy(isJoining = true, joinError = null) }
        try {
            val friend = authorized { api.join(it, code) }
            save(friend)
            // Joining a code brings the owner's whole crew; look again every couple of seconds for a while so
            // each rider shows on the map as soon as their phone sends a position.
            refresh()
            refreshQuickly()
        } catch (e: ApiException) {
            fail(if (e.status == 404) INVALID_CODE else NO_SIGNAL)
        } catch (e: Exception) {
            // The QR already says who invited us, so we can still ride with them; the server learns about it
            // when there is signal again.
            if (inviter != null) {
                save(inviter)
                queueJoin(code, inviter.id)
            } else {
                fail(NO_SIGNAL)
            }
        } finally {
            _state.update { it.copy(isJoining = false) }
        }
    }

    /** Removes everyone from your friends on this phone and the server. They aren't notified. */
    fun leave() {
        val ids = _state.value.friends.map { it.id }
        prefs.edit().remove("crew").remove("serverCrew").remove("pendingJoinIds").apply()
        // No invite either: with nobody to wait for, stop sharing until a new one is shown.
        _state.update { it.copy(friends = emptyList(), justJoined = null, invite = null) }
        scope.launch {
            ids.forEach { id -> runCatching { authorized { api.removeFriend(it, id) } } }
            // Everyone who just left the crew is now a recent rider.
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
        // Showing an invite counts as being in a crew: the rider is waiting for people to join, so their position
        // is already on the server when the first one does.
        val inCrew = _state.map { it.friends.isNotEmpty() || it.invite != null }.distinctUntilChanged()

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
                    // Not sharing yet, but someone may join our code, so look now and then while the app is open.
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

    // region Storage

    private fun save(friend: Friend) {
        val crew = localCrew().filter { it.id != friend.id } + friend.copy(isRiding = false, latitude = null, longitude = null)
        writeCrew(crew, added = readAdded() + (friend.id to System.currentTimeMillis()))
        _state.update { it.copy(friends = merged(it.friends, crew), justJoined = friend, joinError = null) }
    }

    /** Riders joined without signal today, kept until they show up on the server. */
    private fun localCrew(): List<Friend> {
        val stored = readCrew() ?: return emptyList()
        val array = stored.optJSONArray("riders") ?: return emptyList()
        return List(array.length()) {
            val o = array.getJSONObject(it)
            Friend(o.getString("id"), o.getString("name"), o.getString("initials"))
        }
    }

    private fun readCrew(): JSONObject? {
        val stored = prefs.getString("crew", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return stored.takeIf { it.optString("day") == today() }
    }

    /** The riders the server last listed, so a restart without signal still shows the whole crew, not just who we joined. */
    private fun readServerCrew(): List<Friend> {
        val stored = prefs.getString("serverCrew", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
        if (stored.optString("day") != today()) return emptyList()
        val array = stored.optJSONArray("riders") ?: return emptyList()
        return List(array.length()) {
            val o = array.getJSONObject(it)
            Friend(o.getString("id"), o.getString("name"), o.getString("initials"))
        }
    }

    private fun writeServerCrew(crew: List<Friend>) {
        val riders = JSONArray().also { array ->
            crew.forEach { array.put(JSONObject().put("id", it.id).put("name", it.displayName).put("initials", it.initials)) }
        }
        prefs.edit().putString("serverCrew", JSONObject().put("day", today()).put("riders", riders).toString()).apply()
    }

    private fun readAdded(): Map<String, Long> {
        val added = readCrew()?.optJSONObject("added") ?: return emptyMap()
        return added.keys().asSequence().associateWith { added.getLong(it) }
    }

    private fun writeCrew(crew: List<Friend>, added: Map<String, Long>) {
        val riders = JSONArray().also { array ->
            crew.forEach { array.put(JSONObject().put("id", it.id).put("name", it.displayName).put("initials", it.initials)) }
        }
        val times = JSONObject().also { o -> added.filterKeys { id -> crew.any { it.id == id } }.forEach { (id, t) -> o.put(id, t) } }
        prefs.edit().putString("crew", JSONObject().put("day", today()).put("riders", riders).put("added", times).toString()).apply()
    }

    /** Forgets riders the server no longer lists, unless they were added a moment ago or their join is still queued. */
    private fun pruneLocalCrew(serverIds: Set<String>, keeping: Set<String>) {
        val added = readAdded()
        val now = System.currentTimeMillis()
        val crew = localCrew().filter { rider ->
            rider.id in serverIds || rider.id in keeping || now - (added[rider.id] ?: 0L) <= GRACE_MS
        }
        writeCrew(crew, added)
    }

    /** Joins waiting for signal: invite code -> id of the rider it belongs to. */
    private fun pendingJoins(): Map<String, String> {
        val json = prefs.getString("pendingJoinIds", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        return json.keys().asSequence().associateWith { json.getString(it) }
    }

    private fun writePending(joins: Map<String, String>) {
        prefs.edit().putString("pendingJoinIds", JSONObject(joins).toString()).apply()
    }

    private fun queueJoin(code: String, riderId: String) {
        if (!api.isConfigured) return
        writePending(pendingJoins() + (code to riderId))
    }

    private suspend fun syncPendingJoins() {
        val joins = pendingJoins()
        if (joins.isEmpty() || _state.value.me == null) return
        val left = mutableMapOf<String, String>()
        for ((code, riderId) in joins) {
            try {
                authorized { api.join(it, code) }
            } catch (e: ApiException) {
                // An unknown code will never work; anything else is worth another try.
                if (e.status != 404) left[code] = riderId
            } catch (e: Exception) {
                left[code] = riderId
            }
        }
        writePending(left)
    }

    private fun merged(vararg lists: List<Friend>): List<Friend> = lists.flatMap { it }.distinctBy { it.id }

    private fun today() = java.time.LocalDate.now().toString()

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
        const val GRACE_MS = 10 * 60_000L
        const val INVALID_CODE = "That code doesn't look right. Codes are 8 letters and numbers."
        const val OWN_CODE = "That's your own code. Let a friend scan it instead."
        const val NO_NAME = "Add your name first to ride together."
        const val NO_SIGNAL = "We are offline. Check your signal and try again."
    }
}
