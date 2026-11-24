package com.bgdeveloper.ridelirium.domain

import com.bgdeveloper.ridelirium.data.TrackPointEntity
import com.bgdeveloper.ridelirium.friends.Sport
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

    fun bullets(analysis: RideAnalysis, points: List<TrackPointEntity>, units: Units, fallback: Sport = Sport.BOARD): List<String> {
        // The sport can change during a ride; the one ridden longest leads.
        val timeline = SportTimeline(points, fallback)
        val sport = timeline.dominant ?: fallback
        val sports = timeline.distinct.ifEmpty { listOf(sport) }
        val mixed = sports.size > 1
        // One neutral vocabulary when the sports differ.
        val words = if (mixed) Sport.BIKE.words else sport.words
        val runs = analysis.runs
        val lifts = analysis.lifts
        val riding = analysis.duration(Activity.RUN)
        val lifting = analysis.duration(Activity.LIFT)
        val standing = analysis.duration(Activity.STILL)
        val total = riding + lifting + standing
        val bullets = mutableListOf<String>()

        if (runs.isEmpty()) {
            bullets += "No downhill ${words.runs} were detected in this recording."
        } else {
            val numbered = runs.withIndex()
            // Speed is no goal on foot, so a hike never singles out its fastest stretch.
            numbered.filter { timeline.sportAt(it.value.startIndex).showsSpeed }.maxByOrNull { it.value.maxSpeed }?.let {
                bullets += "${words.run.cap()} ${it.index + 1} was your fastest, topping out at ${units.speed(it.value.maxSpeed)}."
            }
            if (runs.size > 1) {
                numbered.maxByOrNull { it.value.vertical }?.let {
                    bullets += "${words.run.cap()} ${it.index + 1} had the most descent: ${units.altitude(it.value.vertical)} over ${units.distance(it.value.distance)}."
                }
            }
            if (sports.any { it.showsSpeed } && !mixed && runs.size >= 4) {
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
            bullets += "You spent ${share(riding)}% of the day ${words.downActivity}, ${share(lifting)}% ${words.upActivity} and ${share(standing)}% ${words.stopActivity}."
        }
        analysis.stills.maxByOrNull { it.durationSeconds }?.takeIf { it.durationSeconds >= LONG_STOP_SECONDS }?.let {
            val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(points[it.startIndex].timestamp))
            bullets += "Your longest ${words.stop} was ${units.compactDuration(it.durationSeconds)}, around $time."
        }
        if (lifts.isNotEmpty()) {
            val average = lifts.sumOf { it.durationSeconds } / lifts.size
            bullets += "${lifts.size} ${if (lifts.size == 1) words.lift else words.lifts}, about ${units.compactDuration(average)} each."
        }
        if (total >= MIN_SECONDS_FOR_HOURLY_DESCENT && analysis.totalVertical > 0) {
            val perHour = analysis.totalVertical / (total / 3600)
            bullets += "That is ${units.altitude(perHour)} of descent for every hour on the mountain."
        }
        return bullets
    }
}

private fun String.cap() = replaceFirstChar { it.uppercase() }
