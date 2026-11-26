package com.bgdeveloper.ridelirium

import com.bgdeveloper.ridelirium.data.TrackPointEntity
import com.bgdeveloper.ridelirium.domain.Activity
import com.bgdeveloper.ridelirium.domain.ActivityDetector
import com.bgdeveloper.ridelirium.domain.DistanceRule
import com.bgdeveloper.ridelirium.domain.LiveActivityClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityDetectorTest {
    /** One point per second; [dAlt] metres of altitude and [dLat] degrees of latitude per step. */
    private fun leg(from: TrackPointEntity?, steps: Int, dAlt: Double, dLat: Double, speed: Double): List<TrackPointEntity> {
        val out = mutableListOf<TrackPointEntity>()
        var last = from ?: TrackPointEntity(lat = 42.0, lon = 23.0, altitude = 2000.0, speed = speed, timestamp = 0, session = 1)
        repeat(steps) {
            last = last.copy(
                lat = last.lat + dLat, altitude = last.altitude + dAlt,
                speed = speed, timestamp = last.timestamp + 1000,
            )
            out += last
        }
        return out
    }

    @Test
    fun `finds two runs separated by a lift`() {
        val run1 = leg(null, 120, -1.5, 0.00006, 7.0)
        val lift = leg(run1.last(), 200, +0.9, -0.00002, 2.5)
        val run2 = leg(lift.last(), 100, -1.5, 0.00006, 7.0)
        val analysis = ActivityDetector.analyze(run1 + lift + run2)

        assertEquals(2, analysis.runs.size)
        assertEquals(1, analysis.lifts.size)
        assertTrue("downhill distance ${analysis.downhillDistance}", analysis.downhillDistance in 1200.0..2000.0)
        assertTrue("vertical ${analysis.totalVertical}", analysis.totalVertical in 300.0..400.0)
    }

    @Test
    fun `map holds one position while standing and moves again after`() {
        val run = leg(null, 120, -1.5, 0.00006, 7.0)
        // Drift around the stop: back and forth by about a metre.
        val still = leg(run.last(), 120, 0.0, 0.0, 0.1).mapIndexed { i, p -> p.copy(lat = p.lat + if (i % 2 == 0) 0.00001 else -0.00001) }
        val lift = leg(still.last(), 200, +0.9, -0.00002, 2.5)
        val points = run + still + lift
        val segments = ActivityDetector.analyze(points).segments
        val shown = DistanceRule.mapIndices(points, segments)
        val stop = segments.single { it.activity == Activity.STILL }

        assertEquals(points.size, shown.size)
        assertEquals(1, (stop.startIndex..stop.endIndex).map { shown[it] }.distinct().size)
        assertEquals(points.lastIndex, shown.last())
    }

    @Test
    fun `run then stop then lift`() {
        val run = leg(null, 120, -1.5, 0.00006, 7.0)
        val still = leg(run.last(), 120, 0.0, 0.0, 0.1)
        val lift = leg(still.last(), 200, +0.9, -0.00002, 2.5)
        val analysis = ActivityDetector.analyze(run + still + lift)

        assertEquals(listOf(Activity.RUN, Activity.STILL, Activity.LIFT), analysis.segments.map { it.activity })
        assertEquals(180.0, analysis.totalVertical, 25.0)
        assertEquals(180.0, analysis.liftVertical, 25.0)
    }

    @Test
    fun `standing still is not a run and adds no distance`() {
        val analysis = ActivityDetector.analyze(leg(null, 200, 0.0, 0.0, 0.0))
        assertEquals(0, analysis.runs.size)
        assertEquals(0.0, analysis.movingDistance, 0.01)
    }

    @Test
    fun `a short flat traverse does not split a run`() {
        val a = leg(null, 60, -1.5, 0.00006, 7.0)
        val flat = leg(a.last(), 10, 0.0, 0.00004, 4.0)
        val b = leg(flat.last(), 60, -1.5, 0.00006, 7.0)
        assertEquals(1, ActivityDetector.analyze(a + flat + b).runs.size)
    }

    @Test
    fun `live classifier follows riding then stopping`() {
        val live = LiveActivityClassifier()
        val ride = leg(null, 12, -1.5, 0.00006, 7.0)
        ride.forEach { live.add(it.timestamp, it.altitude, it.speed, it.lat, it.lon) }
        assertEquals(Activity.RUN, live.activity)

        leg(ride.last(), 12, 0.0, 0.0, 0.0).forEach { live.add(it.timestamp, it.altitude, it.speed, it.lat, it.lon) }
        assertEquals(Activity.STILL, live.activity)
    }

    @Test
    fun `a flat ride still counts its distance`() {
        // Walking or driving on level ground: no climb, no descent.
        val flat = leg(null, 200, 0.0, 0.00002, 1.5)
        val analysis = ActivityDetector.analyze(flat)
        assertTrue("moving distance ${analysis.movingDistance}", analysis.movingDistance > 300.0)
    }

    @Test
    fun `a ride with no speed data still counts its distance`() {
        val flat = leg(null, 200, 0.0, 0.00004, 0.0)
        val analysis = ActivityDetector.analyze(flat)
        assertTrue("moving distance ${analysis.movingDistance}", analysis.movingDistance > 600.0)
    }

    @Test
    fun `live classifier starts counting when you move off on the flat`() {
        val live = LiveActivityClassifier()
        leg(null, 12, 0.0, 0.00002, 1.5).forEach { live.add(it.timestamp, it.altitude, it.speed, it.lat, it.lon) }
        assertEquals(Activity.RUN, live.activity)
    }

    @Test
    fun `a walk between two stops keeps its distance`() {
        val start = leg(null, 60, 0.0, 0.0, 0.0)
        val walk = leg(start.last(), 120, 0.0, 0.00002, 1.4)
        val end = leg(walk.last(), 60, 0.0, 0.0, 0.0)
        val analysis = ActivityDetector.analyze(start + walk + end)
        assertTrue("moving distance ${analysis.movingDistance}", analysis.movingDistance > 200.0)
    }
}
