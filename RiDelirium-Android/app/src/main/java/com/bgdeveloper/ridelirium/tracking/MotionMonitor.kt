package com.bgdeveloper.ridelirium.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.bgdeveloper.ridelirium.domain.MotionKind
import com.bgdeveloper.ridelirium.domain.MotionSample
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streams Google Play services activity recognition. Does nothing without the ACTIVITY_RECOGNITION
 * permission; then no activity is recorded and auto start/stop never fires.
 */
@Singleton
class MotionMonitor @Inject constructor(@ApplicationContext private val context: Context) {
    private val _latest = MutableStateFlow<MotionSample?>(null)
    val latest: StateFlow<MotionSample?> = _latest.asStateFlow()
    private var running = false

    /** Always false below [MIN_SDK]: the permission does not exist there, so nothing is recorded or acted on. */
    val hasPermission: Boolean
        get() = isSupported && ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, MotionReceiver::class.java).setPackage(context.packageName),
        // Play services fills in the result extras, so the intent has to be mutable.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !hasPermission) return
        running = true
        ActivityRecognition.getClient(context).requestActivityUpdates(DETECTION_INTERVAL_MILLIS, pendingIntent())
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        running = false
        _latest.value = null
        if (hasPermission) ActivityRecognition.getClient(context).removeActivityUpdates(pendingIntent())
    }

    internal fun onResult(result: ActivityRecognitionResult) {
        val best = result.mostProbableActivity
        val kind = when (best.type) {
            DetectedActivity.IN_VEHICLE -> MotionKind.AUTOMOTIVE
            DetectedActivity.ON_BICYCLE -> MotionKind.CYCLING
            DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_FOOT -> MotionKind.WALKING
            DetectedActivity.STILL -> MotionKind.STATIONARY
            else -> MotionKind.UNKNOWN
        }
        // Play services reports 0-100; map to the same low/medium/high steps as iOS.
        val confidence = when {
            best.confidence >= 75 -> 2
            best.confidence >= 50 -> 1
            else -> 0
        }
        _latest.value = MotionSample(kind, confidence)
    }

    companion object {
        private const val DETECTION_INTERVAL_MILLIS = 10_000L

        /** Android 10: the first version with the ACTIVITY_RECOGNITION runtime permission. */
        const val MIN_SDK = 29

        /** Motion activity (auto start and stop, the activity column in the CSV) needs Android 10 or later. */
        val isSupported: Boolean get() = Build.VERSION.SDK_INT >= MIN_SDK
    }
}

@AndroidEntryPoint
class MotionReceiver : BroadcastReceiver() {
    @Inject lateinit var monitor: MotionMonitor

    override fun onReceive(context: Context, intent: Intent) {
        if (ActivityRecognitionResult.hasResult(intent)) {
            ActivityRecognitionResult.extractResult(intent)?.let(monitor::onResult)
        }
    }
}
