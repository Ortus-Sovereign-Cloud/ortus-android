package cc.ortus.kit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.Serializable

/** Mail by JMAP, as the web client does. */
@Serializable data class Address(val name: String? = null, val email: String? = null) { val shown get() = name?.ifEmpty { null } ?: email ?: "" }
@Serializable data class Invite(val method: String, val summary: String, val start: Long? = null, val end: Long? = null, val answer: String? = null, val cancelled: Boolean = false)
/** How a message one sent is getting to one recipient outside: queued, deferred (trying again), sent or bounced. */
@Serializable data class Delivery(val recipient: String? = null, val state: String? = null, val attempts: Int? = null, val lastError: String? = null)
@Serializable data class Email(
    val id: String, val threadId: String, val subject: String, val from: List<Address>? = null, val to: List<Address>? = null, val replyTo: List<Address>? = null,
    val ortusDelivery: List<Delivery>? = null,
    val receivedAt: String, val preview: String? = null, val keywords: Map<String, Boolean>? = null,
    val ortusInvite: Invite? = null, val ortusText: String? = null, val messageId: List<String>? = null, val references: List<String>? = null,
) {
    val unread get() = keywords?.get("\$seen") != true
    val sender get() = from?.firstOrNull()?.shown ?: ""
    /** Whom it went to (for Sent). */
    val recipients get() = (to ?: emptyList()).joinToString(", ") { it.shown }
    /** The worst delivery state, for a mark in Sent: failed, trying, or null. */
    val deliveryState: String? get() = ortusDelivery?.let { d ->
        when {
            d.any { it.state == "bounced" } -> "failed"
            d.any { it.state == "deferred" } -> "trying"
            else -> null
        }
    }
}

private val using = buildJsonArray { listOf("urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail", "urn:ietf:params:jmap:submission").forEach { add(it) } }
private val listProps = listOf("threadId", "subject", "from", "to", "receivedAt", "preview", "keywords", "ortusInvite", "ortusDelivery")
private fun props(list: List<String>) = buildJsonArray { list.forEach { add(it) } }

suspend fun Client.jmap(calls: JsonArray): Map<String, JsonObject> {
    val answer = raw("POST", "/mail/web/jmap", buildJsonObject { put("using", using); put("methodCalls", calls) }).jsonObject
    val out = mutableMapOf<String, JsonObject>()
    for (r in answer["methodResponses"]?.jsonArray ?: JsonArray(emptyList())) {
        val (name, args, id) = Triple(r.jsonArray[0].jsonPrimitive.content, r.jsonArray[1].jsonObject, r.jsonArray[2].jsonPrimitive.content)
        if (name == "error") throw ApiError(0, args["type"]?.jsonPrimitive?.contentOrNull ?: "jmap", "The mail server refused.")
        out.putIfAbsent(id, args)
    }
    return out
}

private fun call(name: String, args: JsonObject, id: String) = buildJsonArray { add(name); add(args); add(id) }
private fun ref(of: String, name: String, path: String) = buildJsonObject { put("resultOf", of); put("name", name); put("path", path) }

/** The folders the app shows, by their role. */
val FOLDERS = listOf("inbox", "sent", "archive", "trash")

suspend fun Client.inbox(): List<Email> = mailFolder("inbox")

/** A folder by its role (inbox, sent, archive, trash), newest first, threads collapsed. */
suspend fun Client.mailFolder(role: String): List<Email> {
    val boxes = jmap(buildJsonArray { add(call("Mailbox/get", JsonObject(emptyMap()), "b")) })["b"]?.get("list")?.jsonArray ?: return emptyList()
    val inbox = boxes.firstOrNull { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == role }?.jsonObject?.get("id")?.jsonPrimitive?.content ?: return emptyList()
    val got = jmap(buildJsonArray {
        add(call("Email/query", buildJsonObject {
            put("filter", buildJsonObject { put("inMailbox", inbox) })
            put("sort", buildJsonArray { add(buildJsonObject { put("property", "receivedAt"); put("isAscending", false) }) })
            put("collapseThreads", true); put("limit", 100)
        }, "q"))
        add(call("Email/get", buildJsonObject { put("#ids", ref("q", "Email/query", "/ids")); put("properties", props(listProps)) }, "l"))
    })
    return json.decodeFromJsonElement(kotlinx.serialization.serializer<List<Email>>(), got["l"]?.get("list") ?: JsonArray(emptyList()))
}

suspend fun Client.thread(id: String): List<Email> {
    val got = jmap(buildJsonArray {
        add(call("Thread/get", buildJsonObject { put("ids", buildJsonArray { add(id) }) }, "t"))
        add(call("Email/get", buildJsonObject { put("#ids", ref("t", "Thread/get", "/list/*/emailIds")); put("properties", props(listProps + listOf("replyTo", "messageId", "references", "ortusText"))) }, "e"))
    })
    return json.decodeFromJsonElement(kotlinx.serialization.serializer<List<Email>>(), got["e"]?.get("list") ?: JsonArray(emptyList()))
}

