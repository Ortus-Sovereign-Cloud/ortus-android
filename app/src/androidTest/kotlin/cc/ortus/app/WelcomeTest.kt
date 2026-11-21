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
            // S1.9: she signs the flood plan with the phone's key.
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithText("More").performClick()
            compose.onNodeWithText("For you").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Flood response plan 2026").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("decide").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Signed.").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    /** S2.4: the IT administrator fixes the two failed mailbox imports from the phone. */
    @Test fun anAdministratorFixesImports() {
        val server = args.getString("ortusServer").orEmpty()
        val code = args.getString("ortusAdminCode").orEmpty()
        val passwords = args.getString("ortusImportPasswords").orEmpty()
        need(server, code, passwords)
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
            assert(admin { compose.onAllNodesWithText("2 mailboxes failed").fetchSemanticsNodes().isNotEmpty() }) { "two failures" }
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
            assert(admin { compose.onAllNodesWithText("All systems normal").fetchSemanticsNodes().isNotEmpty() }) { "the imports still fail" }
            Thread.sleep(15_000)
            androidx.test.espresso.Espresso.pressBack()
            assert(admin { compose.onAllNodesWithText("All systems normal").fetchSemanticsNodes().isNotEmpty() }) { "an import failed again" }
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
                assert(gapEnd < gapStart) { "the title is not at the right: start gap $gapStart, end gap $gapEnd" }
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { locales.applicationLocales = android.os.LocaleList.getEmptyLocaleList() }
        }
    }
}
