package com.bgdeveloper.ridelirium.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/** What one ride day amounts to, reduced to the numbers progress is judged by. */
data class DayMetrics(
    val dateMillis: Long,
    val runs: Int,
    val downhillDistance: Double,
    val vertical: Double,
    val topSpeed: Double,
    val ridingSeconds: Double,
    val totalSeconds: Double,
) {
    companion object {
        fun of(session: SessionSummary): DayMetrics {
            val a = session.analysis
            return DayMetrics(
                dateMillis = session.startMillis,
                runs = a.runs.size,
                downhillDistance = a.downhillDistance,
                vertical = a.totalVertical,
                topSpeed = a.runs.maxOfOrNull { it.maxSpeed } ?: 0.0,
                ridingSeconds = a.duration(Activity.RUN),
                totalSeconds = a.duration(Activity.RUN) + a.duration(Activity.LIFT) + a.duration(Activity.STILL),
            )
        }
    }
}

/**
 * Plain-language progress over many rides, worked out in code: earlier rides against later ones.
 * Keep in sync with `RideInsightFacts(period:)` in `StatsRange.swift` on iOS. Android has no on-device
 * language model, so these lines are the whole summary.
 */
object PeriodInsights {
    private const val CHANGE_PERCENT = 8

    fun bullets(metrics: List<DayMetrics>, units: Units): List<String> {
        val days = metrics.filter { it.runs > 0 }.sortedBy { it.dateMillis }
        if (days.isEmpty()) return listOf("No downhill runs were found in this period.")

        val bullets = mutableListOf<String>()
        val runs = days.sumOf { it.runs }
        bullets += "${days.size} ride ${if (days.size == 1) "day" else "days"}, $runs runs, " +
            "${units.altitude(days.sumOf { it.vertical })} of descent over ${units.distance(days.sumOf { it.downhillDistance })}."
        if (days.size == 1) {
            bullets += "Only one ride day in this period, so there is no trend to show yet."
            return bullets
        }
        days.maxByOrNull { it.vertical }?.let { bullets += "Most descent in a day: ${units.altitude(it.vertical)} on ${day(it.dateMillis)}." }
        days.maxByOrNull { it.topSpeed }?.let { bullets += "Fastest day: ${units.speed(it.topSpeed)} on ${day(it.dateMillis)}." }
        bullets += trends(days, units)
        return bullets
    }

    private fun day(millis: Long): String =
        DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))

    /**
     * Earlier rides against later ones. With 2 or 3 days it is the first against the last; with more, the
     * first half against the second. Changes under 8% are noise and are not reported.
     */
    fun trends(days: List<DayMetrics>, units: Units): List<String> {
        if (days.size < 2) return emptyList()
        val early: List<DayMetrics>
        val late: List<DayMetrics>
        if (days.size >= 4) {
            val half = days.size / 2
            early = days.take(half)
            late = days.drop(half)
        } else {
            early = listOf(days.first())
            late = listOf(days.last())
        }
        val out = mutableListOf<String>()

        fun compare(label: String, before: Double, after: Double, format: (Double) -> String) {
            if (before <= 0) return
            val change = (after - before) / before * 100
            if (abs(change) < CHANGE_PERCENT) return
            out += "$label is ${abs(change).roundToInt()}% ${if (change > 0) "higher" else "lower"} in your later rides (${format(after)} against ${format(before)})."
        }

        compare("Top speed", early.map { it.topSpeed }.average(), late.map { it.topSpeed }.average()) { units.speed(it) }
        compare("Descent per day", early.map { it.vertical }.average(), late.map { it.vertical }.average()) { units.altitude(it) }
        compare("Runs per day", early.map { it.runs.toDouble() }.average(), late.map { it.runs.toDouble() }.average()) { it.roundToInt().toString() }

        fun perHour(group: List<DayMetrics>): Double {
            val time = group.sumOf { it.totalSeconds }
            return if (time > 0) group.sumOf { it.vertical } / (time / 3600) else 0.0
        }
        compare("Descent per hour on the mountain", perHour(early), perHour(late)) { units.altitude(it) }

        if (out.isEmpty()) out += "Your numbers are steady across this period: no big change either way."
        return out
    }
}
