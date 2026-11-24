package com.bgdeveloper.ridelirium.domain

import com.bgdeveloper.ridelirium.data.TrackPointEntity
import com.bgdeveloper.ridelirium.friends.Sport

/**
 * Which sport the rider had chosen along a recording. It can change mid-ride: a snowboard day that ends with a walk
 * down. Points saved before the sport was stored fall back to [fallback]. Keep in sync with `RideSports.swift` on iOS.
 */
class SportTimeline(points: List<TrackPointEntity>, private val fallback: Sport) {
    data class Span(val sport: Sport, val seconds: Double)

    private val labels = points.map { Sport.fromKey(it.sport) ?: fallback }

    /** Contiguous stretches in ride order, with the time spent in each. */
    val spans: List<Span> = buildList {
        labels.forEachIndexed { index, sport ->
            val step = if (index + 1 < points.size) maxOf(0L, points[index + 1].timestamp - points[index].timestamp) / 1000.0 else 0.0
            val last = lastOrNull()
            if (last != null && last.sport == sport) {
                this[lastIndex] = last.copy(seconds = last.seconds + step)
            } else {
                add(Span(sport, step))
            }
        }
    }

    /** Sports in the order they were first used. */
    val distinct: List<Sport> get() = spans.map { it.sport }.distinct()

    /** The sport with the most time, or null for an empty recording. */
    val dominant: Sport? get() = spans.groupBy { it.sport }.maxByOrNull { (_, list) -> list.sumOf { it.seconds } }?.key

    fun sportAt(index: Int): Sport = labels.getOrNull(index) ?: fallback
}
