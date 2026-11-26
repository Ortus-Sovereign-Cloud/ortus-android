package cc.ortus.app

import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable

import androidx.compose.ui.semantics.contentDescription

import cc.ortus.kit.quickly

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.ortus.kit.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Where a tab has gone: a thread, a room, a folder, a document, … */
sealed class Place {
    data class Thread(val id: String) : Place()
    data class Channel(val room: Room, val title: String) : Place()
    /** One thread of a room: the message it answers (`root`) and its replies. */
    data class ChatThread(val room: Room, val title: String, val root: String) : Place()
    data class Folder(val id: String?, val title: String) : Place()
    data class Share(val item: DriveItem) : Place()
    data class Doc(val id: String) : Place()
    data object Compose : Place()
    data class Call(val room: Room, val title: String) : Place()
    /** The calendar on a given day (an event found by search). */
    data class Day(val at: Long) : Place()
    data object ForYou : Place()
    data object Notifications : Place()
    data object Search : Place()
    data object Admin : Place()
}

fun initials(name: String): String = name.split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.ifEmpty { name.take(2) }.uppercase()
fun time(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

@Composable fun Avatar(name: String, size: Int = 40) =
    Surface(color = Accent.copy(alpha = .85f), shape = MaterialTheme.shapes.small, modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) { Text(initials(name), color = Background, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
    }

@Composable fun Title(text: String) = Text(text, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Text1, modifier = Modifier.padding(16.dp))

@Composable
fun Tabs(model: Model) {
    var tab by model::tab
    val stack = remember { mutableStateListOf<Place>() }
    LaunchedEffect(Unit) { model.refresh() }
    // The bell counts again when a push arrives (and once signed in).
    LaunchedEffect(Unit) { Pushes.arrived.collect { model.countUnread() } }
    // A call answered from its ringing: straight into it.
    LaunchedEffect(model.answered) {
        val (id, title) = model.answered ?: return@LaunchedEffect
        model.answered = null
        tab = 1
        stack.clear()
        stack.add(Place.Call(Room(id = id, name = title, kind = "channel"), title))
    }
    Scaffold(containerColor = Background, topBar = {
        val banner = model.offline || model.waiting > 0
        Column {
            if (banner) {
                Text(if (model.waiting > 0) stringResource(R.string.offline_waiting, model.waiting) else stringResource(R.string.offline_banner), color = Text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().background(Surface).statusBarsPadding().padding(vertical = 6.dp).testTag("offline").semantics { liveRegion = LiveRegionMode.Polite },
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            // The main screens: search and the bell at the top.
            if (stack.isEmpty()) TopActions(model.unread, search = { stack.add(Place.Search) }, notifications = { stack.add(Place.Notifications) },
                modifier = if (banner) Modifier else Modifier.statusBarsPadding())
        }
    }, bottomBar = {
        Sections(tab) { tab = it; stack.clear() }
    }) { pad ->
        Column(Modifier.padding(pad)) {
            val open: (Place) -> Unit = { stack.add(it) }
            val back: () -> Unit = { stack.removeLastOrNull() }
            androidx.activity.compose.BackHandler(enabled = stack.isNotEmpty()) { back() }
            // A way back on every screen opened from a list (a call has its own Leave).
            if (stack.isNotEmpty() && stack.last() !is Place.Call) {
                IconButton(back, Modifier.testTag("back")) { Icon(OrtusIcons.Back, contentDescription = stringResource(R.string.back), tint = Text1) }
            }
            when (val top = stack.lastOrNull()) {
                is Place.Thread -> ThreadScreen(model, top.id)
                is Place.Channel -> ChannelScreen(model, top.room, top.title, open)
                is Place.ChatThread -> ChatScreen(model, top.room, top.title, top.root, open)
                is Place.Folder -> FolderScreen(model, top.id, top.title, open)
                is Place.Share -> ShareScreen(model, top.item)
                is Place.Doc -> DocScreen(model, top.id)
                Place.Compose -> ComposeScreen(model, back)
                is Place.Call -> CallScreen(model, top.room, top.title, back)
                Place.ForYou -> ForYouScreen(model)
                Place.Notifications -> NotificationsScreen(model)
                Place.Search -> SearchScreen(model, open)
                is Place.Day -> CalendarScreen(model, top.at)
                Place.Admin -> AdminScreen(model)
                null -> when (tab) {
                    0 -> InboxScreen(model, open)
                    1 -> ChatsScreen(model, open)
                    2 -> CalendarScreen(model)
                    3 -> FolderScreen(model, null, stringResource(R.string.tab_drive), open)
                    else -> MoreScreen(model, open)
                }
            }
        }
    }
}

@Composable
fun InboxScreen(model: Model, open: (Place) -> Unit) {
    var emails by remember { mutableStateOf<List<Email>>(emptyList()) }
    var folder by remember { mutableStateOf("inbox") }
    LaunchedEffect(folder) { runCatching { quickly({ model.client!!.mailFolder(folder) }) { emails = it } } }
    val names = mapOf("inbox" to R.string.folder_inbox, "sent" to R.string.folder_sent, "archive" to R.string.folder_archive, "trash" to R.string.folder_trash)
    val sent = folder == "sent"
    LazyColumn {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Title(stringResource(names.getValue(folder))) }
                Button({ open(Place.Compose) }, Modifier.padding(end = 16.dp).testTag("compose")) { Text(stringResource(R.string.compose_title)) }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FOLDERS.forEach { f ->
                    FilterChip(selected = f == folder, onClick = { if (f != folder) { emails = emptyList(); folder = f } }, label = { Text(stringResource(names.getValue(f))) })
                }
            }
        }
        model.mailNote?.let { n -> item { Text(n, color = Text2, modifier = Modifier.padding(horizontal = 16.dp).testTag("note")) } }
        if (emails.isEmpty()) item { Text(stringResource(R.string.inbox_empty), color = Text2, modifier = Modifier.padding(16.dp)) }
        items(emails, key = { it.id }) { e ->
            // In Sent: whom it went to, and how its delivery goes.
            val who = if (sent) e.recipients else e.sender
            Row(Modifier.fillMaxWidth().clickable { open(Place.Thread(e.threadId)) }.padding(16.dp, 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(who)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(who, color = Text1, fontWeight = if (e.unread && !sent) FontWeight.Bold else FontWeight.Normal, maxLines = 1, modifier = Modifier.weight(1f))
                        if (sent) e.deliveryState?.let { state ->
                            Text(stringResource(if (state == "failed") R.string.delivery_failed else R.string.delivery_trying), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                color = if (state == "failed") Color(0xFFFF6B6B) else Color(0xFFFFA94D))
                        }
                    }
                    Text(e.subject, color = Text1, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun ThreadScreen(model: Model, id: String) {
    var emails by remember { mutableStateOf<List<Email>>(emptyList()) }
    var reply by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val sent = stringResource(R.string.thread_sent)
    suspend fun load() { runCatching { quickly({ model.client!!.thread(id) }) { emails = it } } }
    LaunchedEffect(id) { load() }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            emails.firstOrNull()?.let { item { Text(it.subject, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Text1) } }
            items(emails, key = { it.id }) { e ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { Avatar(e.sender, 36); Text(e.sender, fontWeight = FontWeight.SemiBold, color = Text1) }
                    Text(e.ortusText ?: e.preview ?: "", color = Text1)
                    // How a message one sent is getting to each recipient outside.
                    e.ortusDelivery?.takeIf { it.isNotEmpty() }?.forEach { d ->
                        val to = d.recipient ?: ""
                        val (label, color) = when (d.state) {
                            "sent" -> stringResource(R.string.delivery_done, to) to Color(0xFF4FD1C5)
                            "bounced" -> stringResource(R.string.delivery_notDone, to) to Color(0xFFFF6B6B)
                            "deferred" -> stringResource(R.string.delivery_retrying, to) to Color(0xFFFFA94D)
                            else -> stringResource(R.string.delivery_sendingTo, to) to Text2
                        }
                        Text(label, color = color, fontSize = 13.sp, modifier = Modifier.testTag("delivery"))
                    }
                    e.ortusInvite?.takeIf { it.method == "REQUEST" && !it.cancelled }?.let { inv ->
                        Surface(color = Surface, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(inv.summary, fontWeight = FontWeight.SemiBold, color = Text1)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(R.string.rsvp_yes to "accepted", R.string.rsvp_maybe to "tentative", R.string.rsvp_no to "declined").forEach { (l, v) ->
                                        OutlinedButton({ scope.launch { runCatching { model.client!!.rsvp(e, v) }; load() } }, Modifier.weight(1f)) { Text(stringResource(l)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // What the reply did, beside the field (the list's end may be off screen).
        note?.let { Text(it, color = Text2, modifier = Modifier.padding(horizontal = 16.dp).testTag("note")) }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(reply, { reply = it }, Modifier.weight(1f).testTag("reply"), placeholder = { Text(stringResource(R.string.thread_reply, emails.lastOrNull()?.sender ?: "")) })
            Button({ scope.launch {
                val last = emails.lastOrNull() ?: return@launch
                note = runCatching { model.client!!.reply(last, reply); reply = ""; sent }.getOrElse {
                    if (it is cc.ortus.kit.Queued) { reply = ""; model.queuedText() } else model.say(it)
                }
                load()
            } }, enabled = reply.isNotBlank(), modifier = Modifier.padding(start = 8.dp).testTag("send")) { Text(stringResource(R.string.thread_send)) }
        }
    }
}

@Composable
fun ChatsScreen(model: Model, open: (Place) -> Unit) {
    var data by remember { mutableStateOf<Rooms?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun load() { failure = model.loadList({ data?.rooms?.isNotEmpty() == true }) { quickly({ model.client!!.rooms() }) { data = it; it.me?.let { me -> model.chatMe = me } } } }
    LaunchedEffect(Unit) { load() }
    LazyColumn {
        item { Title(stringResource(R.string.tab_chat)) }
        item { ListStatus(failure, data?.rooms?.isEmpty() == true, R.string.chat_noRooms, PaddingValues(16.dp)) { scope.launch { load() } } }
        items(data?.rooms ?: emptyList(), key = { it.id }) { r ->
            val title = data!!.title(r)
            Row(Modifier.fillMaxWidth().clickable { open(Place.Channel(r, title)) }.padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(if (r.kind == "direct") title else "#")
                Text(title, color = Text1, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                if (r.federated == true) Text(stringResource(R.string.chat_external), color = androidx.compose.ui.graphics.Color(0xFFF5A35B), fontSize = 12.sp)
                r.unread?.takeIf { it > 0 }?.let { Badge { Text("$it") } }
            }
        }
    }
}

@Composable
fun ChannelScreen(model: Model, room: Room, title: String, open: (Place) -> Unit) = ChatScreen(model, room, title, null, open)

@Composable
fun CalendarScreen(model: Model, at: Long? = null) {
    // Today, or the day of the event a search found.
    var day by remember { mutableLongStateOf((at?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() } ?: java.time.LocalDate.now())
        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()) }
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    val scope = rememberCoroutineScope()
    suspend fun load() { runCatching { quickly({ model.client!!.events(day, day + 86_400_000) }) { events = it } } }
    LaunchedEffect(day) { load() }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(DateFormat.getDateInstance(DateFormat.FULL).format(Date(day)), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Text1) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ day -= 86_400_000 }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.calendar_previous)) }
                OutlinedButton({ day += 86_400_000 }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.calendar_next)) }
            }
        }
        if (events.isEmpty()) item { Text(stringResource(R.string.calendar_free), color = Text2) }
        items(events, key = { it.id + it.start }) { e ->
            Surface(color = Surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${time(e.start)}–${time(e.end)}", color = Text2, fontSize = 13.sp)
                    Text(e.summary, fontWeight = FontWeight.SemiBold, color = Text1)
                    if (e.waiting) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(R.string.rsvp_yes to "accepted", R.string.rsvp_maybe to "tentative", R.string.rsvp_no to "declined").forEach { (l, v) ->
                            OutlinedButton({ scope.launch { runCatching { model.client!!.answer(e, v) }; load() } }) { Text(stringResource(l)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FolderScreen(model: Model, parent: String?, title: String, open: (Place) -> Unit) {
    // Another folder starts afresh: never one folder's rows under another's title.
    var items by remember(parent) { mutableStateOf<List<DriveItem>>(emptyList()) }
    var failure by remember(parent) { mutableStateOf<String?>(null) }
    var loaded by remember(parent) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun load() {
        failure = model.loadList({ items.isNotEmpty() }) { items = model.client!!.folder(parent).items; loaded = true }
    }
    LaunchedEffect(parent) { load() }
    LazyColumn {
        item { Title(title) }
        item { ListStatus(failure, loaded && items.isEmpty(), R.string.drive_empty, PaddingValues(16.dp)) { scope.launch { load() } } }
        items(items, key = { it.id }) { i ->
            Row(Modifier.fillMaxWidth().clickable {
                open(when { i.kind == "folder" -> Place.Folder(i.id, i.name); i.mime == "application/vnd.ortus.doc" -> Place.Doc(i.id); else -> Place.Share(i) })
            }.padding(16.dp, 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (i.kind == "folder") "📁" else "📄")
                Text(i.name, color = Text1, modifier = Modifier.weight(1f), maxLines = 1)
                i.label?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFF5A35B), fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun ShareScreen(model: Model, item: DriveItem) {
    var shares by remember { mutableStateOf<Shares?>(null) }
    var email by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun load() { shares = runCatching { model.client!!.shares(item) }.getOrNull() }
    LaunchedEffect(item.id) { load() }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(item.name, fontWeight = FontWeight.Bold, color = Text1, fontSize = 18.sp)
        Text(stringResource(R.string.share_title), fontWeight = FontWeight.SemiBold, color = Text1, modifier = Modifier.semantics { heading() })
        ((shares?.people ?: emptyList()) + (shares?.outside ?: emptyList())).forEach { h -> Text("${h.name ?: h.email} · ${h.role ?: ""}", color = Text1) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(email, { email = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.share_address)) })
            Button({ scope.launch { note = runCatching { model.client!!.share(item, email); email = ""; null }.getOrElse { model.say(it) }; load() } }, enabled = email.contains("@"), modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.share_add)) }
        }
        Text(stringResource(R.string.share_link), fontWeight = FontWeight.SemiBold, color = Text1, modifier = Modifier.semantics { heading() })
        val url = shares?.link?.url
        if (url != null) Text(url, color = Accent, fontSize = 13.sp, maxLines = 1)
        else Button({ scope.launch { runCatching { model.client!!.makeLink(item) }; load() } }) { Text(stringResource(R.string.share_makeLink)) }
        note?.let { Text(it, color = Text2) }
    }
}

@Composable
fun DocScreen(model: Model, id: String) {
    var text by remember { mutableStateOf<DocText?>(null) }
    var comments by remember { mutableStateOf<List<DocComment>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id) {
        text = runCatching { model.client!!.docText(id) }.getOrNull()
        comments = runCatching { model.client!!.comments(id) }.getOrDefault(emptyList())
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(text?.name ?: "", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Text1) }
            item { Text(text?.now ?: "", color = Text1) }
            if (comments.isNotEmpty()) item { Text(stringResource(R.string.doc_comments), fontWeight = FontWeight.SemiBold, color = Text1, modifier = Modifier.semantics { heading() }) }
            items(comments.filter { it.resolved != true }, key = { it.id }) { c -> Surface(color = Surface, shape = MaterialTheme.shapes.medium) { Column(Modifier.padding(12.dp)) { Text(c.body, color = Text1); Text(c.author ?: "", color = Text2, fontSize = 12.sp) } } }
        }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.doc_comment)) })
            Button({ scope.launch { comments = runCatching { model.client!!.comment(id, draft) }.getOrDefault(comments); draft = "" } }, enabled = draft.isNotBlank(), modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.thread_send)) }
        }
    }
}

