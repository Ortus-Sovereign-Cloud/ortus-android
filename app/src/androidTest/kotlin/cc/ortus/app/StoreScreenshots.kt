package cc.ortus.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Play Store screenshots, against an Ortus filled with the sample
 * organisation (workflow `screenshots`, which passes the owner's session
 * as ortusScreenshotSession). Skipped everywhere else. The PNGs land in
 * the app's external files (screenshots/), which the workflow pulls.
 */
class StoreScreenshots {
    @get:Rule val compose = createEmptyComposeRule()
    private val args = InstrumentationRegistry.getArguments()

    /** The system's own screen capture, into /data/local/tmp (it outlives the app, which the test run uninstalls). */
    private fun shell(command: String) {
        val out = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(2000)
        shell("mkdir -p /data/local/tmp/ortus-shots")
        shell("screencap -p /data/local/tmp/ortus-shots/$name.png")
    }

    private fun waitText(text: String, timeout: Long = 30_000) =
        compose.waitUntil(timeout) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun storeScreenshots() {
        val server = args.getString("ortusServer").orEmpty()
        val session = args.getString("ortusScreenshotSession").orEmpty()
        assumeTrue("only in the screenshots workflow", server.isNotEmpty() && session.isNotEmpty())
        // A phone sign-in code from the owner's session, made now.
        val c = java.net.URL("$server/api/v1/me/phone-link").openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $session")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("X-Requested-With", "ortus")
        c.outputStream.use { it.write("{}".toByteArray()) }
        val code = org.json.JSONObject(c.inputStream.bufferedReader().readText()).getString("link").substringAfterLast("p=")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val open = android.net.Uri.Builder().scheme("ortus").authority("signin")
            .appendQueryParameter("server", server).appendQueryParameter("code", code).build()
        val intent = Intent(Intent.ACTION_VIEW, open, context, MainActivity::class.java).putExtra("reset", true)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("confirmLink").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("confirmLink").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("makeKey").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("makeKey").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Chat").fetchSemanticsNodes().isNotEmpty() }

            // 1. Mail: the inbox, then a conversation.
            waitText("Radio check")
            shot("1-mail")
            compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Sandbag stock", substring = true))
            compose.onAllNodesWithText("Sandbag stock", substring = true)[0].performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("reply").fetchSemanticsNodes().isNotEmpty() }
            waitText("4,800")
            shot("2-thread")
            androidx.test.espresso.Espresso.pressBack()

            // 2. Chat: the channels, then one.
            compose.onNodeWithText("Chat").performClick()
            waitText("flood-response")
            shot("3-chat")
            compose.onAllNodesWithText("flood-response", substring = true)[0].performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("message").fetchSemanticsNodes().isNotEmpty() }
            waitText("Bridge gauge")
            shot("4-channel")
            androidx.test.espresso.Espresso.pressBack()

            // 3. Calendar: today.
            compose.onNodeWithText("Calendar").performClick()
            compose.waitUntil(20_000) {
                listOf("Sandbag move", "Readiness review", "Town hall").any { compose.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty() }
            }
            shot("5-calendar")
        }
    }
}
