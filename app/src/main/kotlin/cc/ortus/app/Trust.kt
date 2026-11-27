package cc.ortus.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import cc.ortus.kit.EscrowSigner
import cc.ortus.kit.VerificationParty
import cc.ortus.kit.nameOf
import org.ortus.e2e.DeviceRef
import org.ortus.e2e.QrImage
import org.ortus.e2e.VerificationView

/** The 64 emoji of Matrix's verification, by number (screen readers name them). */
val SAS_EMOJI = listOf(
    "🐶", "🐱", "🦁", "🐎", "🦄", "🐷", "🐘", "🐰", "🐼", "🐓", "🐧", "🐢", "🐟", "🐙", "🦋", "🌷",
    "🌳", "🌵", "🍄", "🌏", "🌙", "☁️", "🔥", "🍌", "🍎", "🍓", "🌽", "🍕", "🎂", "❤️", "😀", "🤖",
    "🎩", "👓", "🔧", "🎅", "👍", "☂️", "⌛", "⏰", "🎁", "💡", "📕", "✏️", "📎", "✂️", "🔒", "🔑",
    "🔨", "☎️", "🏁", "🚂", "🚲", "✈️", "🚀", "🏆", "⚽", "🎸", "🎺", "🔔", "⚓", "🎧", "📁", "📌",
)

/** The end-to-end library's refusals of an escrow key (its words, said here in the person's language). */
const val ESCROW_UNSIGNED = "The organisation's escrow key is not signed by an administrator: ask them to make it again."
const val ESCROW_NOT_ACCEPTED = "The organisation's escrow key is not the one you were shown: look at it again."

/**
 * Recovery on the phone: not set up yet, set up here (the recovery key is
 * shown once, to keep somewhere safe; first, the organisation's escrow key
 * that can recover the person's history too: its fingerprint, who set it
 * and whether the person verified them); set up, the recovery key brings this
 * phone's history and vouches for it; recovered, it can verify the
 * person's other device.
 */
