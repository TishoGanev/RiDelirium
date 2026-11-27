package com.bgdeveloper.ridelirium.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.bgdeveloper.ridelirium.data.RideRepository
import com.bgdeveloper.ridelirium.data.SessionMetadataStore
import com.bgdeveloper.ridelirium.friends.Sport
import com.bgdeveloper.ridelirium.data.SettingsRepository
import com.bgdeveloper.ridelirium.data.TrackPointEntity
import com.bgdeveloper.ridelirium.domain.Activity
import com.bgdeveloper.ridelirium.domain.DistanceRule
import com.bgdeveloper.ridelirium.domain.FixFilter
import com.bgdeveloper.ridelirium.domain.LiveActivityClassifier
import com.bgdeveloper.ridelirium.domain.SessionSummary
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
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
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.google.firebase.crashlytics.FirebaseCrashlytics
import javax.inject.Inject
import javax.inject.Singleton

data class TrackingState(
    val isTracking: Boolean = false,
    val isPaused: Boolean = false,
    val current: Location? = null,
    val distanceMeters: Double = 0.0,
    val maxSpeed: Double = 0.0,
    val coordinates: List<LatLng> = emptyList(),
    val elapsedSeconds: Long = 0,
    /** What the rider is doing right now; standing still adds nothing to distance or the track. */
    val activity: Activity = Activity.STILL,
    /** Total G-force right now (about 1.0 at rest) and the highest this ride; null without an accelerometer. */
    val gForce: Double? = null,
    val maxGForce: Double? = null,
) {
    val hasFix: Boolean get() = current != null

    /** Location.getSpeed is 0 when the device can't compute one. */
    val currentSpeed: Double get() = maxOf(0f, current?.speed ?: 0f).toDouble()
}

/**
 * Process-wide tracking session. Lives in a singleton so a ride survives the UI going away;
 * [TrackingService] keeps the process in the foreground while a ride is recording.
 */
