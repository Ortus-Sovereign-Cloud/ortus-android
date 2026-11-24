package cc.ortus.kit

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchTest {
    private fun hit(app: String, path: String?, start: Long? = null) = SearchHit(app, "x", "t", meta = HitMeta(path = path, start = start))

    /** Each app's result path (as the apps index them) opens its own screen. */
    @Test fun aResultOpensItsItem() {
        assertEquals(HitTarget.Thread("th1"), hit("mail", "/mail?thread=th1").target())
        assertEquals(HitTarget.Room("!r1:ortus"), hit("chat", "/chat?room=%21r1%3Aortus&message=\$e1").target())
        assertEquals(HitTarget.Folder("f1"), hit("drive", "/drive?folder=f1").target())
        assertEquals(HitTarget.File("d1"), hit("drive", "/drive?file=d1").target())
        assertEquals(HitTarget.Doc("doc1"), hit("docs", "/docs?doc=doc1").target())
        assertEquals(HitTarget.Day(1_700_000_000_000), hit("calendar", "/calendar?event=e1", 1_700_000_000_000).target())
    }

    /** No screen for it in the app: the web page, at the path the web opens. */
    @Test fun otherKindsOpenTheWeb() {
        assertEquals(HitTarget.Web("/tasks?task=t1"), hit("tasks", "/tasks?task=t1").target())
        assertEquals(HitTarget.Web("/people?section=contacts"), hit("people", "/people?section=contacts").target())
        assertEquals(HitTarget.Web("/records?record=r1"), hit("records", "/records?record=r1").target())
        // An event without its start, a result without a path: the app's page.
        assertEquals(HitTarget.Web("/calendar?event=e1"), hit("calendar", "/calendar?event=e1").target())
        assertEquals(HitTarget.Web("/mail"), hit("mail", null).target())
        // Never another site's address.
        assertEquals(HitTarget.Web("/drive"), hit("drive", "//evil.example/x").target())
    }

    @Test fun readsTheResultsMeta() {
        val r = json.decodeFromString(SearchResults.serializer(), """{"results":[{"app":"mail","id":"m1","title":"Gauge","snippet":"s","meta":{"path":"/mail?thread=t9","from":"anna@x","attachment":false},"at":1,"mine":true}],"words":["gauge"]}""")
        assertEquals(HitTarget.Thread("t9"), r.results[0].target())
        assertEquals(3, json.decodeFromString(Notices.serializer(), """{"items":[],"unread":3,"counts":{},"quiet":{}}""").unread)
    }
}
