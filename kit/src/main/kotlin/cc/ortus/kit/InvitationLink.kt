package cc.ortus.kit

import java.net.URI
import java.net.URLDecoder

/** An invitation handed to the app: the installation and its code. */
data class InvitationLink(val server: String, val code: String) {
    companion object {
        private val CODE = Regex("^[A-Za-z0-9_-]{16,200}$")

        private fun pairs(text: String): Map<String, String> = text.split('&').mapNotNull {
            val (k, v) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }.toMap()

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
                    val query = pairs(uri.rawQuery ?: return null)
                    val server = query["server"]?.let { Client.serverUrl(it) } ?: return null
                    val code = query["code"]?.takeIf { CODE.matches(it) } ?: return null
                    InvitationLink(server, code)
                }
                "http", "https" -> {
                    if (uri.host == null) return null
                    val fragment = uri.rawFragment ?: return null
                    // The shared app link (https://join.ortus.cc/i#s=…&c=…).
                    if (uri.path == "/i") {
                        val items = pairs(fragment)
                        val server = items["s"]?.let { Client.serverUrl(it) } ?: return null
                        val code = items["c"]?.takeIf { CODE.matches(it) } ?: return null
                        return InvitationLink(server, code)
                    }
                    if (uri.path != "/welcome/accept" || !CODE.matches(fragment)) return null
                    InvitationLink(WebAuthn.origin(text.trim()), fragment)
                }
                else -> null
            }
        }
    }
}
