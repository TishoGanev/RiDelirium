package com.bgdeveloper.snowman.friends

import com.bgdeveloper.snowman.BuildConfig
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

    suspend fun updateName(token: String, name: String) {
        call("PUT", "riders/me", token, JSONObject().put("name", name))
    }

    suspend fun inviteCode(token: String): String = JSONObject(call("POST", "invites", token)).getString("code")

    suspend fun join(token: String, code: String): Friend = friend(JSONObject(call("POST", "invites/$code/join", token)))

    suspend fun friends(token: String): List<Friend> {
        val array = JSONArray(call("GET", "friends", token))
        return List(array.length()) { friend(array.getJSONObject(it)) }
    }

    suspend fun removeFriend(token: String, id: String) {
        call("DELETE", "friends/$id", token)
    }

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

    private fun friend(json: JSONObject) = Friend(
        id = json.getString("id"),
        displayName = json.getString("displayName"),
        initials = json.getString("initials"),
        isRiding = json.optBoolean("isRiding"),
        latitude = if (json.has("lat")) json.getDouble("lat") else null,
        longitude = if (json.has("lon")) json.getDouble("lon") else null,
        speedKmh = if (json.has("speedKmh")) json.getDouble("speedKmh") else null,
        updatedAtMs = if (json.has("ageSeconds")) System.currentTimeMillis() - json.getLong("ageSeconds") * 1000 else null,
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