/** An address one may send from. */
@Serializable data class Identity(val id: String, val name: String? = null, val email: String)
/** Someone to write to, as Compose suggests them. */
@Serializable data class Suggestion(val name: String? = null, val email: String) { val shown get() = name?.ifEmpty { null } ?: email }
@Serializable private data class Suggestions(val people: List<Suggestion> = emptyList())

/** The addresses one may send from (none: no mail address here). */
suspend fun Client.identities(): List<Identity> {
    val got = jmap(buildJsonArray { add(call("Identity/get", JsonObject(emptyMap()), "i")) })
    return json.decodeFromJsonElement(kotlinx.serialization.serializer<List<Identity>>(), got["i"]?.get("list") ?: JsonArray(emptyList()))
}

/** People to write to whose name or address starts with `query`. */
suspend fun Client.suggest(query: String): List<Suggestion> =
    json.decodeFromJsonElement(Suggestions.serializer(), raw("GET", "/mail/people?q=${java.net.URLEncoder.encode(query, "UTF-8")}")).people

/** Sends a new message from `identity` to `to`. */
suspend fun Client.send(identity: Identity, to: List<String>, subject: String, text: String) {
    val boxes = jmap(buildJsonArray { add(call("Mailbox/get", JsonObject(emptyMap()), "b")) })
    val drafts = boxes["b"]?.get("list")?.jsonArray?.firstOrNull { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "drafts" }?.jsonObject?.get("id")?.jsonPrimitive?.content
        ?: throw ApiError(0, "notSent", "Not sent.")
    val draft = buildJsonObject {
        put("mailboxIds", buildJsonObject { put(drafts, true) })
        put("keywords", buildJsonObject { put("\$draft", true); put("\$seen", true) })
        put("from", buildJsonArray { add(buildJsonObject { put("name", identity.name ?: ""); put("email", identity.email) }) })
        put("to", buildJsonArray { to.forEach { a -> add(buildJsonObject { put("email", a) }) } })
        put("subject", subject)
        put("textBody", buildJsonArray { add(buildJsonObject { put("partId", "1"); put("type", "text/plain") }) })
        put("bodyValues", buildJsonObject { put("1", buildJsonObject { put("value", text) }) })
    }
    val sent = jmap(buildJsonArray {
        add(call("Email/set", buildJsonObject { put("create", buildJsonObject { put("m", draft) }) }, "c"))
        add(call("EmailSubmission/set", buildJsonObject {
            put("create", buildJsonObject { put("s", buildJsonObject { put("identityId", identity.id); put("emailId", "#m") }) })
            put("onSuccessDestroyEmail", buildJsonArray { add("#s") })
        }, "s"))
    })
    (sent["c"]?.get("notCreated") as? JsonObject)?.get("m")?.let { throw ApiError(0, "notSent", "Not sent.") }
    (sent["s"]?.get("notCreated") as? JsonObject)?.get("s")?.let { throw ApiError(0, "notSent", "Not sent.") }
}

suspend fun Client.reply(last: Email, text: String) {
    val got = jmap(buildJsonArray { add(call("Identity/get", JsonObject(emptyMap()), "i")); add(call("Mailbox/get", JsonObject(emptyMap()), "b")) })
    val identity = got["i"]?.get("list")?.jsonArray?.firstOrNull()?.jsonObject ?: throw ApiError(0, "forbiddenFrom", "You cannot send from here.")
    val drafts = got["b"]?.get("list")?.jsonArray?.firstOrNull { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "drafts" }?.jsonObject?.get("id")?.jsonPrimitive?.content
        ?: throw ApiError(0, "forbiddenFrom", "You cannot send from here.")
    val to = (last.replyTo?.takeIf { it.isNotEmpty() } ?: last.from ?: emptyList())
    val subject = if (last.subject.lowercase().startsWith("re:")) last.subject else "Re: ${last.subject}"
    val draft = buildJsonObject {
        put("mailboxIds", buildJsonObject { put(drafts, true) })
        put("keywords", buildJsonObject { put("\$draft", true); put("\$seen", true) })
        put("from", buildJsonArray { add(buildJsonObject { put("name", identity["name"] ?: JsonPrimitive("")); put("email", identity["email"] ?: JsonPrimitive("")) }) })
        put("to", buildJsonArray { to.forEach { a -> add(buildJsonObject { put("name", a.name ?: ""); put("email", a.email ?: "") }) } })
        put("subject", subject)
        put("references", buildJsonArray { ((last.references ?: emptyList()) + (last.messageId ?: emptyList())).forEach { add(it) } })
        last.messageId?.let { m -> put("inReplyTo", buildJsonArray { m.forEach { add(it) } }) }
        put("textBody", buildJsonArray { add(buildJsonObject { put("partId", "1"); put("type", "text/plain") }) })
        put("bodyValues", buildJsonObject { put("1", buildJsonObject { put("value", text) }) })
    }
    val sent = jmap(buildJsonArray {
        add(call("Email/set", buildJsonObject { put("create", buildJsonObject { put("r", draft) }) }, "c"))
        add(call("EmailSubmission/set", buildJsonObject {
            put("create", buildJsonObject { put("s", buildJsonObject { put("identityId", identity["id"]!!); put("emailId", "#r") }) })
            put("onSuccessDestroyEmail", buildJsonArray { add("#s") })
        }, "s"))
    })
    (sent["s"]?.get("notCreated") as? JsonObject)?.get("s")?.let { throw ApiError(0, "notSent", "Not sent.") }
}

