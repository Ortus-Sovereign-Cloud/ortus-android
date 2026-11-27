package cc.ortus.app

import org.json.JSONObject

/**
 * The owner (Taras, made by ci/start-ortus.sh) signed in afresh, with the
 * password and the authenticator's code: a phone sign-in code needs a
 * session of the last ten minutes, and the one CI made is older once the
 * emulator has started and the app is built (or a test before took long).
 */
object Owner {
    private fun post(server: String, path: String, body: JSONObject): JSONObject {
        val c = java.net.URL("$server/api/v1$path").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("X-Requested-With", "ortus")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val status = c.responseCode
        val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        check(status in 200..299) { "$path: $status $text" }
        return JSONObject(text)
    }

    /**
     * A new session; without the authenticator's secret (a run outside
     * CI), the session given. The code is of a new 30-second step: an
     * earlier test may have used the current one.
     */
    fun freshSession(server: String, totpSecret: String, given: String): String {
        if (totpSecret.isEmpty()) return given
        val ticket = post(server, "/session/password", JSONObject().put("email", "taras@ministry.example").put("password", "flood-plan-2026-ministry")).getString("ticket")
        val step = System.currentTimeMillis() / 30_000
        while (System.currentTimeMillis() / 30_000 == step) Thread.sleep(250)
        return post(server, "/session/code", JSONObject().put("ticket", ticket).put("code", totp(totpSecret))).getString("session_token")
    }

    /** The authenticator's code now (RFC 6238: SHA-1, six digits, 30 seconds). */
    fun totp(secret: String, at: Long = System.currentTimeMillis()): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0
        var value = 0
        val key = java.io.ByteArrayOutputStream()
        for (ch in secret.uppercase()) {
            if (ch == '=') continue
            value = ((value shl 5) or alphabet.indexOf(ch).coerceAtLeast(0)) and 0xffff
            bits += 5
            if (bits >= 8) { key.write((value shr (bits - 8)) and 0xff); bits -= 8 }
        }
        val mac = javax.crypto.Mac.getInstance("HmacSHA1").apply { init(javax.crypto.spec.SecretKeySpec(key.toByteArray(), "HmacSHA1")) }
            .doFinal(java.nio.ByteBuffer.allocate(8).putLong(at / 30_000).array())
        val o = mac[19].toInt() and 15
        val n = ((mac[o].toInt() and 0x7f) shl 24) or ((mac[o + 1].toInt() and 0xff) shl 16) or ((mac[o + 2].toInt() and 0xff) shl 8) or (mac[o + 3].toInt() and 0xff)
        return "%06d".format(n % 1_000_000)
    }
}
