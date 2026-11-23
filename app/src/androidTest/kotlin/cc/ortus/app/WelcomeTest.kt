package cc.ortus.app

import android.content.Intent
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** The phone steps against a real Ortus on the CI machine (10.0.2.2). */
class WelcomeTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val args = InstrumentationRegistry.getArguments()

    /**
     * Waits for [ready]; when the app shows an error instead (or time runs
     * out), fails with the error's text, so CI says what went wrong.
     */
    private fun waitFor(what: String, ready: () -> Boolean) {
        val error = { compose.onAllNodesWithTag("error").fetchSemanticsNodes() }
        runCatching { compose.waitUntil(60_000) { ready() || error().isNotEmpty() } }
        if (!ready()) {
            val text = error().flatMap { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty() }.joinToString { it.text }
            val screen = compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().flatMap { root ->
                fun walk(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
                    n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty().map { it.text } +
                        listOfNotNull(n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.let { "#$it" }) +
                        n.children.flatMap { walk(it) }
                walk(root)
            }
            val log = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("logcat -d -t 200 ortus:V AndroidRuntime:E System.err:W OkHttp:V *:S").let { fd ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().readText()
            }
            throw AssertionError("$what did not show; the app says: ${text.ifEmpty { "(nothing)" }}; on screen: ${screen.joinToString(" | ")}\nlog:\n$log")
        }
    }

    /** A check that always runs (Kotlin's assert is off on Android). */
    /** A link names its installation; the person says yes. */
    private fun confirmLink() {
        waitFor("the link's installation to confirm") { compose.onAllNodesWithTag("confirmLink").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("confirmLink").performClick()
    }

    private fun check(ok: Boolean, what: String) { if (!ok) throw AssertionError(what) }

    /** Each import account as login:state:copied, from the server. */
    private fun importStates(server: String, token: String): String {
        val c = java.net.URL("$server/api/v1/mail/admin/imports").openConnection() as java.net.HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $token")
        val imports = org.json.JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("imports")
        val out = mutableListOf<String>()
        for (i in 0 until imports.length()) {
            val accounts = imports.getJSONObject(i).getJSONArray("accounts")
            for (j in 0 until accounts.length()) {
                val a = accounts.getJSONObject(j)
                out += "${a.getString("login")}:${a.getString("state")}:${minOf(a.optInt("copied", 0), 1)}"
            }
        }
        return out.sorted().joinToString(",")
    }

    /** Runs the step when there is an Ortus; in CI its absence fails. */
    private fun need(vararg values: String) {
        val there = values.all { it.isNotEmpty() }
        if (!there && args.getString("ortusLive") == "1") throw AssertionError("ORTUS_SERVER and the codes are not set")
        assumeTrue(there)
    }

    /** S3.2: the code from the welcome letter, then the phone becomes the key; then her inbox and a reply. */
    @Test fun thePhoneBecomesTheKey() {
        val server = args.getString("ortusServer").orEmpty()
        val code = args.getString("ortusCode").orEmpty()
        need(server, code)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra("reset", true).putExtra("server", server).putExtra("code", code)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.onNodeWithText("Continue").performClick()
            waitFor("the welcome screen") { compose.onAllNodesWithTag("welcome").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("makeKey").performClick()
            waitFor("the tab bar after making the key") { compose.onAllNodesWithText("More").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("More").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("iryna@ministry.example").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Mail").performClick()
            waitFor("Anna's mail in the inbox") { compose.onAllNodesWithText("Gauge report and updated shelter agreement").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Gauge report and updated shelter agreement").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("reply").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reply").performTextInput("Thanks, will review")
            compose.onNodeWithTag("send").performClick()
            waitFor("the reply sent") { compose.onAllNodesWithText("Sent.").fetchSemanticsNodes().isNotEmpty() }
            // Her reply is in Sent, with whom it went to. (The first Back
            // may only close the keyboard; then one more leaves the thread.)
            androidx.test.espresso.Espresso.pressBack()
            compose.waitForIdle()
            if (compose.onAllNodesWithText("Sent").fetchSemanticsNodes().isEmpty()) androidx.test.espresso.Espresso.pressBack()
            waitFor("the folders") { compose.onAllNodesWithText("Sent").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Sent").performClick()
            waitFor("the reply in Sent") { compose.onAllNodesWithText("Gauge report", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Inbox").performClick()
            // A new message to Taras (who has joined), picked from the suggestions.
            compose.onNodeWithTag("compose").performClick()
            waitFor("the compose screen") { compose.onAllNodesWithTag("to").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("to").performTextInput("taras")
            waitFor("a suggestion for Taras") { compose.onAllNodesWithText("taras@ministry.example").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("taras@ministry.example").performClick()
            compose.onNodeWithTag("subject").performTextInput("Depot keys")
            compose.onNodeWithTag("message").performTextInput("The keys are with the guard.")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("sendMessage").performClick()
            waitFor("the new message sent") { compose.onAllNodesWithText("Sent.").fetchSemanticsNodes().isNotEmpty() }
            // S1.9: she signs the flood plan with the phone's key.
            compose.onNodeWithText("More").performClick()
            compose.onNodeWithText("For you").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Flood response plan 2026").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("decide").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Signed.").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    /** An invitation page on the phone hands the app its link (ortus://join): the app joins with it. */
    @Test fun anInvitationLinkOpensTheApp() {
        val server = args.getString("ortusServer").orEmpty()
        val code = args.getString("ortusLinkCode").orEmpty()
        need(server, code)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val link = android.net.Uri.Builder().scheme("ortus").authority("join")
            .appendQueryParameter("server", server).appendQueryParameter("code", code).build()
        val intent = Intent(Intent.ACTION_VIEW, link, context, MainActivity::class.java).putExtra("reset", true)
        ActivityScenario.launch<MainActivity>(intent).use {
            confirmLink()
            waitFor("the welcome screen after the link") { compose.onAllNodesWithTag("welcome").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    /** "Sign in on your phone" from the web: the code from the owner's web session signs the app in. */
    @Test fun aPhoneSignInCodeFromTheWeb() {
        val server = args.getString("ortusServer").orEmpty()
        val session = args.getString("ortusOwnerSession").orEmpty()
        need(server, session)
        val c = java.net.URL("$server/api/v1/me/phone-link").openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $session")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("X-Requested-With", "ortus")
        c.outputStream.use { it.write("{}".toByteArray()) }
        val link = org.json.JSONObject(c.inputStream.bufferedReader().readText()).getString("link")
        val code = link.substringAfterLast("p=")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val open = android.net.Uri.Builder().scheme("ortus").authority("signin")
            .appendQueryParameter("server", server).appendQueryParameter("code", code).build()
        val intent = Intent(Intent.ACTION_VIEW, open, context, MainActivity::class.java).putExtra("reset", true)
        // A channel to call in.
        val room = java.net.URL("$server/api/v1/chat/rooms").openConnection() as java.net.HttpURLConnection
        room.requestMethod = "POST"
        room.doOutput = true
        room.setRequestProperty("Authorization", "Bearer $session")
        room.setRequestProperty("Content-Type", "application/json")
        room.setRequestProperty("X-Requested-With", "ortus")
        room.outputStream.use { it.write("""{"kind":"channel","name":"phone-calls"}""".toByteArray()) }
        check(room.responseCode == 200, "the channel")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.apply {
            grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
            grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            confirmLink()
            waitFor("the welcome screen after the sign-in code") { compose.onAllNodesWithTag("welcome").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("makeKey").performClick()
            waitFor("the tab bar after making the key") { compose.onAllNodesWithText("Chat").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Chat").performClick()
            waitFor("the channel") { compose.onAllNodesWithText("phone-calls", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("phone-calls", substring = true)[0].performClick()
            compose.onNodeWithTag("call").performClick()
            waitFor("the call screen") { compose.onAllNodesWithTag("call").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithTag("hangUp").fetchSemanticsNodes().isNotEmpty() }
            // Natively: the phone joined and the media node answered its offer.
            waitFor("the media node's answer") { compose.onAllNodesWithTag("answered").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("callError").fetchSemanticsNodes().isNotEmpty() }
            val failed = compose.onAllNodesWithTag("callError").fetchSemanticsNodes()
            check(failed.isEmpty(), "the call failed: " + failed.joinToString { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString().orEmpty() })
            compose.onNodeWithTag("mute").performClick()
            compose.onNodeWithTag("hangUp").performClick()
        }
    }

    /** S2.4: the IT administrator fixes the two failed mailbox imports from the phone. */
    @Test fun anAdministratorFixesImports() {
        val server = args.getString("ortusServer").orEmpty()
        val code = args.getString("ortusAdminCode").orEmpty()
        val passwords = args.getString("ortusImportPasswords").orEmpty()
        val owner = args.getString("ortusOwnerSession").orEmpty()
        need(server, code, passwords, owner)
        val right = passwords.split(",").associate { it.substringBefore("=") to it.substringAfter("=") }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra("reset", true).putExtra("server", server).putExtra("code", code)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.onNodeWithText("Continue").performClick()
            waitFor("the welcome screen") { compose.onAllNodesWithTag("makeKey").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("makeKey").performClick()
            waitFor("the tab bar after making the key") { compose.onAllNodesWithText("More").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("More").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Admin").fetchSemanticsNodes().isNotEmpty() }
            // Opens Admin until it shows what is wanted (the list loads once per opening).
            fun admin(until: () -> Boolean): Boolean {
                for (i in 0 until 12) {
                    compose.onNodeWithText("Admin").performClick()
                    if (runCatching { compose.waitUntil(5_000) { until() } }.isSuccess) return true
                    androidx.test.espresso.Espresso.pressBack()
                }
                return false
            }
            // Both imports fail on their own.
            check(admin { compose.onAllNodesWithText("2 mailboxes failed").fetchSemanticsNodes().isNotEmpty() }, "two failures")
            compose.onNodeWithTag("askOwners").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("The owners have a reconnect link in their inbox.").fetchSemanticsNodes().isNotEmpty() }
            // He puts in the right passwords and retries both.
            for (login in right.keys.sorted()) {
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("password-$login").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("password-$login").performScrollTo().performTextInput(right.getValue(login))
                compose.onNodeWithTag("retry-$login").performScrollTo().performClick()
                compose.waitUntil(20_000) { compose.onAllNodesWithText("Trying again.").fetchSemanticsNodes().isNotEmpty() }
            }
            // Both copy: nothing fails again (a wrong password fails in seconds).
            androidx.test.espresso.Espresso.pressBack()
            check(admin { compose.onAllNodesWithText("All systems normal").fetchSemanticsNodes().isNotEmpty() }, "the imports still fail")
            Thread.sleep(15_000)
            androidx.test.espresso.Espresso.pressBack()
            check(admin { compose.onAllNodesWithText("All systems normal").fetchSemanticsNodes().isNotEmpty() }, "an import failed again")
            // And the mail came: both accounts copied, each with its old
            // mail (asked of the server, as the owner).
            var states = ""
            for (i in 0 until 90) {
                states = importStates(server, owner)
                if (states == "olha.old@ministry.example:done:1,petro.old@ministry.example:done:1") break
                Thread.sleep(1000)
            }
            check(states == "olha.old@ministry.example:done:1,petro.old@ministry.example:done:1", "the imports: $states")
        }
    }

    /**
     * The first screen in Arabic: the app set to Arabic (per-app language),
     * its title in Arabic sits against the right edge, the start in RTL.
     */
    @Test fun joinScreenInArabic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val locales = context.getSystemService(android.app.LocaleManager::class.java)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { locales.applicationLocales = android.os.LocaleList.forLanguageTags("ar") }
        try {
            val intent = Intent(context, MainActivity::class.java).putExtra("reset", true)
            ActivityScenario.launch<MainActivity>(intent).use {
                val title = compose.onNodeWithText("انضم إلى مؤسستك").assertExists().fetchSemanticsNode()
                val root = compose.onRoot().fetchSemanticsNode()
                val gapEnd = root.boundsInRoot.right - title.boundsInRoot.right
                val gapStart = title.boundsInRoot.left - root.boundsInRoot.left
                check(gapEnd < gapStart, "the title is not at the right: start gap $gapStart, end gap $gapEnd")
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { locales.applicationLocales = android.os.LocaleList.getEmptyLocaleList() }
        }
    }
}
