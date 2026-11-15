package com.bgdeveloper.ridelirium.friends

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** A group of riders who see each other on the map. [members] includes you. */
data class Crew(
    val id: String,
    val name: String,
    val code: String,
    val ownerId: String,
    /** When your membership ends (epoch ms); null = until you leave. */
    val myExpiresAtMs: Long?,
    val members: List<Friend>,
) {
    val invite: Invite get() = Invite(code, null)

    /** Everyone but [meId]. */
    fun others(meId: String?): List<Friend> = members.filter { it.id != meId }
}

/** What a code opens, shown before joining. */
data class CrewPreview(val code: String, val name: String, val ownerName: String, val memberCount: Int, val isMember: Boolean)

/** How long a joiner stays in a crew. */
enum class Stay(val title: String) {
    TODAY("TODAY"),
    WEEK("WEEK"),
    UNTIL_I_LEAVE("UNTIL I LEAVE");

    /** Epoch seconds the server should end the membership, or null for "until I leave". */
    fun untilEpochSeconds(now: ZonedDateTime = ZonedDateTime.now()): Long? = when (this) {
        TODAY -> now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toEpochSecond()
        WEEK -> now.plusDays(7).toEpochSecond()
        UNTIL_I_LEAVE -> null
    }
}

/** "UNTIL TONIGHT", "5 DAYS LEFT", "3 H LEFT"; null when there is no end. */
fun timeLeftLabel(expiresAtMs: Long?, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String? {
    expiresAtMs ?: return null
    val left = expiresAtMs - nowMs
    if (left <= 0) return "ENDED"
    val end = Instant.ofEpochMilli(expiresAtMs).atZone(zone)
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    // Ends tonight: midnight after today, or any time later today.
    if (end.toLocalDate() == now.toLocalDate() || end == now.toLocalDate().plusDays(1).atStartOfDay(zone)) return "UNTIL TONIGHT"
    val days = Math.ceil(left / 86_400_000.0).toInt()
    if (days >= 2) return "$days DAYS LEFT"
    return "${Math.ceil(left / 3_600_000.0).toInt()} H LEFT"
}
