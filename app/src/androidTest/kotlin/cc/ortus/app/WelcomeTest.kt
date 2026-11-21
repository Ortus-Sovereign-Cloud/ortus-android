package cc.ortus.app

import android.content.Intent
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

    /** S3.2: the code from the welcome letter, then the phone becomes the key; then her inbox and a reply. */
    @Test fun thePhoneBecomesTheKey() {
        val server = args.getString("ortusServer").orEmpty()
        val code = args.getString("ortusCode").orEmpty()
        assumeTrue(server.isNotEmpty() && code.isNotEmpty())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra("reset", true).putExtra("server", server).putExtra("code", code)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.onNodeWithText("Continue").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("welcome").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("makeKey").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("More").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("More").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("iryna@ministry.example").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Mail").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Gauge report and updated shelter agreement").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Gauge report and updated shelter agreement").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("reply").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reply").performTextInput("Thanks, will review")
            compose.onNodeWithTag("send").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Sent.").fetchSemanticsNodes().isNotEmpty() }
            // S1.9: she signs the flood plan with the phone's key.
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithText("More").performClick()
            compose.onNodeWithText("For you").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Flood response plan 2026").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("decide").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Signed.").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    /** S2.4: the IT administrator fixes failed mailbox imports from the phone. */
    @Test fun anAdministratorFixesImports() {
        val server = args.getString("ortusServer").orEmpty()
        val code = args.getString("ortusAdminCode").orEmpty()
        assumeTrue(server.isNotEmpty() && code.isNotEmpty())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra("reset", true).putExtra("server", server).putExtra("code", code)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.onNodeWithText("Continue").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("makeKey").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("makeKey").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("More").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("More").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Admin").fetchSemanticsNodes().isNotEmpty() }
            // The import fails on its own: open Admin again until it shows.
            var shown = false
            for (i in 0 until 12) {
                compose.onNodeWithText("Admin").performClick()
                shown = runCatching { compose.waitUntil(5_000) { compose.onAllNodesWithTag("askOwners").fetchSemanticsNodes().isNotEmpty() } }.isSuccess
                if (shown) break
                androidx.test.espresso.Espresso.pressBack()
            }
            assert(shown)
            compose.onNodeWithTag("askOwners").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("The owners have a reconnect link in their inbox.").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    /** The first screen opens in Arabic, right to left. */
    @Test fun joinScreenInArabic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale("ar")) }
        val ar = context.createConfigurationContext(config)
        assert(ar.resources.getString(R.string.join_title) == "انضم إلى مؤسستك")
        assert(config.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL)
    }
}
