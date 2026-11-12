package com.bgdeveloper.snowman.data

import android.content.Context
import android.location.Geocoder
import com.bgdeveloper.snowman.domain.SessionSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Resolves a resort or town name for each session's start point. Results are cached across launches. */
@Singleton
class PlaceNameResolver @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("session_place_names", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val attempted = mutableSetOf<String>()
    private val mutex = Mutex() // Geocoder is rate-limited, so lookups run one at a time.

    private val _names = MutableStateFlow(prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap())
    val names: StateFlow<Map<String, String>> = _names.asStateFlow()

    fun resolve(session: SessionSummary) {
        val key = session.placeKey
        val first = session.points.firstOrNull() ?: return
        if (_names.value.containsKey(key) || !synchronized(attempted) { attempted.add(key) }) return
        scope.launch {
            mutex.withLock {
                val name = lookup(first.lat, first.lon) ?: return@withLock
                prefs.edit().putString(key, name).apply()
                _names.update { it + (key to name) }
            }
        }
    }

    /** The town or resort at a position, for the idle Track screen. Not cached: the rider moves. */
    suspend fun nameAt(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        mutex.withLock { lookup(lat, lon) }
    }

    @Suppress("DEPRECATION")
    private fun lookup(lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        val address = try {
            Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
        } catch (_: Exception) {
            null
        }
        return address?.locality ?: address?.subAdminArea ?: address?.featureName
    }
}
