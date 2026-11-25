package cc.ortus.app

import android.content.Context
import cc.ortus.kit.ChatMessage
import cc.ortus.kit.Client
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.ortus.e2e.E2eException
import org.ortus.e2e.HttpResponse
import org.ortus.e2e.OrtusE2e
import org.ortus.e2e.Transport
import org.ortus.e2e.TransportException
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * End-to-end rooms on this phone, through ortus-chat's phones/e2e library:
 * this device's keys in a store of its own (in noBackupFilesDir, encrypted
 * with a random passphrase that the Keystore seals), opened for the person
 * signed in. Every call blocks while it talks to the server, so all of
 * them run on one thread of their own.
 *
 * The store, its passphrase and the device id belong together: when the
 * store cannot be opened (its passphrase is lost with the Keystore key, a
 * restored phone), this phone becomes a new device, as an old id with new
 * keys would be refused by the others.
 */
class EndToEnd(private val context: Context, private val client: Client) {
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "ortus-e2e").apply { isDaemon = true } }.asCoroutineDispatcher()
    private var library: OrtusE2e? = null
    /** What was opened, by message id: opening a message again is not needed. */
    private val opened = HashMap<String, String>()

    private suspend fun <T> on(block: () -> T): T = withContext(thread) { block() }

    /** The library's HTTP, signed in as the person: never answered from the phone's cache nor queued offline. */
    private class Http(private val client: Client) : Transport {
        override fun http(method: String, path: String, body: String?): HttpResponse = try {
            val bytes = body?.toByteArray()
            val (status, data) = OkTransport.send(method, "${client.server}/api/v1$path", client.headers(method, bytes != null), bytes)
            HttpResponse(status.toUShort(), String(data))
        } catch (e: Exception) {
            throw TransportException.Failed(e.message ?: e.toString())
        }
    }

    private fun fresh(): String {
        val b = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING or android.util.Base64.URL_SAFE)
    }

    /** A device id: letters and digits, as the server takes them. */
    private fun newDevice(): String {
        val letters = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return "PHONE" + (1..10).map { letters[r.nextInt(letters.length)] }.joinToString("")
    }

    /** `@{user}:{serverName}`, as `me` of the rooms says. */
    private fun parse(me: String): Pair<String, String> {
        val at = me.removePrefix("@")
        val colon = at.indexOf(':')
        require(colon > 0) { "not a Matrix id: $me" }
        return at.substring(0, colon) to at.substring(colon + 1)
    }

    private fun open(me: String): OrtusE2e {
        library?.let { return it }
        val (user, server) = parse(me)
        val dir = File(context.noBackupFilesDir, "e2e/$user")
        val keyDevice = "e2e.device.$user"
        val keyPass = "e2e.passphrase.$user"
        fun begin(): Pair<String, String> {
            dir.deleteRecursively()
            dir.mkdirs()
            val pair = newDevice() to fresh()
            Store.put(context, keyDevice, pair.first)
            Store.put(context, keyPass, pair.second)
            return pair
        }
        val kept = Store.get(context, keyDevice)?.let { d -> Store.get(context, keyPass)?.let { d to it } }
        var (device, pass) = if (kept != null && dir.isDirectory) kept else begin()
        val made = try {
            OrtusE2e.open(dir.path, pass, user, device, server, Http(client))
        } catch (e: E2eException.Store) {
            // The keys cannot be read: a new device.
            begin().let { (d, p) -> device = d; pass = p }
            OrtusE2e.open(dir.path, pass, user, device, server, Http(client))
        }
        library = made
        return made
    }

    /**
     * Each message with its text, or null when it cannot be opened (a room
     * key that has not come, a sender refused). Room keys waiting for this
     * device are taken in first.
     */
    suspend fun read(me: String, room: String, messages: List<ChatMessage>): Map<String, String?> = on {
        val e2e = open(me)
        runCatching { e2e.inbox() }.onFailure { android.util.Log.w("ortus-e2e", "inbox: $it") }
        messages.associate { m ->
            m.id to (opened[m.id] ?: if (m.sealed == null) null else runCatching { e2e.decrypt(room, m.sealedJson()).text }
                // Why it did not open (the library's reason; never any text).
                .onFailure { android.util.Log.w("ortus-e2e", "${m.id} not opened: $it") }
                .getOrNull()?.also { opened[m.id] = it })
        }
    }

    /** Seals and sends: the message's id. */
    suspend fun send(me: String, room: String, text: String): String = on {
        runCatching { open(me).send(room, text, null) }.onFailure { android.util.Log.w("ortus-e2e", "send: $it") }.getOrThrow()
    }

    /** Signing out: the server drops this device, the phone its keys. */
    suspend fun forget(me: String?) = on {
        // Opened only if this phone has keys for the person: none, nothing to forget.
        val has = me?.let { Store.get(context, "e2e.device.${parse(it).first}") } != null
        runCatching { (library ?: me?.takeIf { has }?.let { open(it) })?.forget() }
        library = null
        opened.clear()
        me?.let { parse(it).first }?.let { user ->
            File(context.noBackupFilesDir, "e2e/$user").deleteRecursively()
            Store.put(context, "e2e.device.$user", null)
            Store.put(context, "e2e.passphrase.$user", null)
        }
        Unit
    }
}
