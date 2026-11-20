package com.bgdeveloper.ridelirium.friends

import com.bgdeveloper.ridelirium.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val status: Int) : Exception("HTTP $status")

/** Thin client for the Ride Together API (Server/api). It only ever sends a name, an id and invite codes. */
class RideTogetherApi {
    val isConfigured: Boolean get() = BuildConfig.API_BASE_URL.isNotEmpty()

    /** Registers this install and returns its secret token. */
    suspend fun register(id: String, name: String): String =
        JSONObject(call("POST", "riders", token = null, body = JSONObject().put("id", id).put("name", name))).getString("token")

    /** Tells the server what I ride; my friends see the new tag at once. */
    suspend fun updateSport(token: String, sport: Sport) {
        call("PUT", "riders/me", token, JSONObject().put("sport", sport.key))
    }

    suspend fun updateName(token: String, name: String) {
        call("PUT", "riders/me", token, JSONObject().put("name", name))
    }

    /** My crews, each with its active members (me included). */
    suspend fun crews(token: String): List<Crew> {
        val array = JSONArray(call("GET", "crews", token))
        return List(array.length()) { crew(array.getJSONObject(it)) }
    }

    suspend fun createCrew(token: String, name: String): Crew = crew(JSONObject(call("POST", "crews", token, JSONObject().put("name", name))))

    suspend fun renameCrew(token: String, id: String, name: String): Crew =
        crew(JSONObject(call("PUT", "crews/$id", token, JSONObject().put("name", name))))

    /** What [code] opens, before joining. */
    suspend fun previewCrew(token: String, code: String): CrewPreview {
        val o = JSONObject(call("GET", "crews/preview/$code", token))
        return CrewPreview(code, o.getString("name"), o.getString("ownerName"), o.getInt("memberCount"), o.optBoolean("isMember"))
    }

    /** Joins the crew behind [code] until [untilEpochSeconds], or until I leave when null. */
    suspend fun joinCrew(token: String, code: String, untilEpochSeconds: Long?): Crew {
        val body = JSONObject().put("code", code).apply { untilEpochSeconds?.let { put("until", it) } }
        return crew(JSONObject(call("POST", "crews/join", token, body)))
    }

    suspend fun setStay(token: String, id: String, untilEpochSeconds: Long?): Crew {
        val body = JSONObject().apply { untilEpochSeconds?.let { put("until", it) } }
        return crew(JSONObject(call("PUT", "crews/$id/membership", token, body)))
    }

    suspend fun leaveCrew(token: String, id: String) {
        call("DELETE", "crews/$id/membership", token)
    }

    /** Drops the crew's meet point; the others get a push. */
    suspend fun setMeet(token: String, id: String, latitude: Double, longitude: Double, kind: MeetKind, inMinutes: Int): Crew {
        val body = JSONObject().put("lat", latitude).put("lon", longitude).put("kind", kind.key).put("inMinutes", inMinutes)
        return crew(JSONObject(call("PUT", "crews/$id/meet", token, body)))
    }

    suspend fun clearMeet(token: String, id: String): Crew = crew(JSONObject(call("DELETE", "crews/$id/meet", token)))

    /** Heartbeat with the rider's position; the server keeps only the latest and expires it after 90 seconds. */
    suspend fun setRiding(token: String, riding: Boolean, latitude: Double? = null, longitude: Double? = null, speedKmh: Double? = null) {
        val body = JSONObject().put("riding", riding)
        if (riding && latitude != null && longitude != null) body.put("lat", latitude).put("lon", longitude).put("speedKmh", speedKmh ?: 0.0)
        call("PUT", "live", token, body)
    }

    /** Where to send "joined / left your crew" and "wait for me" alerts: this phone's FCM token. */
    suspend fun registerPush(token: String, fcmToken: String) {
        call("PUT", "push", token, JSONObject().put("platform", "android").put("token", fcmToken))
    }

    suspend fun unregisterPush(token: String) {
        call("DELETE", "push", token, JSONObject())
    }

    /** Asks a friend to wait for you; the server pushes it to their phone. */
    suspend fun askToWait(token: String, friendId: String, distanceMeters: Int?) {
        val body = JSONObject().apply { distanceMeters?.let { put("distanceMeters", it) } }
        call("POST", "friends/$friendId/wait", token, body)
    }

    /** "OK, waiting": the sender is notified. */
    suspend fun acceptWait(token: String, id: String) {
        call("POST", "waits/$id/accept", token, JSONObject())
    }

    /** People I rode with in the last 30 days who aren't in my crew now. */
    suspend fun recentRiders(token: String): List<Friend> {
        val array = JSONArray(call("GET", "riders/recent", token))
        return List(array.length()) { friend(array.getJSONObject(it)) }
    }

    /** Asks a recent rider to join [crewId]; they get a push with its code. */
    suspend fun invite(token: String, riderId: String, crewId: String) {
        call("POST", "riders/$riderId/invite", token, JSONObject().put("crewId", crewId))
    }

    private fun crew(json: JSONObject): Crew {
        val members = json.getJSONArray("members")
        return Crew(
            id = json.getString("id"),
            name = json.getString("name"),
            code = json.getString("code"),
            ownerId = json.getString("ownerId"),
            myExpiresAtMs = if (json.isNull("myExpiresAt")) null else json.getLong("myExpiresAt") * 1000,
            members = List(members.length()) { friend(members.getJSONObject(it)) },
            meet = json.optJSONObject("meet")?.let { m ->
                MeetPoint(
                    latitude = m.getDouble("lat"),
                    longitude = m.getDouble("lon"),
                    kind = MeetKind.fromKey(m.optString("kind")),
                    atMs = m.getLong("at") * 1000,
                    byId = if (m.isNull("byId")) null else m.optString("byId"),
                    byName = if (m.isNull("byName")) null else m.optString("byName"),
                )
            },
        )
    }

    private fun friend(json: JSONObject) = Friend(
        id = json.getString("id"),
        displayName = json.getString("displayName"),
        initials = json.getString("initials"),
        isRiding = json.optBoolean("isRiding"),
        latitude = if (json.has("lat")) json.getDouble("lat") else null,
        longitude = if (json.has("lon")) json.getDouble("lon") else null,
        speedKmh = if (json.has("speedKmh")) json.getDouble("speedKmh") else null,
        updatedAtMs = if (json.has("ageSeconds")) System.currentTimeMillis() - json.getLong("ageSeconds") * 1000 else null,
        sport = Sport.fromKey(json.optString("sport")),
    )

    private suspend fun call(method: String, path: String, token: String?, body: JSONObject? = null): String =
        withContext(Dispatchers.IO) {
            val connection = URL("${BuildConfig.API_BASE_URL.trimEnd('/')}/$path").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val status = connection.responseCode
                if (status !in 200..299) throw ApiException(status)
                if (status == 204) "" else connection.inputStream.bufferedReader().readText()
            } finally {
                connection.disconnect()
            }
        }
}