@Composable
fun MoreScreen(model: Model, open: (Place) -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.tab_more), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Text1)
        model.me?.let { Column(Modifier.padding(vertical = 8.dp)) { Text(it.name, fontWeight = FontWeight.SemiBold, color = Text1); Text(it.email, color = Text2, modifier = Modifier.testTag("me")) } }
        @Composable fun row(l: Int, p: Place) = Text(stringResource(l), color = Text1, fontSize = 17.sp, modifier = Modifier.fillMaxWidth().clickable { open(p) }.padding(vertical = 12.dp))
        row(R.string.foryou_title, Place.ForYou)
        row(R.string.notify_title, Place.Notifications)
        row(R.string.search_title, Place.Search)
        if (model.me?.role == "admin" || model.me?.role == "owner") row(R.string.admin_title, Place.Admin)
        TextButton({ scope.launch { model.signOut() } }) { Text(stringResource(R.string.more_signOut), color = androidx.compose.ui.graphics.Color(0xFFFF7A6E)) }
    }
}

@Composable
fun ForYouScreen(model: Model) {
    var reviews by remember { mutableStateOf<List<Review>>(emptyList()) }
    var tasks by remember { mutableStateOf<List<WorkTask>>(emptyList()) }
    var note by remember { mutableStateOf<String?>(null) }
    var reviewsFailure by remember { mutableStateOf<String?>(null) }
    var tasksFailure by remember { mutableStateOf<String?>(null) }
    var reviewsLoaded by remember { mutableStateOf(false) }
    var tasksLoaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val signed = stringResource(R.string.foryou_signed); val done = stringResource(R.string.foryou_done); val sentBack = stringResource(R.string.foryou_sentBackComment)
    suspend fun load() {
        // Each list says for itself whether it loaded.
        reviewsFailure = model.loadList({ reviews.isNotEmpty() }) { reviews = model.client!!.waitingReviews(); reviewsLoaded = true }
        tasksFailure = model.loadList({ tasks.isNotEmpty() }) { tasks = model.client!!.myTasks(); tasksLoaded = true }
    }
    LaunchedEffect(Unit) { load() }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.foryou_title), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Text1) }
        note?.let { item { Text(it, color = Text2, modifier = Modifier.testTag("note")) } }
        if (reviewsFailure != null || (reviewsLoaded && reviews.isEmpty())) item { ListStatus(reviewsFailure, reviewsLoaded && reviews.isEmpty(), R.string.foryou_nothing) { scope.launch { load() } } }
        items(reviews, key = { it.id }) { r ->
            Surface(color = Surface, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (r.signs) R.string.foryou_sign else R.string.foryou_approve), color = androidx.compose.ui.graphics.Color(0xFFF5A35B), fontSize = 12.sp)
                    Text(r.name ?: "", fontWeight = FontWeight.SemiBold, color = Text1)
                    r.current?.let { Text(stringResource(R.string.foryou_step, it.n, r.steps.size), color = Text2, fontSize = 14.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ scope.launch { note = runCatching { model.client!!.decide(r, false, sentBack); done }.getOrElse { model.say(it) }; load() } }, Modifier.weight(1f)) { Text(stringResource(R.string.foryou_sendBack)) }
                        Button({ scope.launch {
                            note = runCatching { if (r.signs) model.signInWithKey(); model.client!!.decide(r, true, ""); if (r.signs) signed else done }.getOrElse { model.say(it) }
                            load()
                        } }, Modifier.weight(1f).testTag("decide")) { Text(stringResource(if (r.signs) R.string.foryou_signWithKey else R.string.foryou_approveButton)) }
                    }
                }
            }
        }
        item { Text(stringResource(R.string.foryou_tasks, tasks.count { !it.done }), fontWeight = FontWeight.SemiBold, color = Text1, modifier = Modifier.semantics { heading() }) }
        if (tasksFailure != null || (tasksLoaded && tasks.isEmpty())) item { ListStatus(tasksFailure, tasksLoaded && tasks.isEmpty(), R.string.foryou_noTasks) { scope.launch { load() } } }
        items(tasks, key = { it.id }) { t ->
            Row(Modifier.fillMaxWidth().toggleable(t.done, role = Role.Checkbox) { scope.launch { runCatching { model.client!!.setDone(t, !t.done) }; load() } }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(t.done, null)
                Column { Text(t.title, color = Text1); t.projectName?.let { Text(it, color = Text2, fontSize = 12.sp) } }
            }
        }
    }
}

