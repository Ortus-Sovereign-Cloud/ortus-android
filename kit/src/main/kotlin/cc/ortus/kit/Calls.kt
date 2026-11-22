package cc.ortus.kit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * A call as the phone has it natively: the same media node and the same
 * signalling as the web client. Join; one connection with a data channel
 * named "signalling" and three send-only transceivers (audio, camera,
 * screen); the offer goes to /offer; the node's later offers (others'
 * tracks) come over the data channel in parts and are answered there; a
 * state report every 10 s keeps the session; leave.
 */
@Serializable data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)
@Serializable data class CallSession(val session: String, val user: String, val muted: Boolean? = null, val camera: Boolean? = null)
@Serializable data class CallPerson(val name: String)
@Serializable data class CallInfo(val id: String, val room: String, val sessions: List<CallSession> = emptyList(), val people: Map<String, CallPerson> = emptyMap()) {
    /** The name of whoever a session (a remote stream's id) belongs to. */
    fun nameOf(session: String): String? = sessions.firstOrNull { it.session == session }?.let { people[it.user]?.name }
}
@Serializable data class Joined(val call: CallInfo, val session: String, val ice_servers: List<IceServer> = emptyList())

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

suspend fun Client.joinCall(room: String): Joined = call("POST", "/calls/rooms/${enc(room)}/join", JsonObject(emptyMap()))

/** Sends the connection's offer; gives the node's answer (SDP). */
suspend fun Client.sendOffer(session: String, sdp: String): String {
    val r = raw("POST", "/calls/sessions/${enc(session)}/offer", JsonObject(mapOf("offer" to JsonObject(mapOf("type" to JsonPrimitive("offer"), "sdp" to JsonPrimitive(sdp))))))
    return r.jsonObject["answer"]?.jsonObject?.get("sdp")?.jsonPrimitive?.contentOrNull ?: throw ApiError(0, "no_answer", "The media server gave no answer.")
}

/** Reports muted and camera (and keeps the session); gives the call as it is now. */
suspend fun Client.callState(session: String, muted: Boolean, camera: Boolean): CallInfo {
    val r = raw("POST", "/calls/sessions/${enc(session)}/state", JsonObject(mapOf("muted" to JsonPrimitive(muted), "camera" to JsonPrimitive(camera))))
    return json.decodeFromJsonElement(CallInfo.serializer(), r.jsonObject["call"]!!)
}

suspend fun Client.leaveCall(session: String) {
    raw("POST", "/calls/sessions/${enc(session)}/leave", JsonObject(emptyMap()))
}

/** Session descriptions over the data channel, in parts of at most 16 KB. */
object CallParts {
    private const val SIZE = 16 * 1024

    fun split(text: String, id: String = java.util.UUID.randomUUID().toString().take(8)): List<String> {
        val of = maxOf(1, (text.length + SIZE - 1) / SIZE)
        return (0 until of).map { i ->
            JsonObject(mapOf("id" to JsonPrimitive(id), "part" to JsonPrimitive(i), "of" to JsonPrimitive(of), "data" to JsonPrimitive(text.substring(i * SIZE, minOf(text.length, (i + 1) * SIZE))))).toString()
        }
    }

    /** Puts parts back together; gives the whole message once it is complete. */
    class Joiner {
        private var id: String? = null
        private var got: Array<String?> = emptyArray()

        fun take(raw: String): String? {
            val o = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val part = o["part"]?.jsonPrimitive?.intOrNull ?: return null
            val of = o["of"]?.jsonPrimitive?.intOrNull ?: return null
            val data = o["data"]?.jsonPrimitive?.contentOrNull ?: return null
            if (of < 1 || of > 64 || part < 0 || part >= of) return null
            if (this.id != id || got.size != of) { this.id = id; got = arrayOfNulls(of) }
            got[part] = data
            if (got.any { it == null }) return null
            val whole = got.joinToString("") { it!! }
            this.id = null
            got = emptyArray()
            return whole
        }
    }

    /** A node's offer: its SDP and number, if it is one. */
    fun offer(message: String): Pair<String, Int?>? {
        val o = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return null
        if (o["type"]?.jsonPrimitive?.contentOrNull != "offer") return null
        val sdp = o["sdp"]?.jsonPrimitive?.contentOrNull ?: return null
        return sdp to o["n"]?.jsonPrimitive?.intOrNull
    }

    /** Our answer to the node's offer number `n`. */
    fun answer(sdp: String, n: Int?): String =
        JsonObject(buildMap {
            put("type", JsonPrimitive("answer"))
            put("sdp", JsonPrimitive(sdp))
            if (n != null) put("n", JsonPrimitive(n))
        }).toString()
}
