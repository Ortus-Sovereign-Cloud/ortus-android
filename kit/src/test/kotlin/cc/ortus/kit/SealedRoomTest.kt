package cc.ortus.kit

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** End-to-end rooms: nothing in the clear leaves the phone for them. */
class SealedRoomTest {
    private val sent = mutableListOf<String>()
    private val client = Client("https://ortus.example", "t") { method, url, _, _ ->
        sent += "$method $url"
        200 to when {
            url.endsWith("/chat/rooms") -> """{"rooms":[{"id":"r1","name":"plans","kind":"channel","encrypted":true},{"id":"r2","name":"general","kind":"channel"}],"me":"@u1:ortus.example"}"""
            url.endsWith("/chat/rooms/r1/messages") && method == "GET" -> """{"messages":[{"id":"${'$'}e1","sender":"@u2:ortus.example","text":"","at":1,"thread":"${'$'}e0","sealed":{"algorithm":"m.megolm.v1.aes-sha2","ciphertext":"AAA"}}]}"""
            else -> "{}"
        }.toByteArray()
    }

    @Test fun aSealedRoomNeverGetsAMessageInTheClear() = runBlocking {
        val rooms = client.rooms()
        assertEquals("@u1:ortus.example", rooms.me)
        assertFailsWith<SealedRoom> { client.say("the plans", "r1") }
        assertEquals(listOf("GET https://ortus.example/api/v1/chat/rooms"), sent, "nothing was sent")
        client.say("hello", "r2")
        assertEquals("POST https://ortus.example/api/v1/chat/rooms/r2/messages", sent.last())
    }

    @Test fun aSealedMessageGoesToTheLibraryWhole() = runBlocking {
        val m = client.messages("r1").messages.single()
        val j = json.parseToJsonElement(m.sealedJson()).jsonObject
        assertEquals("\$e1", j["id"]!!.jsonPrimitive.content)
        assertEquals("\$e0", j["thread"]!!.jsonPrimitive.content)
        assertEquals("AAA", j["sealed"]!!.jsonObject["ciphertext"]!!.jsonPrimitive.content)
    }
}
