package com.bgdeveloper.ridelirium.domain

import java.time.LocalDate

/** How far back the Stats list reaches. Keep in sync with `StatsRange.swift` on iOS. */
enum class StatsRange(val title: String, val headerTitle: String) {
    TODAY("TODAY", "TODAY"),
    DAYS_3("3 DAYS", "LAST 3 DAYS"),
    DAYS_7("7 DAYS", "LAST 7 DAYS"),
    MONTH("1 MONTH", "LAST MONTH"),
    SEASON("SEASON", "SEASON"),
    ALL("ALL", "ALL TIME");

    /** Calendar days count, today included: 3 DAYS is today and the two days before it. */
    fun cutoff(today: LocalDate): LocalDate? = when (this) {
        TODAY -> today
        DAYS_3 -> today.minusDays(2)
        DAYS_7 -> today.minusDays(6)
        MONTH -> today.minusDays(29)
        SEASON -> LocalDate.of(Season.containing(today).startYear, 7, 1)
        ALL -> null
    }

    fun contains(date: LocalDate, today: LocalDate = LocalDate.now()): Boolean =
        cutoff(today)?.let { !date.isBefore(it) } ?: true
}
