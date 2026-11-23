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

/** Thrown when a message could not leave the phone and waits in the outbox. */
class Queued : Exception("queued")

/**
 * Messages written without a connection, kept on the phone (without the
 * session: the one in use is added when they go) and sent in order once
 * the installation answers again.
 */
class Outbox(private val file: File, var changed: (Int) -> Unit = {}) {
    @kotlinx.serialization.Serializable
    data class Item(val id: String, val method: String, val url: String, val body: String?)

    private val list = kotlinx.serialization.builtins.ListSerializer(Item.serializer())
    @Volatile private var sending = false

    @Synchronized private fun items(): List<Item> = runCatching { json.decodeFromString(list, file.readText()) }.getOrDefault(emptyList())
    @Synchronized private fun save(items: List<Item>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(list, items))
        tmp.renameTo(file)
    }

    val count: Int get() = items().size

    @Synchronized fun add(method: String, url: String, body: ByteArray?) {
        val now = items() + Item(java.util.UUID.randomUUID().toString(), method, url, body?.let { String(it) })
        save(now)
        changed(now.size)
    }

    @Synchronized fun clear() { file.delete(); changed(0) }

    /** Sends what waits, oldest first; stops at the first that cannot go yet; a refused one (4xx) is dropped. */
    fun flush(inner: Transport, authorization: String?) {
        synchronized(this) { if (sending || items().isEmpty()) return; sending = true }
        try {
            while (true) {
                val next = items().firstOrNull() ?: return
                val headers = buildMap {
                    put("Content-Type", "application/json"); put("Accept", "application/json"); put("X-Requested-With", "ortus")
                    authorization?.let { put("Authorization", it) }
                }
                val status = runCatching { inner.send(next.method, next.url, headers, next.body?.toByteArray()).first }.getOrNull() ?: return
                if (status >= 500) return
                val rest = synchronized(this) { items().filter { it.id != next.id }.also { save(it) } }
                changed(rest.size)
            }
        } finally {
            sending = false
        }
    }
}

class CachingTransport(
    private val inner: Transport,
    val cache: ResponseCache,
    /** Told whether the last read reached the installation. */
    private val reached: (Boolean) -> Unit = {},
) : Transport {
    /** Writes that wait for the connection (mail and chat messages). */
    var outbox: Outbox? = null

    private fun flush(headers: Map<String, String>) {
        val box = outbox ?: return
        if (box.count == 0) return
        val auth = headers["Authorization"]
        Thread { box.flush(inner, auth) }.start()
    }
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

        /** A message to send: chat messages and mail submissions. */
        fun queueable(method: String, url: String, body: ByteArray?): Boolean {
            if (method != "POST") return false
            val path = runCatching { java.net.URI(url).path }.getOrDefault("")
            if (path.contains("/chat/rooms/") && path.endsWith("/messages")) return true
            return path.endsWith("/jmap") && body != null && String(body).contains("EmailSubmission/set")
        }

        /** The request certainly never left the phone (so sending it later cannot send it twice). */
        fun neverLeft(e: Throwable): Boolean =
            e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.NoRouteToHostException

        fun key(method: String, url: String, body: ByteArray?): String =
            sha256("$method $url\n".toByteArray() + (body ?: ByteArray(0))).joinToString("") { "%02x".format(it) }
    }

    override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Pair<Int, ByteArray> {
        if (!reads(method, url, body)) {
            return try {
                inner.send(method, url, headers, body).also { reached(true); flush(headers) }
            } catch (e: Exception) {
                val box = outbox
                if (box != null && queueable(method, url, body) && neverLeft(e)) {
                    box.add(method, url, body)
                    reached(false)
                    throw Queued()
                }
                throw e
            }
        }
        val key = key(method, url, body)
        if (cacheOnly.get()) return 200 to (cache.get(key) ?: throw java.io.IOException("nothing kept"))
        return try {
            val (status, data) = inner.send(method, url, headers, body)
            if (status == 200) cache.put(key, data)
            reached(true)
            flush(headers)
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
