package cc.ortus.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import cc.ortus.kit.Client
import cc.ortus.kit.Push
import cc.ortus.kit.PushRegistration
import cc.ortus.kit.json
import cc.ortus.kit.registerPush
import kotlinx.coroutines.runBlocking
import org.unifiedpush.android.connector.MessagingReceiver

/**
 * UnifiedPush: the distributor gives an endpoint (registered with the
 * installation) and hands over pushes; each names only a notification,
 * whose text is fetched with the fetch token and shown. Without it the
 * notification says only "Something new in Ortus".
 */
class PushReceiver : MessagingReceiver() {
    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        val done = goAsync()
        Thread {
            try {
                val server = Store.get(context, "server") ?: return@Thread
                val token = Store.get(context, "session") ?: return@Thread
                val offset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
                // Off on this installation (or refused): nothing to keep.
                val r = runCatching { runBlocking { Client(server, token, OkTransport).registerPush(endpoint, offset) } }.getOrNull() ?: return@Thread
                Store.put(context, "push", json.encodeToString(PushRegistration.serializer(), r))
            } finally {
                done.finish()
            }
        }.start()
    }

    override fun onUnregistered(context: Context, instance: String) {
        Store.put(context, "push", null)
    }

    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        val (id, server) = Push.parts(message) ?: return
        val done = goAsync()
        Thread {
            try {
                val registration = Store.get(context, "push")?.let { runCatching { json.decodeFromString(PushRegistration.serializer(), it) }.getOrNull() }
                val ours = Store.get(context, "server") == server
                val notice = if (registration != null && ours) {
                    runCatching { runBlocking { Push.fetch(server, registration.fetch_token, id, OkTransport) } }.getOrNull()
                } else null
                show(context, id, notice?.title ?: context.getString(R.string.push_new), notice?.detail.orEmpty(), notice?.path)
            } finally {
                done.finish()
            }
        }.start()
    }

    private fun show(context: Context, id: String, title: String, text: String, path: String?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.push_channel), NotificationManager.IMPORTANCE_HIGH))
        }
        val open = Intent(context, MainActivity::class.java).apply {
            putExtra("path", path ?: "/")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tap = PendingIntent.getActivity(context, id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(context)
        val n = builder
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        runCatching { manager.notify(id.hashCode(), n) }
    }

    companion object {
        const val CHANNEL = "ortus"
    }
}
