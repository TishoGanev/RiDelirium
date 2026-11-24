package com.bgdeveloper.ridelirium.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tempo of a hike, worked out in code and handed to the language model as facts. Pace is time per distance while
 * moving. Android phones record no heart rate, so unlike iOS there is none here.
 * Keep in sync with `HikeMetrics.swift` on iOS.
 */
data class HikeMetrics(
    /** Seconds per metre while not standing; null without distance. */
    val averagePace: Double? = null,
    val climbPace: Double? = null,
    val descentPace: Double? = null,
    /** Metres climbed per hour of climbing. */
    val climbRate: Double? = null,
    /** Second half's pace against the first, in percent; positive means slower. */
    val paceChange: Double? = null,
) {
    fun promptLines(units: Units): List<String> {
        val lines = mutableListOf("Hiking measurements, worked out in code:")
        averagePace?.let { lines += "- Tempo: average pace while moving ${paceText(it, units)}" }
        val split = listOfNotNull(
            climbPace?.let { "climbs ${paceText(it, units)}" },
            descentPace?.let { "descents ${paceText(it, units)}" },
        )
        if (split.isNotEmpty()) lines += "- Pace on " + split.joinToString(", ")
        climbRate?.let { lines += "- Climbing rate: ${units.altitude(it)} per hour of climbing" }
        paceChange?.let {
            lines += if (abs(it) < STEADY_PERCENT) {
                "- Tempo was steady: the second half's pace matched the first half"
            } else {
                "- Pace in the second half was ${abs(it).roundToInt()}% ${if (it > 0) "slower" else "faster"} than in the first half"
            }
        }
        lines += "- Heart rate: not recorded on this phone; do not mention it"
        return lines
    }

    companion object {
        /** A pace change smaller than this is noise. */
        const val STEADY_PERCENT = 8.0

        fun compute(analysis: RideAnalysis): HikeMetrics {
            val moving = analysis.segments.filter { it.activity != Activity.STILL }

            fun pace(group: List<Segment>): Double? {
                val distance = group.sumOf { it.distance }
                val time = group.sumOf { it.durationSeconds }
                return if (distance > 0 && time > 0) time / distance else null
            }

            val climbTime = analysis.duration(Activity.LIFT)
            val total = moving.sumOf { it.durationSeconds }
            var elapsed = 0.0
            val first = mutableListOf<Segment>()
            val second = mutableListOf<Segment>()
            for (segment in moving) {
                if (elapsed + segment.durationSeconds / 2 < total / 2) first += segment else second += segment
                elapsed += segment.durationSeconds
            }
            val early = pace(first)
            val late = pace(second)
            return HikeMetrics(
                averagePace = pace(moving),
                climbPace = pace(moving.filter { it.activity == Activity.LIFT }),
                descentPace = pace(moving.filter { it.activity == Activity.RUN }),
                climbRate = if (climbTime > 0 && analysis.liftVertical > 0) analysis.liftVertical / (climbTime / 3600) else null,
                paceChange = if (total >= 600 && early != null && late != null) (late - early) / early * 100 else null,
            )
        }

        /** "12:30 min/km" from seconds per metre. */
        fun paceText(secondsPerMeter: Double, units: Units): String {
            val whole = (secondsPerMeter * (if (units.metric) 1000.0 else 1609.344)).roundToInt()
            return "%d:%02d min/%s".format(java.util.Locale.US, whole / 60, whole % 60, if (units.metric) "km" else "mi")
        }
    }
}
