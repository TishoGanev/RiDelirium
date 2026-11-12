package com.bgdeveloper.snowman.domain

import com.bgdeveloper.snowman.data.TrackPointEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/** Plain-language observations about one ride, worked out in code from the detected segments. */
object RideInsights {
    private const val LONG_STOP_SECONDS = 300.0
    private const val SPEED_CHANGE_PERCENT = 8
    private const val MIN_SECONDS_FOR_HOURLY_DESCENT = 1800.0

    fun bullets(analysis: RideAnalysis, points: List<TrackPointEntity>, units: Units): List<String> {
        val runs = analysis.runs
        val lifts = analysis.lifts
        val riding = analysis.duration(Activity.RUN)
        val lifting = analysis.duration(Activity.LIFT)
        val standing = analysis.duration(Activity.STILL)
        val total = riding + lifting + standing
        val bullets = mutableListOf<String>()

        if (runs.isEmpty()) {
            bullets += "No downhill runs were detected in this recording."
        } else {
            val numbered = runs.withIndex()
            numbered.maxByOrNull { it.value.maxSpeed }?.let {
                bullets += "Run ${it.index + 1} was your fastest, topping out at ${units.speed(it.value.maxSpeed)}."
            }
            if (runs.size > 1) {
                numbered.maxByOrNull { it.value.vertical }?.let {
                    bullets += "Run ${it.index + 1} had the most descent: ${units.altitude(it.value.vertical)} over ${units.distance(it.value.distance)}."
                }
            }
            if (runs.size >= 4) {
                val half = runs.size / 2
                val first = runs.take(half).map { it.maxSpeed }.average()
                val second = runs.drop(half).map { it.maxSpeed }.average()
                if (first > 0) {
                    val change = (second - first) / first * 100
                    if (abs(change) >= SPEED_CHANGE_PERCENT) {
                        bullets += "Your top speeds were ${abs(change).roundToInt()}% ${if (change < 0) "lower" else "higher"} in the second half of the day."
                    }
                }
            }
        }
        if (total > 0) {
            fun share(value: Double) = (value / total * 100).roundToInt()
            bullets += "You spent ${share(riding)}% of the day riding, ${share(lifting)}% on lifts and ${share(standing)}% standing still."
        }
        analysis.stills.maxByOrNull { it.durationSeconds }?.takeIf { it.durationSeconds >= LONG_STOP_SECONDS }?.let {
            val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(points[it.startIndex].timestamp))
            bullets += "Your longest stop was ${units.compactDuration(it.durationSeconds)}, around $time."
        }
        if (lifts.isNotEmpty()) {
            val average = lifts.sumOf { it.durationSeconds } / lifts.size
            bullets += "${lifts.size} ${if (lifts.size == 1) "lift" else "lifts"}, about ${units.compactDuration(average)} each."
        }
        if (total >= MIN_SECONDS_FOR_HOURLY_DESCENT && analysis.totalVertical > 0) {
            val perHour = analysis.totalVertical / (total / 3600)
            bullets += "That is ${units.altitude(perHour)} of descent for every hour on the mountain."
        }
        return bullets
    }
}
