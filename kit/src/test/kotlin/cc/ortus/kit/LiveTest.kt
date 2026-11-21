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

    /**
     * S2.4: an administrator's phone fixes the two failed mailbox imports:
     * the owners are asked, the right passwords go in, and the mail comes.
     */
    @Test fun anAdministratorFixesTheImports() = runBlocking {
        val server = env("ORTUS_SERVER") ?: return@runBlocking
        val code = env("ORTUS_ADMIN_CODE") ?: return@runBlocking
        val right = (env("ORTUS_IMPORT_PASSWORDS") ?: return@runBlocking).split(",").associate { it.substringBefore("=") to it.substringAfter("=") }
        val client = Client(server)
        Passkey.join(client, code)
        suspend fun accounts() = client.imports().flatMap { it.accounts }
        // Both fail on their own (the passwords Taras was given are wrong).
        var failed = emptyList<ImportAccount>()
        for (i in 0 until 60) {
            failed = accounts().filter { it.failed }
            if (failed.size == 2) break
            kotlinx.coroutines.delay(1000)
        }
        assertEquals(listOf("olha.old@ministry.example", "petro.old@ministry.example"), failed.map { it.login }.sorted())
        assertEquals(setOf("login_failed"), failed.map { it.error }.toSet())
        client.askOwners(failed)
        for (a in failed) client.retry(a, right.getValue(a.login))
        // Both copy, each with the mail from the old server.
        var done = emptyList<ImportAccount>()
        for (i in 0 until 90) {
            done = accounts()
            if (done.all { it.state == "done" }) break
            kotlinx.coroutines.delay(1000)
        }
        assertEquals(listOf("done", "done"), done.map { it.state }, done.toString())
        kotlin.test.assertTrue(done.all { it.copied >= 1 }, done.toString())
    }

    /** Signing in on the phone with a password and the authenticator's code. */
    @Test fun aPasswordAndACode() = runBlocking {
        val server = env("ORTUS_SERVER") ?: return@runBlocking
        val secret = env("ORTUS_OWNER_TOTP") ?: return@runBlocking
        val client = Client(server)
        kotlin.test.assertFailsWith<ApiError> { Passkey.password(client, "taras@ministry.example", "not it") }
        // A code is taken once (the start script used this window's to
        // confirm the app): the next window's code.
        val window = System.currentTimeMillis() / 30_000
        while (System.currentTimeMillis() / 30_000 == window) kotlinx.coroutines.delay(500)
        val ticket = Passkey.password(client, "taras@ministry.example", "flood-plan-2026-ministry")
        val me = Passkey.code(client, ticket, totp(secret))
        assertEquals("taras@ministry.example", me.email)
        assertEquals(me.id, client.call<Me>("GET", "/me").id)
    }

    private fun totp(secret: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0; var value = 0
        val key = java.io.ByteArrayOutputStream()
        for (c in secret.uppercase().trimEnd('=')) {
            value = (value shl 5) or alphabet.indexOf(c); bits += 5
            if (bits >= 8) { key.write((value shr (bits - 8)) and 0xff); bits -= 8 }
        }
        val mac = javax.crypto.Mac.getInstance("HmacSHA1").apply { init(javax.crypto.spec.SecretKeySpec(key.toByteArray(), "HmacSHA1")) }
            .doFinal(java.nio.ByteBuffer.allocate(8).putLong(System.currentTimeMillis() / 30_000).array())
        val o = mac.last().toInt() and 15
        val n = ((mac[o].toInt() and 0x7f) shl 24) or ((mac[o + 1].toInt() and 0xff) shl 16) or ((mac[o + 2].toInt() and 0xff) shl 8) or (mac[o + 3].toInt() and 0xff)
        return "%06d".format(n % 1_000_000)
    }
}
