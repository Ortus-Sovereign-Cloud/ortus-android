package cc.ortus.kit

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CacheTest {
    private class Flaky : Transport {
        var online = true
        var calls = 0
        override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Pair<Int, ByteArray> {
            calls++
            if (!online) throw java.io.IOException("no network")
            return 200 to """{"n":$calls}""".toByteArray()
        }
    }

    private fun n(e: kotlinx.serialization.json.JsonElement) = e.jsonObject["n"]!!.jsonPrimitive.int

    @Test fun readsAreKeptAndServedOffline(): Unit = runBlocking {
        val inner = Flaky()
        var reached: Boolean? = null
        val t = CachingTransport(inner, ResponseCache(kotlin.io.path.createTempDirectory("ortus-cache").toFile())) { reached = it }
        val c = Client("https://ortus.example", "s", t)
        assertEquals(1, n(c.raw("GET", "/chat/rooms")))
        assertEquals(true, reached)
        inner.online = false
        assertEquals(1, n(c.raw("GET", "/chat/rooms")), "the kept answer")
        assertEquals(false, reached)
        assertFailsWith<Exception> { c.raw("GET", "/calendar/events") }
        assertFailsWith<Exception> { c.raw("POST", "/chat/rooms/r1/messages", JsonObject(mapOf("text" to JsonPrimitive("hi")))) }
        // Shown at once: from the phone only, without asking the installation.
        inner.online = true
        val calls = inner.calls
        var shown = mutableListOf<Int>()
        quickly({ n(c.raw("GET", "/chat/rooms")) }) { shown += it }
        assertEquals(listOf(1, calls + 1), shown)
        t.cache.clear()
        inner.online = false
        assertFailsWith<Exception> { c.raw("GET", "/chat/rooms") }
    }

    @Test fun onlyJmapReadsCount() {
        fun jmap(vararg names: String) = """{"methodCalls":[${names.joinToString(",") { "[\"$it\",{},\"x\"]" }}]}""".toByteArray()
        val url = "https://ortus.example/api/v1/mail/web/jmap"
        assertTrue(CachingTransport.reads("POST", url, jmap("Mailbox/get", "Email/query", "Email/get")))
        assertFalse(CachingTransport.reads("POST", url, jmap("Email/get", "Email/set")))
        assertFalse(CachingTransport.reads("POST", "https://ortus.example/api/v1/session/sign-out", null))
        assertFalse(CachingTransport.reads("GET", "https://ortus.example/api/v1/push/notices/n1", null))
    }

    @Test fun messagesWaitAndGoOnceBackOnline(): Unit = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("ortus-outbox").toFile()
        var online = false
        val sent = mutableListOf<Triple<String, Map<String, String>, String?>>()
        val net = Transport { m, u, h, b ->
            if (!online) throw java.net.ConnectException("no network")
            sent += Triple("$m $u", h, b?.let { String(it) })
            200 to "{}".toByteArray()
        }
        val t = CachingTransport(net, ResponseCache(File(dir, "c")))
        t.outbox = Outbox(File(dir, "outbox.json"))
        val c = Client("https://ortus.example", "s1", t)
        assertFailsWith<Queued> { c.raw("POST", "/chat/rooms/r1/messages", JsonObject(mapOf("text" to JsonPrimitive("On my way")))) }
        assertEquals(1, t.outbox!!.count)
        // Other writes still fail plainly.
        val other = runCatching { c.raw("POST", "/calendar/events", JsonObject(emptyMap())) }.exceptionOrNull()
        assertTrue(other != null && other !is Queued, "$other")
        assertEquals(1, t.outbox!!.count)
        // Back online: the next call sends what waited, with the session.
        online = true
        c.raw("GET", "/chat/rooms")
        repeat(50) { if (t.outbox!!.count != 0) Thread.sleep(20) }
        assertEquals(0, t.outbox!!.count)
        val message = sent.first { it.first.endsWith("/messages") }
        assertEquals("Bearer s1", message.second["Authorization"])
        assertTrue(message.third!!.contains("On my way"))
        assertFalse(File(dir, "outbox.json").let { it.exists() && it.readText().contains("s1") }, "no session on disk")
    }
}

