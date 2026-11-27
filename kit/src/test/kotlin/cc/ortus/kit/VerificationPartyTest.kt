package cc.ortus.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Who a verification sheet says it is with. */
class VerificationPartyTest {
    private val people = mapOf("@taras:ministry.example" to Person("Taras"), "olena" to Person("Olena"))
    private val me = "@olena:ministry.example"

    @Test fun namesByEveryForm() {
        assertEquals("Taras", people.nameOf("@taras:ministry.example"))
        assertEquals("Olena", people.nameOf("@olena:ministry.example"))
        assertNull(people.nameOf("@nobody:ministry.example"))
    }

    @Test fun anotherPersonByNameIdAndDevice() {
        val taras = VerificationParty.of("@taras:ministry.example", "TARASPHONE", me, people)
        assertEquals(VerificationParty("Taras", "@taras:ministry.example", "TARASPHONE", false), taras)
        val nobody = VerificationParty.of("@nobody:ministry.example", null, me, people)
        assertNull(nobody.name)
        assertNull(nobody.device)
        assertFalse(nobody.isMe)
    }

    @Test fun onesOwnOtherDevice() {
        val mine = VerificationParty.of("@olena:ministry.example", "LAPTOP", "olena:ministry.example", people)
        assertTrue(mine.isMe)
        assertNull(mine.name)
        assertEquals("LAPTOP", mine.device)
        assertNull(VerificationParty.of(me, "", me, people).device)
    }
}
