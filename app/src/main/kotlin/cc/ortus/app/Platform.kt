package cc.ortus.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cc.ortus.kit.Signer
import cc.ortus.kit.Transport
import cc.ortus.kit.WebAuthn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit

/** HTTP for the app (OkHttp: every method, PATCH included). */
object OkTransport : Transport {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Pair<Int, ByteArray> {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        val payload = body?.toRequestBody("application/json".toMediaType())
            ?: if (method == "GET") null else ByteArray(0).toRequestBody(null)
        b.method(method, payload)
        http.newCall(b.build()).execute().use { r -> return r.code to (r.body?.bytes() ?: ByteArray(0)) }
    }
}

/**
 * The phone's passkey: a P-256 key in the Android Keystore that never
 * leaves it. Where a fingerprint or face is enrolled, each use needs it
 * (within 30 s); the emulator has none.
 */
class PhoneKey private constructor(override val credentialId: ByteArray, private val key: PrivateKey, override val publicKeyX963: ByteArray) : Signer {
    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(data); sign() }

    companion object {
        private const val ALIAS = "ortus-phone-key"

        fun make(context: Context, guarded: Boolean): PhoneKey {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .apply { if (guarded) setUserAuthenticationRequired(true).setUserAuthenticationParameters(30, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL) }
                .build()
            val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
            val id = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            Store.put(context, "credential", id.joinToString("") { "%02x".format(it) })
            return PhoneKey(id, pair.private, WebAuthn.x963(pair.public.encoded))
        }

        fun load(context: Context): PhoneKey? {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val entry = ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry ?: return null
            val id = Store.get(context, "credential")?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray() ?: return null
            return PhoneKey(id, entry.privateKey, WebAuthn.x963(entry.certificate.publicKey.encoded))
        }
    }
}

/** A few values kept on the phone (the session, the server). */
object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("ortus", Context.MODE_PRIVATE)
    fun put(c: Context, k: String, v: String?) = prefs(c).edit().apply { if (v == null) remove(k) else putString(k, v) }.apply()
    fun get(c: Context, k: String): String? = prefs(c).getString(k, null)
    fun clear(c: Context) = prefs(c).edit().clear().apply()
}
