package cc.ortus.app

import android.Manifest
import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cc.ortus.kit.Room

/**
 * MobileCall: a room's call, as the web client has it (the same media
 * server), in a web view signed in with the app's session. The camera and
 * microphone are granted to the installation's own page only; leaving the
 * call (the page goes anywhere but /calls) closes the screen.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CallScreen(model: Model, room: Room, title: String, close: () -> Unit) {
    val client = model.client ?: return close()
    val server = Uri.parse(client.server)
    var allowed by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { allowed = true }
    LaunchedEffect(Unit) { ask.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, color = Text1, fontSize = 18.sp, modifier = Modifier.weight(1f))
            TextButton(close) { Text(stringResource(R.string.call_close)) }
        }
        if (!allowed) return@Column
        AndroidView(modifier = Modifier.fillMaxSize().testTag("call"), factory = { context ->
            val secure = server.scheme == "https"
            CookieManager.getInstance().setCookie(client.server, "${if (secure) "__Host-ortus" else "ortus"}=${client.token}; Path=/${if (secure) "; Secure" else ""}")
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) {
                        val ours = request.origin.host == server.host && request.origin.scheme == server.scheme
                        if (ours) request.grant(request.resources) else request.deny()
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url
                        if (url.host == server.host && url.path == "/calls") return false
                        close()
                        return true
                    }
                }
                loadUrl(server.buildUpon().path("/calls").appendQueryParameter("room", room.id).build().toString())
            }
        })
    }
}
