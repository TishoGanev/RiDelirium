package com.bgdeveloper.ridelirium.data

import android.content.Context
import com.bgdeveloper.ridelirium.domain.StatsRange
import com.bgdeveloper.ridelirium.domain.Units
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** App-wide preferences. Units are shared by Track, Stats and Daily stats. */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _units = MutableStateFlow(Units(prefs.getBoolean(KEY_METRIC, true)))
    val units: StateFlow<Units> = _units.asStateFlow()

    private val _range = MutableStateFlow(prefs.getString(KEY_RANGE, null)?.let { runCatching { StatsRange.valueOf(it) }.getOrNull() } ?: StatsRange.ALL)
    /** How far back the Stats list reaches. */
    val statsRange: StateFlow<StatsRange> = _range.asStateFlow()

    fun setStatsRange(range: StatsRange) {
        prefs.edit().putString(KEY_RANGE, range.name).apply()
        _range.value = range
    }

    fun setMetric(metric: Boolean) {
        prefs.edit().putBoolean(KEY_METRIC, metric).apply()
        _units.value = Units(metric)
    }

    private companion object {
        const val KEY_METRIC = "unitsMetric"
        const val KEY_RANGE = "statsRange"
    }
}
