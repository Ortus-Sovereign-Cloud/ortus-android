package cc.ortus.kit

import java.security.MessageDigest
import java.util.Base64

/** Base64url without padding, as WebAuthn and the Ortus API use it. */
fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

fun String.fromBase64Url(): ByteArray = Base64.getUrlDecoder().decode(this)

fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

/** The little CBOR (RFC 8949) WebAuthn needs. */
sealed class Cbor {
    data class UInt(val value: Long) : Cbor()
    data class NInt(val value: Long) : Cbor()
    class Bytes(val value: ByteArray) : Cbor()
    data class Text(val value: String) : Cbor()
    data class Map(val pairs: List<Pair<Cbor, Cbor>>) : Cbor()

    private fun head(major: Int, v: Long): ByteArray {
        val m = major shl 5
        return when {
            v < 24 -> byteArrayOf((m or v.toInt()).toByte())
            v < 256 -> byteArrayOf((m or 24).toByte(), v.toByte())
            v < 65536 -> byteArrayOf((m or 25).toByte(), (v shr 8).toByte(), v.toByte())
            else -> byteArrayOf((m or 26).toByte(), (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        }
    }

    fun encoded(): ByteArray = when (this) {
        is UInt -> head(0, value)
        is NInt -> head(1, -1 - value)
        is Bytes -> head(2, value.size.toLong()) + value
        is Text -> value.toByteArray().let { head(3, it.size.toLong()) + it }
        is Map -> pairs.fold(head(5, pairs.size.toLong())) { acc, (k, v) -> acc + k.encoded() + v.encoded() }
    }
}

/**
 * The phone as a passkey: the app holds a P-256 key (in the Android
 * Keystore) and speaks WebAuthn to the installation as a browser's
 * authenticator would, through /me/passkeys and /session/passkey.
 */
object WebAuthn {
    private fun json(s: String): String = kotlinx.serialization.json.JsonPrimitive(s).toString()

    fun clientData(type: String, challenge: String, origin: String): ByteArray =
        "{\"type\":${json(type)},\"challenge\":${json(challenge)},\"origin\":${json(origin)},\"crossOrigin\":false}".toByteArray()

    /** The relying party id of an installation: its host. */
    fun rpId(publicUrl: String): String = java.net.URI(publicUrl).host ?: publicUrl

    /** The installation's public address without a trailing slash. */
    fun origin(publicUrl: String): String = publicUrl.trimEnd('/')

    /** COSE form of a P-256 key from its X9.63 bytes (0x04 ‖ x ‖ y). */
    fun coseKey(x963: ByteArray): ByteArray {
        require(x963.size == 65 && x963[0] == 4.toByte()) { "an uncompressed P-256 point" }
        return Cbor.Map(listOf(
            Cbor.UInt(1) to Cbor.UInt(2),
            Cbor.UInt(3) to Cbor.NInt(-7),
            Cbor.NInt(-1) to Cbor.UInt(1),
            Cbor.NInt(-2) to Cbor.Bytes(x963.copyOfRange(1, 33)),
            Cbor.NInt(-3) to Cbor.Bytes(x963.copyOfRange(33, 65)),
        )).encoded()
    }

    fun registrationData(rpId: String, credentialId: ByteArray, publicKeyX963: ByteArray): ByteArray =
        sha256(rpId.toByteArray()) + byteArrayOf(0x45, 0, 0, 0, 0) + ByteArray(16) +
            byteArrayOf((credentialId.size shr 8).toByte(), credentialId.size.toByte()) + credentialId + coseKey(publicKeyX963)

    fun attestationObject(authData: ByteArray): ByteArray = Cbor.Map(listOf(
        Cbor.Text("fmt") to Cbor.Text("none"),
        Cbor.Text("attStmt") to Cbor.Map(emptyList()),
        Cbor.Text("authData") to Cbor.Bytes(authData),
    )).encoded()

    fun assertionData(rpId: String): ByteArray = sha256(rpId.toByteArray()) + byteArrayOf(0x05, 0, 0, 0, 0)

    fun signedBytes(authData: ByteArray, clientData: ByteArray): ByteArray = authData + sha256(clientData)

    /** A P-256 public key in X.509 (as Java gives it) to its X9.63 bytes. */
    fun x963(encoded: ByteArray): ByteArray = encoded.copyOfRange(encoded.size - 65, encoded.size)
}
