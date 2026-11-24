package cc.ortus.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.ortus.kit.DriveItem
import cc.ortus.kit.HitTarget
import cc.ortus.kit.Rooms
import cc.ortus.kit.SearchHit
import cc.ortus.kit.target

/** The sections, named as the web names its apps (Drive, not Files). */
val SECTIONS = listOf(R.string.tab_mail, R.string.tab_chat, R.string.tab_calendar, R.string.tab_drive, R.string.tab_more)

@Composable
fun Sections(tab: Int, select: (Int) -> Unit) {
    NavigationBar(containerColor = Surface) {
        SECTIONS.forEachIndexed { i, l ->
            val icon = listOf(OrtusIcons.Mail, OrtusIcons.Chat, OrtusIcons.Calendar, OrtusIcons.Drive, OrtusIcons.More)[i]
            NavigationBarItem(selected = tab == i, onClick = { select(i) }, icon = { Icon(icon, contentDescription = null) }, label = { Text(stringResource(l)) })
        }
    }
}

/**
 * Search and the bell on every main screen, as the web's shell has them
 * (not only under More); the bell's badge is the unread count. Icons only:
 * a title here would repeat the section's own.
 */
@Composable
fun TopActions(unread: Int, search: () -> Unit, notifications: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        IconButton(search, Modifier.testTag("openSearch")) { Icon(OrtusIcons.Search, contentDescription = stringResource(R.string.search_title), tint = Text1) }
        val label = if (unread > 0) pluralStringResource(R.plurals.notify_unread, unread, unread) else stringResource(R.string.notify_title)
        IconButton(notifications, Modifier.testTag("openNotifications")) {
            BadgedBox(badge = {
                if (unread > 0) Badge { Text(if (unread > 99) "99+" else "$unread", fontSize = 11.sp, modifier = Modifier.testTag("unread")) }
            }) { Icon(OrtusIcons.Bell, contentDescription = label, tint = Text1) }
        }
    }
}

/** Search results, each one opening its item. */
@Composable
fun SearchHits(hits: List<SearchHit>, pick: (SearchHit) -> Unit) = Column {
    hits.forEach { h ->
        Column(Modifier.fillMaxWidth().clickable { pick(h) }.padding(vertical = 8.dp).testTag("hit")) {
            Text(h.title, color = Text1)
            h.snippet?.takeIf { it.isNotEmpty() }?.let { Text(it, color = Text2, fontSize = 13.sp, maxLines = 2) }
        }
    }
}

/**
 * The app's screen for a result, or null when only the web has one. A
 * room is looked up, never made from the result: an end-to-end room must
 * stay locked on the phone.
 */
suspend fun place(h: SearchHit, rooms: suspend () -> Rooms?): Place? = when (val t = h.target()) {
    is HitTarget.Thread -> Place.Thread(t.id)
    is HitTarget.Room -> rooms()?.let { all -> all.rooms.find { it.id == t.id }?.let { Place.Channel(it, all.title(it)) } }
    is HitTarget.Folder -> Place.Folder(t.id, h.title)
    is HitTarget.File -> Place.Share(DriveItem(t.id, h.title, "file", mime = h.meta?.mime))
    is HitTarget.Doc -> Place.Doc(t.id)
    is HitTarget.Day -> Place.Day(t.at)
    is HitTarget.Web -> null
}

/**
 * A page of the installation's web app in a Custom Tab: the protocol's
 * session extra on a view intent (no browser library; a browser without
 * Custom Tabs opens it as a page).
 */
fun openWeb(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).putExtras(Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
    runCatching { context.startActivity(intent) }
}
