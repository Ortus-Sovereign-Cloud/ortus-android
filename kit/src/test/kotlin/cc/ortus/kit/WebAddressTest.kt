package cc.ortus.kit

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** A web page from the app: signed in with a one-time ticket, else the plain page. */
class WebAddressTest {
    @Test fun aTicketWhenTheServerGivesOne() = runBlocking {
        val sent = mutableListOf<String>()
        val client = Client("https://ortus.example", "t") { method, url, _, body ->
            sent += "$method $url ${body?.let { String(it) }}"
            200 to """{"url":"https://ortus.example/session/web#t=ab12&next=/people"}""".toByteArray()
        }
        assertEquals("https://ortus.example/session/web#t=ab12&next=/people", client.webAddress("/people"))
        assertEquals("""POST https://ortus.example/api/v1/me/web-ticket {"next":"/people"}""", sent.single())
    }

    @Test fun thePlainPageWithoutOne() = runBlocking {
        val client = Client("https://ortus.example", "t") { _, _, _, _ -> 404 to "{}".toByteArray() }
        assertEquals("https://ortus.example/people", client.webAddress("/people"))
    }
}
