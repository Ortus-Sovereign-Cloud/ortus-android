package cc.ortus.kit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

class ApiError(val status: Int, val code: String, override val message: String) : Exception(message)

/** Sends a request: method, URL, headers, body; gives status and body. */
fun interface Transport {
    fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Pair<Int, ByteArray>
}

/** java.net.http, for the JVM (tests, tools); the app brings its own. */
object JvmTransport : Transport {
    private val http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15)).build()
    override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Pair<Int, ByteArray> {
        val b = java.net.http.HttpRequest.newBuilder(URI(url)).timeout(java.time.Duration.ofSeconds(30))
            .method(method, if (body != null) java.net.http.HttpRequest.BodyPublishers.ofByteArray(body) else java.net.http.HttpRequest.BodyPublishers.noBody())
        headers.forEach { (k, v) -> b.header(k, v) }
        val r = http.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray())
        return r.statusCode() to r.body()
    }
}

val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

/**
 * The Ortus API as the phone uses it: JSON, the session token as
 * `Authorization: Bearer`, and `X-Requested-With: ortus` on changes.
 */
class Client(val server: String, var token: String? = null, val transport: Transport = JvmTransport) {
    companion object {
        /** The address a person typed: https added when missing. */
        fun serverUrl(typed: String): String? {
            var text = typed.trim().trimEnd('/')
            if (text.isEmpty()) return null
            if (!text.contains("://")) text = "https://$text"
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() !in listOf("http", "https") || uri.host == null) return null
            // An installation lives at its origin: "Ortus.Example:443/x" is https://ortus.example.
            return WebAuthn.origin(text)
        }
    }

    fun headers(method: String, hasBody: Boolean): Map<String, String> = buildMap {
        put("Accept", "application/json")
        if (method != "GET") put("X-Requested-With", "ortus")
        if (hasBody) put("Content-Type", "application/json")
        token?.let { put("Authorization", "Bearer $it") }
    }

    suspend fun raw(method: String, path: String, body: JsonElement? = null): JsonElement = withContext(Dispatchers.IO) {
        val bytes = body?.toString()?.toByteArray()
        val (status, data) = transport.send(method, "$server/api/v1$path", headers(method, bytes != null), bytes)
        val text = String(data).ifEmpty { "{}" }
        if (status !in 200..299) {
            val e = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            val err = (e?.get("error") as? JsonObject) ?: e
            throw ApiError(status, err?.get("code")?.jsonPrimitive?.contentOrNull ?: "http_$status",
                err?.get("message")?.jsonPrimitive?.contentOrNull ?: "The server answered $status.")
        }
        json.parseToJsonElement(text)
    }

    suspend inline fun <reified T> call(method: String, path: String, body: JsonElement? = null): T =
        json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), raw(method, path, body))
}