@Composable
fun NotificationsScreen(model: Model) {
    var items by remember { mutableStateOf<List<Notice>>(emptyList()) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun load() {
        failure = model.loadList({ items.isNotEmpty() }) { items = model.client!!.notices(); loaded = true }
    }
    LaunchedEffect(Unit) {
        load()
        // Read now: the bell's badge goes (counted again, as others may have come).
        if (failure == null) runCatching { model.client!!.markNoticesRead() }.onSuccess { model.unread = 0; model.countUnread() }
    }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.notify_title), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Text1) }
        if (failure != null || (loaded && items.isEmpty())) item { ListStatus(failure, loaded && items.isEmpty(), R.string.notify_empty) { scope.launch { load() } } }
        items(items, key = { it.id }) { n ->
            Column { Text(n.title, color = Text1, fontWeight = if (n.read) FontWeight.Normal else FontWeight.SemiBold); n.detail?.takeIf { it.isNotEmpty() }?.let { Text(it, color = Text2, fontSize = 14.sp) } }
        }
    }
}

@Composable
fun SearchScreen(model: Model, open: (Place) -> Unit) {
    var words by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var failure by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // A result opens its item here, or its web page when the app has no screen for it.
    fun go(h: SearchHit) = scope.launch {
        val c = model.client ?: return@launch
        place(h) { runCatching { c.rooms() }.getOrNull() }?.let(open) ?: openWeb(context, c.server + h.path)
    }
    suspend fun search() {
        searched = false
        failure = model.loadList({ false }) { hits = emptyList(); hits = model.client!!.search(words); searched = true }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.search_title), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Text1)
        OutlinedTextField(words, { words = it }, Modifier.fillMaxWidth().testTag("searchWords"), placeholder = { Text(stringResource(R.string.search_prompt)) }, singleLine = true,
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { scope.launch { search() } }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search))
        ListStatus(failure, searched && hits.isEmpty(), R.string.search_none) { scope.launch { search() } }
        SearchHits(hits) { go(it) }
    }
}

