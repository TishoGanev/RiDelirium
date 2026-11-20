package com.bgdeveloper.ridelirium

import com.bgdeveloper.ridelirium.friends.Stay
import com.bgdeveloper.ridelirium.friends.sharingLabel
import com.bgdeveloper.ridelirium.friends.timeLeftLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CrewTimeTest {
    private val zone = ZoneId.of("Europe/Sofia")
    private val now = ZonedDateTime.of(2026, 11, 16, 10, 12, 0, 0, zone)
    private val nowMs = now.toInstant().toEpochMilli()
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone)
    private fun ms(day: Int, hour: Int, minute: Int = 0) = at(day, hour, minute).toInstant().toEpochMilli()

    @Test
    fun presetsAreExactEndTimes() {
        assertEquals(at(16, 12, 12), Stay.Preset.TWO_HOURS.until(now))
        assertEquals(at(16, 14, 12), Stay.Preset.FOUR_HOURS.until(now))
        assertEquals(at(16, 23, 59), Stay.Preset.TONIGHT.until(now))
    }

    @Test
    fun clampDropsSecondsAndStaysWithinThirtyDays() {
        assertEquals(at(16, 10, 13), Stay.clamp(now.minusMinutes(10), now))
        assertEquals(at(16, 13, 45), Stay.clamp(at(16, 13, 45).plusSeconds(30), now))
        assertEquals(now.plusDays(30), Stay.clamp(now.plusDays(40), now))
    }

    @Test
    fun labels() {
        assertNull(timeLeftLabel(null, nowMs, zone))
        assertEquals("UNTIL 13:45", timeLeftLabel(ms(16, 13, 45), nowMs, zone))
        assertEquals("UNTIL TOMORROW 09:00", timeLeftLabel(ms(17, 9), nowMs, zone))
        assertEquals("UNTIL SAT 21 18:00", timeLeftLabel(ms(21, 18), nowMs, zone))
        assertEquals("ENDED", timeLeftLabel(nowMs - 1, nowMs, zone))
        assertEquals("3 H 33 MIN", Stay.duration(nowMs, ms(16, 13, 45)))
        assertEquals("45 MIN", Stay.duration(nowMs, ms(16, 10, 57)))
        assertEquals("2 D 4 H", Stay.duration(nowMs, ms(18, 14, 12)))
        assertEquals("SHARING UNTIL 13:45 · 3 H 33 MIN LEFT", sharingLabel(ms(16, 13, 45), nowMs, zone))
    }
}
