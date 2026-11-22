package cc.ortus.kit

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallsTest {
    @Test fun partsRoundTrip() {
        val long = "v=0\r\n" + "a=x".repeat(20_000)
        val parts = CallParts.split(long, "abc")
        assertEquals(4, parts.size)
        val j = CallParts.Joiner()
        // Out of order: whole only when every part is in.
        assertNull(j.take(parts[2]))
        assertNull(j.take(parts[0]))
        assertNull(j.take(parts[3]))
        assertEquals(long, j.take(parts[1]))
        assertNull(j.take("""{"id":"x","part":5,"of":2,"data":"y"}"""))
        assertNull(j.take("not json"))
        assertEquals("hi", CallParts.Joiner().take(CallParts.split("hi")[0]))
    }

    @Test fun offersAndAnswers() {
        assertEquals("v=0" to 3, CallParts.offer("""{"type":"offer","sdp":"v=0","n":3}"""))
        assertNull(CallParts.offer("""{"type":"answer","sdp":"v=0"}"""))
        val a = json.parseToJsonElement(CallParts.answer("v=1", 3)).toString()
        assertTrue(a.contains("\"type\":\"answer\"") && a.contains("\"n\":3"), a)
    }

    @Test fun joinsWithTheRelay(): Unit = runBlocking {
        val body = """{"call":{"id":"c1","room":"r1","sessions":[{"session":"s2","user":"u2","muted":true}],"people":{"u2":{"name":"Taras"}}},"session":"s1","ice_servers":[{"urls":["turns:turn.example:443?transport=tcp"],"username":"1:s1","credential":"x"}]}"""
        val seen = mutableListOf<String>()
        val c = Client("https://ortus.example", "t", Transport { m, u, _, _ -> seen += "$m $u"; 200 to body.toByteArray() })
        val j = c.joinCall("r 1")
        assertEquals("s1", j.session)
        assertEquals("turns:turn.example:443?transport=tcp", j.ice_servers[0].urls[0])
        assertEquals("Taras", j.call.nameOf("s2"))
        assertEquals("POST https://ortus.example/api/v1/calls/rooms/r+1/join", seen[0])
    }

    @Test fun sendsTheOffer(): Unit = runBlocking {
        var sent = ""
        val c = Client("https://ortus.example", "t", Transport { _, _, _, b -> sent = String(b!!); 200 to """{"answer":{"type":"answer","sdp":"v=0 answer"}}""".toByteArray() })
        assertEquals("v=0 answer", c.sendOffer("s1", "v=0 offer"))
        assertTrue(sent.contains("\"type\":\"offer\"") && sent.contains("v=0 offer"), sent)
    }
}
