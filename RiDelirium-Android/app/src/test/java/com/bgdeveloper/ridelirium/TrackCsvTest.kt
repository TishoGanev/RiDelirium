package com.bgdeveloper.ridelirium

import com.bgdeveloper.ridelirium.data.TrackPointEntity
import com.bgdeveloper.ridelirium.domain.DayMetrics
import com.bgdeveloper.ridelirium.domain.DistanceRule
import com.bgdeveloper.ridelirium.domain.FixFilter
import com.bgdeveloper.ridelirium.domain.MotionAutoControl
import com.bgdeveloper.ridelirium.domain.MotionKind
import com.bgdeveloper.ridelirium.domain.MotionSample
import com.bgdeveloper.ridelirium.domain.PeriodInsights
import com.bgdeveloper.ridelirium.domain.SessionMetadata
import com.bgdeveloper.ridelirium.domain.StatsRange
import com.bgdeveloper.ridelirium.domain.TrackCsv
import com.bgdeveloper.ridelirium.domain.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TrackCsvTest {
    private val base = 1_792_000_000_000L

    private fun point(
        offsetMs: Long, speed: Double = 3.0, rawSpeed: Double? = speed, accuracy: Double? = 5.0,
        lat: Double = 42.0, vertical: Double = -1.0,
    ) = TrackPointEntity(
        lat = lat, lon = 23.0, altitude = 2000.0, speed = maxOf(speed, 0.0), timestamp = base + offsetMs, session = 1,
        rawSpeed = rawSpeed, horizontalAccuracy = accuracy, verticalAccuracy = vertical,
    )

    // region timestamps

    @Test fun formatKeepsMilliseconds() {
        assertEquals("1792000000.950", TrackCsv.format(base + 950))
        assertEquals("1792000000.005", TrackCsv.format(base + 5))
    }

    @Test fun parseReadsBothFormats() {
        assertEquals(base + 950, TrackCsv.parse("1792000000.950"))
        assertEquals(base + 950, TrackCsv.parse("2026-10-14T17:46:40.950Z"))
        assertEquals(base, TrackCsv.parse("2026-10-14T17:46:40Z"))
        assertNull(TrackCsv.parse("not a date"))
    }

    // endregion

    // region invalid values

    @Test fun invalidValuesAreEmptyNotZero() {
        val fields = TrackCsv.row(point(0, speed = 0.0, rawSpeed = -1.0, vertical = -1.0)).split(",")
        assertEquals(TrackCsv.COLUMNS.size, fields.size)
        assertEquals("", fields[4])
        assertEquals("5.0", fields[5])
        assertEquals(listOf("", "", "", ""), fields.subList(6, 10))
    }

    @Test fun gForceIsLastColumnAndEmptyWithoutSensor() {
        assertEquals("g_force", TrackCsv.COLUMNS.last())
        assertEquals("", TrackCsv.row(point(0)).split(",").last())
        val withG = point(0).copy(gForce = 1.42)
        assertEquals("1.42", TrackCsv.row(withG).split(",").last())
    }

    @Test fun zeroIsKeptWhenValid() {
        assertEquals("0.0", TrackCsv.row(point(0, speed = 0.0, rawSpeed = 0.0)).split(",")[4])
    }

    @Test fun versionOnePointFallsBackToStoredSpeed() {
        val old = TrackPointEntity(lat = 42.0, lon = 23.0, altitude = 2000.0, speed = 4.0, timestamp = base, session = 1)
        val fields = TrackCsv.row(old).split(",")
        assertEquals("4.0", fields[4])
        assertEquals(listOf("", "", "", "", ""), fields.subList(5, 10))
    }

    @Test fun csvHasVersionLineMetadataAndHeader() {
        val lines = TrackCsv.csv(emptyList(), SessionMetadata(3, 1)).split("\n")
        assertEquals("# ridelirium-csv-version: 4", lines[0])
        assertTrue("# rejected_low_accuracy: 3" in lines)
        assertTrue("# rejected_stale: 1" in lines)
        assertEquals(TrackCsv.COLUMNS.joinToString(","), lines[3])
    }

    @Test fun fixesAtPoint95AndPoint05GetDifferentTimestamps() {
        val stamps = listOf(950L, 2050L, 3050L, 3950L).map { TrackCsv.row(point(it)).split(",")[0] }
        assertEquals(stamps.size, stamps.toSet().size)
    }

    // endregion

    // region fix filter

    @Test fun filterRejectsPoorAccuracy() {
        val filter = FixFilter()
        assertEquals(FixFilter.Verdict.ACCEPT, filter.check(50f, base))
        assertEquals(FixFilter.Verdict.LOW_ACCURACY, filter.check(50.1f, base + 1000))
        assertEquals(FixFilter.Verdict.LOW_ACCURACY, filter.check(null, base + 2000))
        assertEquals(2, filter.metadata.rejectedLowAccuracy)
        assertEquals(0, filter.metadata.rejectedStale)
    }

    @Test fun filterRejectsOlderAndRepeatedTimestamps() {
        val filter = FixFilter()
        assertEquals(FixFilter.Verdict.ACCEPT, filter.check(5f, base + 10_000))
        assertEquals(FixFilter.Verdict.STALE, filter.check(5f, base + 9_000))
        assertEquals(FixFilter.Verdict.STALE, filter.check(5f, base + 10_000))
        assertEquals(FixFilter.Verdict.ACCEPT, filter.check(5f, base + 11_000))
        assertEquals(2, filter.metadata.rejectedStale)
    }

    @Test fun rejectedFixDoesNotMoveTheLastTimestamp() {
        val filter = FixFilter()
        filter.check(5f, base + 10_000)
        filter.check(200f, base + 20_000)
        assertEquals(FixFilter.Verdict.ACCEPT, filter.check(5f, base + 11_000))
    }

    @Test fun filterRejectsFixCachedFromBeforeRecordingStarted() {
        val filter = FixFilter(notBeforeMillis = base)
        assertEquals(FixFilter.Verdict.STALE, filter.check(5f, base - 30_000))
        assertEquals(FixFilter.Verdict.ACCEPT, filter.check(5f, base + 1_000))
    }

    // endregion

    // region distance rule

    @Test fun distanceRule() {
        assertFalse(DistanceRule.counts(3.0, 0.2, 5.0))
        assertTrue(DistanceRule.counts(8.0, 0.2, 5.0))
        assertTrue(DistanceRule.counts(3.0, 0.5, 5.0))
        assertTrue(DistanceRule.counts(3.0, -1.0, 5.0))
        assertTrue(DistanceRule.counts(3.0, null, 5.0))
        assertTrue(DistanceRule.counts(3.0, 0.0, null))
    }

    @Test fun standingStillAddsNoDistance() {
        val points = (0 until 600).map { point(it * 1000L, speed = 0.0, accuracy = 8.0, lat = 42.0 + (it % 4) * 0.00002) }
        assertEquals(0.0, DistanceRule.steps(points).sum(), 0.001)
    }

    @Test fun realMovementIsStillCounted() {
        val points = (0 until 60).map { point(it * 1000L, speed = 5.5, accuracy = 8.0, lat = 42.0 + it * 0.00005) }
        assertEquals(59 * 0.00005 * 111_195, DistanceRule.steps(points).sum(), 20.0)
    }

    // endregion

    // region motion auto control

    @Test fun autoStopAfterIdleMinutes() {
        val control = MotionAutoControl(autoStop = true, stopAfterMillis = 180_000)
        val still = MotionSample(MotionKind.STATIONARY, 2)
        assertEquals(MotionAutoControl.Action.NONE, control.evaluate(still, base, true))
        assertEquals(MotionAutoControl.Action.NONE, control.evaluate(still, base + 179_000, true))
        assertEquals(MotionAutoControl.Action.STOP, control.evaluate(still, base + 180_000, true))
    }

    @Test fun movingResetsIdleTimerAndSettingsOffDoNothing() {
        val control = MotionAutoControl(autoStop = true, stopAfterMillis = 180_000)
        control.evaluate(MotionSample(MotionKind.WALKING, 2), base, true)
        control.evaluate(MotionSample(MotionKind.AUTOMOTIVE, 2), base + 100_000, true)
        assertEquals(MotionAutoControl.Action.NONE, control.evaluate(MotionSample(MotionKind.WALKING, 2), base + 250_000, true))

        val off = MotionAutoControl()
        assertEquals(MotionAutoControl.Action.NONE, off.evaluate(MotionSample(MotionKind.STATIONARY, 2), base + 9_999_000, true))
        assertEquals(MotionAutoControl.Action.NONE, off.evaluate(MotionSample(MotionKind.AUTOMOTIVE, 2), base, false))
    }

    @Test fun autoStartNeedsDrivingWithConfidence() {
        val control = MotionAutoControl(autoStart = true)
        assertEquals(MotionAutoControl.Action.NONE, control.evaluate(MotionSample(MotionKind.AUTOMOTIVE, 0), base, false))
        assertEquals(MotionAutoControl.Action.NONE, control.evaluate(MotionSample(MotionKind.WALKING, 2), base, false))
        assertEquals(MotionAutoControl.Action.START, control.evaluate(MotionSample(MotionKind.AUTOMOTIVE, 1), base, false))
    }

    // endregion

    // region stats range and progress

    @Test fun rangeCutoffsCountCalendarDaysIncludingToday() {
        val today = LocalDate.of(2026, 11, 2)
        assertEquals(LocalDate.of(2026, 11, 2), StatsRange.TODAY.cutoff(today))
        assertEquals(LocalDate.of(2026, 10, 31), StatsRange.DAYS_3.cutoff(today))
        assertEquals(LocalDate.of(2026, 10, 27), StatsRange.DAYS_7.cutoff(today))
        assertEquals(LocalDate.of(2026, 10, 4), StatsRange.MONTH.cutoff(today))
        assertEquals(LocalDate.of(2026, 7, 1), StatsRange.SEASON.cutoff(today))
        assertEquals(LocalDate.of(2026, 7, 1), StatsRange.SEASON.cutoff(LocalDate.of(2027, 3, 1)))
        assertNull(StatsRange.ALL.cutoff(today))
        assertTrue(StatsRange.DAYS_3.contains(LocalDate.of(2026, 10, 31), today))
        assertFalse(StatsRange.DAYS_3.contains(LocalDate.of(2026, 10, 30), today))
    }

    private val units = Units(metric = true)
    private fun day(d: Int, runs: Int = 8, vertical: Double = 4000.0, top: Double = 20.0) =
        DayMetrics(base + d * 86_400_000L, runs, 20_000.0, vertical, top, 7200.0, 18_000.0)

    @Test fun trendComparesFirstAndLastWithTwoDays() {
        val bullets = PeriodInsights.bullets(listOf(day(1, top = 20.0), day(2, top = 25.0)), units)
        assertTrue(bullets.toString(), bullets.any { it.contains("Top speed is 25% higher") })
    }

    @Test fun trendUsesHalvesWithFourDays() {
        val days = listOf(day(1, vertical = 4000.0), day(2, vertical = 4000.0), day(3, vertical = 2000.0), day(4, vertical = 2000.0))
        assertTrue(PeriodInsights.bullets(days, units).any { it.contains("Descent per day is 50% lower") })
    }

    @Test fun steadyNumbersSaySo() {
        assertTrue(PeriodInsights.bullets(listOf(day(1), day(2)), units).any { it.contains("steady") })
    }

    @Test fun singleDayHasNoTrendAndNoRunsMeansNothing() {
        assertTrue(PeriodInsights.bullets(listOf(day(1)), units).any { it.contains("no trend") })
        assertEquals(listOf("No downhill runs were found in this period."), PeriodInsights.bullets(listOf(day(1, runs = 0)), units))
    }

    // endregion

    @Test
    fun csvWritesSportLineOnlyWhenKnown() {
        val known = TrackCsv.csv(emptyList(), SessionMetadata(sport = "walk")).split("\n")
        assertTrue(known.contains("# sport: walk"))
        assertEquals(TrackCsv.COLUMNS.joinToString(","), known[4])
        assertFalse(TrackCsv.csv(emptyList()).contains("# sport:"))
    }
}
