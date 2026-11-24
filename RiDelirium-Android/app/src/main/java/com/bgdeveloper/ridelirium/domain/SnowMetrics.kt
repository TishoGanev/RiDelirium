package com.bgdeveloper.ridelirium.domain

import com.bgdeveloper.ridelirium.data.TrackPointEntity
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Extra measurements for skiing and snowboarding, worked out in code and handed to the language model as facts.
 * Turns come from the GPS heading, so they are approximate; air time and jump height are not measured.
 * Keep in sync with `SnowMetrics.swift` on iOS.
 */
data class SnowMetrics(
    val turns: Int = 0,
    /** Average heading change in degrees per second while riding a run; null without heading data. */
    val turnRate: Double? = null,
    val peakG: Double? = null,
    /** Fixes with more than [IMPACT_G] of G-force: hard landings or bumps. */
    val hardImpacts: Int? = null,
    /** Distance over time while not standing. */
    val averageMovingSpeed: Double = 0.0,
    /** Seconds between the end of one run and the start of the next. */
    val restBetweenRuns: List<Double> = emptyList(),
) {
    /** Lines for the model's data block. */
    fun promptLines(analysis: RideAnalysis, units: Units): List<String> {
        val runs = analysis.runs
        val riding = analysis.duration(Activity.RUN)
        val total = riding + analysis.duration(Activity.LIFT) + analysis.duration(Activity.STILL)
        val lines = mutableListOf("Snow sport measurements, worked out in code:")
        lines += "- Vertical descent: ${units.altitude(analysis.totalVertical)} over ${runs.size} ${if (runs.size == 1) "run" else "runs"}"
        lines += "- Max speed ${units.speed(analysis.movingMaxSpeed)}; average speed while moving ${units.speed(averageMovingSpeed)}"
        if (peakG != null) {
            var line = "- Peak G-force: ${"%.2f".format(java.util.Locale.US, peakG)} G"
            if (hardImpacts != null) {
                line += "; $hardImpacts hard ${if (hardImpacts == 1) "impact" else "impacts"} above $IMPACT_G G (landings or bumps)"
            }
            lines += line
        } else {
            lines += "- Peak G-force: not recorded on this phone"
        }
        if (turnRate != null) {
            val perRun = if (runs.isEmpty()) 0.0 else turns.toDouble() / runs.size
            lines += "- Turns: about $turns (about ${perRun.roundToInt()} per run); average turning ${turnRate.roundToInt()} degrees per second. " +
                "Estimated from the GPS heading, so approximate"
        }
        lines += "- Air time and jump height are not measured; never mention them"
        if (total > 0) {
            val descent = if (total >= 600) analysis.totalVertical / (total / 3600) else analysis.totalVertical
            lines += "- Intensity: ${units.altitude(descent)} of descent per hour on the mountain; riding ${(riding / total * 100).roundToInt()}% of the time"
        }
        if (restBetweenRuns.isNotEmpty()) {
            lines += "- Recovery: on average ${units.compactDuration(restBetweenRuns.average())} between runs, longest ${units.compactDuration(restBetweenRuns.max())}"
        }
        return lines
    }

    companion object {
        const val IMPACT_G = 2.5
        /** Heading swing in one direction that counts as a turn, in degrees. */
        const val MIN_TURN_SWEEP = 25.0
        /** Slower than this (m/s) the heading is too noisy to read. */
        const val MIN_TURN_SPEED = 3.0

        fun compute(analysis: RideAnalysis, points: List<TrackPointEntity>): SnowMetrics {
            val movingTime = analysis.duration(Activity.RUN) + analysis.duration(Activity.LIFT)
            val averageSpeed = if (movingTime > 0) analysis.movingDistance / movingTime else 0.0

            val gs = points.mapNotNull { it.gForce }
            var turns = 0
            var headingTime = 0.0
            var headingChange = 0.0
            for (run in analysis.runs) {
                if (run.endIndex >= points.size) continue
                var direction = 0.0
                var sweep = 0.0
                fun closeSweep() { if (abs(sweep) >= MIN_TURN_SWEEP) turns++ }
                for (i in run.startIndex + 1..run.endIndex) {
                    val previous = points[i - 1]
                    val point = points[i]
                    val course = point.course ?: continue
                    val before = previous.course ?: continue
                    if (course < 0 || before < 0 || point.speed < MIN_TURN_SPEED || previous.speed < MIN_TURN_SPEED) continue
                    val dt = (point.timestamp - previous.timestamp) / 1000.0
                    if (dt <= 0 || dt > 3) continue
                    var delta = course - before
                    if (delta > 180) delta -= 360
                    if (delta < -180) delta += 360
                    headingTime += dt
                    headingChange += abs(delta)
                    if (abs(delta) < 2) continue
                    if (direction == 0.0 || (delta > 0) == (direction > 0)) {
                        direction = if (delta > 0) 1.0 else -1.0
                        sweep += delta
                    } else {
                        closeSweep()
                        direction = if (delta > 0) 1.0 else -1.0
                        sweep = delta
                    }
                }
                closeSweep()
            }

            val rests = analysis.runs.zipWithNext().mapNotNull { (current, next) ->
                if (next.startIndex >= points.size || current.endIndex >= points.size) return@mapNotNull null
                ((points[next.startIndex].timestamp - points[current.endIndex].timestamp) / 1000.0).takeIf { it > 0 }
            }
            return SnowMetrics(
                turns = turns,
                turnRate = if (headingTime > 0) headingChange / headingTime else null,
                peakG = gs.maxOrNull(),
                hardImpacts = if (gs.isEmpty()) null else gs.count { it > IMPACT_G },
                averageMovingSpeed = averageSpeed,
                restBetweenRuns = rests,
            )
        }
    }
}
