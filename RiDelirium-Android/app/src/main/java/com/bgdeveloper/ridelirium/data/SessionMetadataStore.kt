package com.bgdeveloper.ridelirium.data

import android.content.Context
import com.bgdeveloper.ridelirium.domain.SessionMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Per-session facts the points don't carry, such as how many fixes were rejected. Stays on this phone. */
@Singleton
class SessionMetadataStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("sessionMetadata", Context.MODE_PRIVATE)

    fun save(session: Int, metadata: SessionMetadata) {
        prefs.edit().putString(key(session), "${metadata.rejectedLowAccuracy},${metadata.rejectedStale}").apply()
    }

    fun load(session: Int): SessionMetadata {
        val parts = prefs.getString(key(session), null)?.split(',')?.mapNotNull { it.toIntOrNull() }
        return if (parts != null && parts.size == 2) SessionMetadata(parts[0], parts[1]) else SessionMetadata()
    }

    private fun key(session: Int) = "sessionMetadata.$session"
}
