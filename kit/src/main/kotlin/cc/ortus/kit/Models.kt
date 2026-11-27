package cc.ortus.kit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

@Serializable data class Me(val id: String, val email: String, val name: String, val role: String, val session_token: String? = null)
@Serializable data class CreationOptions(val challenge: String, val rp: Rp) { @Serializable data class Rp(val id: String? = null) }
@Serializable data class RequestOptions(val challenge: String, val rpId: String? = null)

@Serializable data class Room(val id: String, val name: String, val kind: String, val topic: String? = null, val unread: Int? = null, val federated: Boolean? = null, val with: List<String>? = null, val encrypted: Boolean? = null)
@Serializable data class Person(val name: String, val email: String? = null)
/** `me` is the person's Matrix id, `@{user}:{serverName}` (end-to-end rooms need it). */
@Serializable data class Rooms(val rooms: List<Room>, val people: Map<String, Person> = emptyMap(), val me: String? = null) {
    fun title(r: Room): String = when {
        r.kind == "direct" -> r.with?.firstOrNull()?.let { people[it]?.name ?: it } ?: r.name
        r.kind == "channel" -> "# ${r.name}"
        else -> r.name
    }
}
/** In an end-to-end room `text` is empty and `sealed` holds the message for the phone's keys. */
@Serializable data class ChatMessage(val id: String, val sender: String, val text: String, val at: Long, val thread: String? = null, val deleted: Boolean? = null, val sealed: kotlinx.serialization.json.JsonObject? = null, val replies: Int? = null) {
    /** The message as the end-to-end library opens it: id, sender, thread and sealed. */
    fun sealedJson(): String = buildJsonObject {
        put("id", id)
        put("sender", sender)
        thread?.let { put("thread", it) }
        sealed?.let { put("sealed", it) }
    }.toString()
}
@Serializable data class Messages(val messages: List<ChatMessage>, val people: Map<String, Person> = emptyMap())

/**
 * A person's name by their Matrix id (`@user:server`, as the end-to-end
 * library names them) or their bare id; null when unknown.
 */
fun Map<String, Person>.nameOf(user: String): String? {
    get(user)?.let { return it.name }
    val bare = user.removePrefix("@")
    get(bare)?.let { return it.name }
    return bare.substringBefore(':', "").takeIf { it.isNotEmpty() }?.let { get(it)?.name }
}

/**
 * Who a verification is with, as its sheet says it: another person (by
 * name when known, always by id) or the person themself, on which device
 * (null until the library knows it).
 */
data class VerificationParty(val name: String?, val user: String, val device: String?, val isMe: Boolean) {
    companion object {
        fun of(user: String, device: String?, me: String?, people: Map<String, Person>): VerificationParty {
            val isMe = me != null && me.removePrefix("@") == user.removePrefix("@")
            return VerificationParty(if (isMe) null else people.nameOf(user), user, device?.takeIf { it.isNotEmpty() }, isMe)
        }
    }
}

@Serializable data class Event(val id: String, val summary: String, val start: Long, val end: Long, val location: String? = null, val answer: String? = null, val mine: Boolean? = null) {
    val waiting get() = mine != true && answer == "NEEDS-ACTION"
}
@Serializable data class Events(val events: List<Event>)

@Serializable data class DriveItem(val id: String, val name: String, val kind: String, val size: Long? = null, val mime: String? = null, val modifiedAt: Long? = null, val label: String? = null)
@Serializable data class Folder(val items: List<DriveItem>)
@Serializable data class Shares(val people: List<Holder>? = null, val outside: List<Holder>? = null, val link: Link? = null) {
    @Serializable data class Holder(val share: String? = null, val email: String? = null, val name: String? = null, val role: String? = null)
    @Serializable data class Link(val url: String? = null, val expiresAt: Long? = null)
}

@Serializable data class ReviewStep(val n: Int, val title: String? = null, val kind: String? = null, val state: String)
@Serializable data class Review(val id: String, val doc: String, val name: String? = null, val state: String, val steps: List<ReviewStep>) {
    val current get() = steps.firstOrNull { it.state == "current" }
    val signs get() = current?.kind == "sign"
}
@Serializable data class Waiting(val waiting: List<Review>)
@Serializable data class WorkTask(val id: String, val title: String, val state: String? = null, val projectName: String? = null) { val done get() = state == "done" }
@Serializable data class Tasks(val tasks: List<WorkTask>)

@Serializable data class Notice(val id: String, val app: String, val kind: String, val title: String, val detail: String? = null, val at: Long, val read: Boolean)
/** The list and how many are unread (the web's bell shows the same count). */
@Serializable data class Notices(val items: List<Notice>, val unread: Int = 0)
/** Where a result opens (`path`, as the web follows it) and what the app shows of it. */
@Serializable data class HitMeta(val path: String? = null, val kind: String? = null, val mime: String? = null, val start: Long? = null)
@Serializable data class SearchHit(val app: String, val id: String, val title: String, val snippet: String? = null, val meta: HitMeta? = null)
@Serializable data class SearchResults(val results: List<SearchHit>)

@Serializable data class DocText(val name: String, val now: String, val label: String? = null)
@Serializable data class DocComment(val id: String, val author: String? = null, val body: String, val quote: String? = null, val resolved: Boolean? = null)
@Serializable data class DocComments(val comments: List<DocComment>)

@Serializable data class ImportAccount(val id: String, val login: String, val target: String, val state: String, val error: String? = null, val copied: Int = 0) { val failed get() = state == "failed" }
@Serializable data class MailImport(val id: String, val host: String, val accounts: List<ImportAccount>)
@Serializable data class Imports(val imports: List<MailImport>)

/** JSON bodies, built briefly. */
fun body(vararg pairs: Pair<String, Any?>) = buildJsonObject {
    for ((k, v) in pairs) when (v) {
        null -> {}
        is String -> put(k, v)
        is Number -> put(k, v)
        is Boolean -> put(k, v)
        is List<*> -> putJsonArray(k) { v.forEach { add(it.toString()) } }
        else -> put(k, v.toString())
    }
}
