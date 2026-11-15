package com.bgdeveloper.ridelirium

import com.bgdeveloper.ridelirium.friends.Stay
import com.bgdeveloper.ridelirium.friends.timeLeftLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CrewTimeTest {
    private val zone = ZoneId.of("Europe/Sofia")
    private val now = ZonedDateTime.of(2026, 11, 13, 15, 30, 0, 0, zone)

    @Test fun todayEndsAtNextLocalMidnight() {
        assertEquals(ZonedDateTime.of(2026, 11, 14, 0, 0, 0, 0, zone).toEpochSecond(), Stay.TODAY.untilEpochSeconds(now))
    }

    @Test fun weekIsSevenDays() {
        assertEquals(now.plusDays(7).toEpochSecond(), Stay.WEEK.untilEpochSeconds(now))
    }

    @Test fun untilILeaveHasNoEnd() {
        assertNull(Stay.UNTIL_I_LEAVE.untilEpochSeconds(now))
    }

    @Test fun labels() {
        val nowMs = now.toInstant().toEpochMilli()
        assertNull(timeLeftLabel(null, nowMs, zone))
        assertEquals("UNTIL TONIGHT", timeLeftLabel(Stay.TODAY.untilEpochSeconds(now)!! * 1000, nowMs, zone))
        assertEquals("7 DAYS LEFT", timeLeftLabel(Stay.WEEK.untilEpochSeconds(now)!! * 1000, nowMs, zone))
        assertEquals("ENDED", timeLeftLabel(nowMs - 1, nowMs, zone))
        // Tomorrow at 10:00: 18.5 hours away, not tonight.
        assertEquals("19 H LEFT", timeLeftLabel(now.plusHours(18).plusMinutes(30).toInstant().toEpochMilli(), nowMs, zone))
    }
}
