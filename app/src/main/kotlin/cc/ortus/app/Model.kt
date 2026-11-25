package cc.ortus.app

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import cc.ortus.kit.rooms
import cc.ortus.kit.ApiError
import cc.ortus.kit.Discovery
import cc.ortus.kit.InvitationLink
import cc.ortus.kit.Client
import cc.ortus.kit.Me
import cc.ortus.kit.Passkey
import cc.ortus.kit.unregisterPush
import cc.ortus.kit.unreadNotices
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlin.coroutines.suspendCoroutine

enum class Stage { Join, Welcome, In }

/** Bumped by each push that arrives while the app runs (the bell counts again). */
object Pushes { val arrived = kotlinx.coroutines.flow.MutableStateFlow(0) }

/** Where the app is, and the signed-in client. */
class Model(private val activity: FragmentActivity) {
    var stage by mutableStateOf(Stage.Join)
    var me by mutableStateOf<Me?>(null)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    /** The open tab (a notification's tap chooses it). */
    var tab by mutableStateOf(0)
    /** A call answered from its ringing notification: (room id, title). */
    var answered by mutableStateOf<Pair<String, String>?>(null)

    /** Takes in a call the person answered (the ringing stops). */
    fun answer(intent: android.content.Intent?) {
        val room = intent?.getStringExtra("callRoom") ?: return
        context.getSystemService(android.app.NotificationManager::class.java).cancel(intent.getIntExtra("callNotice", 0))
        answered = room to (intent.getStringExtra("callTitle") ?: "")
    }
    /** The installation could not be reached: screens show what was kept. */
    var offline by mutableStateOf(false)
    /** Messages written offline that wait to be sent. */
    var waiting by mutableStateOf(0)

    fun queuedText(): String = context.getString(R.string.offline_queued)

    /** Messages written without a connection (no-backup storage: kept until sent). */
    private fun outbox(server: String) = cc.ortus.kit.Outbox(java.io.File(context.noBackupFilesDir, "ortus-outbox-" + cc.ortus.kit.sha256(server.toByteArray()).take(8).joinToString("") { "%02x".format(it) } + ".json"))

    /** The answers this phone keeps for an installation (app cache: never backed up). */
    private fun cache(server: String) = cc.ortus.kit.ResponseCache(java.io.File(context.cacheDir, "ortus-" + cc.ortus.kit.sha256(server.toByteArray()).take(8).joinToString("") { "%02x".format(it) }))

    /** A client whose reads are kept, and answered from the phone when the installation cannot be reached. */
    private fun makeClient(server: String, token: String? = null): Client {
        val transport = cc.ortus.kit.CachingTransport(OkTransport, cache(server)) { reached ->
            activity.runOnUiThread { if (offline != !reached) offline = !reached }
        }
        transport.outbox = outbox(server).also { box ->
            box.changed = { n -> activity.runOnUiThread { waiting = n } }
            waiting = box.count
        }
        return Client(server, token, transport)
    }
    var client: Client? = null
        private set

    private var e2e: EndToEnd? = null
    private var e2eClient: Client? = null
    /** End-to-end rooms for the person signed in, made when first needed. */
    fun endToEnd(): EndToEnd? {
        val c = client ?: return null
        if (e2eClient !== c) {
            e2e = EndToEnd(context, c)
            e2eClient = c
        }
        return e2e
    }
    /** The person's Matrix id, `me` of the rooms (end-to-end rooms need it). */
    var chatMe: String? = null
    private val context: Context get() = activity

    init {
        val server = Store.get(context, "server")
        val token = Store.get(context, "session")
        if (server != null && token != null) {
            client = makeClient(server, token)
            stage = Stage.In
            startPush()
        }
    }

    /** Unread notifications, as the web's bell counts them (the top bar's badge). */
    var unread by mutableStateOf(0)

    /** Counts them again: on resume, on a push, after reading them. */
    suspend fun countUnread() {
        val c = client ?: return
        if (stage != Stage.In) return
        runCatching { c.unreadNotices() }.onSuccess { unread = it }
    }

    suspend fun refresh() {
        val c = client ?: return
        try { me = c.call("GET", "/me") } catch (e: ApiError) { if (e.status == 401) expired() } catch (_: Exception) {}
    }

    private fun keep(c: Client) {
        Store.put(context, "server", c.server)
        Store.put(context, "session", c.token)
        startPush()
    }

