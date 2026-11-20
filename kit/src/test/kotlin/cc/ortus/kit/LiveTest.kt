package cc.ortus.kit

import kotlinx.coroutines.runBlocking
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals

/** Against a real Ortus (ci/start-ortus.sh gives ORTUS_SERVER and ORTUS_CODE). */
class LiveTest {
    private class SoftwareKey : Signer {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        override val credentialId = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        override val publicKeyX963 = WebAuthn.x963(pair.public.encoded)
        override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }
    }

    @Test fun thePhoneBecomesTheKey() = runBlocking {
        val server = System.getenv("ORTUS_SERVER") ?: return@runBlocking
        val code = System.getenv("ORTUS_CODE") ?: return@runBlocking
        val client = Client(server)
        val me = Passkey.join(client, code)
        val key = SoftwareKey()
        Passkey.register(client, key, "test phone")
        val again = Passkey.signIn(Client(server), key, me.id)
        assertEquals(me.email, again.email)
        // Her inbox has Anna's mail.
        assertEquals(true, client.inbox().any { it.subject == "Gauge report and updated shelter agreement" })
    }
}
