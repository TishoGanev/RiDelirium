package com.bgdeveloper.snowman.domain

import com.bgdeveloper.snowman.data.TrackPointEntity
import com.google.android.gms.maps.model.LatLng
import java.time.LocalDate
import java.time.ZoneId

data class SessionSummary(val session: Int, val points: List<TrackPointEntity>) {
    val startMillis: Long get() = points.firstOrNull()?.timestamp ?: 0L
    val endMillis: Long get() = points.lastOrNull()?.timestamp ?: 0L
    /** Runs, lifts and stops; computed once per summary. */
    val analysis: RideAnalysis by lazy { ActivityDetector.analyze(points) }

    val maxSpeed: Double
        get() = if (analysis.segments.isEmpty()) points.maxOfOrNull { it.speed } ?: 0.0 else analysis.movingMaxSpeed
    val coordinates: List<LatLng> get() = points.map { LatLng(it.lat, it.lon) }

    /** Stable across launches even if a deleted session's number is reused. */
    val placeKey: String get() = "$session-${startMillis / 1000}"

    val startDate: LocalDate get() = java.time.Instant.ofEpochMilli(startMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    /** Distance covered while moving; GPS drift from standing still isn't counted. */
    val distance: Double
        get() {
            if (analysis.segments.isNotEmpty()) return analysis.movingDistance
            return DistanceRule.steps(points).sum()
        }
}

/** Ski seasons run across New Year, so July starts the next one (Nov 2025 and Mar 2026 are both 2025/26). */
data class Season(val startYear: Int) {
    val label: String get() = "%d/%02d".format(startYear, (startYear + 1) % 100)

    companion object {
        fun containing(date: LocalDate) = Season(if (date.monthValue >= 7) date.year else date.year - 1)
    }
}
