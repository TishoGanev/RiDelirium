package com.bgdeveloper.ridelirium

import com.bgdeveloper.ridelirium.data.TrackPointEntity
import com.bgdeveloper.ridelirium.domain.Activity
import com.bgdeveloper.ridelirium.domain.RideAnalysis
import com.bgdeveloper.ridelirium.domain.Segment
import com.bgdeveloper.ridelirium.domain.SnowMetrics
import com.bgdeveloper.ridelirium.domain.Units
import com.bgdeveloper.ridelirium.domain.coachBrief
import com.bgdeveloper.ridelirium.domain.showsSpeed
import com.bgdeveloper.ridelirium.friends.Sport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnowMetricsTest {
    /** A straight run with the heading swinging 10 degrees per second, direction flipping every four seconds. */
    private fun carvingRun(swings: Int): Pair<RideAnalysis, List<TrackPointEntity>> {
        val points = mutableListOf<TrackPointEntity>()
        var course = 180.0
        var second = 0
        repeat(swings) { swing ->
            repeat(4) {
                course += if (swing % 2 == 0) 10 else -10
                points += TrackPointEntity(
                    lat = 42.0, lon = 23.0, altitude = 2000.0 - second, speed = 10.0,
                    timestamp = 1_792_000_000_000L + second * 1000L, session = 1,
                    course = course, gForce = if (second == 6) 2.8 else 1.1,
                )
                second++
            }
        }
        val run = Segment(Activity.RUN, 0, points.size - 1, 400.0, second.toDouble(), 10.0, second.toDouble())
        return RideAnalysis(listOf(run)) to points
    }

    @Test fun countsOneTurnPerHeadingSwing() {
        val (analysis, points) = carvingRun(6)
        assertEquals(6, SnowMetrics.compute(analysis, points).turns)
    }

    @Test fun turnRateIsAverageHeadingChangePerSecond() {
        val (analysis, points) = carvingRun(4)
        assertEquals(10.0, SnowMetrics.compute(analysis, points).turnRate!!, 0.01)
    }

    @Test fun peakGAndHardImpacts() {
        val (analysis, points) = carvingRun(4)
        val metrics = SnowMetrics.compute(analysis, points)
        assertEquals(2.8, metrics.peakG!!, 0.001)
        assertEquals(1, metrics.hardImpacts)
    }

    @Test fun noHeadingMeansNoTurnLine() {
        val (analysis, points) = carvingRun(4)
        val metrics = SnowMetrics.compute(analysis, points.map { it.copy(course = null) })
        assertNull(metrics.turnRate)
        assertFalse(metrics.promptLines(analysis, Units()).any { it.contains("Turns") })
    }

    @Test fun hikeNeverPushesSpeedAndSnowSportsGetMetrics() {
        assertFalse(Sport.HIKE.showsSpeed)
        assertTrue(Sport.HIKE.coachBrief.contains("never praise or push top speed"))
        assertFalse(Sport.HIKE.coachBrief.contains("G-force"))
        assertTrue(Sport.SKI.coachBrief.contains("peak G-force"))
        assertTrue(Sport.BOARD.coachBrief.contains("peak G-force"))
        assertNotNull(Sport.BIKE.coachBrief)
    }
}

class MixedSportTest {
    private fun point(second: Int, sport: Sport?) = TrackPointEntity(
        lat = 42.0, lon = 23.0, altitude = 2000.0, speed = 1.0,
        timestamp = 1_792_000_000_000L + second * 1000L, session = 1, sport = sport?.key,
    )

    @Test fun timelineKeepsOrderAndDominantSport() {
        val points = (0 until 10).map { point(it * 10, if (it < 6) Sport.BOARD else Sport.HIKE) }
        val timeline = com.bgdeveloper.ridelirium.domain.SportTimeline(points, Sport.SKI)
        assertEquals(listOf(Sport.BOARD, Sport.HIKE), timeline.distinct)
        assertEquals(Sport.BOARD, timeline.dominant)
        assertEquals(Sport.HIKE, timeline.sportAt(8))
    }

