package cc.ortus.kit

import java.net.URI
import java.net.URLDecoder

/** An invitation handed to the app: the installation and its code. */
data class InvitationLink(val server: String, val code: String) {
    companion object {
        private val CODE = Regex("^[A-Za-z0-9_-]{16,200}$")

        /**
         * Reads the app's own link (`ortus://join?server=…&code=…`, which an
         * invitation page offers on a phone) or an invitation link
         * (`https://ortus.example/welcome/accept#code`); null for anything else.
         */
        fun parse(text: String): InvitationLink? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            return when (uri.scheme?.lowercase()) {
                "ortus" -> {
                    if (uri.host?.lowercase() != "join") return null
                    val query = (uri.rawQuery ?: return null).split('&').mapNotNull {
                        val (k, v) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
                        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
                    }.toMap()
                    val server = query["server"]?.let { Client.serverUrl(it) } ?: return null
                    val code = query["code"]?.takeIf { CODE.matches(it) } ?: return null
                    InvitationLink(server, code)
                }
                "http", "https" -> {
                    if (uri.host == null || uri.path != "/welcome/accept") return null
                    val code = uri.rawFragment?.takeIf { CODE.matches(it) } ?: return null
                    InvitationLink(WebAuthn.origin(text.trim()), code)
                }
                else -> null
            }
        }
    }
}
