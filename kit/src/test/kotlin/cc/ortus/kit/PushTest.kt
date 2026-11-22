package cc.ortus.kit

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushTest {
    private class Seen(val status: Int, val body: String) : Transport {
        val requests = mutableListOf<Triple<String, String, Map<String, String>>>()
        var sent: String? = null
        override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Pair<Int, ByteArray> {
            requests += Triple(method, url, headers)
            sent = body?.let { String(it) }
            return status to this.body.toByteArray()
        }
    }

    @Test fun registersTheEndpoint() = runBlocking {
        val t = Seen(200, """{"id":"pd1","fetch_token":"f1"}""")
        val r = Client("https://ortus.example", "s1", t).registerPush("https://ntfy.example/upAbC?up=1", 60)
        assertEquals(PushRegistration("pd1", "f1"), r)
        assertEquals("https://ortus.example/api/v1/me/push-devices", t.requests[0].second)
        val sent = json.parseToJsonElement(t.sent!!).toString()
        assertTrue(sent.contains("\"platform\":\"unifiedpush\"") && sent.contains("ntfy.example") && sent.contains("\"utc_offset\":60"), sent)
    }

    @Test fun fetchesWithTheFetchTokenOnly() = runBlocking {
        val t = Seen(200, """{"app":"chat","title":"Anna mentioned you","detail":"#flood-response","path":"/chat?room=r1","at":1}""")
        val n = Push.fetch("https://ortus.example", "f1", "nabc", t)
        assertEquals("Anna mentioned you", n.title)
        val (method, url, headers) = t.requests[0]
        assertEquals("GET", method)
        assertEquals("https://ortus.example/api/v1/push/notices/nabc", url)
        assertEquals("f1", headers["X-Ortus-Push"])
        assertNull(headers["Authorization"], "never the session")
        assertFailsWith<ApiError> { Push.fetch("https://ortus.example", "f1", "nabc", Seen(401, "{}")) }
    }

    @Test fun readsOnlyOurPushes() {
        assertEquals("n1" to "https://ortus.example", Push.parts("""{"n":"n1","s":"https://ortus.example"}""".toByteArray()))
        assertNull(Push.parts("""{"n":"n1"}""".toByteArray()))
        assertNull(Push.parts("""{"n":"../x","s":"https://ortus.example"}""".toByteArray()))
        assertNull(Push.parts("""{"n":"n1","s":"https://ortus.example/elsewhere"}""".toByteArray()))
        assertNull(Push.parts("not json".toByteArray()))
    }

    @Test fun opensTheRightTab() {
        assertEquals(0, Push.tab("/mail?thread=t1"))
        assertEquals(1, Push.tab("/chat?room=r1"))
        assertEquals(2, Push.tab("/calendar"))
        assertEquals(3, Push.tab("/drive"))
        assertEquals(4, Push.tab(null))
    }
}
