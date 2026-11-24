package cc.ortus.kit

import java.net.URLDecoder

/** Where a search result opens in the app (the web follows the result's path). */
sealed class HitTarget {
    data class Thread(val id: String) : HitTarget()
    /** A chat room (the app looks it up: an end-to-end room stays locked). */
    data class Room(val id: String) : HitTarget()
    data class Folder(val id: String) : HitTarget()
    data class File(val id: String) : HitTarget()
    data class Doc(val id: String) : HitTarget()
    /** An event: the calendar on its day. */
    data class Day(val at: Long) : HitTarget()
    /** A kind the app has no screen for (people, tasks, records, …): the web page. */
    data class Web(val path: String) : HitTarget()
}

/** The result's page, as the web's search takes it: its path, else its app's. */
val SearchHit.path: String
    get() = meta?.path?.takeIf { it.startsWith("/") && !it.startsWith("//") && '\\' !in it } ?: "/$app"

/** The web's result → page mapping, read back into the app's screens. */
fun SearchHit.target(): HitTarget {
    val p = path
    val app = p.drop(1).substringBefore('?').substringBefore('#').substringBefore('/')
    val query = p.substringAfter('?', "").substringBefore('#').split('&').filter { it.isNotEmpty() }.associate {
        decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
    }
    fun q(name: String) = query[name]?.takeIf { it.isNotEmpty() }
    return when (app) {
        "mail" -> q("thread")?.let { HitTarget.Thread(it) }
        "chat" -> q("room")?.let { HitTarget.Room(it) }
        "drive" -> q("folder")?.let { HitTarget.Folder(it) } ?: q("file")?.let { HitTarget.File(it) }
        "docs" -> q("doc")?.let { HitTarget.Doc(it) }
        "calendar" -> meta?.start?.let { HitTarget.Day(it) }
        else -> null
    } ?: HitTarget.Web(p)
}

private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
