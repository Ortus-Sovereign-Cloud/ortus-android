package cc.ortus.kit

import java.net.URI
import java.net.URLDecoder

/**
 * A link handed to the app: the installation and its code, an invitation's
 * or a phone sign-in code's ("Sign in on your phone" on the web).
 */
data class InvitationLink(val server: String, val code: String, val kind: Kind = Kind.Invite) {
    enum class Kind { Invite, Phone }

    companion object {
        private val CODE = Regex("^[A-Za-z0-9_-]{16,200}$")

        private fun pairs(text: String): Map<String, String> = text.split('&').mapNotNull {
            val (k, v) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }.toMap()

        /**
         * Reads the app's own link (`ortus://join?server=…&code=…`, which an
         * invitation page offers on a phone; `ortus://signin?…` for a phone
         * sign-in code), the shared app link (`https://join.ortus.cc/i#s=…&c=…`;
         * `&p=…` for a phone sign-in code) or an invitation link
         * (`https://ortus.example/welcome/accept#code`); null for anything else.
         */
        fun parse(text: String): InvitationLink? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            return when (uri.scheme?.lowercase()) {
                "ortus" -> {
                    val host = uri.host?.lowercase()
                    if (host != "join" && host != "signin") return null
                    val query = pairs(uri.rawQuery ?: return null)
                    val server = query["server"]?.let { Client.serverUrl(it) } ?: return null
                    val code = query["code"]?.takeIf { CODE.matches(it) } ?: return null
                    InvitationLink(server, code, if (host == "signin") Kind.Phone else Kind.Invite)
                }
                "http", "https" -> {
                    if (uri.host == null) return null
                    val fragment = uri.rawFragment ?: return null
                    // The shared app link (https://join.ortus.cc/i#s=…&c=…).
                    if (uri.path == "/i") {
                        val items = pairs(fragment)
                        val phone = "p" in items
                        val server = items["s"]?.let { Client.serverUrl(it) } ?: return null
                        val code = items[if (phone) "p" else "c"]?.takeIf { CODE.matches(it) } ?: return null
                        return InvitationLink(server, code, if (phone) Kind.Phone else Kind.Invite)
                    }
                    if (uri.path != "/welcome/accept" || !CODE.matches(fragment)) return null
                    InvitationLink(WebAuthn.origin(text.trim()), fragment)
                }
                else -> null
            }
        }
    }
}
