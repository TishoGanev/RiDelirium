package com.bgdeveloper.ridelirium.domain

import com.bgdeveloper.ridelirium.data.TrackPointEntity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** What the rider was doing: descending, riding a lift, or standing still. */
enum class Activity { RUN, LIFT, STILL }

/** A stretch of a ride with one activity. */
data class Segment(
    val activity: Activity,
    val startIndex: Int,
    val endIndex: Int,
    val distance: Double,
    /** Metres descended for a run, metres climbed for a lift, 0 when standing. */
    val vertical: Double,
    val maxSpeed: Double,
    val durationSeconds: Double,
) {
    val averageSpeed: Double get() = if (durationSeconds > 0) distance / durationSeconds else 0.0
}

data class RideAnalysis(val segments: List<Segment>) {
    val runs: List<Segment> get() = segments.filter { it.activity == Activity.RUN }
    val lifts: List<Segment> get() = segments.filter { it.activity == Activity.LIFT }
    val stills: List<Segment> get() = segments.filter { it.activity == Activity.STILL }

    val downhillDistance: Double get() = runs.sumOf { it.distance }
    val totalVertical: Double get() = runs.sumOf { it.vertical }
    val liftVertical: Double get() = lifts.sumOf { it.vertical }

    /** Distance covered while not standing; GPS drift from a stopped rider is left out. */
    val movingDistance: Double get() = segments.filter { it.activity != Activity.STILL }.sumOf { it.distance }
    val movingMaxSpeed: Double get() = segments.filter { it.activity != Activity.STILL }.maxOfOrNull { it.maxSpeed } ?: 0.0

    fun duration(activity: Activity): Double = segments.filter { it.activity == activity }.sumOf { it.durationSeconds }

    companion object {
        val EMPTY = RideAnalysis(emptyList())
    }
}

/**
 * Splits a recorded ride into runs, lifts and stops from altitude and speed.
 *
 * Keep in sync with `ActivityDetector.swift` on iOS; both use the same thresholds.
 */
object ActivityDetector {
    enum class Raw { RUN, LIFT, STILL, FLAT }

    /** Smoothed descent faster than this (m/s) is a run; climb faster than [LIFT_RATE] is a lift. */
    const val RUN_RATE = -0.25
    const val LIFT_RATE = 0.2
    /** Standing still: slow by the GPS speed and not covering ground either. */
    const val STILL_SPEED = 0.6
    const val MOVING_DISPLACEMENT = 2.0

    private val MIN_DURATION = mapOf(Activity.RUN to 15.0, Activity.LIFT to 30.0, Activity.STILL to 20.0)
    private const val MIN_RUN_DROP = 15.0
    private const val MIN_RUN_DISTANCE = 75.0
    private const val MIN_LIFT_CLIMB = 15.0
    private const val MIN_LIFT_DISTANCE = 100.0

    /**
     * One point's label from a smoothed vertical rate and ground speed. [Raw.FLAT] means moving without
     * clearly climbing or descending, so it takes on its neighbours' label.
     */
    fun raw(rate: Double, speed: Double, displacementSpeed: Double): Raw = when {
        speed < STILL_SPEED && displacementSpeed < MOVING_DISPLACEMENT -> Raw.STILL
        rate < RUN_RATE -> Raw.RUN
        rate > LIFT_RATE -> Raw.LIFT
        else -> Raw.FLAT
    }

    private class Range(var activity: Activity, val start: Int, var end: Int)

    fun analyze(points: List<TrackPointEntity>): RideAnalysis {
        val n = points.size
        if (n < 3) return RideAnalysis.EMPTY

        val times = DoubleArray(n) { points[it].timestamp / 1000.0 }
        val altitude = smoothed(points.map { it.altitude }, radius = 2)
        val speed = smoothed(points.map { it.speed }, radius = 2)

        // segment[i] is the horizontal distance added by point i; steps that are only GPS noise count as 0.
        val segment = DoubleArray(n)
        DistanceRule.steps(points).copyInto(segment)

        // Some recordings carry no GPS speed at all (every value 0). Then ground speed has to come
        // from the distance covered, or the whole ride would look like standing still.
        val hasSpeed = points.any { it.speed > 0.5 }

        val raws = Array(n) { Raw.FLAT }
        for (i in 0 until n) {
            val lo = max(0, i - 3)
            val hi = min(n - 1, i + 3)
            val dt = times[hi] - times[lo]
            if (dt <= 0) continue
            val displacement = haversine(points[lo], points[hi]) / dt
            raws[i] = raw(
                rate = (altitude[hi] - altitude[lo]) / dt,
                speed = if (hasSpeed) speed[i] else displacement,
                displacementSpeed = displacement,
            )
        }

        var ranges = merge(group(resolve(raws)))

        fun distance(r: Range) = if (r.end > r.start) (r.start + 1..r.end).sumOf { segment[it] } else 0.0
        fun drop(r: Range) = altitude[r.start] - altitude[r.end]
        fun duration(r: Range) = times[r.end] - times[r.start]

        fun isValid(r: Range): Boolean {
            if (duration(r) < (MIN_DURATION[r.activity] ?: 0.0)) return false
            return when (r.activity) {
                Activity.RUN -> drop(r) >= MIN_RUN_DROP && distance(r) >= MIN_RUN_DISTANCE
                Activity.LIFT -> -drop(r) >= MIN_LIFT_CLIMB && distance(r) >= MIN_LIFT_DISTANCE
                Activity.STILL -> true
            }
        }

        // A stretch too short or too small to count takes on a neighbour's activity. Movement that is
        // merely small (a flat walk between two stops) is kept: dropping it would erase real distance.
        var i = 0
        while (ranges.size > 1 && i < ranges.size) {
            val range = ranges[i]
            if (isValid(range)) { i++; continue }
            val tooShort = duration(range) < (MIN_DURATION[range.activity] ?: 0.0)
            val movingNeighbour = listOf(i - 1, i + 1).firstOrNull { it in ranges.indices && ranges[it].activity != Activity.STILL }
            if (range.activity == Activity.STILL || tooShort) {
                range.activity = if (i > 0) ranges[i - 1].activity else ranges[i + 1].activity
            } else if (movingNeighbour != null) {
                range.activity = ranges[movingNeighbour].activity
            } else {
                i++
                continue
            }
            ranges = merge(ranges)
            i = max(0, i - 1)
        }

        return RideAnalysis(
            ranges.map { r ->
                Segment(
                    activity = r.activity,
                    startIndex = r.start,
                    endIndex = r.end,
                    distance = distance(r),
                    vertical = when (r.activity) {
                        Activity.RUN -> max(drop(r), 0.0)
                        Activity.LIFT -> max(-drop(r), 0.0)
                        Activity.STILL -> 0.0
                    },
                    maxSpeed = (r.start..r.end).maxOf { points[it].speed },
                    durationSeconds = duration(r),
                )
            }
        )
    }