@Singleton
class TrackingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: RideRepository,
    private val settings: SettingsRepository,
    private val motion: MotionMonitor,
    private val gSensor: GForceMonitor,
    private val sessionMetadata: SessionMetadataStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val _state = MutableStateFlow(TrackingState())
    val state: StateFlow<TrackingState> = _state.asStateFlow()

    private var updatesJob: Job? = null
    private var timerJob: Job? = null
    private var session = 0
    private var lastLocation: Location? = null
    private val classifier = LiveActivityClassifier()
    private var segmentStart = 0L
    private var accumulatedMillis = 0L
    private var fixFilter = FixFilter(ACCURACY_THRESHOLD)
    /** The ride in progress, kept on disk so a ride survives Android killing the app. */
    private val activeRide = context.getSharedPreferences("activeRide", Context.MODE_PRIVATE)
    private val restoring = Mutex()

    init {
        // Android killed the app mid-ride (battery saver, low memory): pick the ride up where it was.
        scope.launch { restoreActiveRide() }
    }

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Safe to call repeatedly; call again once permission is granted. */
    fun startLocationUpdates() = restartUpdates()

    /**
     * The permissions asked as a ride starts have been answered: motion can be listened to now, and the ride
     * notification is posted again so it shows if notifications were just allowed.
     */
    fun onRidePermissionsResult() {
        if (!_state.value.isTracking) return
        refreshMotionMonitoring()
        startService()
    }

    fun start() {
        if (_state.value.isTracking) return
        scope.launch {
            session = repository.nextSessionId()
            lastLocation = null
            classifier.reset()
            fixFilter = FixFilter(ACCURACY_THRESHOLD, notBeforeMillis = System.currentTimeMillis())
            accumulatedMillis = 0
            _state.update {
                it.copy(
                    isTracking = true, isPaused = false, distanceMeters = 0.0, maxSpeed = 0.0,
                    coordinates = emptyList(), elapsedSeconds = 0, activity = Activity.STILL,
                    gForce = null, maxGForce = null,
                )
            }
            gSensor.start()
            startTimer()
            saveActiveRide()
            restartUpdates()
            refreshMotionMonitoring()
            startService()
        }
    }

    /**
     * Keeps the process in the foreground. Android 12+ can refuse this from the background (a restore after a
     * kill); the ride then records only while the process lives, so the failure is logged rather than hidden.
     */
    private fun startService() {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java)) }
            .onFailure {
                android.util.Log.e("Tracking", "foreground service not started", it)
                runCatching { FirebaseCrashlytics.getInstance().recordException(it) }
            }
    }

    /**
     * Restores a ride that was recording when the process died: same session, its points so far, its distance,
     * top speed and clock. True when a ride is (now) in progress.
     */
    suspend fun restoreActiveRide(): Boolean = restoring.withLock {
        if (_state.value.isTracking) return true
        val saved = activeRide.getInt("session", 0)
        if (saved == 0) return false
        val points = repository.sessionPoints(saved)
        val paused = activeRide.getBoolean("paused", false)
        val now = System.currentTimeMillis()
        session = saved
        lastLocation = null
        classifier.reset()
        fixFilter = FixFilter(ACCURACY_THRESHOLD, notBeforeMillis = now)
        // The clock kept running while the app was dead, unless the ride was paused.
        accumulatedMillis = activeRide.getLong("accumulated", 0) +
            if (paused) 0 else maxOf(0L, now - activeRide.getLong("segmentStart", now))
        _state.update {
            it.copy(
                isTracking = true, isPaused = paused,
                distanceMeters = activeRide.getFloat("distance", 0f).toDouble(),
                maxSpeed = maxOf(activeRide.getFloat("maxSpeed", 0f).toDouble(), points.maxOfOrNull { p -> p.speed } ?: 0.0),
                coordinates = SessionSummary(saved, points).coordinates,
                elapsedSeconds = accumulatedMillis / 1000, activity = Activity.STILL,
                gForce = null, maxGForce = points.mapNotNull { p -> p.gForce }.maxOrNull(),
            )
        }
        gSensor.start()
        if (paused) segmentStart = now else startTimer()
        saveActiveRide()
        restartUpdates()
        refreshMotionMonitoring()
        startService()
        true
    }

    private fun saveActiveRide() {
        val s = _state.value
        activeRide.edit()
            .putInt("session", session)
            .putLong("accumulated", accumulatedMillis)
            .putLong("segmentStart", segmentStart)
            .putBoolean("paused", s.isPaused)
            .putFloat("distance", s.distanceMeters.toFloat())
            .putFloat("maxSpeed", s.maxSpeed.toFloat())
            .apply()
    }

    fun pause() {
        val s = _state.value
        if (!s.isTracking || s.isPaused) return
        accumulatedMillis = currentElapsedMillis()
        timerJob?.cancel()
        _state.update { it.copy(isPaused = true, elapsedSeconds = accumulatedMillis / 1000) }
        saveActiveRide()
    }

    fun resume() {
        val s = _state.value
        if (!s.isTracking || !s.isPaused) return
        lastLocation = null
        classifier.reset()
        gSensor.takePeak()
        _state.update { it.copy(isPaused = false, activity = Activity.STILL) }
        startTimer()
        saveActiveRide()
    }

    fun stop() {
        if (!_state.value.isTracking) return
        timerJob?.cancel()
        gSensor.stop()
        sessionMetadata.save(session, fixFilter.metadata)
        activeRide.edit().clear().apply()
        _state.update { it.copy(isTracking = false, isPaused = false) }
        restartUpdates()
        refreshMotionMonitoring()
        context.stopService(Intent(context, TrackingService::class.java))
    }

    private fun currentElapsedMillis() = accumulatedMillis + (System.currentTimeMillis() - segmentStart)

    private fun startTimer() {
        segmentStart = System.currentTimeMillis()
        timerJob?.cancel()
        timerJob = scope.launch {
            var tick = 0
            while (true) {
                delay(1000)
                _state.update { it.copy(elapsedSeconds = currentElapsedMillis() / 1000, gForce = gSensor.latest ?: it.gForce) }
                // Distance and top speed for a restore after the app is killed; points are already in the database.
                if (++tick % 10 == 0 && _state.value.isTracking) saveActiveRide()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartUpdates() {
        updatesJob?.cancel()
        if (!hasPermission) return
        val tracking = _state.value.isTracking
        val request = LocationRequest.Builder(
            if (tracking) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            if (tracking) 1000L else 5000L,
        ).build()
        updatesJob = scope.launch { locationUpdates(request).collect(::handle) }
    }

    @SuppressLint("MissingPermission")
    private fun locationUpdates(request: LocationRequest): Flow<Location> = callbackFlow {
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                // Several fixes can arrive at once; dropping all but the last left gaps in the timeline.
                result.locations.forEach { trySend(it) }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }

    private fun handle(location: Location) {
        _state.update { it.copy(current = location) }
        val s = _state.value
        if (!s.isTracking || s.isPaused) return
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        if (fixFilter.check(accuracy, location.time) != FixFilter.Verdict.ACCEPT) return

        val speed = maxOf(location.speed, 0f).toDouble()
        val rawSpeed = if (location.hasSpeed()) location.speed.toDouble() else -1.0
        classifier.add(location.time, location.altitude, speed, location.latitude, location.longitude)
        val activity = classifier.activity
        // While standing, GPS drift would add distance and speed from nothing. The point is still
        // stored below, so the ride keeps its timeline.
        if (activity != Activity.STILL) {
            val last = lastLocation
            // Noise-sized steps are skipped and the anchor stays, so slow real movement still adds up.
            val step = if (last == null) 0.0 else location.distanceTo(last).toDouble()
            val counts = last == null || DistanceRule.counts(step, rawSpeed, accuracy?.toDouble())
            if (counts) lastLocation = location
            // The map line grows only with real steps; the fix itself is saved below either way.
            _state.update {
                it.copy(
                    distanceMeters = it.distanceMeters + if (counts) step else 0.0,
                    maxSpeed = maxOf(it.maxSpeed, speed),
                    coordinates = if (counts) it.coordinates + LatLng(location.latitude, location.longitude) else it.coordinates,
                    activity = activity,
                )
            }
        } else {
            _state.update { it.copy(activity = activity) }
        }
        val sample = motion.latest.value
        val peakG = gSensor.takePeak()
        if (peakG != null) _state.update { it.copy(maxGForce = maxOf(it.maxGForce ?: 0.0, peakG)) }
        val point = TrackPointEntity(
            lat = location.latitude, lon = location.longitude, altitude = location.altitude,
            speed = speed, timestamp = location.time, session = session,
            rawSpeed = rawSpeed,
            horizontalAccuracy = accuracy?.toDouble() ?: -1.0,
            verticalAccuracy = if (Build.VERSION.SDK_INT >= 26 && location.hasVerticalAccuracy()) location.verticalAccuracyMeters.toDouble() else -1.0,
            speedAccuracy = if (Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond.toDouble() else -1.0,
            course = if (location.hasBearing()) location.bearing.toDouble() else -1.0,
            courseAccuracy = if (Build.VERSION.SDK_INT >= 26 && location.hasBearingAccuracy()) location.bearingAccuracyDegrees.toDouble() else -1.0,
            activity = sample?.kind?.csv,
            activityConfidence = sample?.confidence,
            gForce = peakG,
            sport = Sport.saved(context).key,
        )
        scope.launch(Dispatchers.IO) { repository.addPoint(point) }
    }

    /** Motion data is only listened to while recording; it fills the activity column of the CSV. */
    private fun refreshMotionMonitoring() {
        if (_state.value.isTracking) motion.start() else motion.stop()
    }

    private companion object {
        /** Fixes less accurate than this are not recorded. Stricter than the 50 m default of [FixFilter]. */
        const val ACCURACY_THRESHOLD = 20f
    }
}
