package com.bgdeveloper.ridelirium.friends

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A group of riders who see each other on the map. [members] includes you. */
data class Crew(
    val id: String,
    val name: String,
    val code: String,
    val ownerId: String,
    /** When your membership ends (epoch ms); null = until you leave. */
    val myExpiresAtMs: Long?,
    val members: List<Friend>,
    /** Where the crew meets next; null when nobody dropped a meet point (or it is long past). */
    val meet: MeetPoint? = null,
) {
    val invite: Invite get() = Invite(code, null)

    /** Everyone but [meId]. */
    fun others(meId: String?): List<Friend> = members.filter { it.id != meId }
}

/** What is at a meet point. */
enum class MeetKind(val key: String) {
    LIFT("lift"), HUT("hut"), PARKING("parking"), HERE("here");

    val title: String get() = name

    /** "AT THE LIFT", "HERE". */
    val place: String get() = if (this == HERE) "HERE" else "AT THE $name"

    companion object {
        fun fromKey(key: String?): MeetKind = entries.firstOrNull { it.key == key } ?: HERE
    }
}

/** One meet point per crew: any member drops it with a long press on the map, everyone sees it. */
data class MeetPoint(
    val latitude: Double,
    val longitude: Double,
    val kind: MeetKind,
    /** When to meet (epoch ms). */
    val atMs: Long,
    val byId: String?,
    val byName: String?,
) {
    /** "NOW", "IN 8 MIN", "4 MIN AGO". */
    fun whenLabel(nowMs: Long = System.currentTimeMillis()): String {
        val minutes = Math.round((atMs - nowMs) / 60_000.0).toInt()
        return when {
            minutes > 0 -> "IN $minutes MIN"
            minutes < 0 -> "${-minutes} MIN AGO"
            else -> "NOW"
        }
    }
}

/** What a code opens, shown before joining. */
data class CrewPreview(val code: String, val name: String, val ownerName: String, val memberCount: Int, val isMember: Boolean)

/** How long a rider shares their location with a crew: an exact end time, to the minute, at most 30 days ahead. */
object Stay {
    const val MAX_DAYS = 30L

    /** The quick picks beside the wheels. */
    enum class Preset(val title: String) {
        TWO_HOURS("2 H"),
        FOUR_HOURS("4 H"),
        TONIGHT("TONIGHT");

        fun until(now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime = clamp(
            when (this) {
                TWO_HOURS -> now.plusHours(2)
                FOUR_HOURS -> now.plusHours(4)
                TONIGHT -> now.withHour(23).withMinute(59).withSecond(0).withNano(0)
            },
            now,
        )
    }

    /** Where the picker opens: two hours from now. */
    fun defaultEnd(now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime = Preset.TWO_HOURS.until(now)

    /** The next whole minute. */
    fun earliest(now: ZonedDateTime): ZonedDateTime = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)

    fun latest(now: ZonedDateTime): ZonedDateTime = now.plusDays(MAX_DAYS)

    /** [end] without seconds, kept between the next minute and 30 days ahead. */
    fun clamp(end: ZonedDateTime, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
        val minute = end.truncatedTo(ChronoUnit.MINUTES)
        return when {
            minute.isBefore(earliest(now)) -> earliest(now)
            minute.isAfter(latest(now)) -> latest(now)
            else -> minute
        }
    }

    /** "13:45", in 24-hour time. */
    fun clock(end: ZonedDateTime): String = "%02d:%02d".format(end.hour, end.minute)

    /** "TODAY", "TOMORROW" or "SAT 21". */
    fun day(end: ZonedDateTime, now: ZonedDateTime): String {
        val date = end.withZoneSameInstant(now.zone).toLocalDate()
        return when (date) {
            now.toLocalDate() -> "TODAY"
            now.toLocalDate().plusDays(1) -> "TOMORROW"
            else -> "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)} ${date.dayOfMonth}".uppercase()
        }
    }

    /** "3 H 33 MIN", "45 MIN", "2 D 4 H". */
    fun duration(nowMs: Long, endMs: Long): String {
        val minutes = maxOf(0L, Math.ceil((endMs - nowMs) / 60_000.0).toLong())
        val days = minutes / 1_440
        val hours = minutes % 1_440 / 60
        val rest = minutes % 60
        return when {
            days > 0 -> if (hours > 0) "$days D $hours H" else "$days D"
            hours > 0 -> if (rest > 0) "$hours H $rest MIN" else "$hours H"
            else -> "$rest MIN"
        }
    }
}

/** "UNTIL 13:45", "UNTIL TOMORROW 09:00", "UNTIL SAT 21 18:00" or "ENDED"; null when there is no end. */
fun timeLeftLabel(expiresAtMs: Long?, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String? {
    expiresAtMs ?: return null
    if (expiresAtMs <= nowMs) return "ENDED"
    val end = Instant.ofEpochMilli(expiresAtMs).atZone(zone)
    val day = Stay.day(end, Instant.ofEpochMilli(nowMs).atZone(zone))
    return if (day == "TODAY") "UNTIL ${Stay.clock(end)}" else "UNTIL $day ${Stay.clock(end)}"
}

/** "SHARING UNTIL 13:45 · 3 H 33 MIN LEFT" for the crew page. */
fun sharingLabel(expiresAtMs: Long?, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String? {
    val left = timeLeftLabel(expiresAtMs, nowMs, zone) ?: return null
    return if (expiresAtMs!! <= nowMs) left else "SHARING $left · ${Stay.duration(nowMs, expiresAtMs)} LEFT"
}
