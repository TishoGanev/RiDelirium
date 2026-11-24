package com.bgdeveloper.ridelirium.domain

import com.bgdeveloper.ridelirium.data.TrackPointEntity
import java.time.Instant

/**
 * Session CSV export, plus the rules that decide which GPS fixes are recorded and counted.
 * Keep in sync with `TrackCSV.swift` on iOS.
 *
 * Format versions (the first line tells which one; no such line means version 1):
 * - 1: `timestamp,latitude,longitude,altitude_m,speed_mps`; ISO 8601 timestamps, invalid speed written as 0.
 * - 2: `# ridelirium-csv-version: 2` and `# key: value` comment lines, ISO 8601 timestamps with milliseconds,
 *   accuracy, course and activity columns; invalid values are empty fields. Read with `comment='#'` in pandas.
 * - 3: as 2, but `timestamp` is unix time in seconds with milliseconds (`1792000000.950`).
 * - 4: as 3, plus `g_force`: peak total G-force since the previous row, gravity included (1.0 at rest); empty without an accelerometer.
 * - 5: as 4, plus `heart_rate_bpm`: the Apple Watch heart rate when the fix arrived; always empty on Android.
 *
 * Comment lines are free-form `# key: value` pairs and readers skip them, so a new one needs no version bump.
 * `# sport: ski|board|bike|walk` (walk is the hike sport) is written when the sport of the recording is known.
 */
object TrackCsv {
    const val FORMAT_VERSION = 5

    val COLUMNS = listOf(
        "timestamp", "latitude", "longitude", "altitude_m", "speed_mps",
        "horizontal_accuracy_m", "vertical_accuracy_m", "speed_accuracy_mps",
        "course_deg", "course_accuracy_deg", "activity", "activity_confidence", "g_force",
        "heart_rate_bpm",
    )

    /** Unix time in seconds with milliseconds, e.g. `1792000000.950`. */
    fun format(millis: Long): String =
        "${Math.floorDiv(millis, 1000L)}.${Math.floorMod(millis, 1000L).toString().padStart(3, '0')}"

    /** Reads unix time, and also the ISO 8601 text of versions 1 and 2 (with or without fractions). */
    fun parse(text: String): Long? {
        text.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return Math.round(it * 1000) }
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
    }

    /** "Unknown" is a negative number from the location APIs. That is not 0, so it becomes an empty field. */
    fun field(value: Double?): String =
        if (value == null || value < 0) "" else value.toString()

    private fun signed(value: Double) = value.toString()

    fun row(p: TrackPointEntity): String {
        // Points saved before version 2 only have the clamped `speed`; newer ones keep the raw value.
        val speed = p.rawSpeed ?: p.speed
        return listOf(
            format(p.timestamp), signed(p.lat), signed(p.lon), signed(p.altitude), field(speed),
            field(p.horizontalAccuracy), field(p.verticalAccuracy), field(p.speedAccuracy),
            field(p.course), field(p.courseAccuracy), p.activity.orEmpty(), p.activityConfidence?.toString().orEmpty(), field(p.gForce),
            "",
        ).joinToString(",")
    }

    fun csv(points: List<TrackPointEntity>, metadata: SessionMetadata = SessionMetadata()): String = buildString {
        append("# ridelirium-csv-version: $FORMAT_VERSION\n")
        metadata.commentLines.forEach { append(it).append('\n') }
        append(COLUMNS.joinToString(",")).append('\n')
        points.forEach { append(row(it)).append('\n') }
    }
}

/** Facts about one recording that the points themselves don't carry. */
data class SessionMetadata(val rejectedLowAccuracy: Int = 0, val rejectedStale: Int = 0, val sport: String? = null) {
    val commentLines: List<String>
        get() = listOf("# rejected_low_accuracy: $rejectedLowAccuracy", "# rejected_stale: $rejectedStale") +
            listOfNotNull(sport?.let { "# sport: $it" })
}

/**
 * Decides whether a GPS fix is recorded. A fix with poor accuracy (indoors) or an older timestamp than
 * the last recorded one (a cached position replayed by the system) is dropped and counted.
 */
class FixFilter(private val maxHorizontalAccuracy: Float = 50f, private val notBeforeMillis: Long? = null) {
    enum class Verdict { ACCEPT, LOW_ACCURACY, STALE }

    private var lastTimestamp: Long? = null
    var metadata = SessionMetadata()
        private set

    /** Equal timestamps count as stale: two points in one instant are what broke the speed calculation. */
    fun check(horizontalAccuracy: Float?, timestampMillis: Long): Verdict {
        if (horizontalAccuracy == null || horizontalAccuracy < 0 || horizontalAccuracy > maxHorizontalAccuracy) {
            metadata = metadata.copy(rejectedLowAccuracy = metadata.rejectedLowAccuracy + 1)
            return Verdict.LOW_ACCURACY
        }
        val last = lastTimestamp
        if ((last != null && timestampMillis <= last) || (notBeforeMillis != null && timestampMillis < notBeforeMillis)) {
            metadata = metadata.copy(rejectedStale = metadata.rejectedStale + 1)
            return Verdict.STALE
        }
        lastTimestamp = timestampMillis
        return Verdict.ACCEPT
    }
}

/**
 * GPS noise while standing adds distance from nothing. A step that is slow and smaller than the fix's own
 * uncertainty is noise.
 */
object DistanceRule {
    const val SLOW_SPEED = 0.5

    /** [speed] negative or null means unknown, and then the step is kept; the same goes for [accuracy]. */
    fun counts(displacement: Double, speed: Double?, accuracy: Double?): Boolean {
        if (speed == null || speed < 0 || accuracy == null || accuracy < 0) return true
        return !(speed < SLOW_SPEED && displacement < accuracy)
    }

    /**
     * Distance added at each point, 0 for the first. A dropped step keeps the old anchor, so a slow but
     * real drift still counts once it is larger than the accuracy.
     */
    fun steps(points: List<TrackPointEntity>): DoubleArray {
        val steps = DoubleArray(points.size)
        if (points.isEmpty()) return steps
        var anchor = points[0]
        for (i in 1 until points.size) {
            val p = points[i]
            val distance = ActivityDetector.haversine(anchor.lat, anchor.lon, p.lat, p.lon)
            if (counts(distance, p.rawSpeed, p.horizontalAccuracy)) {
                steps[i] = distance
                anchor = p
            }
        }
        return steps
    }
}
