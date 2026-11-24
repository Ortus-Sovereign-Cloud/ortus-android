package cc.ortus.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cc.ortus.kit.HitMeta
import cc.ortus.kit.Room
import cc.ortus.kit.Rooms
import cc.ortus.kit.SearchHit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Review U6: the phone's navigation as the web's (no installation needed). */
class NavigationTest {
    @get:Rule val compose = createComposeRule()

    /** The bell in the top bar carries the unread count, and opens the list. */
    @Test fun theBellShowsTheUnreadCount() {
        var opened = false
        compose.setContent { TopActions(3, search = {}, notifications = { opened = true }) }
        compose.onNodeWithTag("unread", useUnmergedTree = true).assertTextEquals("3")
        compose.onNodeWithContentDescription("Notifications, 3 unread").assertExists()
        compose.onNodeWithTag("openNotifications").performClick()
        check(opened) { "the bell did not open the notifications" }
    }

    @Test fun noBadgeWhenAllIsRead() {
        compose.setContent { TopActions(0, search = {}, notifications = {}) }
        compose.onAllNodesWithTag("unread", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Notifications").assertExists()
        compose.onNodeWithTag("openSearch").assertHasClickAction()
    }

    /** Every result is tappable and opens its item: a message its thread, a chat result its room, … */
    @Test fun aSearchResultOpensItsItem() {
        val mail = SearchHit("mail", "m1", "Gauge report", "The bridge gauge", HitMeta(path = "/mail?thread=t1"))
        val chat = SearchHit("chat", "\$e1", "#flood-response", "Gauge at 4.1 m", HitMeta(path = "/chat?room=r1&message=%24e1"))
        val task = SearchHit("tasks", "k1", "Check the gauge", null, HitMeta(path = "/tasks?task=k1"))
        var picked: SearchHit? = null
        compose.setContent { SearchHits(listOf(mail, chat, task)) { picked = it } }
        compose.onAllNodesWithTag("hit").assertCountEquals(3)
        compose.onNodeWithText("Gauge report").performClick()
        val rooms = Rooms(listOf(Room(id = "r1", name = "flood-response", kind = "channel")))
        runBlocking {
            assertEquals(Place.Thread("t1"), place(picked!!) { rooms })
            compose.onNodeWithText("#flood-response").performClick()
            assertEquals(Place.Channel(rooms.rooms[0], "# flood-response"), place(picked!!) { rooms })
            // No screen for tasks here: the web page (a Custom Tab).
            compose.onNodeWithText("Check the gauge").performClick()
            assertNull(place(picked!!) { rooms })
        }
    }

    /** The section is named as on the web: Drive, not Files. */
    @Test fun theSectionIsNamedDrive() {
        compose.setContent { Sections(0) {} }
        compose.onNodeWithText("Drive").assertExists()
        compose.onAllNodesWithText("Files").assertCountEquals(0)
    }
}
