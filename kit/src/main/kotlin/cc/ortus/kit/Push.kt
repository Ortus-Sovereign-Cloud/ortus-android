package cc.ortus.kit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * Notifications while the app is closed, through UnifiedPush (no Google
 * services): the phone's distributor app gives an endpoint, the
 * installation posts to it a push naming only a notification's id, and the
 * app fetches the text itself with its fetch token (which can do nothing
 * else).
 */
@Serializable
data class PushRegistration(val id: String, val fetch_token: String)

@Serializable
data class PushNotice(val app: String, val title: String, val detail: String? = null, val path: String? = null)

object Push {
    /** The notification id and installation a push names, if it is one of ours. */
    fun parts(message: ByteArray): Pair<String, String>? {
        val o = runCatching { json.parseToJsonElement(String(message)).jsonObject }.getOrNull() ?: return null
        val id = o["n"]?.jsonPrimitive?.contentOrNull ?: return null
        val s = o["s"]?.jsonPrimitive?.contentOrNull ?: return null
        if (id.isEmpty() || id.length > 64 || !id.all { it.isLetterOrDigit() }) return null
        val server = Client.serverUrl(s) ?: return null
        if (server != s.trim().trimEnd('/')) return null
        return id to server
    }

    /** The text of one notification, with the fetch token (never the session). */
    suspend fun fetch(server: String, fetchToken: String, id: String, transport: Transport = JvmTransport): PushNotice = withContext(Dispatchers.IO) {
        val url = "$server/api/v1/push/notices/" + URLEncoder.encode(id, "UTF-8")
        val (status, data) = transport.send("GET", url, mapOf("Accept" to "application/json", "X-Ortus-Push" to fetchToken), null)
        if (status != 200) throw ApiError(status, "http_$status", "The server answered $status.")
        json.decodeFromString(PushNotice.serializer(), String(data))
    }

    /** Whether a push is a call (it rings). */
    fun isCall(message: ByteArray): Boolean =
        runCatching { json.parseToJsonElement(String(message)).jsonObject["c"]?.jsonPrimitive?.contentOrNull == "1" }.getOrDefault(false)

    /** The room a call's path names (`/calls?room=…`). */
    fun callRoom(path: String?): String? =
        path?.takeIf { it.startsWith("/calls") }?.substringAfter("room=", "")?.substringBefore("&")?.takeIf { it.isNotEmpty() }
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }

    /** Which tab a notification's path opens: 0 Mail, 1 Chat, 2 Calendar, 3 Files, 4 More. */
    fun tab(path: String?): Int {
        val p = path ?: ""
        return when {
            p.startsWith("/mail") -> 0
            p.startsWith("/chat") || p.startsWith("/calls") -> 1
            p.startsWith("/calendar") -> 2
            p.startsWith("/drive") || p.startsWith("/docs") -> 3
            else -> 4
        }
    }
}

/** Registers this phone's UnifiedPush endpoint for its person's notifications. */
suspend fun Client.registerPush(endpoint: String, utcOffsetMinutes: Int): PushRegistration =
    call("POST", "/me/push-devices", JsonObject(mapOf(
        "platform" to JsonPrimitive("unifiedpush"),
        "token" to JsonPrimitive(endpoint),
        "utc_offset" to JsonPrimitive(utcOffsetMinutes),
    )))

suspend fun Client.unregisterPush(id: String) {
    raw("DELETE", "/me/push-devices/" + URLEncoder.encode(id, "UTF-8"))
}
