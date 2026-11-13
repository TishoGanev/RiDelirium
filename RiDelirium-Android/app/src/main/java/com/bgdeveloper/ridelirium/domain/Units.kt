package com.bgdeveloper.ridelirium.domain

import java.util.Locale

data class Units(val metric: Boolean = true) {
    fun distanceValue(meters: Double): Double = (meters / 1000).let { if (metric) it else it * KM_TO_MILES }

    fun speedValue(metersPerSecond: Double): Double =
        (maxOf(metersPerSecond, 0.0) * 3.6).let { if (metric) it else it * KM_TO_MILES }

    fun altitudeValue(meters: Double): Double = if (metric) meters else meters * FEET_PER_METER

    val distanceUnit: String get() = if (metric) "km" else "mi"
    val speedUnit: String get() = if (metric) "KM/H" else "MPH"
    val altitudeUnit: String get() = if (metric) "m" else "ft"

    fun distance(meters: Double): String = format("%.2f %s", distanceValue(meters), distanceUnit)
    fun speed(metersPerSecond: Double): String = format("%.1f %s", speedValue(metersPerSecond), speedUnit)
    fun altitude(meters: Double): String = format("%.0f %s", altitudeValue(meters), altitudeUnit)

    fun duration(seconds: Long): String =
        format("%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)

    /** "1H 42M" or "12M"; for summaries where seconds are noise. */
    fun compactDuration(seconds: Double): String {
        val minutes = (seconds / 60).toInt()
        return if (minutes >= 60) "${minutes / 60}H ${minutes % 60}M" else "${minutes}M"
    }

    private fun format(pattern: String, vararg args: Any) = String.format(Locale.US, pattern, *args)

    private companion object {
        const val KM_TO_MILES = 0.621
        const val FEET_PER_METER = 3.281
    }
}
