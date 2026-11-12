package com.bgdeveloper.snowman.data

import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Current conditions where the rider is. All values metric, as Open-Meteo sends them. */
data class Weather(
    val temperatureC: Double,
    val apparentC: Double,
    /** WMO weather code. */
    val code: Int,
    val windKmh: Double,
    val gustKmh: Double,
    /** Where the wind comes from, degrees clockwise from north. */
    val windFromDeg: Double,
    val snowDepthM: Double?,
    val snowfall24hCm: Double,
    val visibilityM: Double?,
    val latitude: Double,
    val longitude: Double,
    val fetchedAtMs: Long,
) {
    enum class Sky { CLEAR, CLOUD, FOG, RAIN, SNOW, STORM }

    val sky: Sky
        get() = when (code) {
            0, 1 -> Sky.CLEAR
            45, 48 -> Sky.FOG
            in 51..67, in 80..82 -> Sky.RAIN
            in 71..77, 85, 86 -> Sky.SNOW
            in 95..99 -> Sky.STORM
            else -> Sky.CLOUD
        }

    val label: String
        get() = when (code) {
            0 -> "CLEAR"
            1 -> "MOSTLY CLEAR"
            2 -> "PARTLY CLOUDY"
            3 -> "OVERCAST"
            45, 48 -> "FOG"
            in 51..57 -> "DRIZZLE"
            61, 80 -> "LIGHT RAIN"
            63, 81 -> "RAIN"
            65, 82 -> "HEAVY RAIN"
            66, 67 -> "FREEZING RAIN"
            71, 85 -> "LIGHT SNOW"
            73 -> "SNOW"
            75, 86 -> "HEAVY SNOW"
            77 -> "SNOW GRAINS"
            in 95..99 -> "THUNDERSTORM"
            else -> ""
        }
}

/**
 * Weather for the idle Track screen, from Open-Meteo (free, no key). The position is rounded to about a kilometre
 * before it is sent. Refreshes at most every 15 minutes, or sooner after moving 5 km.
 */
@Singleton
class WeatherRepository @Inject constructor() {
    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather.asStateFlow()

    private val mutex = Mutex()
    private var lastAttemptMs = 0L

    suspend fun refresh(latitude: Double, longitude: Double, altitudeMeters: Double?) {
        if (!mutex.tryLock()) return
        try {
            val now = System.currentTimeMillis()
            val last = _weather.value
            if (last != null && now - last.fetchedAtMs < FRESH_MS && distance(last, latitude, longitude) < MOVED_METERS) return
            // Failed or in-flight lookups are not retried on every GPS fix.
            if (now - lastAttemptMs < RETRY_MS) return
            lastAttemptMs = now
            fetch(latitude, longitude, altitudeMeters)?.let { _weather.value = it }
        } finally {
            mutex.unlock()
        }
    }

    private fun distance(w: Weather, latitude: Double, longitude: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(w.latitude, w.longitude, latitude, longitude, out)
        return out[0]
    }

    private suspend fun fetch(latitude: Double, longitude: Double, altitudeMeters: Double?): Weather? = withContext(Dispatchers.IO) {
        val lat = "%.2f".format(Locale.US, latitude)
        val lon = "%.2f".format(Locale.US, longitude)
        // On a mountain the grid cell's elevation can be far off, so the GPS altitude gives a truer temperature.
        val elevation = altitudeMeters?.takeIf { it in -100.0..6000.0 }?.let { "&elevation=%.0f".format(Locale.US, it) } ?: ""
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon$elevation" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,snow_depth,visibility" +
            "&hourly=snowfall&past_hours=24&forecast_hours=1&wind_speed_unit=kmh&timezone=auto"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (connection.responseCode !in 200..299) return@withContext null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val current = json.getJSONObject("current")
            val snowfall = json.optJSONObject("hourly")?.optJSONArray("snowfall")
            val snowfall24h = snowfall?.let { array -> (0 until array.length()).sumOf { array.optDouble(it, 0.0).takeIf { v -> !v.isNaN() } ?: 0.0 } } ?: 0.0
            Weather(
                temperatureC = current.getDouble("temperature_2m"),
                apparentC = current.optDouble("apparent_temperature", current.getDouble("temperature_2m")),
                code = current.optInt("weather_code", -1),
                windKmh = current.optDouble("wind_speed_10m", 0.0),
                gustKmh = current.optDouble("wind_gusts_10m", 0.0),
                windFromDeg = current.optDouble("wind_direction_10m", 0.0),
                snowDepthM = current.optNumber("snow_depth"),
                snowfall24hCm = snowfall24h,
                visibilityM = current.optNumber("visibility"),
                latitude = latitude,
                longitude = longitude,
                fetchedAtMs = System.currentTimeMillis(),
            )
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.optNumber(key: String): Double? =
        if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

    private companion object {
        const val FRESH_MS = 15 * 60_000L
        const val RETRY_MS = 2 * 60_000L
        const val MOVED_METERS = 5_000f
    }
}