    @Test fun mixedRideTellsTheModelAboutBothSports() {
        val points = (0 until 20).map { point(it * 10, if (it < 10) Sport.BOARD else Sport.HIKE) }
        val run = Segment(Activity.RUN, 0, 9, 500.0, 100.0, 12.0, 90.0)
        val walk = Segment(Activity.RUN, 10, 19, 500.0, 80.0, 2.0, 90.0)
        val facts = com.bgdeveloper.ridelirium.domain.InsightFacts.ride(RideAnalysis(listOf(run, walk)), points, "Test", Units(), Sport.BOARD)
        assertEquals(listOf(Sport.BOARD, Sport.HIKE), facts.sports)
        assertTrue(facts.prompt.contains("Sports during this ride, in order: snowboarding"))
        assertTrue(facts.prompt.contains("(hiking)"))
        assertTrue(facts.instructions.contains("never apply one sport's advice to another"))
        assertTrue(facts.instructions.contains("outdoor sports coach"))
        assertFalse(facts.bullets.any { it.contains("Descent 2 was your fastest") })
    }

    @Test fun singleSportRideKeepsItsOwnCoach() {
        val points = (0 until 10).map { point(it, Sport.HIKE) }
        val run = Segment(Activity.RUN, 0, 9, 500.0, 100.0, 2.0, 9.0)
        val facts = com.bgdeveloper.ridelirium.domain.InsightFacts.ride(RideAnalysis(listOf(run)), points, "Test", Units(), Sport.BOARD)
        assertEquals(listOf(Sport.HIKE), facts.sports)
        assertTrue(facts.instructions.contains("hiking guide"))
        assertFalse(facts.prompt.contains("Snow sport measurements"))
    }
}

class HikeMetricsTest {
    private fun hike(): Pair<RideAnalysis, List<TrackPointEntity>> {
        val points = (0..40).map {
            TrackPointEntity(lat = 42.0, lon = 23.0, altitude = 1000.0, speed = 1.0, timestamp = 1_792_000_000_000L + it * 60_000L, session = 1, sport = Sport.HIKE.key)
        }
        val climb = Segment(Activity.LIFT, 0, 19, 2000.0, 300.0, 1.5, 1200.0)
        val descent = Segment(Activity.RUN, 20, 40, 1500.0, 300.0, 1.5, 1200.0)
        return RideAnalysis(listOf(climb, descent)) to points
    }

    @Test fun tempoIsPaceClimbRateAndFade() {
        val (analysis, _) = hike()
        val metrics = com.bgdeveloper.ridelirium.domain.HikeMetrics.compute(analysis)
        assertEquals(2400.0 / 3500, metrics.averagePace!!, 0.001)
        assertEquals(900.0, metrics.climbRate!!, 0.1)
        assertEquals(33.3, metrics.paceChange!!, 0.5)
    }

    @Test fun paceTextMatchesIos() {
        assertEquals("12:30 min/km", com.bgdeveloper.ridelirium.domain.HikeMetrics.paceText(0.75, Units(metric = true)))
        assertTrue(com.bgdeveloper.ridelirium.domain.HikeMetrics.paceText(0.75, Units(metric = false)).endsWith("min/mi"))
    }

    @Test fun hikePromptCarriesTempoAndNoHeartRate() {
        val (analysis, points) = hike()
        val facts = com.bgdeveloper.ridelirium.domain.InsightFacts.ride(analysis, points, "Test", Units(), Sport.HIKE)
        assertTrue(facts.prompt.contains("Hiking measurements"))
        assertTrue(facts.prompt.contains("average pace while moving"))
        assertTrue(facts.prompt.contains("Heart rate: not recorded"))
        assertTrue(facts.instructions.contains("Pay special attention to heart rate and tempo"))
    }

    @Test fun snowRideGetsNoHikingBlock() {
        val (analysis, points) = hike()
        val facts = com.bgdeveloper.ridelirium.domain.InsightFacts.ride(analysis, points.map { it.copy(sport = Sport.SKI.key) }, "Test", Units(), Sport.SKI)
        assertFalse(facts.prompt.contains("Hiking measurements"))
        assertFalse(facts.instructions.contains("Pay special attention to heart rate and tempo"))
    }
}
