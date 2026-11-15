package com.bgdeveloper.ridelirium.data

import com.bgdeveloper.ridelirium.domain.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RideRepository @Inject constructor(private val dao: TrackPointDao) {

    fun observeSessions(): Flow<List<SessionSummary>> = dao.observeAll()
        .map { points ->
            points.groupBy { it.session }
                .map { (session, list) -> SessionSummary(session, list) }
                .sortedByDescending { it.startMillis }
        }
        .flowOn(Dispatchers.Default)

    fun observeSession(session: Int): Flow<SessionSummary?> = dao.observeSession(session)
        .map { if (it.isEmpty()) null else SessionSummary(session, it) }
        .flowOn(Dispatchers.Default)

    suspend fun nextSessionId(): Int = dao.maxSession() + 1

    suspend fun addPoint(point: TrackPointEntity) = dao.insert(point)

    /** Every point of [session] so far, oldest first. */
    suspend fun sessionPoints(session: Int): List<TrackPointEntity> = dao.observeSession(session).first()

    suspend fun deleteSession(session: Int) = dao.deleteSession(session)
}
