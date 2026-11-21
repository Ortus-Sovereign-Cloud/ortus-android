package cc.ortus.app

import android.app.KeyguardManager
import android.content.Context
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
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
 * leaves it, usable only after the phone's lock (face, fingerprint or
 * screen lock) within 30 s. Debug builds on a phone without a lock (the
 * CI emulator) make an unguarded key; release builds refuse instead.
 * A new key is made under a fresh alias and replaces the old one only
 * once the server has taken it.
 */
class PhoneKey private constructor(val alias: String, override val credentialId: ByteArray, private val key: PrivateKey, override val publicKeyX963: ByteArray) : Signer {
    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(data); sign() }

    /** The phone has no lock to guard a key with. */
    class NoLock : Exception("no screen lock")

    companion object {
        private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        fun locked(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

        /** A new key, not yet the phone's key (see [keep]). */
        fun make(context: Context): PhoneKey {
            val guarded = locked(context)
            if (!guarded && !BuildConfig.DEBUG) throw NoLock()
            val alias = "ortus-phone-key-" + java.util.UUID.randomUUID()
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .apply { if (guarded) setUserAuthenticationRequired(true).setUserAuthenticationParameters(30, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL) }
                .build()
            val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
            val id = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            return PhoneKey(alias, id, pair.private, WebAuthn.x963(pair.public.encoded))
        }

        /** [key] (registered) becomes the phone's key; the older one goes. */
        fun keep(context: Context, key: PhoneKey) {
            val old = Store.get(context, "keyAlias")
            Store.put(context, "keyAlias", key.alias)
            Store.put(context, "credential", key.credentialId.joinToString("") { "%02x".format(it) })
            if (old != null && old != key.alias) runCatching { keystore().deleteEntry(old) }
        }

        /** Throws a key made and never kept away. */
        fun drop(key: PhoneKey) { runCatching { keystore().deleteEntry(key.alias) } }

        fun load(context: Context): PhoneKey? {
            val alias = Store.get(context, "keyAlias") ?: return null
            val entry = keystore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry ?: return null
            val id = Store.get(context, "credential")?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray() ?: return null
            return PhoneKey(alias, id, entry.privateKey, WebAuthn.x963(entry.certificate.publicKey.encoded))
        }

        /** Removes the phone's key (signing out, or a key the phone invalidated). */
        fun forget(context: Context) {
            Store.get(context, "keyAlias")?.let { a -> runCatching { keystore().deleteEntry(a) } }
            Store.put(context, "keyAlias", null)
            Store.put(context, "credential", null)
        }
    }
}

/**
 * A few values kept on the phone (the session, the server, which key).
 * Each is sealed with an AES key in the Keystore, so a copy of the app's
 * files (which backups exclude anyway) holds no working session.
 */
object Store {
    private const val SEAL = "ortus-store"
    private fun prefs(c: Context) = c.getSharedPreferences("ortus", Context.MODE_PRIVATE)

    private fun seal(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(SEAL, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(SEAL, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }

    fun put(c: Context, k: String, v: String?) {
        val e = prefs(c).edit()
        if (v == null) e.remove(k) else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, seal()) }
            val sealed = cipher.iv + cipher.doFinal(v.toByteArray())
            e.putString(k, Base64.encodeToString(sealed, Base64.NO_WRAP))
        }
        e.apply()
    }

    fun get(c: Context, k: String): String? {
        val sealed = prefs(c).getString(k, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        if (sealed.size < 13) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, seal(), GCMParameterSpec(128, sealed, 0, 12)) }
            String(cipher.doFinal(sealed, 12, sealed.size - 12))
        }.getOrNull()
    }

    fun clear(c: Context) = prefs(c).edit().clear().apply()
}
