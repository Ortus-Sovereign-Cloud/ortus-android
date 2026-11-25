package cc.ortus.app

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.ortus.e2e.HttpResponse
import org.ortus.e2e.OrtusE2e
import org.ortus.e2e.Transport
import java.io.File

/**
 * An end-to-end room on the phone, against a real Ortus on the CI machine:
 * another device of the same person (the library in this test, signed in
 * with the owner's session) writes in the room; the phone opens it, and
 * what the phone writes opens on the other device. The server keeps no
 * text of either.
 */
class EndToEndTest {
    @get:Rule val compose = createEmptyComposeRule()
    // A hang fails with where it hung, not at the job's time limit.
    @get:Rule val limit: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(300)
    private val args = InstrumentationRegistry.getArguments()

    private fun call(server: String, session: String, method: String, path: String, body: String? = null): Pair<Int, String> {
        val c = java.net.URL("$server/api/v1$path").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $session")
        c.setRequestProperty("X-Requested-With", "ortus")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = c.responseCode
        val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        return status to text
    }

    private fun waitFor(what: String, timeout: Long = 60_000, ready: () -> Boolean) {
        runCatching { compose.waitUntil(timeout) { ready() } }
        if (!ready()) {
            val shown = compose.onAllNodes(isRoot()).fetchSemanticsNodes().flatMap { root ->
                fun walk(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
                    n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.children.flatMap { walk(it) }
                walk(root)
            }
            throw AssertionError("waited for $what; the screen shows: ${shown.joinToString(" | ").take(1500)}")
        }
    }

    @Test fun anEndToEndRoomOpensAndSends() {
        val server = args.getString("ortusServer").orEmpty()
        val session = args.getString("ortusOwnerSession").orEmpty()
        if (args.getString("ortusLive") == "1" && (server.isEmpty() || session.isEmpty())) throw AssertionError("ORTUS_SERVER and the owner's session are not set")
        assumeTrue(server.isNotEmpty() && session.isNotEmpty())

        // End-to-end rooms allowed, and one made.
        check(call(server, session, "PATCH", "/chat/policy", """{"encryptedRooms":true}""").first == 200) { "the policy" }
        val made = call(server, session, "POST", "/chat/rooms", """{"kind":"channel","name":"sealed-plans","encrypted":true}""")
        check(made.first == 200) { "the room: ${made.second}" }
        val room = JSONObject(made.second).getString("id")
        val me = JSONObject(call(server, session, "GET", "/chat/rooms").second).getString("me")
        val user = me.removePrefix("@").substringBefore(':')
        val serverName = me.substringAfter(':')

        // The phone, signed in as the owner with a phone sign-in code.
        val (_, linked) = call(server, session, "POST", "/me/phone-link", "{}")
        val code = JSONObject(linked).getString("link").substringAfterLast("p=")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val open = android.net.Uri.Builder().scheme("ortus").authority("signin")
            .appendQueryParameter("server", server).appendQueryParameter("code", code).build()
        val intent = Intent(Intent.ACTION_VIEW, open, context, MainActivity::class.java).putExtra("reset", true)

        // The other device: the library in this test, with its own store.
        val transport = object : Transport {
            override fun http(method: String, path: String, body: String?): HttpResponse =
                call(server, session, method, path, body).let { (s, t) -> HttpResponse(s.toUShort(), t) }
        }
        val store = File(context.cacheDir, "e2e-peer").apply { deleteRecursively(); mkdirs() }

        ActivityScenario.launch<MainActivity>(intent).use {
            waitFor("the link's installation to confirm") { compose.onAllNodesWithTag("confirmLink").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("confirmLink").performClick()
            waitFor("the welcome screen") { compose.onAllNodesWithTag("welcome").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("makeKey").performClick()
            waitFor("the tab bar") { compose.onAllNodesWithText("Chat").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Chat").performClick()
            waitFor("the room") { compose.onAllNodesWithText("sealed-plans", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("sealed-plans", substring = true)[0].performClick()
            // Opening the room publishes this phone's keys.
            waitFor("the sealed room (not the computer-only note)") {
                compose.onAllNodesWithTag("sealedRoom").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("locked").fetchSemanticsNodes().isNotEmpty()
            }
            check(compose.onAllNodesWithTag("locked").fetchSemanticsNodes().isEmpty()) { "the phone has no end-to-end library" }
            // The room is open once the server lists the phone's device.
            val phoneListed = { call(server, session, "GET", "/chat/rooms/$room/devices").second.contains("\"PHONE") }
            waitFor("the phone's device keys on the server") { phoneListed() }

            // The other device writes; the phone opens it.
            val peer = OrtusE2e.open(store.path, "peer passphrase", user, "TESTPEER", serverName, transport)
            val sent = peer.send(room, "Shelter list for District 4 is ready", null)
            val kept = JSONObject(call(server, session, "GET", "/chat/rooms/$room/messages").second).getJSONArray("messages")
            for (i in 0 until kept.length()) check(kept.getJSONObject(i).optString("text").isEmpty()) { "the server keeps no text" }
            compose.onNodeWithTag("message").performTextInput("Received, thank you")
            compose.onNodeWithTag("sendChat").performClick()
            val held = { call(server, session, "GET", "/chat/rooms/$room/messages").second.let { j -> JSONObject(j).getJSONArray("messages").length() } }
            try {
                waitFor("the other device's message, opened") { compose.onAllNodesWithText("Shelter list for District 4 is ready").fetchSemanticsNodes().isNotEmpty() }
            } catch (e: AssertionError) {
                throw AssertionError("${e.message}; the server holds ${held()} messages")
            }

            // What the phone wrote opens on the other device.
            peer.inbox()
            val messages = JSONObject(call(server, session, "GET", "/chat/rooms/$room/messages").second).getJSONArray("messages")
            val texts = (0 until messages.length()).map { messages.getJSONObject(it) }.filter { it.optString("id") != sent && it.has("sealed") }
                .mapNotNull { runCatching { peer.decrypt(room, it.toString()).text }.getOrNull() }
            check("Received, thank you" in texts) { "the phone's message did not open on the other device: $texts" }
            peer.forget()
        }
    }
}
