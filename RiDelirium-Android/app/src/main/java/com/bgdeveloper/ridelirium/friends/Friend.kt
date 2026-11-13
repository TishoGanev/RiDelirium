package com.bgdeveloper.ridelirium.friends

import com.bgdeveloper.ridelirium.BuildConfig
import java.net.URLDecoder
import java.net.URLEncoder

data class Friend(
    val id: String,
    val displayName: String,
    val initials: String,
    val isRiding: Boolean = false,
    /** Where they are right now; only set while they ride and share it with you. */
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speedKmh: Double? = null,
    /** When that position was sent (epoch ms), from the server's age in seconds. */
    val updatedAtMs: Long? = null,
) {
    val hasPosition: Boolean get() = latitude != null && longitude != null

    /** Stable per rider, from the same hash as the iPhone app. */
    val avatarColor: Long
        get() {
            val hash = id.fold(0) { acc, c -> (acc * 31 + c.code) and 0xFFFF }
            return AVATAR_COLORS[hash % AVATAR_COLORS.size]
        }

    private companion object {
        val AVATAR_COLORS = longArrayOf(0xFF353535, 0xFF8A8A8A, 0xFFED213D)
    }
}

/** "Alex Rider" -> "AR"; "ME" when there is nothing to take letters from. */
fun initialsOf(name: String): String =
    name.split(" ").filter { it.isNotBlank() }.take(2).map { it.first().uppercaseChar() }.joinToString("").ifEmpty { "ME" }

/** An invite to ride together. The QR code encodes [url]; scanning it opens the app and joins. */
data class Invite(val code: String, val inviter: Friend?) {
    val url: String get() = InviteLink.url(code, inviter, InviteLink.configuredHost)
}

/** Same format as the iPhone app, so either platform can scan the other's QR code. */
object InviteLink {
    const val SCHEME = "snowboarder"
    private const val PREFIX = "$SCHEME://join"
    private const val WEB_PATH = "/join/"

    /**
     * The API's host, which serves https://<host>/join/<code> so the link is tappable in Viber, WhatsApp and the like.
     * Null when the app talks to a bare IP address or is not configured.
     */
    val configuredHost: String? by lazy { hostOf(BuildConfig.API_BASE_URL) }

    fun hostOf(baseUrl: String): String? {
        val host = baseUrl.substringAfter("://", "").substringBefore('/').substringBefore(':')
        return host.takeIf { it.isNotEmpty() && !it.first().isDigit() }
    }

    /** An https link when [webHost] is known, else the app's own scheme with the inviter's details. */
    fun url(code: String, inviter: Friend?, webHost: String? = null): String {
        if (webHost != null) return "https://$webHost$WEB_PATH$code"
        val query = inviter?.let {
            "?id=${encode(it.id)}&name=${encode(it.displayName)}&initials=${encode(it.initials)}"
        }.orEmpty()
        return "$PREFIX/$code$query"
    }

    /** Whether [scheme]/[host]/[path] is an invite for this app: the https link or the snowboarder:// form. */
    fun isInvite(scheme: String?, host: String?, path: String?, webHost: String? = configuredHost): Boolean =
        (scheme == SCHEME && host == "join") ||
            (scheme == "https" && webHost != null && host.equals(webHost, ignoreCase = true) && path.orEmpty().startsWith(WEB_PATH))

    /** Accepts a scanned or opened invite link, or a typed code such as "ABCD-2345". */
    fun parse(text: String, webHost: String? = configuredHost): Pair<String, Friend?>? {
        val trimmed = text.trim()
        val webPrefix = webHost?.let { "https://$it$WEB_PATH" }
        if (webPrefix != null && trimmed.startsWith(webPrefix, ignoreCase = true)) {
            return normalize(trimmed.substring(webPrefix.length).substringBefore('?').substringBefore('#'))?.let { it to null }
        }
        if (!trimmed.startsWith("$PREFIX/", ignoreCase = true)) {
            return normalize(trimmed)?.let { it to null }
        }
        val rest = trimmed.substring(PREFIX.length)
        val code = normalize(rest.substringBefore('?')) ?: return null
        val items = rest.substringAfter('?', "").split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to decode(it.substringAfter('=')) }
        val id = items["id"]
        val name = items["name"]
        val inviter = if (!id.isNullOrEmpty() && !name.isNullOrEmpty()) {
            Friend(id.uppercase(), name, items["initials"] ?: initialsOf(name))
        } else null
        return code to inviter
    }

    private fun normalize(raw: String): String? {
        val code = raw.uppercase().filter { it.isLetterOrDigit() }
        return code.takeIf { it.length == 8 }
    }

    // iOS writes a space as %20 and leaves "+" alone, so "+" stays a plus on the way in.
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
}
