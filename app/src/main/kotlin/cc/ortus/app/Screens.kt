package cc.ortus.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
    data class Folder(val id: String?, val title: String) : Place()
    data class Share(val item: DriveItem) : Place()
    data class Doc(val id: String) : Place()
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
    var tab by remember { mutableIntStateOf(0) }
    val stack = remember { mutableStateListOf<Place>() }
    LaunchedEffect(Unit) { model.refresh() }
    val labels = listOf(R.string.tab_mail, R.string.tab_chat, R.string.tab_calendar, R.string.tab_files, R.string.tab_more)
    Scaffold(containerColor = Background, bottomBar = {
        NavigationBar(containerColor = Surface) {
            labels.forEachIndexed { i, l ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i; stack.clear() }, icon = { Text("•") }, label = { Text(stringResource(l)) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad)) {
            val open: (Place) -> Unit = { stack.add(it) }
            val back: () -> Unit = { stack.removeLastOrNull() }
            androidx.activity.compose.BackHandler(enabled = stack.isNotEmpty()) { back() }
            when (val top = stack.lastOrNull()) {
                is Place.Thread -> ThreadScreen(model, top.id)
                is Place.Channel -> ChannelScreen(model, top.room, top.title)
                is Place.Folder -> FolderScreen(model, top.id, top.title, open)
                is Place.Share -> ShareScreen(model, top.item)
                is Place.Doc -> DocScreen(model, top.id)
                Place.ForYou -> ForYouScreen(model)
                Place.Notifications -> NotificationsScreen(model)
                Place.Search -> SearchScreen(model)
                Place.Admin -> AdminScreen(model)
                null -> when (tab) {
                    0 -> InboxScreen(model, open)
                    1 -> ChatsScreen(model, open)
                    2 -> CalendarScreen(model)
                    3 -> FolderScreen(model, null, stringResource(R.string.tab_files), open)
                    else -> MoreScreen(model, open)
                }
            }
        }
    }
}

@Composable
fun InboxScreen(model: Model, open: (Place) -> Unit) {
    var emails by remember { mutableStateOf<List<Email>>(emptyList()) }
    LaunchedEffect(Unit) { emails = runCatching { model.client!!.inbox() }.getOrDefault(emptyList()) }
    LazyColumn {
        item { Title(stringResource(R.string.inbox_title)) }
        if (emails.isEmpty()) item { Text(stringResource(R.string.inbox_empty), color = Text2, modifier = Modifier.padding(16.dp)) }
        items(emails, key = { it.id }) { e ->
            Row(Modifier.fillMaxWidth().clickable { open(Place.Thread(e.threadId)) }.padding(16.dp, 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(e.sender)
                Column(Modifier.weight(1f)) {
                    Text(e.sender, color = Text1, fontWeight = if (e.unread) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
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
    suspend fun load() { emails = runCatching { model.client!!.thread(id) }.getOrDefault(emptyList()) }
    LaunchedEffect(id) { load() }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            emails.firstOrNull()?.let { item { Text(it.subject, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Text1) } }
            items(emails, key = { it.id }) { e ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { Avatar(e.sender, 36); Text(e.sender, fontWeight = FontWeight.SemiBold, color = Text1) }
                    Text(e.ortusText ?: e.preview ?: "", color = Text1)
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
            note?.let { item { Text(it, color = Text2, modifier = Modifier.testTag("note")) } }
        }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(reply, { reply = it }, Modifier.weight(1f).testTag("reply"), placeholder = { Text(stringResource(R.string.thread_reply, emails.lastOrNull()?.sender ?: "")) })
            Button({ scope.launch {
                val last = emails.lastOrNull() ?: return@launch
                note = runCatching { model.client!!.reply(last, reply); reply = ""; sent }.getOrElse { model.say(it) }
                load()
            } }, enabled = reply.isNotBlank(), modifier = Modifier.padding(start = 8.dp).testTag("send")) { Text(stringResource(R.string.thread_send)) }
        }
    }
}

@Composable
fun ChatsScreen(model: Model, open: (Place) -> Unit) {
    var data by remember { mutableStateOf<Rooms?>(null) }
    LaunchedEffect(Unit) { data = runCatching { model.client!!.rooms() }.getOrNull() }
    LazyColumn {
        item { Title(stringResource(R.string.tab_chat)) }
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
fun ChannelScreen(model: Model, room: Room, title: String) {
    var data by remember { mutableStateOf<Messages?>(null) }
    var text by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    suspend fun load() { data = runCatching { model.client!!.messages(room.id) }.getOrNull() }
    LaunchedEffect(room.id) { load() }
    Column(Modifier.fillMaxSize()) {
        Text(title, fontWeight = FontWeight.Bold, color = Text1, fontSize = 18.sp, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(data?.messages?.filter { it.deleted != true && it.thread == null } ?: emptyList(), key = { it.id }) { m ->
                val name = data?.people?.get(m.sender)?.name ?: m.sender
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(name, 32)
                    Column { Text("$name · ${time(m.at)}", color = Text2, fontSize = 12.sp); Text(m.text, color = Text1) }
                }
            }
        }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f).testTag("message"), placeholder = { Text(stringResource(R.string.chat_message, title)) })
            Button({ scope.launch { val said = text; text = ""; runCatching { model.client!!.say(said, room.id) }; load() } }, enabled = text.isNotBlank(), modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.thread_send)) }
        }
    }
}

