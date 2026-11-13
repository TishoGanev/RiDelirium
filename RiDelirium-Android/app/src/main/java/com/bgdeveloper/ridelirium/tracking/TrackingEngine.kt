package com.bgdeveloper.ridelirium.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.bgdeveloper.ridelirium.data.RideRepository
import com.bgdeveloper.ridelirium.data.SessionMetadataStore
import com.bgdeveloper.ridelirium.data.SettingsRepository
import com.bgdeveloper.ridelirium.data.TrackPointEntity
import com.bgdeveloper.ridelirium.domain.Activity
import com.bgdeveloper.ridelirium.domain.DistanceRule
import com.bgdeveloper.ridelirium.domain.FixFilter
import com.bgdeveloper.ridelirium.domain.LiveActivityClassifier
import com.bgdeveloper.ridelirium.domain.MotionAutoControl
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
    private val autoControl = MotionAutoControl()

    init {
        // Motion activity decides auto start/stop; settings decide whether it is listened to at all.
        scope.launch { motion.latest.collect { applyMotion() } }
        scope.launch { settings.autoStart.collect { refreshMotionMonitoring() } }
    }

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Safe to call repeatedly; call again once permission is granted. */
    fun startLocationUpdates() = restartUpdates()

    fun start() {
        if (_state.value.isTracking) return
        scope.launch {
            session = repository.nextSessionId()
            lastLocation = null
            classifier.reset()
            fixFilter = FixFilter(ACCURACY_THRESHOLD, notBeforeMillis = System.currentTimeMillis())
            autoControl.reset()
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
            restartUpdates()
            refreshMotionMonitoring()
            // Android 12+ refuses to start a foreground service from the background, which is where
            // auto start fires from. The ride still records while the process lives.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java)) }
        }
    }

    fun pause() {
        val s = _state.value
        if (!s.isTracking || s.isPaused) return
        accumulatedMillis = currentElapsedMillis()
        timerJob?.cancel()
        _state.update { it.copy(isPaused = true, elapsedSeconds = accumulatedMillis / 1000) }
    }

    fun resume() {
        val s = _state.value
        if (!s.isTracking || !s.isPaused) return
        lastLocation = null
        classifier.reset()
        gSensor.takePeak()
        _state.update { it.copy(isPaused = false, activity = Activity.STILL) }
        startTimer()
    }

    fun stop() {
        if (!_state.value.isTracking) return
        timerJob?.cancel()
        gSensor.stop()
        sessionMetadata.save(session, fixFilter.metadata)
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
            while (true) {
                delay(1000)
                _state.update { it.copy(elapsedSeconds = currentElapsedMillis() / 1000, gForce = gSensor.latest ?: it.gForce) }
                applyMotion()
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
            _state.update {
                it.copy(
                    distanceMeters = it.distanceMeters + if (counts) step else 0.0,
                    maxSpeed = maxOf(it.maxSpeed, speed),
                    coordinates = it.coordinates + LatLng(location.latitude, location.longitude),
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
            verticalAccuracy = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters.toDouble() else -1.0,
            speedAccuracy = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond.toDouble() else -1.0,
            course = if (location.hasBearing()) location.bearing.toDouble() else -1.0,
            courseAccuracy = if (location.hasBearingAccuracy()) location.bearingAccuracyDegrees.toDouble() else -1.0,
            activity = sample?.kind?.csv,
            activityConfidence = sample?.confidence,
            gForce = peakG,
        )
        scope.launch(Dispatchers.IO) { repository.addPoint(point) }
    }

    /** Motion data is only listened to while recording, or when auto start needs it to notice driving. */
    private fun refreshMotionMonitoring() {
        if (_state.value.isTracking || settings.autoStart.value) motion.start() else motion.stop()
    }

    private fun applyMotion() {
        autoControl.autoStart = settings.autoStart.value
        autoControl.autoStop = settings.autoStop.value
        autoControl.stopAfterMillis = settings.stopMinutes.value * 60_000L
        when (autoControl.evaluate(motion.latest.value, System.currentTimeMillis(), _state.value.isTracking)) {
            MotionAutoControl.Action.START -> start()
            MotionAutoControl.Action.STOP -> stop()
            MotionAutoControl.Action.NONE -> Unit
        }
    }

    private companion object {
        /** Fixes less accurate than this are not recorded. Stricter than the 50 m default of [FixFilter]. */
        const val ACCURACY_THRESHOLD = 20f
    }
}
