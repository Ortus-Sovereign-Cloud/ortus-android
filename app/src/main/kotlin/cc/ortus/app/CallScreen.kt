package cc.ortus.app

import android.Manifest
import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cc.ortus.kit.Room
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * MobileCall: a room's call, natively (libwebrtc), on the same media server
 * as the web client. Mute, camera, speaker and hang up; the screen stays
 * on while the call lasts; leaving closes it on the server at once.
 */
@Composable
fun CallScreen(model: Model, room: Room, title: String, close: () -> Unit) {
    val client = model.client ?: return close()
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { allowed = true }
    LaunchedEffect(Unit) { ask.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }
    Column(Modifier.fillMaxSize().testTag("call")) {
        if ((context as? MainActivity)?.pip != true) Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, color = Text1, fontSize = 18.sp, modifier = Modifier.weight(1f))
            TextButton(close) { Text(stringResource(R.string.call_close)) }
        }
        if (!allowed) return@Column
        val call = remember { NativeCall(context, client, room.id) }
        val activity = context as? MainActivity
        DisposableEffect(Unit) {
            val window = (context as? Activity)?.window
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.inCall = true
            call.start()
            onDispose {
                activity?.inCall = false
                call.end()
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        // The small window: only the others' video.
        if (activity?.pip == true) {
            Box(Modifier.fillMaxSize()) {
                call.remotes.firstOrNull { it.video != null }?.let { Tile(it.name, it.video, mirror = false, Modifier.fillMaxSize()) }
                    ?: Text(call.remotes.firstOrNull()?.name ?: title, color = Text1, modifier = Modifier.align(Alignment.Center))
            }
            return@Column
        }
        call.error?.let { Text(it, color = Color(0xFFFF8A80), modifier = Modifier.padding(horizontal = 16.dp).testTag("callError")) }
        val state = when {
            call.connected -> R.string.call_connected
            call.answered -> R.string.call_connecting
            else -> R.string.call_joining
        }
        Text(stringResource(state), color = Text2, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp).testTag(if (call.answered) "answered" else "joining"))
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
            if (call.remotes.isEmpty()) {
                Text(stringResource(R.string.call_alone), color = Text2, modifier = Modifier.align(Alignment.Center))
            } else {
                LazyVerticalGrid(GridCells.Fixed(if (call.remotes.size == 1) 1 else 2), Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(call.remotes, key = { it.session }) { r -> Tile(r.name, r.video, mirror = false, Modifier.fillMaxWidth().aspectRatio(if (call.remotes.size == 1) 0.75f else 1f)) }
                }
            }
            call.local?.takeIf { call.camera }?.let { track ->
                Tile(null, track, mirror = true, Modifier.align(Alignment.BottomEnd).padding(8.dp).size(96.dp, 128.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Control(if (call.muted) R.string.call_unmute else R.string.call_mute, call.muted, "mute") { call.toggleMute() }
            Control(if (call.camera) R.string.call_cameraOff else R.string.call_cameraOn, !call.camera, "camera") { call.toggleCamera() }
            Control(if (call.speaker) R.string.call_earpiece else R.string.call_speaker, false, "speaker") { call.toggleSpeaker() }
            if (call.camera) Control(R.string.call_flip, false, "flip") { call.flipCamera() }
            Button(close, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5484D)), modifier = Modifier.testTag("hangUp")) {
                Text(stringResource(R.string.call_hangUp), color = Color.White)
            }
        }
    }
}

@Composable
private fun Control(label: Int, on: Boolean, tag: String, action: () -> Unit) {
    val text = stringResource(label)
    OutlinedButton(action, modifier = Modifier.testTag(tag).semantics { contentDescription = text },
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (on) Surface2 else Color.Transparent)) {
        Text(text, color = Text1, fontSize = 13.sp)
    }
}

/** One person's video, or their name when they show none. */
@Composable
private fun Tile(name: String?, track: VideoTrack?, mirror: Boolean, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Surface2), contentAlignment = Alignment.Center) {
        if (track != null) {
            AndroidView(
                factory = { c ->
                    SurfaceViewRenderer(c).apply {
                        init(NativeCall.egl.eglBaseContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setMirror(mirror)
                        track.addSink(this)
                    }
                },
                onRelease = { v -> runCatching { track.removeSink(v) }; runCatching { v.release() } },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (name != null) {
            Text(name, color = Text1, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(if (track != null) Alignment.BottomStart else Alignment.Center).padding(8.dp))
        }
    }
}
