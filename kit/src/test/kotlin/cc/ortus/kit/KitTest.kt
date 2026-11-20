package cc.ortus.kit

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KitTest {
    @Test fun cbor() {
        assertEquals("1864", Cbor.UInt(100).encoded().joinToString("") { "%02x".format(it) })
        assertEquals("26", Cbor.NInt(-7).encoded().joinToString("") { "%02x".format(it) })
        assertEquals("a0", Cbor.Map(emptyList()).encoded().joinToString("") { "%02x".format(it) })
    }

    @Test fun registrationLayout() {
        val point = byteArrayOf(4) + ByteArray(32) { 1 } + ByteArray(32) { 2 }
        val id = ByteArray(16) { 9 }
        val auth = WebAuthn.registrationData("ortus.example", id, point)
        assertEquals(sha256("ortus.example".toByteArray()).toList(), auth.copyOfRange(0, 32).toList())
        assertEquals(0x45, auth[32].toInt())
        assertEquals(id.toList(), auth.copyOfRange(55, 71).toList())
        assertEquals(0xa3, WebAuthn.attestationObject(auth)[0].toInt() and 0xff)
        assertEquals("ortus.example", WebAuthn.rpId("https://ortus.example:8443"))
        assertEquals("https://ortus.example", WebAuthn.origin("https://ortus.example/"))
    }

    @Test fun serverAddress() {
        assertEquals("https://ortus.example", Client.serverUrl(" ortus.example/ "))
        assertEquals("http://127.0.0.1:8080", Client.serverUrl("http://127.0.0.1:8080"))
        assertNull(Client.serverUrl("ftp://x"))
    }

    @Test fun headersAndErrors() = runBlocking {
        val seen = mutableListOf<Map<String, String>>()
        val ok = Client("https://ortus.example", "t1") { _, _, h, _ -> seen += h; 200 to """{"id":"u1","email":"a@b","name":"A","role":"member"}""".toByteArray() }
        val me: Me = ok.call("GET", "/me")
        assertEquals("u1", me.id)
        assertEquals("Bearer t1", seen[0]["Authorization"])
        assertNull(seen[0]["X-Requested-With"])
        val failing = Client("https://ortus.example") { _, _, _, _ -> 403 to """{"error":{"code":"forbidden","message":"Not yours."}}""".toByteArray() }
        val e = assertFailsWith<ApiError> { failing.raw("POST", "/x", body("a" to 1)) }
        assertEquals("forbidden", e.code)
    }

    @Test fun roomTitles() {
        val rooms = json.decodeFromString(Rooms.serializer(), """{"rooms":[{"id":"r1","name":"flood","kind":"channel"},{"id":"r2","name":"","kind":"direct","with":["@u2:x"]}],"people":{"@u2:x":{"name":"Anna"}}}""")
        assertEquals("# flood", rooms.title(rooms.rooms[0]))
        assertEquals("Anna", rooms.title(rooms.rooms[1]))
    }
}
