package cc.ortus.kit

import kotlinx.coroutines.runBlocking
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Against a real Ortus (ci/start-ortus.sh gives ORTUS_SERVER, ORTUS_CODE and
 * ORTUS_ADMIN_CODE). Skipped without one, except with ORTUS_LIVE=1 (CI),
 * where a missing value fails.
 */
class LiveTest {
    private fun env(name: String): String? = System.getenv(name)
        ?: if (System.getenv("ORTUS_LIVE") == "1") kotlin.test.fail("$name is not set") else null

    private class SoftwareKey : Signer {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        override val credentialId = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        override val publicKeyX963 = WebAuthn.x963(pair.public.encoded)
        override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }
    }

    @Test fun thePhoneBecomesTheKey() = runBlocking {
        val server = env("ORTUS_SERVER") ?: return@runBlocking
        val code = env("ORTUS_CODE") ?: return@runBlocking
        val client = Client(server)
        val me = Passkey.join(client, code)
        val key = SoftwareKey()
        Passkey.register(client, key, "test phone")
        val again = Passkey.signIn(Client(server), key, me.id)
        assertEquals(me.email, again.email)
        // Back in after signing out: the key alone names the account.
        val back = Client(server)
        assertEquals(me.email, Passkey.signIn(back, key).email)
        back.raw("POST", "/session/sign-out", body())
        kotlin.test.assertFailsWith<ApiError> { back.call<Me>("GET", "/me") }
        // Her inbox has Anna's mail.
        assertEquals(true, client.inbox().any { it.subject == "Gauge report and updated shelter agreement" })
        // S1.9: she signs the plan with her phone's key (a fresh sign-in, as signing asks).
        val signer = Client(server, again.session_token)
        val review = signer.waitingReviews().single { it.name == "Flood response plan 2026" }
        assertEquals(true, review.signs)
        val before = signer.token
        Passkey.signIn(signer, key, me.id)
        // The signing session is a new one, made by the phone's key.
        kotlin.test.assertNotEquals(before, signer.token)
        val security = signer.call<kotlinx.serialization.json.JsonObject>("GET", "/me/security")
        val used = security["passkeys"].toString()
        kotlin.test.assertTrue(Regex("\"last_used_at\":\\d+").containsMatchIn(used), used)
        signer.decide(review, true)
        assertEquals(emptyList(), signer.waitingReviews().map { it.id })
    }

    /** S2.4: an administrator's phone fixes failed mailbox imports. */
    @Test fun anAdministratorAsksTheOwners() = runBlocking {
        val server = env("ORTUS_SERVER") ?: return@runBlocking
        val code = env("ORTUS_ADMIN_CODE") ?: return@runBlocking
        val client = Client(server)
        Passkey.join(client, code)
        // The import fails on its own (the old server does not answer).
        var failed = emptyList<ImportAccount>()
        for (i in 0 until 60) {
            failed = client.imports().flatMap { it.accounts }.filter { it.failed }
            if (failed.isNotEmpty()) break
            kotlinx.coroutines.delay(1000)
        }
        assertEquals(listOf("iryna.old"), failed.map { it.login })
        client.askOwners(failed)
        client.retry(failed.single(), "the right one")
    }
}