@Composable
fun RecoveryDialog(model: Model, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var state by remember { mutableStateOf<org.ortus.e2e.RecoveryState?>(null) }
    var key by remember { mutableStateOf("") }
    // The new recovery key: on screen until the person says it is saved.
    // Never written anywhere: held in this process only, so that the
    // screen made again (a configuration change) still shows it.
    val once: ShownOnce = androidx.lifecycle.viewmodel.compose.viewModel()
    var made by once::key
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val me = model.chatMe
    val restored = stringResource(R.string.chat_recoveryRestored)
    val wrong = stringResource(R.string.chat_recoveryWrong)
    val label = stringResource(R.string.chat_recoveryKey)
    val unsigned = stringResource(R.string.chat_recoveryEscrowUnsigned)
    val notShown = stringResource(R.string.chat_recoveryEscrowNotShown)
    // The organisation's escrow key, as shown to the person: set-up seals the
    // recovery key to this one only. Set-up is offered once it is known
    // (none, or a signed one), never after an error.
    var escrow by remember { mutableStateOf<org.ortus.e2e.EscrowInfo?>(null) }
    var escrowKnown by remember { mutableStateOf(false) }
    fun escrowError(e: Exception): String = when {
        e is org.ortus.e2e.E2eException.Invalid && e.detail == ESCROW_UNSIGNED -> unsigned
        e is org.ortus.e2e.E2eException.Invalid && e.detail == ESCROW_NOT_ACCEPTED -> notShown
        e is org.ortus.e2e.E2eException.Invalid -> e.detail
        else -> model.say(e)
    }
    suspend fun loadEscrow() {
        escrow = null
        escrowKnown = false
        try {
            escrow = model.endToEnd()!!.escrowInfo(me!!)
            escrowKnown = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            note = escrowError(e)
        }
    }
    LaunchedEffect(me) {
        if (me != null) state = runCatching { model.endToEnd()!!.recoveryState(me) }.getOrNull()
        if (state?.set == false) loadEscrow()
    }
    val saved = { made = null; model.trustChanged++; close() }
    // A recovery key on screen (the new one, or one being typed): no
    // screenshots, screen recording or recents thumbnail of it.
    val secret = made != null || state?.let { it.set && !it.here } == true
    SecureWindow(secret)
    AlertDialog(
        // The new key is not lost by a tap outside or Back (nor while it is being made): only "I've saved it" closes.
        onDismissRequest = { if (made == null && !busy) close() },
        properties = DialogProperties(
            dismissOnBackPress = made == null && !busy,
            dismissOnClickOutside = made == null && !busy,
            securePolicy = if (secret) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
        ),
        modifier = Modifier.testTag("recovery"),
        title = { Text(stringResource(R.string.chat_recovery)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val s = state
                val k = made
                when {
                    k != null -> {
                        Text(stringResource(R.string.chat_recoveryNewKeyLead))
                        SelectionContainer {
                            Text(k, fontFamily = FontFamily.Monospace, fontSize = 16.sp, modifier = Modifier.testTag("recoveryNewKey"))
                        }
                        TextButton({ copySecret(context, label, k) }, Modifier.testTag("recoveryCopy")) { Text(stringResource(R.string.chat_recoveryCopy)) }
                    }
                    s == null -> Text("…")
                    !s.set -> {
                        Text(stringResource(R.string.chat_recoveryNoneLead))
                        escrow?.let { e ->
                            val signer = EscrowSigner.of(e.signer, e.signerStatus, me, model.chatPeople)
                            val you = stringResource(R.string.chat_escrowSignerYou)
                            val status = when (signer.status) {
                                EscrowSigner.Status.You -> you
                                EscrowSigner.Status.Verified -> stringResource(R.string.chat_escrowSignerVerified)
                                EscrowSigner.Status.Unverified -> stringResource(R.string.chat_escrowSignerUnverified)
                            }
                            Text(stringResource(R.string.chat_recoveryEscrowInfo, e.fingerprint, if (signer.isMe) you else signer.name, status), modifier = Modifier.testTag("recoveryEscrow"))
                            if (e.changed) Text(stringResource(R.string.chat_recoveryEscrowChanged), modifier = Modifier.testTag("recoveryEscrowChanged"))
                        }
                    }
                    !s.here -> {
                        Text(stringResource(R.string.chat_recoveryEnterLead))
                        // No autocorrect, suggestions or learning of what is typed: it unlocks everything.
                        OutlinedTextField(
                            key, { key = it }, Modifier.fillMaxWidth().testTag("recoveryKey"), label = { Text(label) },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                        )
                    }
                    else -> Text(stringResource(R.string.chat_recoveryHere))
                }
                note?.let { Text(it, modifier = Modifier.testTag("recoveryNote").semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        },
        confirmButton = {
            val s = state
            when {
                made != null -> Button(saved, Modifier.testTag("recoverySaved")) { Text(stringResource(R.string.chat_recoverySaved)) }
                s != null && !s.set && escrowKnown -> Button({
                    scope.launch {
                        busy = true
                        note = null
                        try {
                            made = model.endToEnd()!!.setUpRecovery(me!!, escrow?.key)
                            state = model.endToEnd()!!.recoveryState(me)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val said = escrowError(e)
                            // The escrow changed meanwhile: the one set-up would use now is shown.
                            if (e is org.ortus.e2e.E2eException.Invalid && e.detail == ESCROW_NOT_ACCEPTED) loadEscrow()
                            note = said
                        }
                        busy = false
                    }
                }, enabled = !busy, modifier = Modifier.testTag("recoverySetUp")) { Text(stringResource(R.string.chat_recoverySetUp)) }
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
        dismissButton = { if (made == null) TextButton(close, enabled = !busy) { Text(stringResource(R.string.chat_close)) } },
    )
}

/** The new recovery key while it is on screen: in memory, kept across the screen made again, gone with the activity. */
class ShownOnce : androidx.lifecycle.ViewModel() {
    var key by mutableStateOf<String?>(null)
}

/** How long a copied recovery key stays on the clipboard. */
const val SECRET_ON_CLIPBOARD_MS = 120_000L

/**
 * A secret on the clipboard: marked sensitive (not shown in the clipboard's
 * preview, Android 13 and later), and taken off again after two minutes
 * unless something else was copied since.
 */
fun copySecret(context: android.content.Context, label: String, secret: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    val clip = android.content.ClipData.newPlainText(label, secret)
    val sensitive = if (android.os.Build.VERSION.SDK_INT >= 33) android.content.ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
    clip.description.extras = android.os.PersistableBundle().apply { putBoolean(sensitive, true) }
    clipboard.setPrimaryClip(clip)
    // Not tied to the dialog, which closes long before. In the background
    // the clipboard may not be readable: then it is cleared anyway.
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        val now = runCatching { clipboard.primaryClipDescription }.getOrNull()
        if (now == null || now.label == label) runCatching { clipboard.clearPrimaryClip() }
    }, SECRET_ON_CLIPBOARD_MS)
}

/** The activity's window kept out of screenshots, recordings and recents while [on]. */
@Composable
fun SecureWindow(on: Boolean) {
    val context = LocalContext.current
    DisposableEffect(on) {
        var c: android.content.Context? = context
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        val window = (c as? android.app.Activity)?.window
        if (on) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (on) window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/**
 * A room's devices nobody vouched for: each by its person (their name when
 * known, else their id) and its device id, with "Verify", which asks that
 * device; the Verifications overlay takes over from there.
 */
@Composable
fun UnverifiedDialog(model: Model, devices: List<DeviceRef>, people: Map<String, cc.ortus.kit.Person>, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = close,
        modifier = Modifier.testTag("unverifiedList"),
        title = { Text(stringResource(R.string.chat_unverifiedTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.chat_unverifiedLead))
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(devices.size) { i ->
                        val d = devices[i]
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(people.nameOf(d.user) ?: d.user)
                                Text(stringResource(R.string.chat_unverifiedDevice, d.device), fontSize = 12.sp)
                            }
                            OutlinedButton({
                                val me = model.chatMe ?: return@OutlinedButton
                                scope.launch {
                                    busy = true
                                    note = null
                                    try {
                                        model.endToEnd()!!.verifyDevice(me, d.user, d.device)
                                        close()
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        note = model.say(e)
                                    }
                                    busy = false
                                }
                            }, enabled = !busy, modifier = Modifier.testTag("verify-${d.device}")) { Text(stringResource(R.string.chat_verifyDevice)) }
                        }
                    }
                }
                note?.let { Text(it, modifier = Modifier.testTag("unverifiedNote").semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        },
        confirmButton = { TextButton(close) { Text(stringResource(R.string.chat_close)) } },
    )
}

/** Who a verification is with: another person by name (when known) and id, or "this is you"; and the device. */
@Composable
fun VerificationWho(party: VerificationParty) {
    Column(Modifier.testTag("verifyWho").semantics(mergeDescendants = true) {}) {
        if (party.isMe) {
            Text(party.device?.let { stringResource(R.string.chat_verifyYouDevice, it) } ?: stringResource(R.string.chat_verifyYou))
        } else {
            Text(stringResource(R.string.chat_verifyWith, party.name ?: party.user))
            if (party.name != null) Text(party.user, fontSize = 12.sp)
            party.device?.let { Text(stringResource(R.string.chat_unverifiedDevice, it), fontSize = 12.sp) }
        }
    }
}

/**
 * A verification QR code: dark modules on white, with a white quiet zone
 * of four modules, in either theme; drawn sharp (one pixel a module, scaled
 * without smoothing).
 */
@Composable
fun QrPicture(image: QrImage, description: String, modifier: Modifier = Modifier) {
    val bitmap = remember(image) {
        val w = image.width.toInt()
        val side = w + 8
        val pixels = IntArray(side * side) { android.graphics.Color.WHITE }
        for (y in 0 until w) for (x in 0 until w) if (image.modules[y * w + x]) pixels[(y + 4) * side + x + 4] = android.graphics.Color.BLACK
        android.graphics.Bitmap.createBitmap(pixels, side, side, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(bitmap, description, modifier.size(240.dp), filterQuality = FilterQuality.None)
}

/**
 * Verifications, for the whole app: looked for every few seconds while the
 * app is in front and this phone's keys are open; an incoming one is asked
 * about; when both are ready the person chooses a QR code (scan the other
 * device's, or show this phone's) or emoji; emoji are compared, a scan of
 * this phone's code is confirmed, the end is said.
 */
@Composable
fun Verifications(model: Model) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var shown by remember { mutableStateOf<VerificationView?>(null) }
    var dismissed by remember { mutableStateOf(setOf<String>()) }
    // This phone's code, for the verification whose id it carries.
    var qr by remember { mutableStateOf<Pair<String, QrImage>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val camera = remember { hasCamera(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    suspend fun refresh() {
        val e2e = model.endToEnd()
        val me = model.chatMe
        if (e2e != null && me != null && e2e.isOpen) {
            val list = runCatching { e2e.verifications(me) }.getOrDefault(emptyList())
            val current = list.lastOrNull { it.id !in dismissed && it.state != "cancelled" } ?: list.lastOrNull { it.id == shown?.id }
            if (current?.state == "done" && shown?.state != "done") model.trustChanged++
            if (current?.id != shown?.id) note = null
            shown = current?.takeIf { it.id !in dismissed }
        }
    }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                refresh()
                delay(3_000)
            }
        }
    }
    val v = shown ?: return
    val me = model.chatMe ?: return
    val e2e = model.endToEnd() ?: return
    val close = { dismissed = dismissed + v.id; shown = null; scanning = false; note = null }
    val wrongCode = stringResource(R.string.chat_verifyScanWrong)
    /** One step of the verification: its error said, then where it stands now. */
    fun act(step: suspend () -> Unit) {
        scope.launch {
            note = null
            try {
                step()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                note = model.say(e)
            }
            refresh()
        }
    }
    val cancel = { act { e2e.cancelVerification(me, v.id) }; close() }
    // This phone's code, once it is to be shown (also when the poll found it so).
    LaunchedEffect(v.id, v.state) {
        if (v.state == "qr" && qr?.first != v.id) runCatching { e2e.showQr(me, v.id) }.onSuccess { qr = v.id to it }.onFailure { note = model.say(it) }
    }
    AlertDialog(
        onDismissRequest = {
            if (v.state != "done" && v.state != "cancelled") scope.launch { runCatching { e2e.cancelVerification(me, v.id) } }
            close()
        },
        modifier = Modifier.testTag("verification"),
        title = { Text(stringResource(R.string.chat_verifyTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                // Who asks, on every step: the person (or oneself) and the device.
                VerificationWho(VerificationParty.of(v.user, v.device, me, model.chatPeople))
                when (v.state) {
                    "incoming" -> Text(stringResource(R.string.chat_verifyIncoming))
                    "ready" -> {
                        Text(stringResource(R.string.chat_verifyChoose))
                        if (v.canScan && camera) OutlinedButton({ scanning = true }, Modifier.fillMaxWidth().testTag("verifyScan")) { Text(stringResource(R.string.chat_verifyScan)) }
                        if (v.canShow) OutlinedButton({
                            act { val image = e2e.showQr(me, v.id); qr = v.id to image }
                        }, Modifier.fillMaxWidth().testTag("verifyShow")) { Text(stringResource(R.string.chat_verifyShow)) }
                        OutlinedButton({ act { e2e.startEmoji(me, v.id) } }, Modifier.fillMaxWidth().testTag("verifyByEmoji")) { Text(stringResource(R.string.chat_verifyEmoji)) }
                    }
                    "qr" -> {
                        Text(stringResource(R.string.chat_verifyShowLead))
                        qr?.takeIf { it.first == v.id }?.let { (_, image) ->
                            QrPicture(image, stringResource(R.string.chat_verifyQr), Modifier.align(Alignment.CenterHorizontally).testTag("verifyQr"))
                        } ?: Text("…")
                    }
                    "scanned" -> Text(stringResource(R.string.chat_verifyScanned))
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
                note?.let { Text(it, modifier = Modifier.testTag("verifyNote")) }
            }
        },
        confirmButton = {
            when (v.state) {
                "incoming" -> Button({ act { e2e.acceptVerification(me, v.id) } }, Modifier.testTag("verifyAccept")) { Text(stringResource(R.string.chat_verifyAccept)) }
                "emoji" -> Button({ act { e2e.confirmVerification(me, v.id, true) } }, Modifier.testTag("verifySame")) { Text(stringResource(R.string.chat_verifySame)) }
                "qr" -> TextButton({ act { e2e.startEmoji(me, v.id) } }, Modifier.testTag("verifyEmojiInstead")) { Text(stringResource(R.string.chat_verifyEmojiInstead)) }
                "scanned" -> Button({ act { e2e.confirmScanned(me, v.id, true) } }, Modifier.testTag("verifyScannedYes")) { Text(stringResource(R.string.chat_verifyYes)) }
                "ready" -> TextButton(cancel, Modifier.testTag("verifyCancel")) { Text(stringResource(R.string.chat_verifyCancel)) }
                else -> TextButton(close) { Text(stringResource(R.string.chat_close)) }
            }
        },
        dismissButton = {
            when (v.state) {
                "incoming" -> TextButton({ scope.launch { runCatching { e2e.cancelVerification(me, v.id) } }; close() }, Modifier.testTag("verifyDecline")) { Text(stringResource(R.string.chat_verifyDecline)) }
                "emoji" -> TextButton({ act { e2e.confirmVerification(me, v.id, false) } }, Modifier.testTag("verifyDifferent")) { Text(stringResource(R.string.chat_verifyDifferent)) }
                "scanned" -> TextButton({ act { e2e.confirmScanned(me, v.id, false) } }, Modifier.testTag("verifyScannedNo")) { Text(stringResource(R.string.chat_verifyNo)) }
                "qr" -> TextButton(cancel, Modifier.testTag("verifyCancel")) { Text(stringResource(R.string.chat_verifyCancel)) }
                else -> {}
            }
        },
    )
    // The camera, over the dialog: the other device's code goes to the library as read.
    if (scanning) VerificationScanner(found = { bytes ->
        scanning = false
        act {
            try {
                e2e.scanQr(me, v.id, bytes)
            } catch (e: org.ortus.e2e.E2eException.Invalid) {
                note = wrongCode
            }
        }
    }, close = { scanning = false })
}
