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
@Serializable data class Email(
    val id: String, val threadId: String, val subject: String, val from: List<Address>? = null, val replyTo: List<Address>? = null,
    val receivedAt: String, val preview: String? = null, val keywords: Map<String, Boolean>? = null,
    val ortusInvite: Invite? = null, val ortusText: String? = null, val messageId: List<String>? = null, val references: List<String>? = null,
) {
    val unread get() = keywords?.get("\$seen") != true
    val sender get() = from?.firstOrNull()?.shown ?: ""
}

private val using = buildJsonArray { listOf("urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail", "urn:ietf:params:jmap:submission").forEach { add(it) } }
private val listProps = listOf("threadId", "subject", "from", "receivedAt", "preview", "keywords", "ortusInvite")
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

suspend fun Client.inbox(): List<Email> {
    val boxes = jmap(buildJsonArray { add(call("Mailbox/get", JsonObject(emptyMap()), "b")) })["b"]?.get("list")?.jsonArray ?: return emptyList()
    val inbox = boxes.firstOrNull { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "inbox" }?.jsonObject?.get("id")?.jsonPrimitive?.content ?: return emptyList()
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
suspend fun Client.rooms(): Rooms = call("GET", "/chat/rooms")
suspend fun Client.messages(room: String): Messages = call("GET", "/chat/rooms/$room/messages")
suspend fun Client.say(text: String, room: String) { raw("POST", "/chat/rooms/$room/messages", body("text" to text)) }
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
suspend fun Client.markNoticesRead() { raw("POST", "/notifications/read", body("all" to true)) }
suspend fun Client.search(words: String): List<SearchHit> = call<SearchResults>("GET", "/search?q=" + java.net.URLEncoder.encode(words, "UTF-8")).results
suspend fun Client.docText(doc: String): DocText = call("GET", "/docs/documents/$doc/changes")
suspend fun Client.comments(doc: String): List<DocComment> = call<DocComments>("GET", "/docs/documents/$doc/comments").comments
suspend fun Client.comment(doc: String, text: String): List<DocComment> = call<DocComments>("POST", "/docs/documents/$doc/comments", body("body" to text, "quote" to "")).comments
suspend fun Client.imports(): List<MailImport> = call<Imports>("GET", "/mail/admin/imports").imports
suspend fun Client.retry(a: ImportAccount, password: String) { raw("POST", "/mail/admin/imports/accounts/${a.id}/retry", body("password" to password)) }
suspend fun Client.askOwners(accounts: List<ImportAccount>) { raw("POST", "/mail/admin/imports/ask-owners", buildJsonObject { put("accounts", buildJsonArray { accounts.forEach { add(it.id) } }) }) }
suspend fun Client.invite(name: String, email: String) { raw("POST", "/admin/users", body("name" to name, "email" to email)) }
