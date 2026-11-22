package cc.ortus.kit

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reading without a connection: the last answer to every read (a GET, or a
 * JMAP request made only of gets and queries) is kept on the phone, served
 * when the installation cannot be reached, and shown first so screens open
 * at once. Writes always need the connection.
 */
class ResponseCache(private val dir: File) {
    init { dir.mkdirs() }
    private fun file(key: String) = File(dir, "$key.json")

    @Synchronized fun get(key: String): ByteArray? = runCatching { file(key).takeIf { it.exists() }?.readBytes() }.getOrNull()

    @Synchronized fun put(key: String, data: ByteArray) {
        runCatching {
            val tmp = File(dir, "$key.tmp")
            tmp.writeBytes(data)
            tmp.renameTo(file(key))
        }
    }

    /** Everything kept (signing out, another account). */
    @Synchronized fun clear() { dir.listFiles()?.forEach { it.delete() } }
}

class CachingTransport(
    private val inner: Transport,
    val cache: ResponseCache,
    /** Told whether the last read reached the installation. */
    private val reached: (Boolean) -> Unit = {},
) : Transport {
    companion object {
        /** Answer reads from the phone only (to show what was kept at once). */
        val cacheOnly: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

        /** Whether a request only reads. */
        fun reads(method: String, url: String, body: ByteArray?): Boolean {
            val path = runCatching { java.net.URI(url).path }.getOrDefault("")
            if (method == "GET") return !path.contains("/push/") && !path.contains("/session")
            if (method != "POST" || !path.endsWith("/jmap") || body == null) return false
            val calls = runCatching {
                (json.parseToJsonElement(String(body)) as kotlinx.serialization.json.JsonObject)["methodCalls"] as kotlinx.serialization.json.JsonArray
            }.getOrNull() ?: return false
            return calls.isNotEmpty() && calls.all { c ->
                val name = runCatching { ((c as kotlinx.serialization.json.JsonArray)[0] as kotlinx.serialization.json.JsonPrimitive).content }.getOrNull() ?: ""
                name.endsWith("/get") || name.endsWith("/query")
            }
        }

        fun key(method: String, url: String, body: ByteArray?): String =
            sha256("$method $url\n".toByteArray() + (body ?: ByteArray(0))).joinToString("") { "%02x".format(it) }
    }

    override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Pair<Int, ByteArray> {
        if (!reads(method, url, body)) return inner.send(method, url, headers, body)
        val key = key(method, url, body)
        if (cacheOnly.get()) return 200 to (cache.get(key) ?: throw java.io.IOException("nothing kept"))
        return try {
            val (status, data) = inner.send(method, url, headers, body)
            if (status == 200) cache.put(key, data)
            reached(true)
            status to data
        } catch (e: Exception) {
            // No connection: what was kept, if anything.
            reached(false)
            cache.get(key)?.let { 200 to it } ?: throw e
        }
    }
}

/** Shows what the phone kept at once, then what the installation says. */
suspend fun <T> quickly(load: suspend () -> T, show: (T) -> Unit) {
    runCatching { withContext(CachingTransport.cacheOnly.asContextElement(true)) { load() } }.getOrNull()?.let(show)
    show(load())
}
