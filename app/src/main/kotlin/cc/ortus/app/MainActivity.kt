package cc.ortus.app

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import android.os.Bundle
import cc.ortus.kit.InvitationLink
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

val Background = Color(0xFF0B0D12)
val Surface = Color(0xFF13161C)
val Surface2 = Color(0xFF1C1F27)
val Text1 = Color(0xFFEDF0F5)
val Text2 = Color(0xFF9EA6B5)
val Accent = Color(0xFF8C9EFF)
val Teal = Color(0xFF4FD1C5)

class MainActivity : FragmentActivity() {
    private var model: Model? = null

    /** A call is on screen: leaving the app keeps it in a small window. */
    var inCall = false

    /** Whether the screen is the small picture-in-picture window now. */
    var pip by mutableStateOf(false)
        private set

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (inCall && android.os.Build.VERSION.SDK_INT >= 26) {
            runCatching { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(3, 4)).build()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip = isInPictureInPictureMode
    }

    /** Back in the app: the bell counts again (read elsewhere, or new). */
    override fun onResume() {
        super.onResume()
        model?.let { m -> lifecycleScope.launch { m.countUnread() } }
    }

    /** An invitation link while the app is open. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { model?.open(it) }
        intent.getStringExtra("path")?.let { model?.tab = cc.ortus.kit.Push.tab(it) }
        model?.answer(intent)
    }

    /** The installation an organisation's device management set, if any. */
    private fun managedServer(): String? =
        (getSystemService(RESTRICTIONS_SERVICE) as? android.content.RestrictionsManager)
            ?.applicationRestrictions?.getString("server")?.trim()?.takeIf { it.isNotEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The launch link and the test reset belong to the first start only:
        // when the screen is made again (a rotation, a configuration change),
        // a used one-time code must not be tried again, nor the key forgotten.
        val first = savedInstanceState == null
        // Test builds only: another app must not wipe the key or name a server.
        if (BuildConfig.DEBUG && first && intent.getBooleanExtra("reset", false)) { PhoneKey.forget(this); Store.clear(this) }
        val model = Model(this)
        this.model = model
        if (first) intent?.dataString?.let { model.open(it) }
        if (first) intent?.getStringExtra("path")?.let { model.tab = cc.ortus.kit.Push.tab(it) }
        if (first) model.answer(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Background, surface = Surface, onBackground = Text1, onSurface = Text1)) {
                Surface(Modifier.fillMaxSize(), color = Background) {
                    when (model.stage) {
                        Stage.Join -> JoinScreen(model,
                            intent.getStringExtra("server")?.takeIf { BuildConfig.DEBUG } ?: managedServer() ?: Store.get(this, "server") ?: "",
                            intent.getStringExtra("code")?.takeIf { BuildConfig.DEBUG } ?: "")
                        Stage.Welcome -> WelcomeScreen(model)
                        Stage.In -> Tabs(model)
                    }
                }
            }
        }
    }
}

@Composable
fun Mark() {
    Column(Modifier.padding(bottom = 16.dp)) {
        Row { Box(Modifier.size(22.dp, 20.dp).padding(end = 2.dp)) { Surface(color = Accent, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxSize()) {} }
              Box(Modifier.size(22.dp, 20.dp)) { Surface(color = Accent, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxSize()) {} } }
        Spacer(Modifier.height(4.dp))
        Surface(color = Teal, shape = MaterialTheme.shapes.small, modifier = Modifier.size(46.dp, 5.dp)) {}
    }
}