suspend fun Client.rsvp(email: Email, answer: String) { raw("POST", "/mail/messages/${email.id}/rsvp", body("answer" to answer)) }
/**
 * A page of the installation's web app, signed in: an address with a
 * one-time ticket (it works once, within a minute, after the person's yes
 * on the page); the plain page when no ticket can be had.
 */
suspend fun Client.webAddress(path: String): String = runCatching {
    raw("POST", "/me/web-ticket", buildJsonObject { put("next", path) }).jsonObject["url"]!!.jsonPrimitive.content
}.getOrElse { server + path }

/** Refused before anything leaves the phone: an end-to-end room's messages are sealed on it. */
class SealedRoom(val room: String) : Exception("This room is end-to-end encrypted.")

/** The rooms; the end-to-end ones are remembered, and never get a message in the clear. */
suspend fun Client.rooms(): Rooms = call<Rooms>("GET", "/chat/rooms").also { r -> r.rooms.filter { it.encrypted == true }.forEach { sealedRooms.add(it.id) } }
/** A room's latest messages, or one thread's (its root and replies). */
suspend fun Client.messages(room: String, thread: String? = null): Messages =
    call("GET", "/chat/rooms/$room/messages" + (thread?.let { "?thread=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""))
/** Sends a message; its client id makes a resend from the outbox one message. */
suspend fun Client.say(text: String, room: String, thread: String? = null) {
    if (room in sealedRooms) throw SealedRoom(room)
    val b = buildJsonObject {
        put("text", text)
        put("client_id", java.util.UUID.randomUUID().toString())
        thread?.let { put("thread", it) }
    }
    raw("POST", "/chat/rooms/$room/messages", b)
}
suspend fun Client.events(from: Long, to: Long): List<Event> = call<Events>("GET", "/calendar/events?from=$from&to=$to").events.sortedBy { it.start }
suspend fun Client.answer(event: Event, answer: String) { raw("POST", "/calendar/events/${event.id}/rsvp", body("answer" to answer)) }
suspend fun Client.folder(parent: String?): Folder = call("GET", "/drive/items" + (parent?.let { "?parent=$it" } ?: ""))
suspend fun Client.shares(item: DriveItem): Shares = call("GET", "/drive/items/${item.id}/shares")
suspend fun Client.share(item: DriveItem, email: String) { raw("POST", "/drive/items/${item.id}/shares", body("email" to email, "role" to "view")) }
suspend fun Client.makeLink(item: DriveItem) { raw("POST", "/drive/items/${item.id}/link", body("expiresAt" to System.currentTimeMillis() + 30L * 86_400_000)) }
suspend fun Client.waitingReviews(): List<Review> = call<Waiting>("GET", "/docs/reviews/mine").waiting
suspend fun Client.decide(review: Review, approve: Boolean, comment: String = "") { raw("POST", "/docs/reviews/${review.id}/decide", body("approve" to approve, "comment" to comment)) }
suspend fun Client.myTasks(): List<WorkTask> = call<Tasks>("GET", "/tasks/mine").tasks
suspend fun Client.setDone(t: WorkTask, done: Boolean) { raw("PATCH", "/tasks/tasks/${t.id}", body("state" to if (done) "done" else "todo")) }
suspend fun Client.notices(): List<Notice> = call<Notices>("GET", "/notifications").items
suspend fun Client.unreadNotices(): Int = call<Notices>("GET", "/notifications").unread
suspend fun Client.markNoticesRead() { raw("POST", "/notifications/read", body("all" to true)) }
suspend fun Client.search(words: String): List<SearchHit> = call<SearchResults>("GET", "/search?q=" + java.net.URLEncoder.encode(words, "UTF-8")).results
suspend fun Client.docText(doc: String): DocText = call("GET", "/docs/documents/$doc/changes")
suspend fun Client.comments(doc: String): List<DocComment> = call<DocComments>("GET", "/docs/documents/$doc/comments").comments
suspend fun Client.comment(doc: String, text: String): List<DocComment> = call<DocComments>("POST", "/docs/documents/$doc/comments", body("body" to text, "quote" to "")).comments
suspend fun Client.imports(): List<MailImport> = call<Imports>("GET", "/mail/admin/imports").imports
suspend fun Client.retry(a: ImportAccount, password: String) { raw("POST", "/mail/admin/imports/accounts/${a.id}/retry", body("password" to password)) }
suspend fun Client.askOwners(accounts: List<ImportAccount>) { raw("POST", "/mail/admin/imports/ask-owners", buildJsonObject { put("accounts", buildJsonArray { accounts.forEach { add(it.id) } }) }) }
suspend fun Client.invite(name: String, email: String) { raw("POST", "/admin/users", body("name" to name, "email" to email)) }