    /** Flat points follow what came before; after a stop they follow the next run or lift. */
    private fun resolve(raws: Array<Raw>): List<Activity> {
        val labels = arrayOfNulls<Activity>(raws.size)
        var current: Activity? = null
        for (i in raws.indices) {
            when (raws[i]) {
                Raw.RUN -> { current = Activity.RUN; labels[i] = current }
                Raw.LIFT -> { current = Activity.LIFT; labels[i] = current }
                Raw.STILL -> { current = Activity.STILL; labels[i] = current }
                Raw.FLAT -> labels[i] = if (current == Activity.STILL) null else current
            }
        }
        // Moving on the flat is never standing: it follows the next run or lift, else it is riding.
        var next = Activity.RUN
        for (i in labels.indices.reversed()) {
            val label = labels[i]
            if (label != null) next = if (label == Activity.STILL) Activity.RUN else label else labels[i] = next
        }
        return labels.map { it ?: Activity.RUN }
    }

    private fun group(labels: List<Activity>): List<Range> {
        val ranges = mutableListOf<Range>()
        labels.forEachIndexed { i, label ->
            val last = ranges.lastOrNull()
            if (last != null && last.activity == label) last.end = i else ranges += Range(label, i, i)
        }
        return ranges
    }

    private fun merge(ranges: List<Range>): MutableList<Range> {
        val merged = mutableListOf<Range>()
        for (range in ranges) {
            val last = merged.lastOrNull()
            if (last != null && last.activity == range.activity) last.end = range.end else merged += range
        }
        return merged
    }

    /** Moving average; GPS altitude and speed are too noisy to use raw. */
    private fun smoothed(values: List<Double>, radius: Int): DoubleArray = DoubleArray(values.size) { i ->
        val from = max(0, i - radius)
        val to = min(values.size - 1, i + radius)
        var sum = 0.0
        for (j in from..to) sum += values[j]
        sum / (to - from + 1)
    }

    // Own haversine rather than android.location.Location, so this runs in plain JVM unit tests.
    fun haversine(a: TrackPointEntity, b: TrackPointEntity): Double = haversine(a.lat, a.lon, b.lat, b.lon)

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
}

/**
 * Labels the rider live from the last few GPS fixes. A label only changes after three agreeing fixes,
 * so one noisy fix doesn't flip it.
 */
class LiveActivityClassifier {
    private class Sample(val time: Double, val altitude: Double, val speed: Double, val lat: Double, val lon: Double)

    private val samples = ArrayDeque<Sample>()
    private var candidate: Activity? = null
    private var candidateCount = 0

    var activity: Activity = Activity.STILL
        private set

    fun reset() {
        samples.clear()
        candidate = null
        candidateCount = 0
        activity = Activity.STILL
    }

    fun add(timeMillis: Long, altitude: Double, speed: Double, lat: Double, lon: Double) {
        samples.addLast(Sample(timeMillis / 1000.0, altitude, max(speed, 0.0), lat, lon))
        if (samples.size > 8) samples.removeFirst()
        if (samples.size < 4) return

        val first = samples.first()
        val last = samples.last()
        val avgSpeed = samples.takeLast(5).map { it.speed }.average()
        val head = samples.take(3)
        val tail = samples.takeLast(3)
        val dt = tail.map { it.time }.average() - head.map { it.time }.average()
        val rate = if (dt > 0) (tail.map { it.altitude }.average() - head.map { it.altitude }.average()) / dt else 0.0
        val span = last.time - first.time
        val displacement = if (span > 0) ActivityDetector.haversine(first.lat, first.lon, last.lat, last.lon) / span else 0.0

        val target = when (ActivityDetector.raw(rate, avgSpeed, displacement)) {
            ActivityDetector.Raw.RUN -> Activity.RUN
            ActivityDetector.Raw.LIFT -> Activity.LIFT
            ActivityDetector.Raw.STILL -> Activity.STILL
            // Flat but moving: keep a lift or run going, but moving off from a stop means riding.
            ActivityDetector.Raw.FLAT -> if (activity == Activity.STILL) Activity.RUN else null
        }
        if (target == null || target == activity) {
            candidate = null
            candidateCount = 0
            return
        }
        candidateCount = if (candidate == target) candidateCount + 1 else 1
        candidate = target
        if (candidateCount >= 3) {
            activity = target
            candidate = null
            candidateCount = 0
        }
    }
}