@Composable
fun AdminScreen(model: Model) {
    var imports by remember { mutableStateOf<List<MailImport>>(emptyList()) }
    var note by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    val passwords = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()
    val asked = stringResource(R.string.admin_asked); val retrying = stringResource(R.string.admin_retrying); val invited = stringResource(R.string.admin_invited)
    var failure by remember { mutableStateOf<String?>(null) }
    suspend fun load() { failure = model.loadList({ imports.isNotEmpty() }) { imports = model.client!!.imports() } }
    LaunchedEffect(Unit) { load() }
    val failed = imports.flatMap { it.accounts }.filter { it.failed }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.admin_title), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Text1) }
        if (failure != null) item { ListStatus(failure, false, R.string.admin_normal) { scope.launch { load() } } }
        else item { Text(stringResource(if (failed.isEmpty()) R.string.admin_normal else R.string.admin_needs), fontWeight = FontWeight.SemiBold, color = Text1, modifier = Modifier.semantics { heading() }) }
        // What the last action did, where it is seen (not under the list).
        note?.let { item { Text(it, color = Text2, modifier = Modifier.testTag("note")) } }
        if (failed.isNotEmpty()) {
            item { Text(stringResource(R.string.admin_failed, failed.size), color = Text1) }
            item { Button({ scope.launch { note = runCatching { model.client!!.askOwners(failed); asked }.getOrElse { model.say(it) }; load() } }, Modifier.testTag("askOwners")) { Text(stringResource(R.string.admin_askOwners)) } }
            items(failed, key = { it.id }) { a ->
                Column {
                    Text(a.login, color = Text1); Text(a.error ?: "", color = androidx.compose.ui.graphics.Color(0xFFF5A35B), fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(passwords[a.id] ?: "", { passwords[a.id] = it }, Modifier.weight(1f).testTag("password-${a.login}"), label = { Text(stringResource(R.string.admin_newPassword)) },
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                        Button({ scope.launch { note = runCatching { model.client!!.retry(a, passwords[a.id] ?: ""); retrying }.getOrElse { model.say(it) }; load() } }, Modifier.padding(start = 8.dp).testTag("retry-${a.login}")) { Text(stringResource(R.string.admin_retry)) }
                    }
                }
            }
        }
        item { Text(stringResource(R.string.admin_invite), fontWeight = FontWeight.SemiBold, color = Text1, modifier = Modifier.semantics { heading() }) }
        item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.admin_name)) }) }
        item { OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.share_address)) }) }
        item { Button({ scope.launch { note = runCatching { model.client!!.invite(name, email); name = ""; email = ""; invited }.getOrElse { model.say(it) } } }, enabled = name.isNotBlank() && email.contains("@")) { Text(stringResource(R.string.admin_inviteButton)) } }
    }
}


