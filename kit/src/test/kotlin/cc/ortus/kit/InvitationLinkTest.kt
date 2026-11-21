package cc.ortus.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InvitationLinkTest {
    private val code = "GrZQMSTSLFQ-bZjJLte7CE2wAbAZye7iS0osZU1a8os"

    @Test fun theAppsOwnLink() {
        assertEquals(InvitationLink("https://ortus.cc", code), InvitationLink.parse("ortus://join?server=https%3A%2F%2FOrtus.cc&code=$code"))
        assertEquals("http://10.0.2.2:7900", InvitationLink.parse("ortus://join?server=http%3A%2F%2F10.0.2.2%3A7900&code=$code")?.server)
    }

    @Test fun anInvitationLinkOrItsQrCode() {
        assertEquals(InvitationLink("https://ortus.cc", code), InvitationLink.parse("  https://ortus.cc/welcome/accept#$code\n"))
        assertEquals("https://work.ministry.example:8443", InvitationLink.parse("https://work.ministry.example:8443/welcome/accept#$code")?.server)
    }

    @Test fun theSharedAppLink() {
        assertEquals(InvitationLink("https://work.ministry.gov:8443", code), InvitationLink.parse("https://join.ortus.cc/i#s=https%3A%2F%2Fwork.ministry.gov%3A8443&c=$code"))
        assertNull(InvitationLink.parse("https://join.ortus.cc/i#s=ftp%3A%2F%2Fx&c=$code"))
        assertNull(InvitationLink.parse("https://join.ortus.cc/i#c=$code"))
    }

    @Test fun discoveryAsksTheOrganisation() {
        assertEquals(listOf("https://ministry.gov/.well-known/ortus", "https://ortus.ministry.gov/.well-known/ortus"), Discovery.candidates(" Olena@Ministry.gov "))
        assertEquals(emptyList(), Discovery.candidates("not an address"))
        assertEquals(emptyList(), Discovery.candidates("a@evil.example/x"))
        assertEquals("https://work.ministry.gov", Discovery.server("""{"server":"https://Work.Ministry.gov/"}""".toByteArray()))
        assertNull(Discovery.server("""{"server":"http://work.ministry.gov"}""".toByteArray()), "https only")
        assertNull(Discovery.server("<html>".toByteArray()))
        // The first place that answers with an installation wins.
        val asked = mutableListOf<String>()
        val found = kotlinx.coroutines.runBlocking {
            Discovery.find("olena@ministry.gov") { _, url, _, _ ->
                asked += url
                if (url.startsWith("https://ortus.")) 200 to """{"server":"https://work.ministry.gov"}""".toByteArray() else 404 to ByteArray(0)
            }
        }
        assertEquals("https://work.ministry.gov", found)
        assertEquals(2, asked.size)
    }

    @Test fun anythingElseIsNot() {
        for (text in listOf(
            "",
            "https://ortus.cc/welcome/accept",
            "https://ortus.cc/sign-in#$code",
            "https://ortus.cc/welcome/accept#short",
            "ortus://join?code=$code",
            "ortus://join?server=ftp%3A%2F%2Fx&code=$code",
            "ortus://other?server=https%3A%2F%2Fortus.cc&code=$code",
            "mailto:someone@ortus.cc",
        )) assertNull(InvitationLink.parse(text), text)
    }
}
