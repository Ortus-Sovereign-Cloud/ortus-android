package cc.ortus.app

import android.content.Context
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
        try { me = c.call("GET", "/me") } catch (e: ApiError) { if (e.status == 401) signOut() } catch (_: Exception) {}
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
            error = (e as? ApiError)?.message ?: e.message
        } finally { busy = false }
    }

    private val guarded: Boolean get() =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** Face or fingerprint before the key is used (where one is enrolled). */
    private suspend fun confirm(): Boolean {
        if (!guarded) return true
        return suspendCoroutine { done ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done.resume(true)
                override fun onAuthenticationError(code: Int, text: CharSequence) = done.resume(false)
            })
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(activity.getString(R.string.welcome_key))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())
        }
    }

    /** The phone becomes the person's key, then signs in with it. */
    suspend fun makeKey() {
        val c = client ?: return
        error = null
        busy = true
        try {
            val key = PhoneKey.make(context, guarded)
            if (!confirm()) return
            Passkey.register(c, key, context.getString(R.string.welcome_keyName))
            signInWithKey()
            stage = Stage.In
        } catch (e: Exception) {
            error = (e as? ApiError)?.message ?: e.message
        } finally { busy = false }
    }

    suspend fun signInWithKey() {
        val c = client ?: return
        val key = PhoneKey.load(context) ?: throw ApiError(0, "no_key", context.getString(R.string.welcome_noKey))
        if (!confirm()) throw ApiError(0, "cancelled", context.getString(R.string.welcome_noKey))
        me = Passkey.signIn(c, key, me?.id ?: c.call<Me>("GET", "/me").id)
        keep(c)
    }

    fun signOut() {
        Store.clear(context)
        client = null
        me = null
        stage = Stage.Join
    }
}