/** MobileCompose: a new message. To with suggestions as one types, From when one has more than one address, Subject and text. */
@Composable
fun ComposeScreen(model: Model, done: () -> Unit) {
    var identities by remember { mutableStateOf<List<Identity>?>(null) }
    var from by remember { mutableStateOf<Identity?>(null) }
    val to = remember { mutableStateListOf<String>() }
    var typed by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<Suggestion>>(emptyList()) }
    var subject by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sentText = stringResource(R.string.thread_sent)
    fun address(s: String) = s.trim().let { a -> a.indexOf('@') > 0 && a.substringAfter('@').contains('.') && ' ' !in a }
    fun add(s: String) { val a = s.trim().lowercase(); if (address(a) && a !in to) to.add(a); typed = ""; suggestions = emptyList() }
    LaunchedEffect(Unit) {
        val found = runCatching { model.client!!.identities() }.getOrDefault(emptyList())
        identities = found
        from = found.firstOrNull()
    }
    LaunchedEffect(typed) {
        val q = typed.trim()
        suggestions = if (q.length < 2) emptyList() else runCatching { model.client!!.suggest(q) }.getOrDefault(emptyList()).filter { it.email.lowercase() !in to }.take(5)
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            // Cancel and Send at the top: the keyboard never covers them.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.compose_title), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Text1, modifier = Modifier.weight(1f))
                OutlinedButton(done) { Text(stringResource(R.string.compose_cancel)) }
                Button({
                    if (address(typed)) add(typed)
                    val id = from ?: return@Button
                    sending = true
                    scope.launch {
                        runCatching { model.client!!.send(id, to.toList(), subject, text) }
                            .onSuccess { model.mailNote = sentText; done() }
                            .onFailure { if (it is cc.ortus.kit.Queued) { model.mailNote = model.queuedText(); done() } else error = model.say(it) }
                        sending = false
                    }
                }, enabled = !sending && from != null && (to.isNotEmpty() || address(typed)), modifier = Modifier.testTag("sendMessage")) { Text(stringResource(R.string.compose_send)) }
            }
        }
        if (identities?.isEmpty() == true) item { Text(stringResource(R.string.compose_noAddress), color = Color(0xFFFF6B6B), modifier = Modifier.testTag("noAddress")) }
        identities?.takeIf { it.size > 1 }?.let { list ->
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.compose_from), color = Text2)
                    list.forEach { i -> FilterChip(selected = from?.id == i.id, onClick = { from = i }, label = { Text(i.email) }) }
                }
            }
        }
        items(to.toList(), key = { it }) { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a, color = Text1, modifier = Modifier.weight(1f))
                val removeLabel = stringResource(R.string.compose_remove, a)
                TextButton({ to.remove(a) }, Modifier.semantics { contentDescription = removeLabel }) { Text("×") }
            }
        }
        item {
            OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth().testTag("to"), label = { Text(stringResource(R.string.compose_to)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { add(typed) }))
        }
        items(suggestions, key = { "s-" + it.email }) { p ->
            Column(Modifier.fillMaxWidth().clickable { add(p.email) }.padding(vertical = 6.dp)) {
                Text(p.shown, color = Text1)
                Text(p.email, color = Text2, fontSize = 12.sp)
            }
        }
        item { OutlinedTextField(subject, { subject = it }, Modifier.fillMaxWidth().testTag("subject"), label = { Text(stringResource(R.string.compose_subject)) }, singleLine = true) }
        item { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp).testTag("message"), label = { Text(stringResource(R.string.compose_message)) }) }
        error?.let { e -> item { Text(e, color = Color(0xFFFF6B6B), modifier = Modifier.testTag("error")) } }
    }
}

