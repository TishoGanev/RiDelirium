package com.bgdeveloper.snowman.domain

import com.bgdeveloper.snowman.data.TrackPointEntity
import com.google.android.gms.maps.model.LatLng

/**
 * A recorded ride laid out on a playback timeline.
 *
 * Time follows the recorded timestamps, but any gap longer than [MAX_GAP] seconds (a pause, or GPS
 * dropping out) is squeezed to [MAX_GAP] so playback doesn't sit still.
 */
class RideReplay(private val points: List<TrackPointEntity>) {
    val coordinates: List<LatLng> = points.map { LatLng(it.lat, it.lon) }
    private val offsets = DoubleArray(points.size)
    val duration: Double

    init {
        var elapsed = 0.0
        for (i in points.indices) {
            if (i > 0) elapsed += ((points[i].timestamp - points[i - 1].timestamp) / 1000.0).coerceIn(0.0, MAX_GAP)
            offsets[i] = elapsed
        }
        duration = elapsed
    }

    val isEmpty: Boolean get() = points.isEmpty()

    /** Index of the last point at or before [time]. */
    private fun indexAt(time: Double): Int {
        var low = 0
        var high = offsets.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (offsets[mid] <= time) low = mid else high = mid - 1
        }
        return low
    }

    fun position(time: Double): LatLng? {
        if (isEmpty) return null
        val i = indexAt(time)
        if (i + 1 >= points.size) return coordinates[i]
        val span = offsets[i + 1] - offsets[i]
        val f = if (span > 0) (time - offsets[i]) / span else 0.0
        val a = coordinates[i]
        val b = coordinates[i + 1]
        return LatLng(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f)
    }

    /**
     * Points in the settled part of the trail. It only grows every [CHUNK] points, so the long
     * polyline isn't rebuilt on every frame.
     */
    fun committedCount(time: Double): Int = indexAt(time) / CHUNK * CHUNK + 1

    fun committed(count: Int): List<LatLng> = coordinates.subList(0, minOf(count, coordinates.size)).toList()

    /** The short, changing end of the trail: from the last settled point to the rider. */
    fun head(time: Double): List<LatLng> {
        val position = position(time) ?: return emptyList()
        return coordinates.subList(committedCount(time) - 1, indexAt(time) + 1) + position
    }

    fun speedAt(time: Double): Double = if (isEmpty) 0.0 else points[indexAt(time)].speed
    fun altitudeAt(time: Double): Double = if (isEmpty) 0.0 else points[indexAt(time)].altitude

    /** Peak G-force at [time]; null when the ride has none (no accelerometer, or recorded before it was tracked). */
    fun gForceAt(time: Double): Double? = if (isEmpty) null else points[indexAt(time)].gForce

    companion object {
        val SPEEDS = listOf(1, 2, 4, 8, 16)
        private const val MAX_GAP = 5.0
        private const val CHUNK = 50
    }
}
