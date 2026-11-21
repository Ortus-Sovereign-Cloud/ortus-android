package cc.ortus.kit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Finding an installation from a work email address: the organisation names
 * it at `/.well-known/ortus` (`{"server": "https://…"}`), on its own website
 * or on an `ortus.` host of its domain.
 */
object Discovery {
    private val DOMAIN = Regex("^[a-z0-9.-]+\\.[a-z0-9-]+$")

    /** Where to ask, in order, for `name@ministry.gov`. */
    fun candidates(email: String): List<String> {
        val parts = email.trim().lowercase().split('@')
        if (parts.size != 2 || parts[0].isEmpty() || !DOMAIN.matches(parts[1])) return emptyList()
        val domain = parts[1]
        return listOf("https://$domain/.well-known/ortus", "https://ortus.$domain/.well-known/ortus")
    }

    /** The installation an answer names (https only), if it names one. */
    fun server(answer: ByteArray): String? {
        val text = runCatching { Json.parseToJsonElement(answer.decodeToString()).jsonObject["server"]?.jsonPrimitive?.content }.getOrNull() ?: return null
        return Client.serverUrl(text)?.takeIf { it.startsWith("https://") }
    }

    /** Asks each place in turn; the first answer that names an installation wins. */
    suspend fun find(email: String, transport: Transport = JvmTransport): String? = withContext(Dispatchers.IO) {
        for (url in candidates(email)) {
            val (status, body) = runCatching { transport.send("GET", url, mapOf("Accept" to "application/json"), null) }.getOrNull() ?: continue
            if (status == 200) server(body)?.let { return@withContext it }
        }
        null
    }
}
