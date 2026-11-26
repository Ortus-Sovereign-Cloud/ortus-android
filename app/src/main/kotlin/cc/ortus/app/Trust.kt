package cc.ortus.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ortus.e2e.VerificationView

/** The 64 emoji of Matrix's verification, by number (screen readers name them). */
val SAS_EMOJI = listOf(
    "🐶", "🐱", "🦁", "🐎", "🦄", "🐷", "🐘", "🐰", "🐼", "🐓", "🐧", "🐢", "🐟", "🐙", "🦋", "🌷",
    "🌳", "🌵", "🍄", "🌏", "🌙", "☁️", "🔥", "🍌", "🍎", "🍓", "🌽", "🍕", "🎂", "❤️", "😀", "🤖",
    "🎩", "👓", "🔧", "🎅", "👍", "☂️", "⌛", "⏰", "🎁", "💡", "📕", "✏️", "📎", "✂️", "🔒", "🔑",
    "🔨", "☎️", "🏁", "🚂", "🚲", "✈️", "🚀", "🏆", "⚽", "🎸", "🎺", "🔔", "⚓", "🎧", "📁", "📌",
)

/**
 * Recovery on the phone: set up on a computer (the phone does not make the
 * recovery key); with it set up, the recovery key brings this phone's
 * history and vouches for it; recovered, it can verify the person's other
 * device by emoji.
 */
@Composable
fun RecoveryDialog(model: Model, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<org.ortus.e2e.RecoveryState?>(null) }
    var key by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val me = model.chatMe
    val restored = stringResource(R.string.chat_recoveryRestored)
    val wrong = stringResource(R.string.chat_recoveryWrong)
    LaunchedEffect(me) { if (me != null) state = runCatching { model.endToEnd()!!.recoveryState(me) }.getOrNull() }
    AlertDialog(
        onDismissRequest = close,
        modifier = Modifier.testTag("recovery"),
        title = { Text(stringResource(R.string.chat_recovery)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val s = state
                when {
                    s == null -> Text("…")
                    !s.set -> Text(stringResource(R.string.chat_recoveryOnComputer))
                    !s.here -> {
                        Text(stringResource(R.string.chat_recoveryEnterLead))
                        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().testTag("recoveryKey"), label = { Text(stringResource(R.string.chat_recoveryKey)) })
                    }
                    else -> Text(stringResource(R.string.chat_recoveryHere))
                }
                note?.let { Text(it, modifier = Modifier.testTag("recoveryNote").semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        },
        confirmButton = {
            val s = state
            when {
                s != null && s.set && !s.here -> Button({
                    scope.launch {
                        busy = true
                        note = try {
                            val n = model.endToEnd()!!.recover(me!!, key)
                            state = model.endToEnd()!!.recoveryState(me)
                            model.trustChanged++
                            restored.format(n)
                        } catch (e: org.ortus.e2e.E2eException.Invalid) {
                            wrong
                        } catch (e: Exception) {
                            model.say(e)
                        }
                        busy = false
                    }
                }, enabled = !busy && key.isNotBlank(), modifier = Modifier.testTag("recover")) { Text(stringResource(R.string.chat_recoveryEnter)) }
                s != null && s.here -> Button({
                    scope.launch { runCatching { model.endToEnd()!!.verifyOwnDevices(me!!) }.onFailure { note = model.say(it) }; close() }
                }, modifier = Modifier.testTag("verifyMine")) { Text(stringResource(R.string.chat_verifyMine)) }
                else -> TextButton(close) { Text(stringResource(R.string.chat_close)) }
            }
        },
        dismissButton = { TextButton(close) { Text(stringResource(R.string.chat_close)) } },
    )
}

/**
 * Verifications by emoji, for the whole app: looked for every few seconds
 * while the app is in front and this phone's keys are open; an incoming one
 * is asked about, emoji are compared, the end is said.
 */
@Composable
fun Verifications(model: Model) {
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf<VerificationView?>(null) }
    var dismissed by remember { mutableStateOf(setOf<String>()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val e2e = model.endToEnd()
                val me = model.chatMe
                if (e2e != null && me != null && e2e.isOpen) {
                    val list = runCatching { e2e.verifications(me) }.getOrDefault(emptyList())
                    val current = list.lastOrNull { it.id !in dismissed && it.state != "cancelled" } ?: list.lastOrNull { it.id == shown?.id }
                    if (current?.state == "done" && shown?.state != "done") model.trustChanged++
                    shown = current?.takeIf { it.id !in dismissed }
                }
                delay(3_000)
            }
        }
    }
    val v = shown ?: return
    val me = model.chatMe ?: return
    val close = { dismissed = dismissed + v.id; shown = null }
    AlertDialog(
        onDismissRequest = {
            if (v.state != "done" && v.state != "cancelled") scope.launch { runCatching { model.endToEnd()!!.cancelVerification(me, v.id) } }
            close()
        },
        modifier = Modifier.testTag("verification"),
        title = { Text(stringResource(R.string.chat_verifyTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                when (v.state) {
                    "incoming" -> Text(stringResource(R.string.chat_verifyIncoming))
                    "emoji" -> {
                        Text(stringResource(R.string.chat_verifyCompare))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("verifyEmoji")) {
                            v.emoji.forEach { n -> Text(SAS_EMOJI.getOrElse(n.toInt()) { "?" }, fontSize = 30.sp) }
                        }
                    }
                    "done" -> Text(stringResource(R.string.chat_verifyDone))
                    "cancelled" -> Text(stringResource(R.string.chat_verifyCancelled))
                    else -> Text(stringResource(R.string.chat_verifyWaiting))
                }
            }
        },
        confirmButton = {
            when (v.state) {
                "incoming" -> Button({ scope.launch { runCatching { model.endToEnd()!!.acceptVerification(me, v.id) } } }, Modifier.testTag("verifyAccept")) { Text(stringResource(R.string.chat_verifyAccept)) }
                "emoji" -> Button({ scope.launch { runCatching { model.endToEnd()!!.confirmVerification(me, v.id, true) } } }, Modifier.testTag("verifySame")) { Text(stringResource(R.string.chat_verifySame)) }
                else -> TextButton(close) { Text(stringResource(R.string.chat_close)) }
            }
        },
        dismissButton = {
            when (v.state) {
                "incoming" -> TextButton({ scope.launch { runCatching { model.endToEnd()!!.cancelVerification(me, v.id) } }; close() }) { Text(stringResource(R.string.chat_verifyDecline)) }
                "emoji" -> TextButton({ scope.launch { runCatching { model.endToEnd()!!.confirmVerification(me, v.id, false) } } }, Modifier.testTag("verifyDifferent")) { Text(stringResource(R.string.chat_verifyDifferent)) }
                else -> {}
            }
        },
    )
}
