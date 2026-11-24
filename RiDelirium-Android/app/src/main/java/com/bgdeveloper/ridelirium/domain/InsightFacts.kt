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
    /** Every sport used during the ride, in order. More than one when the rider changed sport on the way. */
    val sports: List<Sport> = listOf(sport),
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
            return "You are a concise $role reading $scope. Use only the numbers you are given. " +
                "Never invent speeds, places or events. Plain, friendly language. No emojis. $brief"
        }

    /** Who the model plays; a ride that changed sport gets a general coach. */
    val role: String get() = if (sports.size > 1) "outdoor sports coach" else sport.coachRole

    /** The sport's brief, or for a ride that changed sport one brief per sport used, in order. */
    val brief: String
        get() {
            if (sports.size <= 1) return sport.coachBrief
            val names = sports.joinToString(", then ") { it.activity.lowercase() }
            return "The rider changed sport during this ride: $names. Each run line names its sport. Coach each part by its own sport, " +
                "use each sport's own words, and never apply one sport's advice to another. " +
                sports.joinToString(" ") { "For ${it.activity.lowercase()}: ${it.coachBrief}" }
        }

    companion object {
        fun ride(analysis: RideAnalysis, points: List<TrackPointEntity>, placeName: String, units: Units, fallback: Sport): InsightFacts {
            val timeline = SportTimeline(points, fallback)
            val sport = timeline.dominant ?: fallback
            val sports = timeline.distinct.ifEmpty { listOf(sport) }
            val mixed = sports.size > 1
            val bullets = RideInsights.bullets(analysis, points, units, fallback)
            val words = if (mixed) Sport.BIKE.words else sport.words
            val runs = analysis.runs
            val lifts = analysis.lifts
            val riding = analysis.duration(Activity.RUN)
            val lifting = analysis.duration(Activity.LIFT)
            val standing = analysis.duration(Activity.STILL)
            val total = riding + lifting + standing
            val sportLine = if (mixed) {
                "Sports during this ride, in order: " +
                    timeline.spans.joinToString(", then ") { "${it.sport.activity.lowercase()} ${units.compactDuration(it.seconds)}" }
            } else {
                "Sport: ${sport.activity.lowercase()}"
            }
            val lines = mutableListOf(
                sportLine,
                "Place: $placeName",
                "Total time: ${units.compactDuration(total)}; riding ${units.compactDuration(riding)}, lifts ${units.compactDuration(lifting)}, standing ${units.compactDuration(standing)}",
                "${words.runs.cap()}: ${runs.size}; downhill distance ${units.distance(analysis.downhillDistance)}; total descent ${units.altitude(analysis.totalVertical)}",
                "${words.lifts.cap()}: ${lifts.size}; total climb ${units.altitude(analysis.liftVertical)}",
            )
            runs.take(MAX_RUN_LINES).forEachIndexed { index, run ->
                val tag = if (mixed) " (${timeline.sportAt(run.startIndex).activity.lowercase()})" else ""
                lines += "${words.run.cap()} ${index + 1}$tag: ${units.duration(run.durationSeconds.toLong())}, ${units.distance(run.distance)}, " +
                    "descent ${units.altitude(run.vertical)}, top speed ${units.speed(run.maxSpeed)}"
            }
            // Snow measurements only describe the parts of the ride spent on snow.
            val snow = RideAnalysis(analysis.segments.filter { timeline.sportAt(it.startIndex).isSnowSport })
            if (snow.runs.isNotEmpty()) lines += SnowMetrics.compute(snow, points).promptLines(snow, units)
            lines += "Observations already worked out:"
            lines += bullets.map { "- $it" }
            // Hiking measurements only describe the parts of the ride on foot.
            val walk = RideAnalysis(analysis.segments.filter { timeline.sportAt(it.startIndex) == Sport.HIKE })
            if (walk.segments.isNotEmpty()) lines += HikeMetrics.compute(walk).promptLines(units)
            return InsightFacts(bullets, lines.joinToString("\n"), hasRuns = runs.isNotEmpty(), isPeriod = false, sport = sport, sports = sports)
        }

        fun period(metrics: List<DayMetrics>, rangeTitle: String, units: Units, sport: Sport): InsightFacts {
            val days = metrics.filter { it.runs > 0 }.sortedBy { it.dateMillis }
            val bullets = PeriodInsights.bullets(metrics, units, sport)
            val lines = mutableListOf(
                "Sport: ${sport.activity.lowercase()}",
                "Period: $rangeTitle",
                "Ride days with ${sport.words.runs}: ${days.size}; ${sport.words.runs} ${days.sumOf { it.runs }}; total descent ${units.altitude(days.sumOf { it.vertical })}; " +
                    "downhill distance ${units.distance(days.sumOf { it.downhillDistance })}",
            )
            val day = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault())
            days.takeLast(MAX_DAY_LINES).forEach {
                lines += "${day.format(Instant.ofEpochMilli(it.dateMillis))}: ${it.runs} ${sport.words.runs}, descent ${units.altitude(it.vertical)}, " +
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

private fun String.cap() = replaceFirstChar { it.uppercase() }

/** The words a sport uses for the ride data's downhill segments ("runs") and climbs ("lifts"). */
data class SportWords(
    val run: String, val runs: String, val lift: String, val lifts: String,
    val downActivity: String, val upActivity: String, val stopActivity: String, val stop: String,
)

val Sport.isSnowSport: Boolean get() = this == Sport.SKI || this == Sport.BOARD

/** False on foot: nobody hikes to go fast. */
val Sport.showsSpeed: Boolean get() = this != Sport.HIKE

val Sport.words: SportWords
    get() = when (this) {
        Sport.SKI, Sport.BOARD -> SportWords("run", "runs", "lift", "lifts", "riding", "on lifts", "standing still", "stop")
        Sport.BIKE -> SportWords("descent", "descents", "climb", "climbs", "descending", "climbing", "stopped", "stop")
        Sport.HIKE -> SportWords("descent", "descents", "climb", "climbs", "descending", "climbing", "resting", "rest")
    }

/** The measurements a ski or snowboard summary covers when the data has them. */
private const val SNOW_METRICS_BRIEF =
    " Cover these where the data gives them, each with its number: vertical descent, max speed, average speed while moving, " +
        "peak G-force, turns (how many and how hard), activity intensity, and recovery between runs. " +
        "Skip any the data lacks; never estimate air time or jump height."

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
                "and whether the legs tire in the second half. Tips are about skiing: edge control, speed control, pacing the day, resting." + SNOW_METRICS_BRIEF
        Sport.BOARD ->
            "The rider snowboards. Runs are snowboard runs and lifts are chairlifts or gondolas. Look at descent per run, top speeds, " +
                "and whether energy drops in the second half. Tips are about snowboarding: carving, edge control, pacing the day, resting." + SNOW_METRICS_BRIEF
        Sport.BIKE ->
            "The rider cycles off-road. Call runs descents and lifts climbs; never mention snow or chairlifts. Look at descent, " +
                "top speeds and the balance between climbing and descending. Tips are about cycling: braking, pacing climbs, hydration."
        Sport.HIKE ->
            "The rider hikes. Call runs descents and lifts climbs or ascents; never mention snow, skiing, snowboarding or chairlifts. " +
                "Speed is not the goal, so never praise or push top speed. Look at total ascent and descent, time moving against resting, " +
                "and steady effort. Pay special attention to heart rate and tempo when the data gives them: the average and highest pulse, " +
                "the pulse on climbs against descents, whether the pulse rose while the pace held or fell (tiredness), the pace on climbs against descents, " +
                "the climbing rate, and whether the pace faded or held in the second half. Judge effort by pulse and a steady tempo, never by speed; " +
                "a pulse that climbs at the same pace means ease off. If heart rate is not recorded, do not mention it. " +
                "Tips are about hiking: steady pace, rest breaks, descending carefully on the knees, water and food."
    }
