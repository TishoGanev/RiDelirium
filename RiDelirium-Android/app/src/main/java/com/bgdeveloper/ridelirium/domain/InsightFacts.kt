package com.bgdeveloper.ridelirium.domain

import com.bgdeveloper.ridelirium.data.TrackPointEntity
import com.bgdeveloper.ridelirium.friends.Sport
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What the on-device language model may talk about. The numbers are worked out in code (see [RideInsights] and
 * [PeriodInsights]); the model only puts them into words. Keep in sync with `RideInsightFacts` on iOS.
 */
data class InsightFacts(
    /** Plain-language observations, shown on every phone. */
    val bullets: List<String>,
    /** Everything the model is told about the ride or period. */
    val prompt: String,
    /** False when there is nothing to write about, so the model is not asked to invent something. */
    val hasRuns: Boolean,
    /** True for a summary over many rides: the model then talks about progress, not one day. */
    val isPeriod: Boolean,
    val sport: Sport,
) {
    /** The model's instructions, with the part that depends on the sport. */
    val instructions: String
        get() {
            val scope = if (isPeriod) {
                "several days of ride data and the changes between earlier and later rides. Say whether the rider is " +
                    "improving, holding steady or slipping, and what changed"
            } else {
                "one day of ride data"
            }
            return "You are a concise ${sport.coachRole} reading $scope. Use only the numbers you are given. " +
                "Never invent speeds, places or events. Plain, friendly language. No emojis. ${sport.coachBrief}"
        }

    companion object {
        fun ride(analysis: RideAnalysis, points: List<TrackPointEntity>, placeName: String, units: Units, sport: Sport): InsightFacts {
            val bullets = RideInsights.bullets(analysis, points, units)
            val runs = analysis.runs
            val lifts = analysis.lifts
            val riding = analysis.duration(Activity.RUN)
            val lifting = analysis.duration(Activity.LIFT)
            val standing = analysis.duration(Activity.STILL)
            val total = riding + lifting + standing
            val lines = mutableListOf(
                "Sport: ${sport.activity.lowercase()}",
                "Place: $placeName",
                "Total time: ${units.compactDuration(total)}; riding ${units.compactDuration(riding)}, lifts ${units.compactDuration(lifting)}, standing ${units.compactDuration(standing)}",
                "Runs: ${runs.size}; downhill distance ${units.distance(analysis.downhillDistance)}; total descent ${units.altitude(analysis.totalVertical)}",
                "Lifts: ${lifts.size}; total climb ${units.altitude(analysis.liftVertical)}",
            )
            runs.take(MAX_RUN_LINES).forEachIndexed { index, run ->
                lines += "Run ${index + 1}: ${units.duration(run.durationSeconds.toLong())}, ${units.distance(run.distance)}, " +
                    "descent ${units.altitude(run.vertical)}, top speed ${units.speed(run.maxSpeed)}"
            }
            lines += "Observations already worked out:"
            lines += bullets.map { "- $it" }
            return InsightFacts(bullets, lines.joinToString("\n"), hasRuns = runs.isNotEmpty(), isPeriod = false, sport = sport)
        }

        fun period(metrics: List<DayMetrics>, rangeTitle: String, units: Units, sport: Sport): InsightFacts {
            val days = metrics.filter { it.runs > 0 }.sortedBy { it.dateMillis }
            val bullets = PeriodInsights.bullets(metrics, units)
            val lines = mutableListOf(
                "Sport: ${sport.activity.lowercase()}",
                "Period: $rangeTitle",
                "Ride days with runs: ${days.size}; runs ${days.sumOf { it.runs }}; total descent ${units.altitude(days.sumOf { it.vertical })}; " +
                    "downhill distance ${units.distance(days.sumOf { it.downhillDistance })}",
            )
            val day = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault())
            days.takeLast(MAX_DAY_LINES).forEach {
                lines += "${day.format(Instant.ofEpochMilli(it.dateMillis))}: ${it.runs} runs, descent ${units.altitude(it.vertical)}, " +
                    "top speed ${units.speed(it.topSpeed)}, riding ${units.compactDuration(it.ridingSeconds)} of ${units.compactDuration(it.totalSeconds)}"
            }
            lines += "Changes already worked out (earlier rides against later rides):"
            lines += bullets.map { "- $it" }
            return InsightFacts(bullets, lines.joinToString("\n"), hasRuns = days.isNotEmpty(), isPeriod = true, sport = sport)
        }

        private const val MAX_RUN_LINES = 20
        private const val MAX_DAY_LINES = 31
    }
}

/** Who the model plays, so a hike is not coached like a snowboard day. */
val Sport.coachRole: String
    get() = when (this) {
        Sport.SKI -> "ski coach"
        Sport.BOARD -> "snowboarding coach"
        Sport.BIKE -> "mountain bike coach"
        Sport.HIKE -> "hiking guide"
    }

/**
 * What this sport's data means and what advice fits it. The ride data calls a downhill segment a "run" and a
 * climb a "lift" whatever the sport; the brief tells the model the right words. Same text as on iOS.
 */
val Sport.coachBrief: String
    get() = when (this) {
        Sport.SKI ->
            "The rider skis. Runs are ski runs and lifts are chairlifts or gondolas. Look at descent per run, top speeds, " +
                "and whether the legs tire in the second half. Tips are about skiing: edge control, speed control, pacing the day, resting."
        Sport.BOARD ->
            "The rider snowboards. Runs are snowboard runs and lifts are chairlifts or gondolas. Look at descent per run, top speeds, " +
                "and whether energy drops in the second half. Tips are about snowboarding: carving, edge control, pacing the day, resting."
        Sport.BIKE ->
            "The rider cycles off-road. Call runs descents and lifts climbs; never mention snow or chairlifts. Look at descent, " +
                "top speeds and the balance between climbing and descending. Tips are about cycling: braking, pacing climbs, hydration."
        Sport.HIKE ->
            "The rider hikes. Call runs descents and lifts climbs or ascents; never mention snow, skiing, snowboarding or chairlifts. " +
                "Speed is not the goal, so never praise or push top speed. Look at total ascent and descent, time moving against resting, " +
                "and steady effort. Tips are about hiking: steady pace, rest breaks, descending carefully on the knees, water and food."
    }