@Composable
fun JoinScreen(model: Model, server0: String, code0: String) {
    var server by remember { mutableStateOf(server0) }
    var code by remember { mutableStateOf(code0) }
    var withPassword by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    if (scanning) CodeScanner(found = { scanning = false; model.open(it) }, close = { scanning = false })
    // An invitation handed to the app fills in what it joins with.
    LaunchedEffect(model.handed) {
        model.handed?.takeIf { it.kind == InvitationLink.Kind.Invite }?.let { server = it.server; code = it.code; withPassword = false }
    }
    Column(Modifier.fillMaxSize().imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Mark()
        model.confirming?.let { link ->
            val host = runCatching { java.net.URI(link.server).let { u -> u.host + if (u.port > 0) ":${u.port}" else "" } }.getOrDefault(link.server)
            Surface(color = Surface2, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("confirm")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.join_confirm, host), color = Text1, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.join_confirmLead), color = Text2, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ model.confirmLink() }, Modifier.testTag("confirmLink")) { Text(stringResource(R.string.join_continue)) }
                        TextButton({ model.confirming = null }) { Text(stringResource(R.string.compose_cancel)) }
                    }
                }
            }
        }
        Text(stringResource(R.string.join_title), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Text1)
        Text(stringResource(R.string.join_lead), color = Text2)
        OutlinedTextField(server, { server = it }, label = { Text(stringResource(R.string.join_server)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("server"))
        if (!withPassword) {
            OutlinedTextField(code, { code = it }, label = { Text(stringResource(R.string.join_code)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("code"))
            // The invitation's QR code, read by the camera.
            if (hasCamera(context)) OutlinedButton({ scanning = true }, enabled = !model.busy, modifier = Modifier.fillMaxWidth().testTag("scan")) {
                Text(stringResource(R.string.join_scan))
            }
        } else if (model.ticket == null) {
            OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.join_email)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().testTag("email"))
            OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.join_password)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().testTag("password"))
        } else {
            OutlinedTextField(totp, { totp = it }, label = { Text(stringResource(R.string.join_totp)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth().testTag("totp"))
        }
        model.error?.let { Text(it, color = Color(0xFFFF7A6E), modifier = Modifier.testTag("error")) }
      }
        when {
            !withPassword -> Button({ scope.launch { model.join(server, code) } }, enabled = !model.busy && server.isNotBlank() && code.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.join_continue))
            }
            model.ticket == null -> Button({ scope.launch { model.signInWithPassword(server, email, password) } }, enabled = !model.busy && server.isNotBlank() && email.contains("@") && password.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp).testTag("signIn")) {
                Text(stringResource(R.string.join_signIn))
            }
            else -> Button({ scope.launch { model.signInWithCode(totp) } }, enabled = !model.busy && totp.length >= 6, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("signInCode")) {
                Text(stringResource(R.string.join_signIn))
            }
        }
        // The phone is already their key: back in without a new invitation.
        if (model.hasKey) OutlinedButton({ scope.launch { model.signInAgain(server) } }, enabled = !model.busy && server.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp).testTag("withPhone")) {
            Text(stringResource(R.string.join_withPhone))
        }
        TextButton({ withPassword = !withPassword; model.ticket = null; model.error = null }, Modifier.fillMaxWidth().testTag("togglePassword")) {
            Text(stringResource(if (withPassword) R.string.join_withCode else R.string.join_withPassword))
        }
    }
}

@Composable
private fun Step(done: Boolean, n: Int, title: Int, detail: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(color = if (done) Teal.copy(alpha = .15f) else Accent, shape = MaterialTheme.shapes.small, modifier = Modifier.size(32.dp)) {
            Box(contentAlignment = androidx.compose.ui.Alignment.Center) { Text(if (done) "✓" else "$n", color = if (done) Teal else Background, fontWeight = FontWeight.Bold) }
        }
        Column { Text(stringResource(title), fontWeight = FontWeight.SemiBold, color = Text1); Text(stringResource(detail), color = Text2, fontSize = 14.sp) }
    }
}

@Composable
fun WelcomeScreen(model: Model) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
      // The steps scroll on a short screen; the button stays in reach.
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Mark()
        Text(stringResource(R.string.welcome_title, model.me?.name?.substringBefore(' ') ?: ""), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Text1, modifier = Modifier.testTag("welcome"))
        Step(true, 1, R.string.welcome_code, R.string.welcome_codeDetail)
        Step(true, 2, R.string.welcome_face, R.string.welcome_faceDetail)
        Step(false, 3, R.string.welcome_key, R.string.welcome_keyDetail)
        Surface(color = Surface, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.welcome_seeTitle), fontWeight = FontWeight.SemiBold, color = Text1, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.welcome_seeYes), color = Teal, fontSize = 14.sp)
                Text(stringResource(R.string.welcome_seeNo), color = Text2, fontSize = 14.sp)
            }
        }
        model.error?.let { Text(it, color = Color(0xFFFF7A6E), modifier = Modifier.testTag("error")) }
      }
        Button({ scope.launch { model.makeKey() } }, enabled = !model.busy, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("makeKey")) {
            Text(stringResource(R.string.welcome_makeKey))
        }
        Text(stringResource(R.string.welcome_lost), color = Text2, fontSize = 12.sp)
    }
}
