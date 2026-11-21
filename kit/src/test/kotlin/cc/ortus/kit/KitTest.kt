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
        // As the server's public_url reads: lower case, no default port, no path.
        assertEquals("https://ortus.example", Client.serverUrl("HTTPS://Ortus.Example:443/inbox"))
        assertEquals("http://10.0.2.2:7878", WebAuthn.origin("http://10.0.2.2:7878/"))
        // The key signs only for the host it talks to, or a parent of it.
        assertEquals("example.org", WebAuthn.checkedRpId("example.org", "https://ortus.example.org"))
        assertEquals("ortus.example.org", WebAuthn.checkedRpId(null, "https://Ortus.example.org"))
        kotlin.test.assertFailsWith<ApiError> { WebAuthn.checkedRpId("bank.example", "https://ortus.example.org") }
        kotlin.test.assertFailsWith<ApiError> { WebAuthn.checkedRpId("ple.org", "https://ortus.example.org") }
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

/** Signing in with a password: the ticket, then the one-time code. */
class PasswordTest {
    @kotlin.test.Test fun passwordThenCode() = kotlinx.coroutines.runBlocking {
        val sent = mutableListOf<Pair<String, String>>()
        val answers = ArrayDeque(listOf("""{"next":"code","ticket":"t1"}""", """{"id":"u1","email":"iryna@ministry.example","name":"Iryna","role":"member","session_token":"s1"}"""))
        val transport = object : Transport {
            override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Pair<Int, ByteArray> {
                sent += url.substringAfter("/api/v1") to String(body ?: ByteArray(0))
                return 200 to answers.removeFirst().toByteArray()
            }
        }
        val client = Client("https://ortus.example", transport = transport)
        val ticket = Passkey.password(client, " iryna@ministry.example ", "pw")
        kotlin.test.assertEquals("t1", ticket)
        val me = Passkey.code(client, ticket, "123 456")
        kotlin.test.assertEquals("s1", client.token)
        kotlin.test.assertEquals("iryna@ministry.example", me.email)
        kotlin.test.assertEquals("/session/password", sent[0].first)
        kotlin.test.assertTrue(sent[0].second.contains("\"email\":\"iryna@ministry.example\""), sent[0].second)
        kotlin.test.assertEquals("/session/code", sent[1].first)
        kotlin.test.assertTrue(sent[1].second.contains("\"code\":\"123456\""), sent[1].second)
    }
}