    /**
     * Notifications while the app is closed: asks once (Android 13 and
     * later), then registers with the phone's UnifiedPush distributor, if it
     * has one; the receiver registers the endpoint with the installation.
     */
    fun startPush() {
        if (activity.intent?.getBooleanExtra("reset", false) == true) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        val distributors = org.unifiedpush.android.connector.UnifiedPush.getDistributors(context)
        val chosen = org.unifiedpush.android.connector.UnifiedPush.getAckDistributor(context) ?: distributors.firstOrNull() ?: return
        org.unifiedpush.android.connector.UnifiedPush.saveDistributor(context, chosen)
        org.unifiedpush.android.connector.UnifiedPush.registerApp(context)
    }

    /** Before the session ends: the installation and the distributor forget this phone. */
    private suspend fun stopPush() {
        val registration = Store.get(context, "push")?.let { runCatching { cc.ortus.kit.json.decodeFromString(cc.ortus.kit.PushRegistration.serializer(), it) }.getOrNull() }
        if (registration != null) client?.let { c -> runCatching { c.unregisterPush(registration.id) } }
        Store.put(context, "push", null)
        runCatching { org.unifiedpush.android.connector.UnifiedPush.unregisterApp(context) }
    }

    /** What the last new message did ("Sent."), shown in Mail. */
    var mailNote by mutableStateOf<String?>(null)

    /** The invitation the app was last handed (its link). */
    var handed by mutableStateOf<InvitationLink?>(null)

    /** An invitation handed to the app: when no one is signed in here, its person joins at once. */
    fun open(text: String) {
        val invitation = InvitationLink.parse(text) ?: run {
            if (stage == Stage.Join) error = context.getString(R.string.join_badLink)
            return
        }
        if (stage != Stage.Join || busy) return
        handed = invitation
        // A link may come from anyone: the person sees which installation
        // it signs them into and says yes first.
        confirming = invitation
    }

    /** A link waiting for the person's yes (the installation it names is shown). */
    var confirming by mutableStateOf<InvitationLink?>(null)

    fun confirmLink() {
        val invitation = confirming ?: return
        confirming = null
        activity.lifecycleScope.launch {
            when (invitation.kind) {
                InvitationLink.Kind.Invite -> join(invitation.server, invitation.code)
                InvitationLink.Kind.Phone -> signInWithPhoneLink(invitation.server, invitation.code)
            }
        }
    }

    /** "Sign in on your phone" from the web: signed in; a phone that is not yet their key becomes it next. */
    suspend fun signInWithPhoneLink(typed: String, code: String) {
        error = null
        val server = Client.serverUrl(typed) ?: run { error = context.getString(R.string.join_badServer); return }
        busy = true
        try {
            val c = makeClient(server)
            me = Passkey.phoneLink(c, code)
            client = c
            keep(c)
            stage = if (hasKey) Stage.In else Stage.Welcome
        } catch (e: Exception) {
            error = say(e)
        } finally { busy = false }
    }

    suspend fun join(typed0: String, code: String) {
        error = null
        // A work email instead of the address: the organisation names its installation.
        var typed = typed0
        if (typed.contains("@")) {
            busy = true
            val found = Discovery.find(typed, OkTransport)
            busy = false
            typed = found ?: run { error = context.getString(R.string.join_notFound); return }
        }
        val server = Client.serverUrl(typed) ?: run { error = context.getString(R.string.join_badServer); return }
        busy = true
        try {
            val c = makeClient(server)
            me = Passkey.join(c, code)
            client = c
            keep(c)
            stage = Stage.Welcome
        } catch (e: Exception) {
            error = say(e)
        } finally { busy = false }
    }

