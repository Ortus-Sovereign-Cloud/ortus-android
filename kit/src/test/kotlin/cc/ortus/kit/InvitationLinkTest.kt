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
