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
import cc.ortus.kit.ApiError
import cc.ortus.kit.Client
import cc.ortus.kit.Me
import cc.ortus.kit.Passkey
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

enum class Stage { Join, Welcome, In }

/** Where the app is, and the signed-in client. */
class Model(private val activity: FragmentActivity) {
    var stage by mutableStateOf(Stage.Join)
    var me by mutableStateOf<Me?>(null)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var client: Client? = null
        private set
    private val context: Context get() = activity

    init {
        val server = Store.get(context, "server")
        val token = Store.get(context, "session")
        if (server != null && token != null) {
            client = Client(server, token, OkTransport)
            stage = Stage.In
        }
    }

    suspend fun refresh() {
        val c = client ?: return
        try { me = c.call("GET", "/me") } catch (e: ApiError) { if (e.status == 401) expired() } catch (_: Exception) {}
    }

    private fun keep(c: Client) {
        Store.put(context, "server", c.server)
        Store.put(context, "session", c.token)
    }

    suspend fun join(typed: String, code: String) {
        error = null
        val server = Client.serverUrl(typed) ?: run { error = context.getString(R.string.join_badServer); return }
        busy = true
        try {
            val c = Client(server, transport = OkTransport)
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

    /** What to tell the person about a failure, in their language. */
    fun say(e: Throwable): String = when {
        e is PhoneKey.NoLock -> context.getString(R.string.key_noLock)
        e is KeyPermanentlyInvalidatedException -> context.getString(R.string.key_invalidated)
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
            val key = PhoneKey.make(context).also { made = it }
            if (!confirm()) {
                PhoneKey.drop(key)
                error = context.getString(R.string.welcome_noKey)
                return
            }
            Passkey.register(c, key, context.getString(R.string.welcome_keyName))
            PhoneKey.keep(context, key)
            made = null
            signInWithKey()
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
            val c = Client(server, transport = OkTransport)
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
            val c = Client(server, transport = OkTransport)
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
        client?.let { c -> runCatching { c.raw("POST", "/session/sign-out", kotlinx.serialization.json.JsonObject(emptyMap())) } }
        Store.put(context, "session", null)
        client = null
        me = null
        stage = Stage.Join
    }
}
