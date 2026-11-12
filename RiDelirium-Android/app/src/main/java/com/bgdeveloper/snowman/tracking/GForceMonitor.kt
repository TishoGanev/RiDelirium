package com.bgdeveloper.snowman.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Total G-force from the accelerometer, gravity included, so a phone at rest reads about 1.0 g.
 * Only listens while recording. [isAvailable] is false on devices without an accelerometer.
 * Keep in sync with `GForceMonitor.swift` on iOS.
 */
@Singleton
class GForceMonitor @Inject constructor(@ApplicationContext context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var running = false

    val isAvailable: Boolean get() = sensor != null

    /** The latest reading in g, or null when not listening. */
    @Volatile var latest: Double? = null
        private set

    @Volatile private var peak = 0.0

    fun start() {
        if (running || sensor == null) return
        running = true
        peak = 0.0
        manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!running) return
        running = false
        manager.unregisterListener(this)
        latest = null
    }

    /** The highest reading since the last call, or null if there was none. Starts a new window. */
    fun takePeak(): Double? {
        val value = peak
        peak = 0.0
        return value.takeIf { it > 0 }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val g = sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH
        latest = g
        if (g > peak) peak = g
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