@Composable
fun CalendarScreen(model: Model) {
    var day by remember { mutableLongStateOf(java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()) }
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    val scope = rememberCoroutineScope()
    suspend fun load() { events = runCatching { model.client!!.events(day, day + 86_400_000) }.getOrDefault(emptyList()) }
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
    var items by remember { mutableStateOf<List<DriveItem>>(emptyList()) }
    LaunchedEffect(parent) { items = runCatching { model.client!!.folder(parent).items }.getOrDefault(emptyList()) }
    LazyColumn {
        item { Title(title) }
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
        Text(stringResource(R.string.share_title), fontWeight = FontWeight.SemiBold, color = Text1)
        ((shares?.people ?: emptyList()) + (shares?.outside ?: emptyList())).forEach { h -> Text("${h.name ?: h.email} · ${h.role ?: ""}", color = Text1) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(email, { email = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.share_address)) })
            Button({ scope.launch { note = runCatching { model.client!!.share(item, email); email = ""; null }.getOrElse { model.say(it) }; load() } }, enabled = email.contains("@"), modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.share_add)) }
        }
        Text(stringResource(R.string.share_link), fontWeight = FontWeight.SemiBold, color = Text1)
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
            if (comments.isNotEmpty()) item { Text(stringResource(R.string.doc_comments), fontWeight = FontWeight.SemiBold, color = Text1) }
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
    val scope = rememberCoroutineScope()
    val signed = stringResource(R.string.foryou_signed); val done = stringResource(R.string.foryou_done); val sentBack = stringResource(R.string.foryou_sentBackComment)
    suspend fun load() {
        reviews = runCatching { model.client!!.waitingReviews() }.getOrDefault(emptyList())
        tasks = runCatching { model.client!!.myTasks() }.getOrDefault(emptyList())
    }
    LaunchedEffect(Unit) { load() }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.foryou_title), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Text1) }
        note?.let { item { Text(it, color = Text2, modifier = Modifier.testTag("note")) } }
        if (reviews.isEmpty()) item { Text(stringResource(R.string.foryou_nothing), color = Text2) }
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
        item { Text(stringResource(R.string.foryou_tasks, tasks.count { !it.done }), fontWeight = FontWeight.SemiBold, color = Text1) }
        items(tasks, key = { it.id }) { t ->
            Row(Modifier.fillMaxWidth().clickable { scope.launch { runCatching { model.client!!.setDone(t, !t.done) }; load() } }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(t.done, null)
                Column { Text(t.title, color = Text1); t.projectName?.let { Text(it, color = Text2, fontSize = 12.sp) } }
            }
        }
    }
}

@Composable
fun NotificationsScreen(model: Model) {
    var items by remember { mutableStateOf<List<Notice>>(emptyList()) }
    LaunchedEffect(Unit) { items = runCatching { model.client!!.notices() }.getOrDefault(emptyList()); runCatching { model.client!!.markNoticesRead() } }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.notify_title), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Text1) }
        items(items, key = { it.id }) { n ->
            Column { Text(n.title, color = Text1, fontWeight = if (n.read) FontWeight.Normal else FontWeight.SemiBold); n.detail?.takeIf { it.isNotEmpty() }?.let { Text(it, color = Text2, fontSize = 14.sp) } }
        }
    }
}

@Composable
fun SearchScreen(model: Model) {
    var words by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.search_title), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Text1)
        OutlinedTextField(words, { words = it }, Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.search_prompt)) }, singleLine = true,
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { scope.launch { hits = runCatching { model.client!!.search(words) }.getOrDefault(emptyList()) } }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search))
        hits.forEach { h -> Column { Text(h.title, color = Text1); h.snippet?.let { Text(it, color = Text2, fontSize = 13.sp, maxLines = 2) } } }
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
    suspend fun load() { imports = runCatching { model.client!!.imports() }.getOrDefault(emptyList()) }
    LaunchedEffect(Unit) { load() }
    val failed = imports.flatMap { it.accounts }.filter { it.failed }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.admin_title), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Text1) }
        item { Text(stringResource(if (failed.isEmpty()) R.string.admin_normal else R.string.admin_needs), fontWeight = FontWeight.SemiBold, color = Text1) }
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
        item { Text(stringResource(R.string.admin_invite), fontWeight = FontWeight.SemiBold, color = Text1) }
        item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.admin_name)) }) }
        item { OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.share_address)) }) }
        item { Button({ scope.launch { note = runCatching { model.client!!.invite(name, email); name = ""; email = ""; invited }.getOrElse { model.say(it) } } }, enabled = name.isNotBlank() && email.contains("@")) { Text(stringResource(R.string.admin_inviteButton)) } }
    }
}