    /** The phone's lock before the key is used (none on a debug build without one). */
    private suspend fun confirm(): Boolean {
        if (!PhoneKey.locked(context)) return BuildConfig.DEBUG
        return suspendCoroutine { done ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done.resume(true)
                override fun onAuthenticationError(code: Int, text: CharSequence) = done.resume(false)
            })
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(activity.getString(R.string.welcome_key))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())
        }
    }

    private fun step(what: String) { android.util.Log.i("ortus", "key: $what") }

    /** What to tell the person about a failure, in their language. */
    fun say(e: Throwable): String = when {
        e is PhoneKey.NoLock -> context.getString(R.string.key_noLock)
        e is KeyPermanentlyInvalidatedException -> context.getString(R.string.key_invalidated)
        e is ApiError && e.code == "phone_link_expired" -> context.getString(R.string.join_phoneLinkExpired)
        e is ApiError && e.status > 0 -> e.message ?: context.getString(R.string.error_generic)
        e is ApiError && e.code == "no_key" -> context.getString(R.string.welcome_noKey)
        e is java.io.IOException -> context.getString(R.string.error_network)
        else -> context.getString(R.string.error_generic)
    }

    /** The phone becomes the person's key, then signs in with it. */
    suspend fun makeKey() {
        val c = client ?: return
        error = null
        busy = true
        var made: PhoneKey? = null
        try {
            step("make the key (phone locked: ${PhoneKey.locked(context)})")
            val key = PhoneKey.make(context).also { made = it }
            step("confirm")
            if (!confirm()) {
                PhoneKey.drop(key)
                error = context.getString(R.string.welcome_noKey)
                return
            }
            step("register")
            Passkey.register(c, key, context.getString(R.string.welcome_keyName))
            PhoneKey.keep(context, key)
            made = null
            step("sign in with it")
            signInWithKey()
            step("signed in")
            stage = Stage.In
        } catch (e: Exception) {
            made?.let { PhoneKey.drop(it) }
            android.util.Log.w("ortus", "making the key failed", e)
            error = say(e)
        } finally { busy = false }
    }

    suspend fun signInWithKey() {
        val c = client ?: return
        val key = PhoneKey.load(context) ?: throw ApiError(0, "no_key", context.getString(R.string.welcome_noKey))
        if (!confirm()) throw ApiError(0, "cancelled", context.getString(R.string.welcome_noKey))
        try {
            me = Passkey.signIn(c, key, me?.id)
        } catch (e: KeyPermanentlyInvalidatedException) {
            // New fingerprints or face: the key is gone for good; make another.
            PhoneKey.forget(context)
            stage = Stage.Welcome
            throw e
        }
        keep(c)
    }

    /** The session ended on the server: sign in again (the key stays). */
    private fun expired() {
        client?.let { cache(it.server).clear(); outbox(it.server).clear() }
        Store.put(context, "session", null)
        client = null
        me = null
        stage = Stage.Join
    }

    /** The ticket between the password and the one-time code. */
    var ticket by mutableStateOf<String?>(null)
    private var pending: Client? = null

    /** Signing in with a password: then the code from the authenticator app. */
    suspend fun signInWithPassword(typed: String, email: String, password: String) {
        error = null
        val server = Client.serverUrl(typed) ?: run { error = context.getString(R.string.join_badServer); return }
        busy = true
        try {
            val c = makeClient(server)
            ticket = Passkey.password(c, email, password)
            pending = c
        } catch (e: Exception) {
            error = if (e is ApiError && e.status == 401) context.getString(R.string.join_wrong) else say(e)
        } finally { busy = false }
    }

    suspend fun signInWithCode(code: String) {
        val c = pending ?: return
        val t = ticket ?: return
        error = null
        busy = true
        try {
            me = Passkey.code(c, t, code)
            client = c
            keep(c)
            ticket = null
            pending = null
            // A phone that is not yet their key becomes it next.
            stage = if (hasKey) Stage.In else Stage.Welcome
        } catch (e: Exception) {
            if (e is ApiError && e.code == "ticket_expired") ticket = null
            error = say(e)
        } finally { busy = false }
    }

    /** The phone holds a key of an account (it signs in with it). */
    val hasKey: Boolean get() = PhoneKey.load(context) != null

    /** Signs in again with the phone's key (after signing out, or a session that ended). */
    suspend fun signInAgain(typed: String) {
        error = null
        val server = Client.serverUrl(typed) ?: run { error = context.getString(R.string.join_badServer); return }
        busy = true
        try {
            val c = makeClient(server)
            client = c
            me = null
            signInWithKey()
            stage = Stage.In
        } catch (e: Exception) {
            client = null
            error = say(e)
        } finally { busy = false }
    }

    /**
     * Ends the session on the server too. The key stays: the phone is
     * still the person's key, and signs in again with it (an administrator
     * turns off a lost phone's key).
     */
    suspend fun signOut() {
        stopPush()
        // End-to-end rooms: the server drops this device, the phone its keys.
        runCatching {
            val me = chatMe ?: client?.let { c -> runCatching { c.rooms().me }.getOrNull() }
            endToEnd()?.forget(me)
        }
        chatMe = null
        client?.let { c -> runCatching { c.raw("POST", "/session/sign-out", kotlinx.serialization.json.JsonObject(emptyMap())) } }
        client?.let { cache(it.server).clear(); outbox(it.server).clear() }
        Store.put(context, "session", null)
        client = null
        me = null
        stage = Stage.Join
    }
}
