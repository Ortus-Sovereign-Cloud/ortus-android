package cc.ortus.kit

/** A key that signs: the Android Keystore's on the phone, a software one in tests. */
interface Signer {
    val credentialId: ByteArray
    val publicKeyX963: ByteArray
    fun sign(data: ByteArray): ByteArray
}

/** Joining, the phone's passkey and signing in with it, as the app does. */
object Passkey {
    /** Follows the invitation code: signed in, the session kept. */
    suspend fun join(client: Client, code: String): Me {
        val token = code.trim().substringAfterLast('#')
        val me: Me = client.call("POST", "/invitation/accept", body("token" to token))
        client.token = me.session_token ?: throw ApiError(0, "no_session", "The code did not sign you in.")
        return me
    }

    /** Registers the key as a passkey of the signed-in person. */
    suspend fun register(client: Client, key: Signer, name: String) {
        val options: CreationOptions = client.call("POST", "/me/passkeys/options", body())
        val rp = WebAuthn.checkedRpId(options.rp.id, client.server)
        val clientData = WebAuthn.clientData("webauthn.create", options.challenge, WebAuthn.origin(client.server))
        val auth = WebAuthn.registrationData(rp, key.credentialId, key.publicKeyX963)
        client.raw("POST", "/me/passkeys", body(
            "name" to name,
            "clientDataJSON" to clientData.base64Url(),
            "attestationObject" to WebAuthn.attestationObject(auth).base64Url(),
        ))
    }

    /**
     * Signs in with the key (the server finds the account by the key when
     * no [userId] is given); the new session is kept, and the one it
     * replaces is ended.
     */
    suspend fun signIn(client: Client, key: Signer, userId: String? = null): Me {
        val options: RequestOptions = client.call("POST", "/session/passkey/options", body())
        val rp = WebAuthn.checkedRpId(options.rpId, client.server)
        val clientData = WebAuthn.clientData("webauthn.get", options.challenge, WebAuthn.origin(client.server))
        val auth = WebAuthn.assertionData(rp)
        val me: Me = client.call("POST", "/session/passkey", body(
            "id" to key.credentialId.base64Url(),
            "clientDataJSON" to clientData.base64Url(),
            "authenticatorData" to auth.base64Url(),
            "signature" to key.sign(WebAuthn.signedBytes(auth, clientData)).base64Url(),
            "userHandle" to userId?.toByteArray()?.base64Url(),
        ))
        val old = client.token
        me.session_token?.let { client.token = it }
        if (old != null && old != client.token) runCatching { Client(client.server, old, client.transport).raw("POST", "/session/sign-out", body()) }
        return me
    }
}
