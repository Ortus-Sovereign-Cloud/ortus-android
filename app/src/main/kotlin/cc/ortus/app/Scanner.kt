package cc.ortus.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import cc.ortus.kit.QrCode
import java.util.concurrent.Executors

/** Whether this phone has a camera to read QR codes with. */
fun hasCamera(context: android.content.Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/**
 * The camera, full screen, reading an invitation's QR code: `found` gets
 * the first code that is an Ortus invitation (its text). Asks for the
 * camera first; without it, closes.
 */
@Composable
fun CodeScanner(found: (String) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) allowed = true else close()
    }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().testTag("scanner")) {
            if (allowed) Camera(found)
            TextButton(close, Modifier.align(Alignment.BottomCenter).padding(32.dp)) {
                Text(stringResource(R.string.join_scanCancel), color = Color.White)
            }
        }
    }
}

@Composable
private fun Camera(found: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val worker = remember { Executors.newSingleThreadExecutor() }
    var done by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { worker.shutdown() } }
    AndroidView(factory = { ctx ->
        val view = PreviewView(ctx)
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(worker) { image ->
                image.use {
                    val plane = it.planes[0]
                    val buffer = plane.buffer
                    val luma = ByteArray(buffer.remaining()).also { bytes -> buffer.get(bytes) }
                    val invitation = QrCode.read(luma, it.width, it.height, plane.rowStride)
                        ?.takeIf { text -> cc.ortus.kit.InvitationLink.parse(text) != null }
                    if (invitation != null && !done) {
                        done = true
                        ContextCompat.getMainExecutor(ctx).execute { found(invitation) }
                    }
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(ctx))
        view
    }, modifier = Modifier.fillMaxSize())
    DisposableEffect(Unit) {
        onDispose { runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() } }
    }
}