/** How often an open room or thread looks for new messages (while on screen). */
private const val CHAT_POLL_MS = 5_000L

/**
 * A room, or one thread of it (`root`: the message it answers): plain
 * rooms as the server gives them; end-to-end rooms opened and sealed on
 * the phone (EndToEnd), a message that cannot be opened said so (never its
 * ciphertext), and nothing sent in the clear. New messages are fetched
 * every few seconds while the screen is on and the app in front. Without
 * the end-to-end library (or its keys) a sealed room says to use a computer.
 */
@Composable
fun ChatScreen(model: Model, room: Room, title: String, root: String?, open: (Place) -> Unit) {
    val sealed = room.encrypted == true
    var data by remember { mutableStateOf<Messages?>(null) }
    var texts by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var unavailable by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    suspend fun load() {
        val c = model.client ?: return
        try {
            if (!sealed) {
                quickly({ c.messages(room.id, root) }) { data = it }
                return
            }
            val me = model.chatMe ?: c.rooms().me?.also { model.chatMe = it } ?: run { unavailable = true; return }
            val got = c.messages(room.id, root)
            texts = model.endToEnd()!!.read(me, room.id, got.messages)
            data = got
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // No library on this phone (a LinkageError), or its keys cannot be made.
            if (sealed && (e is LinkageError || e is org.ortus.e2e.E2eException.Store)) unavailable = true
            else if (data == null) note = model.say(e)
        }
    }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(room.id, root) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                load()
                kotlinx.coroutines.delay(CHAT_POLL_MS)
            }
        }
    }
    if (unavailable) {
        Column(Modifier.fillMaxSize().padding(24.dp).testTag("locked"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Text1, fontSize = 18.sp, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.chat_locked), color = Text2)
        }
        return
    }
    val cannot = stringResource(R.string.chat_cannotOpen)
    // A thread: its root first, then the replies; a room: the messages not in a thread.
    val shown = data?.messages?.filter { it.deleted != true && (if (root == null) it.thread == null else it.id == root || it.thread == root) } ?: emptyList()
    Column(Modifier.fillMaxSize().testTag(if (root != null) "chatThread" else if (sealed) "sealedRoom" else "chatRoom")) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (root != null) stringResource(R.string.chat_thread) else title, fontWeight = FontWeight.Bold, color = Text1, fontSize = 18.sp, modifier = Modifier.semantics { heading() })
                if (root != null) Text(title, color = Text2, fontSize = 12.sp)
                if (sealed) Text(stringResource(R.string.chat_sealed), color = Text2, fontSize = 12.sp)
            }
            // The room's call (the web call page, signed in); an end-to-end room has none.
            if (root == null && !sealed) OutlinedButton({ open(Place.Call(room, title)) }, Modifier.testTag("call")) { Text(stringResource(R.string.call_start)) }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(shown, key = { it.id }) { m ->
                val name = data?.people?.get(m.sender)?.name ?: m.sender
                val body = if (sealed) texts[m.id] else m.text
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(name, 32)
                    Column {
                        Text("$name · ${time(m.at)}", color = Text2, fontSize = 12.sp)
                        if (body != null) Text(body, color = Text1, modifier = Modifier.testTag("opened"))
                        else Text(cannot, color = Text2, fontSize = 14.sp, modifier = Modifier.testTag("cannotOpen"))
                        if (root == null) {
                            val n = m.replies ?: 0
                            TextButton({ open(Place.ChatThread(room, title, m.id)) }, Modifier.testTag("thread-${m.id}")) {
                                Text(if (n > 0) pluralStringResource(R.plurals.chat_replies, n, n) else stringResource(R.string.chat_replyInThread), fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
        note?.let { Text(it, color = Text2, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp).testTag("chatNote").semantics { liveRegion = LiveRegionMode.Polite }) }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f).testTag("message"), placeholder = { Text(if (root != null) stringResource(R.string.chat_replyInThread) else stringResource(R.string.chat_message, title)) })
            Button({ scope.launch {
                val said = text
                text = ""
                note = null
                try {
                    if (sealed) {
                        val me = model.chatMe ?: return@launch
                        model.endToEnd()!!.send(me, room.id, said, root)
                    } else {
                        model.client!!.say(said, room.id, root)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: cc.ortus.kit.Queued) {
                    // Queued offline: it goes by itself.
                    note = model.queuedText()
                } catch (e: Throwable) {
                    // Not sent (in a sealed room, never in the clear): the words come back.
                    text = said
                    note = model.say(e)
                }
                load()
            } }, enabled = text.isNotBlank(), modifier = Modifier.padding(start = 8.dp).testTag("sendChat")) { Text(stringResource(R.string.thread_send)) }
        }
    }
}
