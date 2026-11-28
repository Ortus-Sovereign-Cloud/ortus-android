package cc.ortus.app

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cc.ortus.kit.CallInfo
import cc.ortus.kit.CallParts
import cc.ortus.kit.Client
import cc.ortus.kit.callState
import cc.ortus.kit.joinCall
import cc.ortus.kit.leaveCall
import cc.ortus.kit.sendOffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One other person in the call: their session, name, and video if they show it. */
data class Remote(val session: String, val name: String, val video: VideoTrack?)

/**
 * A call in a room, natively (libwebrtc), with the web client's signalling:
 * see cc.ortus.kit.Calls. Media goes to the installation's media node, or
 * through its TURN relay where only web ports get through.
 */
class NativeCall(private val context: Context, private val client: Client, private val room: String) {
    companion object {
        private var initialised = false
        val egl: EglBase by lazy { EglBase.create() }
    }

    var answered by mutableStateOf(false)
        private set
    var connected by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var muted by mutableStateOf(false)
        private set
    var camera by mutableStateOf(true)
        private set
    var speaker by mutableStateOf(true)
        private set
    var remotes by mutableStateOf<List<Remote>>(emptyList())
        private set
    var local: VideoTrack? by mutableStateOf(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var channel: DataChannel? = null
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var audioTrack: org.webrtc.AudioTrack? = null
    private var session: String? = null
    private var info: CallInfo? = null
    /** Each other session's video tracks, by the sender's mid (camera and screen). */
    private val videos = mutableMapOf<String, MutableMap<String, VideoTrack>>()
    private val joiner = CallParts.Joiner()
    private val audio = context.getSystemService(AudioManager::class.java)

    fun start() {
        scope.launch {
            try {
                if (!initialised) {
                    PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
                    initialised = true
                }
                val f = PeerConnectionFactory.builder()
                    .setAudioDeviceModule(JavaAudioDeviceModule.builder(context).createAudioDeviceModule())
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                    .createPeerConnectionFactory()
                factory = f
                audioTrack = f.createAudioTrack("ortus-audio", f.createAudioSource(MediaConstraints()))
                local = startCamera(f)
                camera = local != null
                @Suppress("DEPRECATION")
                run {
                    audio.mode = AudioManager.MODE_IN_COMMUNICATION
                    audio.isSpeakerphoneOn = speaker
                }
                val joined = client.joinCall(room)
                session = joined.session
                info = joined.call
                connect(f, joined.ice_servers)
                // The session stays while reported; others' names come back.
                while (true) {
                    delay(10_000)
                    val s = session ?: break
                    runCatching { client.callState(s, muted, camera) }.getOrNull()?.let { info = it; refresh() }
                }
            } catch (e: Throwable) {
                android.util.Log.e("ortus", "call: ${e}", e)
                error = e.message ?: e.toString()
            }
        }
    }

    private fun startCamera(f: PeerConnectionFactory): VideoTrack? {
        val cameras = Camera2Enumerator(context)
        val name = cameras.deviceNames.firstOrNull { cameras.isFrontFacing(it) } ?: cameras.deviceNames.firstOrNull() ?: return null
        val cap = cameras.createCapturer(name, null) ?: return null
        val source = f.createVideoSource(false)
        val h = SurfaceTextureHelper.create("ortus-camera", egl.eglBaseContext)
        cap.initialize(h, context, source.capturerObserver)
        runCatching { cap.startCapture(640, 480, 24) }.onFailure { return null }
        capturer = cap
        helper = h
        return f.createVideoTrack("ortus-camera", source)
    }

    private suspend fun connect(f: PeerConnectionFactory, servers: List<cc.ortus.kit.IceServer>) {
        val ice = servers.map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                s.username?.let { setUsername(it) }
                s.credential?.let { setPassword(it) }
            }.createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(ice).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val peer = f.createPeerConnection(config, observer) ?: throw IllegalStateException("no peer connection")
        pc = peer
        val dc = peer.createDataChannel("signalling", DataChannel.Init())
        channel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                val whole = joiner.take(String(bytes)) ?: return
                scope.launch { signal(whole) }
            }
        })
        val streams = listOf("ortus-local")
        val send = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
        audioTrack?.let { peer.addTransceiver(it, RtpTransceiver.RtpTransceiverInit(send, streams)) }
            ?: peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiver.RtpTransceiverInit(send))
        local?.let { peer.addTransceiver(it, RtpTransceiver.RtpTransceiverInit(send, streams)) }
            ?: peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(send))
        // The screen (not shared from the phone).
        peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(send))
        val offer = peer.await { o -> createOffer(o, MediaConstraints()) }
        peer.awaitSet { o -> setLocalDescription(o, offer) }
        val answer = client.sendOffer(session!!, offer.description)
        peer.awaitSet { o -> setRemoteDescription(o, SessionDescription(SessionDescription.Type.ANSWER, answer)) }
        answered = true
    }

    /** The node offers others' tracks (or stops them); we answer over the channel. */
    private suspend fun signal(message: String) {
        val peer = pc ?: return
        val (sdp, n) = CallParts.offer(message) ?: return
        peer.awaitSet { o -> setRemoteDescription(o, SessionDescription(SessionDescription.Type.OFFER, sdp)) }
        val answer = peer.await { o -> createAnswer(o, MediaConstraints()) }
        peer.awaitSet { o -> setLocalDescription(o, answer) }
        for (part in CallParts.split(CallParts.answer(answer.description, n))) {
            channel?.send(DataChannel.Buffer(ByteBuffer.wrap(part.toByteArray()), false))
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidate(candidate: IceCandidate) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            main.post { connected = state == PeerConnection.PeerConnectionState.CONNECTED }
        }
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            val who = streams.firstOrNull()?.id ?: return
            val track = receiver.track()
            main.post {
                if (track is VideoTrack) videos.getOrPut(who) { mutableMapOf() }[track.id()] = track
                refresh(who)
            }
        }
    }

    /** Everyone else, by name, with their video where there is one. */
    private fun refresh(extra: String? = null) {
        val me = session
        val call = info
        val known = call?.sessions?.map { it.session }?.filter { it != me }.orEmpty()
        val all = (known + listOfNotNull(extra) + videos.keys).distinct().filter { it != me }
        remotes = all.map { s ->
            val mine = videos[s].orEmpty()
            val camera = call?.cameraOf(s, mine.keys) ?: mine.keys.minOrNull()
            Remote(s, call?.nameOf(s) ?: "…", camera?.let { mine[it] })
        }
    }

    fun toggleMute() {
        muted = !muted
        audioTrack?.setEnabled(!muted)
        report()
    }

    fun toggleCamera() {
        val track = local ?: return
        camera = !camera
        track.setEnabled(camera)
        report()
    }

    /** Front to back camera and back again. */
    fun flipCamera() {
        capturer?.switchCamera(null)
    }

    fun toggleSpeaker() {
        speaker = !speaker
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = speaker
    }

    private fun report() {
        val s = session ?: return
        scope.launch { runCatching { client.callState(s, muted, camera) } }
    }

    /** Leaves the call (the node drops this session at once) and lets go of everything. */
    fun end() {
        val s = session
        session = null
        if (s != null) CoroutineScope(Dispatchers.IO).launch { runCatching { client.leaveCall(s) } }
        scope.cancel()
        // The views may still hold the tracks for a moment: close the
        // connection and the camera, and let the rest go with the process.
        runCatching { capturer?.stopCapture() }
        runCatching { channel?.close() }
        runCatching { pc?.close() }
        @Suppress("DEPRECATION")
        run {
            audio.isSpeakerphoneOn = false
            audio.mode = AudioManager.MODE_NORMAL
        }
    }
}

/** libwebrtc's callbacks as suspending calls. */
private suspend fun PeerConnection.await(make: PeerConnection.(SdpObserver) -> Unit): SessionDescription =
    suspendCancellableCoroutine { k ->
        make(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = k.resume(sdp)
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) = k.resumeWithException(IllegalStateException(error))
            override fun onSetFailure(error: String) {}
        })
    }

private suspend fun PeerConnection.awaitSet(make: PeerConnection.(SdpObserver) -> Unit) =
    suspendCancellableCoroutine { k ->
        make(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onSetSuccess() = k.resume(Unit)
            override fun onCreateFailure(error: String) {}
            override fun onSetFailure(error: String) = k.resumeWithException(IllegalStateException(error))
        })
    }
